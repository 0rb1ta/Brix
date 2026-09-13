package app.brix.bonding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

import java.util.concurrent.ConcurrentHashMap

/**
 * Requests one network per transport (Wi-Fi + cellular) and forwards
 * availability/loss to the listener.
 *
 * Guarantees:
 *  - duplicate [onAvailable] for the SAME Network (capability flaps re-fire
 *    it) are collapsed — the listener sees one event per network appearance;
 *  - [onNetworkLost] is only forwarded for networks this manager actually
 *    reported as available;
 *  - a failed requestNetwork never leaves the transport stuck "requested";
 *  - the callback is registered before requestNetwork, so a concurrent stop()
 *    can always unregister it (no leaked system listener).
 */
class BondingNetworkManager(context: Context) {
    interface Listener {
        fun onNetworkAvailable(type: String, network: Network)
        fun onNetworkLost(network: Network)
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var listener: Listener? = null

    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private val requestedTransports = mutableSetOf<Int>()

    /** Networks we have announced as available, with their transport label. */
    private val knownNetworks = ConcurrentHashMap<Network, String>()

    @Volatile
    private var epoch = 0L

    @Synchronized
    fun start() {
        if (requestedTransports.isNotEmpty()) return
        epoch += 1
        request(NetworkCapabilities.TRANSPORT_WIFI, "wifi", epoch)
        request(NetworkCapabilities.TRANSPORT_CELLULAR, "cellular", epoch)
    }

    private fun request(transport: Int, type: String, callbackEpoch: Long) {
        if (!requestedTransports.add(transport)) return
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (epoch != callbackEpoch) return
                if (knownNetworks.put(network, type) != null) {
                    Log.d(TAG, "bonding: $type $network duplicate onAvailable — ignored")
                    return
                }
                Log.i(TAG, "bonding: $type network available")
                listener?.onNetworkAvailable(type, network)
            }

            override fun onLost(network: Network) {
                if (epoch != callbackEpoch) return
                // Only forward losses for networks we announced: the framework
                // also fires onLost for networks that never became usable.
                val type = knownNetworks.remove(network)
                if (type == null) {
                    Log.d(TAG, "bonding: $network lost before availability — ignored")
                    return
                }
                Log.i(TAG, "bonding: $type network lost")
                listener?.onNetworkLost(network)
            }
        }
        // Register BEFORE requestNetwork: if stop() interleaves, the callback
        // is already in the list and will be unregistered.
        synchronized(callbacks) { callbacks.add(callback) }
        try {
            connectivityManager.requestNetwork(networkRequest, callback)
        } catch (e: Exception) {
            Log.e(TAG, "bonding: failed to request $type network: ${e.message}")
            synchronized(callbacks) { callbacks.remove(callback) }
            // Otherwise this transport is stuck "requested" and every future
            // start() silently skips it.
            requestedTransports.remove(transport)
        }
    }

    @Synchronized
    fun stop() {
        epoch += 1
        val toUnregister = synchronized(callbacks) {
            val copy = callbacks.toList()
            callbacks.clear()
            copy
        }
        toUnregister.forEach {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        requestedTransports.clear()
        knownNetworks.clear()
    }

    companion object {
        private const val TAG = "Srtla"
    }
}
