package app.brix.streaming.chat

import app.brix.core.diagnostics.PeriodicTasks
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VK Video Live chat has no anonymous read and no public socket a phone can
 * connect to directly — verified against the official docs
 * (dev.live.vkvideo.ru/docs/method/chat, /docs/main/authorization): the only
 * way in is `GET /v1/chat/messages`, an authenticated REST endpoint, polled
 * — there's a pubsub websocket too, but its per-channel subscription token
 * is gated to actual user login (OAuth browser redirect), which is exactly
 * the friction phase 1 is avoiding for every other platform here.
 *
 * Auth is ClientCredentials: the app's own `clientId`/`clientSecret`
 * (registered once on dev.live.vkvideo.ru) exchanged for a Bearer token,
 * no per-viewer login. Token refresh is reactive, not clock-driven: VK's
 * docs disagree with themselves on whether `expire_time` is a duration or a
 * timestamp, so instead of guessing, a 401 on `/chat/messages` just triggers
 * one token refresh + retry.
 *
 * No [StreamReconnector] here — there's no connection to drop, just a poll
 * loop that keeps going through failures (network blip, VK returning
 * garbage) and tries again next tick.
 */
class VkChatClient(
    private val scope: CoroutineScope,
    private val tokenProvider: VkTokenProvider = OkHttpVkTokenProvider(),
    private val messagesFetcher: VkMessagesFetcher = OkHttpVkMessagesFetcher(),
    private val maxMessages: Int = 250,
    private val pollIntervalMs: Long = 4_000L,
) {
    private val tag = "BrixChat"
    private val nextId = AtomicLong(0)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var pollJob: Job? = null
    private val seenIds = mutableSetOf<Long>()
    private var accessToken: String? = null

    fun start(channelUrl: String, clientId: String, clientSecret: String) {
        stop()
        val url = normalizeChannelUrl(channelUrl)
        if (url.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return
        _messages.value = emptyList()
        seenIds.clear()
        accessToken = null
        pollJob = scope.launch {
            PeriodicTasks.register("vk-chat", pollIntervalMs).use {
                while (isActive) {
                    pollOnce(url, clientId, clientSecret)
                    delay(pollIntervalMs)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _connected.value = false
    }

    private suspend fun pollOnce(channelUrl: String, clientId: String, clientSecret: String) {
        var token = accessToken ?: tokenProvider.fetchToken(clientId, clientSecret).also { accessToken = it }
        if (token == null) {
            Log.w(tag, "vk: не удалось получить токен приложения")
            _connected.value = false
            return
        }
        var result = messagesFetcher.fetchMessages(token, channelUrl, maxMessages)
        if (result is VkMessagesResult.Unauthorized) {
            accessToken = null
            token = tokenProvider.fetchToken(clientId, clientSecret).also { accessToken = it }
            result = if (token != null) {
                messagesFetcher.fetchMessages(token, channelUrl, maxMessages)
            } else {
                VkMessagesResult.Failure
            }
        }
        when (result) {
            is VkMessagesResult.Success -> {
                _connected.value = true
                appendNew(result.messages)
            }
            VkMessagesResult.Unauthorized, VkMessagesResult.Failure -> {
                _connected.value = false
            }
        }
    }

    private fun appendNew(fetched: List<VkChatMessage>) {
        val fresh = fetched
            .filter { it.id !in seenIds }
            .sortedBy { it.createdAtSec }
        if (fresh.isEmpty()) return
        val newMessages = fresh.map { dto ->
            ChatMessage(
                id = "vk-${nextId.incrementAndGet()}",
                platform = ChatPlatform.VK,
                author = dto.author,
                colorHex = dto.colorHex,
                text = dto.text,
                timestampMs = dto.createdAtSec * 1000,
            )
        }
        _messages.update { current ->
            val merged = (current + newMessages).takeLast(maxMessages)
            seenIds.clear()
            seenIds += fetched.map { it.id }
            merged
        }
    }
}

/** Accepts either a bare slug or a full URL — matches how a streamer would
 *  naturally type it into settings. */
internal fun normalizeChannelUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://live.vkvideo.ru/${trimmed.removePrefix("/")}"
    }
}
