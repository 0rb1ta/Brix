package app.brix.bonding

import android.net.Network
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 audit test: queued ConnectivityManager callbacks from a PREVIOUS
 * registration must never reach the listener after stop()->start(). The
 * epoch guard in BondingNetworkManager is what keeps a stale onAvailable
 * from resurrecting a connection into the fresh session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BondingNetworkManagerTest {

    private fun testNetwork(id: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(id) as Network

    private class RecordingListener : BondingNetworkManager.Listener {
        val available = mutableListOf<Pair<String, Network>>()
        val lost = mutableListOf<Network>()
        override fun onNetworkAvailable(type: String, network: Network) {
            available += type to network
        }
        override fun onNetworkLost(network: Network) {
            lost += network
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registeredCallbacks(m: BondingNetworkManager): List<android.net.ConnectivityManager.NetworkCallback> {
        val f = m.javaClass.getDeclaredField("callbacks")
        f.isAccessible = true
        return (f.get(m) as List<android.net.ConnectivityManager.NetworkCallback>).toList()
    }

    @Test
    fun `queued onAvailable from old registration is ignored after stop`() {
        val manager = BondingNetworkManager(ApplicationProvider.getApplicationContext())
        val listener1 = RecordingListener()
        manager.listener = listener1
        manager.start()
        val callbacks = registeredCallbacks(manager)
        assertEquals(2, callbacks.size)

        // Live registration: event goes through.
        val net = testNetwork(101)
        callbacks[0].onAvailable(net)
        assertEquals(listOf("wifi" to net), listener1.available)

        // Stop invalidates the registration epoch...
        manager.stop()
        val listener2 = RecordingListener()
        manager.listener = listener2

        // ...but the framework thread may still deliver a QUEUED callback
        // from the old registration. It must be dropped.
        callbacks[0].onAvailable(net)
        callbacks[0].onLost(net)
        assertTrue(listener2.available.isEmpty())
        assertTrue(listener2.lost.isEmpty())
    }

    @Test
    fun `duplicate onAvailable for same network is collapsed`() {
        val manager = BondingNetworkManager(ApplicationProvider.getApplicationContext())
        val listener = RecordingListener()
        manager.listener = listener
        manager.start()
        val callback = registeredCallbacks(manager)[0]

        val net = testNetwork(102)
        callback.onAvailable(net)
        callback.onAvailable(net) // capability flap re-fires availability
        assertEquals(1, listener.available.size)

        callback.onLost(net)
        callback.onLost(net) // second loss was never announced — ignored
        assertEquals(1, listener.lost.size)

        manager.stop()
    }

    @Test
    fun `loss before availability is ignored`() {
        val manager = BondingNetworkManager(ApplicationProvider.getApplicationContext())
        val listener = RecordingListener()
        manager.listener = listener
        manager.start()
        val callback = registeredCallbacks(manager)[0]

        callback.onLost(testNetwork(103))
        assertTrue(listener.lost.isEmpty())

        manager.stop()
    }
}
