package app.brix.bonding

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 race coverage: REG_ERR -> delayed retry must never resurrect a stopped
 * connection (real sockets, real read threads), and a still-running
 * connection must actually re-establish its socket when the retry fires.
 */
class SrtlaConnectionRaceTest {

    private val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
    private val registeredState = java.lang.Enum.valueOf(
        stateClass as Class<out Enum<*>>,
        "REGISTERED",
    )

    private class RecordingDelegate : SrtlaConnection.Delegate {
        var sendFailedAfterStop = 0
        var registeredCount = 0
        override fun onSocketConnected(connection: SrtlaConnection) = Unit
        override fun onRegNgp(connection: SrtlaConnection) = Unit
        override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) = Unit
        override fun onRegistered(connection: SrtlaConnection) { registeredCount++ }
        override fun onPacket(connection: SrtlaConnection, packet: ByteArray) = Unit
        override fun onSrtAck(connection: SrtlaConnection, sn: Long) = Unit
        override fun onSrtNak(connection: SrtlaConnection, sn: Long) = Unit
        override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) = Unit
        override fun onSendFailed(connection: SrtlaConnection) { sendFailedAfterStop++ }
    }

    private fun startRegisteredConnection(fake: FakeSrtlaRec, delegate: RecordingDelegate): SrtlaConnection {
        val connection = SrtlaConnection("cellular", 1f, null)
        connection.delegate = delegate
        connection.start("127.0.0.1", fake.port)
        assertTrue(
            "connection did not start within 3s",
            awaitTrue(3_000) { getField(connection, "running") == true },
        )
        setField(connection, "state", registeredState)
        return connection
    }

    @Test
    fun `REG_ERR packet schedules a delayed reconnect`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = startRegisteredConnection(fake, RecordingDelegate())
            val localPort = (getField(connection, "socket") as DatagramSocket).localPort

            val type = Srtla.PacketType.REG_ERR.rawValue or Srt.CONTROL_PACKET_TYPE_BIT
            val payload = byteArrayOf(
                ((type shr 8) and 0xFF).toByte(),
                (type and 0xFF).toByte(),
            )
            // The connection socket is connect()ed to the fake receiver, so the
            // packet must come FROM that exact peer or the kernel drops it.
            fake.socket.send(
                DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), localPort),
            )

            assertTrue(
                "REG_ERR did not schedule a reconnect within 2s",
                awaitTrue(2_000) { getField(connection, "pendingReconnect") != null },
            )
            @Suppress("UNCHECKED_CAST")
            assertEquals(1, getField(connection, "regErrCount"))

            connection.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `stop during pending delayed retry prevents resurrection`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val delegate = RecordingDelegate()
            val connection = startRegisteredConnection(fake, delegate)

            invokeScheduleReconnect(connection, 300L)
            assertNotNull(getField(connection, "pendingReconnect"))
            val oldThread = getField(connection, "readThread") as Thread?

            connection.stop()

            // Wait well past the retry deadline: if the guard were broken,
            // reconnect() would install a new socket at ~300ms.
            Thread.sleep(900)

            assertEquals(false, getField(connection, "started"))
            assertEquals(false, getField(connection, "running"))
            val threadNow = getField(connection, "readThread") as Thread?
            assertTrue(threadNow === oldThread || threadNow?.isAlive != true)
            assertEquals(
                "onSendFailed fired after stop — stale retry reached reconnect()",
                0,
                delegate.sendFailedAfterStop,
            )
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `delayed retry re-establishes socket when still running`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = startRegisteredConnection(fake, RecordingDelegate())
            val oldThread = getField(connection, "readThread") as Thread?
            val oldSocket = getField(connection, "socket") as DatagramSocket

            invokeScheduleReconnect(connection, 200L)

            assertTrue(
                "retry did not replace the read thread within 3s",
                awaitTrue(3_000) {
                    val t = getField(connection, "readThread") as Thread?
                    t !== null && t !== oldThread && t.isAlive
                },
            )
            assertEquals(true, getField(connection, "running"))
            assertNotSame(oldSocket, getField(connection, "socket"))
            oldSocket.close()
            connection.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `start with mismatched epoch is a no-op for stale queued events`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = SrtlaConnection("wifi", 1f, null)
            connection.attachEpoch(5L)

            connection.start("127.0.0.1", fake.port, epoch = 7L)
            Thread.sleep(300)

            assertEquals(false, getField(connection, "started"))
            assertFalse((getField(connection, "readThread") as Thread?)?.isAlive == true)
        } finally {
            fake.stop()
        }
    }

    /**
     * Карантин не должен воскрешать остановленный линк — та же защита, что уже
     * стоит на отложенном retry после REG_ERR, только вход другой.
     */
    @Test
    fun `stop during quarantine prevents resurrection`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val delegate = RecordingDelegate()
            val connection = startRegisteredConnection(fake, delegate)

            connection.quarantine("test", 300L)
            assertNotNull(getField(connection, "pendingReconnect"))
            val oldThread = getField(connection, "readThread") as Thread?

            connection.stop()
            Thread.sleep(900)

            assertEquals(false, getField(connection, "started"))
            assertEquals(false, getField(connection, "running"))
            val threadNow = getField(connection, "readThread") as Thread?
            assertTrue(threadNow === oldThread || threadNow?.isAlive != true)
            // stop() обязан обнулить и лестницу, иначе следующая сессия
            // стартовала бы уже с накопленной задержкой.
            assertEquals(false, getField(connection, "quarantinePending"))
            assertEquals(0, getField(connection, "quarantineAttempts"))
        } finally {
            fake.stop()
        }
    }

    /**
     * Успешная регистрация снимает карантин и обнуляет лестницу задержек:
     * линк вернулся в группу, и следующий отказ должен снова начинаться с
     * секунды, а не с накопленной минуты.
     */
    @Test
    fun `successful registration clears quarantine`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val delegate = RecordingDelegate()
            val connection = startRegisteredConnection(fake, delegate)

            // Уводим в карантин с большой задержкой, чтобы попытка не успела
            // отработать и состояние осталось наблюдаемым.
            connection.quarantine("test", 60_000L)
            assertEquals(true, getField(connection, "quarantinePending"))
            assertEquals(1, getField(connection, "quarantineAttempts"))

            // Регистрация приходит своим путём: состояние WAIT_FOR_REGISTER_RESPONSE
            // плюс REG3 от приёмника.
            setField(connection, "state", waitForRegisterResponseState)
            invokePrivate(connection, "handleSrtlaReg3")

            assertEquals(false, getField(connection, "quarantinePending"))
            assertEquals(0, getField(connection, "quarantineAttempts"))
            assertEquals(1, delegate.registeredCount)
            connection.stop()
        } finally {
            fake.stop()
        }
    }

    /**
     * Лестница растёт, а не топчется на месте: каждый следующий вход в карантин
     * увеличивает номер попытки. Именно счётчик определяет задержку.
     */
    @Test
    fun `repeated quarantine walks up the ladder`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = startRegisteredConnection(fake, RecordingDelegate())
            for (expected in 1..3) {
                connection.quarantine("test", 60_000L)
                assertEquals(expected, getField(connection, "quarantineAttempts"))
                // Снимаем засов вручную — в бою его снимает отложенная попытка
                // прямо перед стартом.
                setField(connection, "quarantinePending", false)
            }
            connection.stop()
        } finally {
            fake.stop()
        }
    }

    private val waitForRegisterResponseState = java.lang.Enum.valueOf(
        stateClass as Class<out Enum<*>>,
        "WAIT_FOR_REGISTER_RESPONSE",
    )

    private fun invokePrivate(target: Any, name: String) {
        val method = target.javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(target)
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

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }
}
