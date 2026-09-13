package app.brix.streaming.chat

import android.util.Log
import app.brix.streaming.ReconnectPolicy
import app.brix.streaming.StreamReconnector
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Anonymous, read-only Twitch chat: IRC over WebSocket
 * (`wss://irc-ws.chat.twitch.tv:443`), login as `justinfanNNNNN` with no
 * password — no OAuth needed to read. Sending is a separate feature, not
 * built here.
 *
 * Three protocol traps this class exists to not re-learn the hard way:
 * - the server PINGs and silently drops the connection in a few minutes if
 *   we don't PONG back — [onLine] answers every [TwitchIrcEvent.Ping];
 * - without `CAP REQ :twitch.tv/tags` messages arrive with no color, no
 *   badges, no display name casing — just bare text;
 * - RECONNECT is a command from the server, not an error — treated the same
 *   as any other disconnect (close and let the ladder retry).
 *
 * Reconnection rides [StreamReconnector]/[ReconnectPolicy] as-is — same
 * backoff, same virtual-time tests. One deliberate difference from the video
 * transports: chat has no "Failed" state a streamer would notice and act on,
 * so instead of stopping after the ladder's five rungs, [onExhausted] resets
 * it and climbs again — a 6-hour stream should keep trying, not go quiet
 * after 48 seconds of bad luck.
 *
 * Kept off the hot path on purpose: nothing here touches the encoder or the
 * video surface, so it never competes with the stream for CPU/GPU.
 */
class TwitchChatClient(
    scope: CoroutineScope,
    private val socketFactory: ChatSocketFactory = { url, listener -> TwitchWebSocket(url, listener) },
    private val maxMessages: Int = 250,
    private val wsUrl: String = TWITCH_CHAT_WS_URL,
) {
    private val tag = "BrixChat"
    private val nextId = AtomicLong(0)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var channel = ""
    private var socket: ChatSocket? = null
    private var stopped = true

    private val reconnector: StreamReconnector = StreamReconnector(
        scope = scope,
        policy = ReconnectPolicy(),
        onExhausted = {
            Log.w(tag, "chat: ступени кончились, начинаем лестницу заново")
            reconnector.reset()
            reconnector.schedule()
        },
        onAttempt = { openSocket() },
    )

    /** Start (or retarget to a different channel). No-op if already running
     *  for the same channel. */
    fun start(twitchChannel: String) {
        val normalized = twitchChannel.trim().removePrefix("#").lowercase()
        if (normalized.isBlank()) {
            stop()
            return
        }
        if (!stopped && normalized == channel) return
        stopped = false
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
        val nick = "justinfan${Random.nextInt(10_000, 99_999)}"
        // `mySocket` привязывает колбэки к ЭТОМУ конкретному соединению.
        // Закрытие старого сокета в start()/stop() асинхронно — его onClosed
        // может прилететь уже после того, как socket указывает на новое
        // соединение (смена канала, повторный start()). Без проверки
        // identity такой опоздавший колбэк планировал бы лишний реконнект
        // поверх уже рабочей сессии.
        lateinit var mySocket: ChatSocket
        val listener = object : ChatSocketListener {
            override fun onOpen() {
                if (socket !== mySocket) return
                mySocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                mySocket.send("NICK $nick")
                mySocket.send("JOIN #$ch")
                _connected.value = true
                reconnector.reset()
            }

            override fun onLine(line: String) {
                if (socket !== mySocket) return
                when (val event = TwitchIrcParser.parse(line)) {
                    is TwitchIrcEvent.Ping -> mySocket.send("PONG :tmi.twitch.tv")
                    is TwitchIrcEvent.Reconnect -> {
                        Log.i(tag, "chat: сервер прислал RECONNECT")
                        mySocket.close()
                    }
                    is TwitchIrcEvent.Privmsg -> append(event)
                    TwitchIrcEvent.Other -> Unit
                }
            }

            override fun onClosed() {
                if (socket !== mySocket) return
                _connected.value = false
                if (!stopped) reconnector.schedule()
            }

            override fun onFailure(message: String) {
                if (socket !== mySocket) return
                Log.w(tag, "chat: ошибка сокета: $message")
            }
        }
        val created = socketFactory(wsUrl, listener)
        mySocket = created
        socket = created
        created.connect()
    }

    private fun append(event: TwitchIrcEvent.Privmsg) {
        val message = ChatMessage(
            id = "twitch-${nextId.incrementAndGet()}",
            platform = ChatPlatform.TWITCH,
            author = event.displayName,
            colorHex = event.colorHex,
            text = event.text,
            timestampMs = System.currentTimeMillis(),
        )
        // Держим хвост фиксированной длины: эфир идёт часами, сообщений
        // тысячи — без этого предела список растёт всю трансляцию (6.9.1).
        _messages.update { (it + message).takeLast(maxMessages) }
    }
}
