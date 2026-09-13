package app.brix.moblink

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** WebSocket server for the Moblink "streamer" role: accepts relay connections,
 *  runs the Hello -> Identify -> Identified -> Request(startTunnel) handshake
 *  (protocol verified against Moblin/Moblink/MoblinkStreamer.swift), and reports
 *  each relay's tunnel endpoint (its own LAN host:port) to [listener] once ready
 *  — same role as a normal wifi/cellular path, nothing SRTLA-specific here. */
class MoblinkServer(
    private val port: Int,
    private val password: String,
) : WebSocketServer(InetSocketAddress(port)) {

    interface Listener {
        fun onRelayTunnelReady(relayId: String, name: String, host: String, port: Int)
        fun onRelayTunnelClosed(relayId: String)
        fun onRelayListChanged()
    }

    var listener: Listener? = null

    @Volatile
    private var destinationAddress: String? = null

    @Volatile
    private var destinationPort: Int? = null

    private class RelayState(val challenge: String, val salt: String) {
        var identified = false
        var relayId: String = ""
        var name: String = ""
        var nextRequestId = 0
        var pendingStartTunnelId: Int? = null
        var batteryPercentage: Int? = null
        var thermalState: MoblinkThermalState? = null
    }

    private val relays = ConcurrentHashMap<WebSocket, RelayState>()

    fun relaySnapshot(): List<MoblinkRelayInfo> = relays.values
        .filter { it.identified }
        .map { MoblinkRelayInfo(it.relayId, it.name, it.batteryPercentage, it.thermalState) }

    /** Called once the real SRTLA destination is known (or changes) — every
     *  identified relay gets asked to tunnel to it. Mirrors
     *  MoblinkStreamer.startTunnels(address:port:) in real Moblin. */
    fun startTunnels(address: String, port: Int) {
        destinationAddress = address
        destinationPort = port
        relays.forEach { (conn, state) -> if (state.identified) requestStartTunnel(conn, state) }
    }

    fun stopTunnels() {
        destinationAddress = null
        destinationPort = null
        relays.values.forEach { it.pendingStartTunnelId = null }
    }

    override fun onStart() {
        setConnectionLostTimeout(PING_INTERVAL_SEC)
        Log.i(TAG, "moblink: server started on port $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val state = RelayState(randomString(), randomString())
        relays[conn] = state
        send(conn, MessageToRelay.Hello(MOBLINK_API_VERSION, MoblinkAuthentication(state.challenge, state.salt)))
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val state = relays.remove(conn) ?: return
        Log.i(TAG, "moblink: relay disconnected name=${state.name} code=$code reason=$reason")
        if (state.identified) {
            listener?.onRelayTunnelClosed(state.relayId)
            listener?.onRelayListChanged()
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val state = relays[conn] ?: return
        when (val parsed = MessageToStreamer.fromJson(message)) {
            is MessageToStreamer.Identify -> handleIdentify(conn, state, parsed)
            is MessageToStreamer.Response -> handleResponse(conn, state, parsed)
            null -> Log.w(TAG, "moblink: failed to parse message from ${state.name.ifEmpty { "?" }}: $message")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "moblink: websocket error: ${ex.message}")
    }

    private fun handleIdentify(conn: WebSocket, state: RelayState, msg: MessageToStreamer.Identify) {
        val expected = MoblinkAuth.calculateAuthentication(password, state.salt, state.challenge)
        if (msg.authentication != expected) {
            send(conn, MessageToRelay.Identified(MoblinkResult.WrongPassword))
            Log.w(TAG, "moblink: relay sent wrong password (name=${msg.name})")
            conn.close(CLOSE_WRONG_PASSWORD, "wrong password")
            return
        }
        state.identified = true
        state.relayId = msg.id.toString()
        state.name = msg.name.take(30)
        send(conn, MessageToRelay.Identified(MoblinkResult.Ok))
        listener?.onRelayListChanged()
        requestStartTunnel(conn, state)
    }

    private fun requestStartTunnel(conn: WebSocket, state: RelayState) {
        val address = destinationAddress ?: return
        val port = destinationPort ?: return
        val id = ++state.nextRequestId
        state.pendingStartTunnelId = id
        send(conn, MessageToRelay.Request(id, MoblinkRequestData.StartTunnel(address, port)))
    }

    private fun handleResponse(conn: WebSocket, state: RelayState, msg: MessageToStreamer.Response) {
        if (!state.identified) return
        if (msg.result != MoblinkResult.Ok) {
            Log.w(TAG, "moblink: ${state.name} request ${msg.id} failed: ${msg.result.wireName}")
            return
        }
        when (val data = msg.data) {
            is MoblinkResponseData.StartTunnel -> {
                if (msg.id != state.pendingStartTunnelId) return
                val host = conn.getRemoteSocketAddress()?.address?.hostAddress ?: return
                Log.i(TAG, "moblink: tunnel ready ${state.name} -> $host:${data.port}")
                listener?.onRelayTunnelReady(state.relayId, state.name, host, data.port)
            }
            is MoblinkResponseData.Status -> {
                state.batteryPercentage = data.batteryPercentage
                state.thermalState = data.thermalState
                listener?.onRelayListChanged()
            }
            null -> Unit
        }
    }

    private fun send(conn: WebSocket, message: MessageToRelay) {
        runCatching { conn.send(message.toJson()) }
            .onFailure { Log.e(TAG, "moblink: send failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Moblink"
        private const val PING_INTERVAL_SEC = 10
        private const val CLOSE_WRONG_PASSWORD = 4001

        private fun randomString(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.getEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
