package app.brix.bonding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ScheduledFuture

class SrtlaConnectionSelectTest {

    private fun registeredConnection(type: String): SrtlaConnection {
        val connection = SrtlaConnection(type, 1f, null)
        val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
        val registered = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "REGISTERED")
        setField(connection, "state", registered)
        return connection
    }

    @Test
    fun `connection with no data yet is fresh`() {
        val connection = registeredConnection("cellular")
        setField(connection, "everReceivedData", false)
        assertTrue(connection.isDataFresh(nowMillis()))
        assertTrue(connection.score() >= 0)
    }

    @Test
    fun `connection with recent data is fresh and selectable`() {
        val connection = registeredConnection("wifi")
        setField(connection, "everReceivedData", true)
        setField(connection, "lastDataReceivedAt", nowMillis())
        assertTrue(connection.isDataFresh(nowMillis()))
        assertTrue(connection.score() >= 0)
    }

    @Test
    fun `stale connection is not fresh but remains selectable as last resort`() {
        val connection = registeredConnection("wifi")
        setField(connection, "everReceivedData", true)
        setField(connection, "lastDataReceivedAt", nowMillis() - 10_000)
        assertFalse(connection.isDataFresh(nowMillis()))
        // Not vetoed: score stays >= 0 so it can still carry traffic when it is
        // the only remaining path (the freshness check lives in selectConnection).
        assertTrue(connection.score() >= 0)
    }

    @Test
    fun `selectConnection prefers the fresh-data path over a stale one`() {
        val client = SrtlaClient()
        val wifi = client.addConnection("wifi", 1f)
        val cellular = client.addConnection("cellular", 1f)
        // Both registered.
        for (c in listOf(wifi, cellular)) {
            val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
            val registered = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "REGISTERED")
            setField(c, "state", registered)
            setField(c, "everReceivedData", true)
        }
        // Wifi stopped receiving media (server feeds last_address elsewhere),
        // cellular is freshly carrying data -> cellular must win.
        setField(wifi, "lastDataReceivedAt", nowMillis() - 10_000)
        setField(cellular, "lastDataReceivedAt", nowMillis())

        val selected = invokeSelect(client)
        assertEquals(cellular, selected)
    }

    @Test
    fun `selectConnection falls back to stale path when it is the only one`() {
        val client = SrtlaClient()
        val cellular = client.addConnection("cellular", 1f)
        val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
        val registered = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "REGISTERED")
        setField(cellular, "state", registered)
        setField(cellular, "everReceivedData", true)
        setField(cellular, "lastDataReceivedAt", nowMillis() - 10_000)

        val selected = invokeSelect(client)
        assertEquals(cellular, selected)
    }

    @Test
    fun `onTick retries REG2 while waiting for REG3`() {
        val connection = SrtlaConnection("cellular", 1f, null)
        val waiting = java.lang.Enum.valueOf(
            Class.forName("app.brix.bonding.SrtlaConnection\$State") as Class<out Enum<*>>,
            "WAIT_FOR_REGISTER_RESPONSE",
        )
        setField(connection, "state", waiting)
        setField(connection, "lastReg2SendTime", nowMillis() - 2_000)
        setField(connection, "reg2Retries", 0)

        invokeTick(connection)
        assertEquals(1, getField(connection, "reg2Retries"))
    }

    @Test
    fun `onTick stops retrying and reconnects after max REG2 retries`() {
        val connection = SrtlaConnection("cellular", 1f, null)
        val waiting = java.lang.Enum.valueOf(
            Class.forName("app.brix.bonding.SrtlaConnection\$State") as Class<out Enum<*>>,
            "WAIT_FOR_REGISTER_RESPONSE",
        )
        setField(connection, "state", waiting)
        setField(connection, "lastReg2SendTime", nowMillis() - 2_000)
        // Already exhausted retries; socket null means reconnect's startInternal
        // will fail, but the retry counter must not grow further.
        setField(connection, "reg2Retries", 2)

        invokeTick(connection)
        assertEquals(2, getField(connection, "reg2Retries"))
    }

    @Test
    fun `stop cancels delayed registration reconnect`() {
        val connection = SrtlaConnection("cellular", 1f, null)
        setField(connection, "started", true)
        setField(connection, "running", true)

        invokeScheduleReconnect(connection, 5_000L)
        @Suppress("UNCHECKED_CAST")
        val future = getField(connection, "pendingReconnect") as ScheduledFuture<*>

        connection.stop()

        assertTrue(future.isCancelled)
        assertNull(getField(connection, "pendingReconnect"))
    }

    @Test
    fun `retransmitConclusion does nothing when CONNECTED`() {
        val sender = SrtSender(streamId = null, latency = 2000)
        val stateClass = Class.forName("app.brix.bonding.SrtSenderState")
        val connected = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "CONNECTED")
        setField(sender, "state", connected)
        // A stored conclusion exists, but we are already connected.
        setField(sender, "lastConclusionPacket", byteArrayOf(1, 2, 3))
        var output = 0
        sender.onOutput = { output++ }
        sender.retransmitConclusion()
        assertEquals(0, output)
    }

    @Test
    fun `retransmitConclusion resends when still connecting`() {
        val sender = SrtSender(streamId = null, latency = 2000)
        val stateClass = Class.forName("app.brix.bonding.SrtSenderState")
        val connecting = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "CONNECTING")
        setField(sender, "state", connecting)
        setField(sender, "lastConclusionPacket", byteArrayOf(1, 2, 3))
        var output = 0
        sender.onOutput = { output++ }
        sender.retransmitConclusion()
        assertEquals(1, output)
    }

    private fun invokeSelect(client: SrtlaClient): SrtlaConnection? {
        val method = client.javaClass.getDeclaredMethod("selectConnection")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(client) as SrtlaConnection?
    }

    @Test
    fun `selectDataConnection biases toward the higher-weight channel with a live downlink`() {
        val client = SrtlaClient()
        val wifi = client.addConnection("wifi", 80f)
        val cellular = client.addConnection("cellular", 20f)
        val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
        val registered = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "REGISTERED")
        for (c in listOf(wifi, cellular)) {
            setField(c, "state", registered)
            setField(c, "windowSize", 20000)
            setField(c, "latestReceivedTime", nowMillis())
        }
        var wifiCount = 0
        val n = 2000
        repeat(n) { if (invokeSelectData(client) == wifi) wifiCount++ }
        assertTrue("higher-weight channel should get the majority (wifi=$wifiCount/$n)", wifiCount > n / 2)
    }

    @Test
    fun `selectDataConnection still aggregates across links when no SRT-DATA was ever received`() {
        // The overwhelmingly common real case: an upload-only stream, where
        // the receiver only ever sends back control (ACK/NAK/keepalive), so
        // everReceivedData never flips true on any link. hasRecentInbound
        // must still recognize these links as alive via latestReceivedTime
        // (updated on ANY inbound packet, including per-connection SRTLA
        // keepalive replies) — otherwise selectDataConnection's pool is
        // permanently empty and aggregation silently collapses to one link.
        val client = SrtlaClient()
        val wifi = client.addConnection("wifi", 80f)
        val cellular = client.addConnection("cellular", 20f)
        val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
        val registered = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "REGISTERED")
        for (c in listOf(wifi, cellular)) {
            setField(c, "state", registered)
            setField(c, "windowSize", 20000)
            setField(c, "latestReceivedTime", nowMillis())
            assertFalse(getField(c, "everReceivedData") as Boolean)
        }

        val seen = mutableSetOf<SrtlaConnection>()
        repeat(200) { invokeSelectData(client)?.let { seen.add(it) } }

        assertTrue("both links should be selectable with no SRT-DATA ever received", seen.size == 2)
    }

    private fun invokeSelectData(client: SrtlaClient): SrtlaConnection? {
        val method = client.javaClass.getDeclaredMethod("selectDataConnection")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(client) as SrtlaConnection?
    }

    private fun invokeTick(connection: SrtlaConnection) {
        val method = connection.javaClass.getDeclaredMethod("onTick")
        method.isAccessible = true
        method.invoke(connection)
    }

    private fun invokeScheduleReconnect(connection: SrtlaConnection, delayMs: Long) {
        val method = connection.javaClass.getDeclaredMethod(
            "scheduleReconnect",
            Long::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(connection, delayMs, "test", false)
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }
}
