package app.brix.streaming.chat

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

internal const val TWITCH_CHAT_WS_URL = "wss://irc-ws.chat.twitch.tv:443"

/** Real transport: `Java-WebSocket` client, already in the project via
 *  `:moblink` (`api(libs.java.websocket)`), same library [app.brix.moblink.MoblinkServer]
 *  runs server-side. */
internal class TwitchWebSocket(
    url: String,
    private val listener: ChatSocketListener,
) : WebSocketClient(URI(url)), ChatSocket {

    override fun onOpen(handshakedata: ServerHandshake?) {
        listener.onOpen()
    }

    override fun onMessage(message: String) {
        // Twitch may pack several IRC lines into one WS text frame.
        message.split("\r\n").forEach { if (it.isNotEmpty()) listener.onLine(it) }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        listener.onClosed()
    }

    override fun onError(ex: Exception) {
        listener.onFailure(ex.message ?: ex.javaClass.simpleName)
    }
}
