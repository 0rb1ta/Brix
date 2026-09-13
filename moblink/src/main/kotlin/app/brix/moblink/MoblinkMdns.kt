package app.brix.moblink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Announces the streamer on the LAN via mDNS/Bonjour (`_moblink._tcp`), matching
 *  what real Moblin publishes so official relay apps can discover us without a
 *  manually typed IP. Optional: a relay can always be pointed at the IP directly. */
class MoblinkMdns(context: Context) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(port: Int, name: String) {
        unregister()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("name", name)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "moblink: mDNS registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "moblink: mDNS registration failed errorCode=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "moblink: mDNS register threw: ${it.message}") }
    }

    fun unregister() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    companion object {
        private const val TAG = "Moblink"
        private const val SERVICE_TYPE = "_moblink._tcp"
    }
}
