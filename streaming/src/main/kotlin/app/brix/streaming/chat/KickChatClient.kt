package app.brix.streaming.chat

import android.util.Log
import app.brix.streaming.ReconnectPolicy
import app.brix.streaming.StreamReconnector
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Anonymous, read-only Kick chat over Pusher — see [KickPusherParser] and
 * [kickSubscribeChannels] for the protocol notes (channel-naming churn,
 * verified live 04.09). Same shape as [TwitchChatClient]: reconnect rides
 * [StreamReconnector]/[ReconnectPolicy] as-is, exhaustion resets and climbs
 * again rather than landing in a dead "Failed" state.
 *
 * One extra step Twitch doesn't need: the channel slug has to be resolved to
 * numeric chatroom ids via a Cloudflare-fronted REST call before the socket
 * can subscribe to anything — see [channelResolver]. That resolution is
 * cached per `start()` call so a reconnect after a network blip doesn't
 * re-hit the REST endpoint for every retry, only the socket is reopened.
 */
class KickChatClient(
    private val scope: CoroutineScope,
    private val socketFactory: ChatSocketFactory = { url, listener -> KickWebSocket(url, listener) },
    private val channelResolver: KickChannelResolver = OkHttpKickChannelResolver(),
    private val maxMessages: Int = 250,
    private val wsUrl: String = KICK_PUSHER_WS_URL,
) {
    private val tag = "BrixChat"
    private val nextId = AtomicLong(0)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var channel = ""
    private var chatroomId: String? = null
    private var chatroomChannelId: String? = null
    private var socket: ChatSocket? = null
    private var stopped = true

    private val reconnector: StreamReconnector = StreamReconnector(
        scope = scope,
        policy = ReconnectPolicy(),
        onExhausted = {
            Log.w(tag, "kick: ступени кончились, начинаем лестницу заново")
            reconnector.reset()
            reconnector.schedule()
        },
        onAttempt = { openSocket() },
    )

    fun start(kickChannel: String) {
        val normalized = kickChannel.trim().removePrefix("/").lowercase()
        if (normalized.isBlank()) {
            stop()
            return
        }
        if (!stopped && normalized == channel) return
        stopped = false
        if (normalized != channel) {
            chatroomId = null
            chatroomChannelId = null
        }
        channel = normalized
        _messages.value = emptyList()
        socket?.close()
        socket = null
        reconnector.reset()
        openSocket()
    }

    fun stop() {
        stopped = true
        reconnector.cancel()
        socket?.close()
        socket = null
        _connected.value = false
    }

    private fun openSocket() {
        val ch = channel
        if (ch.isBlank()) return
        val cachedRoomId = chatroomId
        val cachedChannelId = chatroomChannelId
        if (cachedRoomId != null && cachedChannelId != null) {
            openSocketWithIds(cachedRoomId, cachedChannelId)
            return
        }
        scope.launch {
            val info = channelResolver.resolve(ch)
            if (info == null) {
                Log.w(tag, "kick: не удалось определить chatroom для $ch")
                if (!stopped) reconnector.schedule()
                return@launch
            }
            chatroomId = info.chatroomId
            chatroomChannelId = info.chatroomChannelId
            if (!stopped && channel == ch) openSocketWithIds(info.chatroomId, info.chatroomChannelId)
        }
    }

    private fun openSocketWithIds(roomId: String, channelId: String) {
        lateinit var mySocket: ChatSocket
        val listener = object : ChatSocketListener {
            override fun onOpen() {
                if (socket !== mySocket) return
                kickSubscribeChannels(roomId, channelId).forEach { ch ->
                    mySocket.send("""{"event":"pusher:subscribe","data":{"auth":"","channel":"$ch"}}""")
                }
                _connected.value = true
                reconnector.reset()
            }

            override fun onLine(line: String) {
                if (socket !== mySocket) return
                when (val event = KickPusherParser.parse(line)) {
                    is KickPusherEvent.ChatMessage -> append(event)
                    KickPusherEvent.Other -> Unit
                }
            }

            override fun onClosed() {
                if (socket !== mySocket) return
                _connected.value = false
                if (!stopped) reconnector.schedule()
            }

            override fun onFailure(message: String) {
                if (socket !== mySocket) return
                Log.w(tag, "kick: ошибка сокета: $message")
            }
        }
        val created = socketFactory(wsUrl, listener)
        mySocket = created
        socket = created
        created.connect()
    }

    private fun append(event: KickPusherEvent.ChatMessage) {
        val message = ChatMessage(
            id = "kick-${nextId.incrementAndGet()}",
            platform = ChatPlatform.KICK,
            author = event.author,
            colorHex = event.colorHex,
            text = event.text,
            timestampMs = System.currentTimeMillis(),
        )
        _messages.update { (it + message).takeLast(maxMessages) }
    }
}
