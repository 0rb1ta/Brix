package app.brix.bonding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic unit tests for SrtlaClient teardown and connection lifecycle.
 *
 * The full REG1/REG2/REG3 happy path is covered on-device (and is inherently
 * async over UDP, so it is not a reliable unit test). Here we test the parts
 * that must be deterministic: removing/clearing connections stops them, and
 * state resets on stop.
 */
class SrtlaClientTest {

    @Test
    fun `clearConnections stops and removes all connections`() {
        val client = SrtlaClient()
        val a = client.addConnection("wifi", 1f)
        val b = client.addConnection("cellular", 1f)
        assertEquals(2, client.connectionCount())

        client.clearConnections()

        assertEquals(0, client.connectionCount())
        assertFalse(isConnectionStarted(a))
        assertFalse(isConnectionStarted(b))
    }

    @Test
    fun `removeConnection stops the given connection only`() {
        val client = SrtlaClient()
        val a = client.addConnection("wifi", 1f)
        val b = client.addConnection("cellular", 1f)

        client.removeConnection(a)

        assertEquals(1, client.connectionCount())
        assertFalse(isConnectionStarted(a))
        // b was never started, so it stays untouched.
        assertFalse(isConnectionStarted(b))
    }

    @Test
    fun `isStarted reflects client state`() {
        val client = SrtlaClient()
        assertFalse("idle client is not started", client.isStarted())
        client.start("127.0.0.1", 1)
        assertTrue("client is started after start()", client.isStarted())
        client.stop()
        assertFalse("client is not started after stop", client.isStarted())
    }

    @Test
    fun `stop invalidates callbacks from the prior transport epoch`() {
        val client = SrtlaClient()
        val connection = client.addConnection("wifi", 1f)

        client.start("127.0.0.1", 1)
        val startEpoch = (getField(client, "epoch") as AtomicLong).get()
        assertTrue(connection.matchesEpoch(startEpoch))

        client.stop()

        assertFalse(connection.matchesEpoch((getField(client, "epoch") as AtomicLong).get()))
    }

    @Test
    fun `stop during socket setup cannot install a zombie socket`() {
        val bindEntered = CountDownLatch(1)
        val unblockBind = CountDownLatch(1)
        val connection = SrtlaConnection(
            "wifi",
            1f,
            bindSocket = {
                bindEntered.countDown()
                assertTrue(unblockBind.await(5, TimeUnit.SECONDS))
            },
        )
        val starter = Thread { connection.start("127.0.0.1", 1) }

        starter.start()
        assertTrue(bindEntered.await(5, TimeUnit.SECONDS))
        connection.stop()
        unblockBind.countDown()
        starter.join(5_000)

        assertFalse(isConnectionStarted(connection))
        assertFalse(getField(connection, "running") as Boolean)
        assertNull(getField(connection, "socket"))
        assertNull(getField(connection, "readThread"))
    }

    @Test
    fun `addConnection registers a connection that can be counted`() {
        val client = SrtlaClient()
        assertEquals(0, client.connectionCount())
        client.addConnection("wifi", 1f)
        client.addConnection("cellular", 1f)
        assertEquals(2, client.connectionCount())
    }

    private fun isConnectionStarted(connection: SrtlaConnection): Boolean {
        val field = connection.javaClass.getDeclaredField("started")
        field.isAccessible = true
        return field.getBoolean(connection)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    /** Карантин отказывается работать на неподнятом линке (`started`/`running`),
     *  а `addConnection` соединение не стартует — поднимаем флаги вручную, как
     *  и состояние в [setRegistered]. */
    private fun setLive(connection: SrtlaConnection) {
        for (name in listOf("started", "running")) {
            val field = connection.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.setBoolean(connection, true)
        }
    }

    private fun setRegistered(connection: SrtlaConnection) {
        val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
        val registered = java.lang.Enum.valueOf(stateClass as Class<out Enum<*>>, "REGISTERED")
        val field = connection.javaClass.getDeclaredField("state")
        field.isAccessible = true
        field.set(connection, registered)
    }

    @Test
    fun `send failure quarantines dead path when another live path exists`() {
        val client = SrtlaClient()
        val dead = client.addConnection("wifi", 1f)
        val live = client.addConnection("cellular", 1f)
        setRegistered(dead)
        setRegistered(live)

        assertEquals(SrtlaClient.SendFailureAction.QUARANTINE, client.decideSendFailure(dead))
    }

    @Test
    fun `send failure retries immediately when it is the only path`() {
        val client = SrtlaClient()
        val only = client.addConnection("cellular", 1f)
        setRegistered(only)

        assertEquals(SrtlaClient.SendFailureAction.RETRY_NOW, client.decideSendFailure(only))
    }

    @Test
    fun `disabled second path does not count as live`() {
        val client = SrtlaClient()
        val dead = client.addConnection("wifi", 1f)
        setRegistered(dead)
        // Второй линк есть, но выключен весом (priority 0) -> score() = -1,
        // раздавать по нему нельзя, значит отказавший линк остаётся
        // единственным и чинится немедленно.
        client.addConnection("cellular", 0f)

        assertEquals(SrtlaClient.SendFailureAction.RETRY_NOW, client.decideSendFailure(dead))
    }

    /**
     * Главное свойство карантина: линк остаётся в бондинге. До правки при живом
     * соседе делался `removeConnection`, и вернуться линк уже не мог —
     * `BondingNetworkManager` схлопывает повторный `onAvailable` для той же
     * `Network`, а интерфейс никуда не девался.
     */
    @Test
    fun `quarantined link stays in the client and leaves the rotation`() {
        val client = SrtlaClient()
        val dead = client.addConnection("wifi", 1f)
        val live = client.addConnection("cellular", 1f)
        setRegistered(dead)
        setRegistered(live)
        setLive(dead)
        assertTrue(dead.score() >= 0)

        dead.quarantine("test")

        assertEquals(2, client.connectionCount())
        assertEquals(-1, dead.score())
        assertTrue(live.score() >= 0)
    }

    /**
     * `onSendFailed` прилетает на каждый неудавшийся пакет — при мёртвой сети
     * это сотни раз в секунду. Засов должен пропустить ровно один подъём.
     */
    @Test
    fun `a burst of quarantine calls schedules exactly one retry`() {
        val client = SrtlaClient()
        val dead = client.addConnection("wifi", 1f)
        setRegistered(dead)
        setLive(dead)

        repeat(100) { dead.quarantine("burst") }

        assertEquals(1, getField(dead, "quarantineAttempts") as Int)
        assertEquals(true, getField(dead, "quarantinePending") as Boolean)
    }
}
