package app.brix.streaming.chat

/**
 * One text-based WebSocket connection, reduced to what [TwitchChatClient]
 * needs. Behind an interface so the client is driven by a fake in tests —
 * no real socket, no Android runtime (mirrors why [app.brix.streaming.StreamReconnector]
 * takes an injectable scope instead of hardcoding `delay`).
 */
interface ChatSocket {
    fun connect()
    fun send(text: String)
    fun close()
}

interface ChatSocketListener {
    fun onOpen()

    /** One already-split IRC line (Twitch may batch several lines, each
     *  `\r\n`-terminated, into a single WS text frame). */
    fun onLine(line: String)
    fun onClosed()
    fun onFailure(message: String)
}

/** `(url, listener) -> a connection, not yet opened`. */
typealias ChatSocketFactory = (url: String, listener: ChatSocketListener) -> ChatSocket
