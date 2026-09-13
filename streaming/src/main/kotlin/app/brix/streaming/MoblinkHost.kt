package app.brix.streaming

import android.content.Context
import android.util.Log
import app.brix.bonding.SrtlaClient
import app.brix.bonding.SrtlaConnection
import app.brix.core.MoblinkSettings
import app.brix.moblink.MoblinkMdns
import app.brix.moblink.MoblinkServer
import java.util.concurrent.ConcurrentHashMap

/**
 * Moblink: чужие телефоны-релеи подключаются к нам по WebSocket и становятся
 * дополнительными каналами бондинга — ровно такими же, как Wi-Fi или сота
 * (см. [onRelayTunnelReady] ниже: `addConnection` + `startAddedConnection` с
 * адресом самого релея). Ради этого в `SrtlaClient`/`SrtlaConnection` ничего
 * менять не пришлось — они изначально умеют свой host:port на соединение.
 *
 * Выделено из [SrtlaStreamer]. Живёт ровно столько же, сколько эфир: поднимается
 * из `start()`, гасится из `stop()`/`release()`.
 */
internal class MoblinkHost(
    private val appContext: Context,
    private val srtlaClient: SrtlaClient,
    private val sessionActive: () -> Boolean,
    private val currentHost: () -> String,
    private val currentPort: () -> Int,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
) {
    private val tag = "BrixStream"

    private var settings = MoblinkSettings()
    private var server: MoblinkServer? = null
    private var mdns: MoblinkMdns? = null
    private val connections = ConcurrentHashMap<String, SrtlaConnection>()

    fun configure(settings: MoblinkSettings) {
        this.settings = settings
    }

    /** Пробросить релеям адрес приёмника, как только он стал известен. */
    fun startTunnels(host: String, port: Int) {
        server?.startTunnels(host, port)
    }

    /** Поднять сервер релеев и объявить себя по mDNS. */
    fun start() {
        if (!settings.enabled || server != null) return
        val newServer = MoblinkServer(settings.port, settings.password)
        newServer.listener = object : MoblinkServer.Listener {
            override fun onRelayTunnelReady(relayId: String, name: String, host: String, port: Int) {
                if (!sessionActive()) return
                val connection = srtlaClient.addConnection("moblink-$name", settings.relayWeight.toFloat())
                connections[relayId]?.let { srtlaClient.removeConnection(it) }
                connections[relayId] = connection
                if (srtlaClient.isStarted() && currentHost().isNotEmpty()) {
                    srtlaClient.startAddedConnection(connection, host, port)
                }
            }

            override fun onRelayTunnelClosed(relayId: String) {
                connections.remove(relayId)?.let { srtlaClient.removeConnection(it) }
            }

            override fun onRelayListChanged() {
                updateState { it.copy(moblinkRelays = newServer.relaySnapshot()) }
            }
        }
        try {
            newServer.start()
            server = newServer
            if (currentHost().isNotEmpty() && currentPort() > 0) {
                newServer.startTunnels(currentHost(), currentPort())
            }
            val newMdns = MoblinkMdns(appContext)
            newMdns.register(settings.port, android.os.Build.MODEL ?: "BRIX")
            mdns = newMdns
        } catch (e: Exception) {
            Log.e(tag, "moblink: failed to start server on port ${settings.port}", e)
            server = null
        }
    }

    fun stop() {
        mdns?.unregister()
        mdns = null
        server?.let { s -> runCatching { s.stop() } }
        server = null
        connections.values.forEach { srtlaClient.removeConnection(it) }
        connections.clear()
        updateState { it.copy(moblinkRelays = emptyList()) }
    }
}
