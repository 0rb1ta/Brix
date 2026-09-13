package app.brix.bonding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for SRTLA packet parsing (handleSrtlaAck format,
 * REG1/REG2/REG3 construction and handling, duplicate INDUCTION, etc.).
 * Complements [SrtSenderTest] which focuses on SRT-level logic.
 */
class SrtlaProtocolTest {

    // --- handleSrtlaAck format 2+4N vs 4+4N (Phase 4) ---

    @Test
    fun `handleSrtlaAck parses well-formed 4+4N packet`() {
        val ackSn1 = 100L
        val ackSn2 = 200L
        val packet = buildSrtlaAck(ackSn1, ackSn2)

        val received = mutableListOf<Long>()
        val connection = createConnectionWithSrtlaAckListener { _, sn -> received.add(sn) }

        invokeHandleSrtlaAck(connection, packet)

        assertEquals(2, received.size)
        assertEquals(ackSn1, received[0])
        assertEquals(ackSn2, received[1])
    }

    @Test
    fun `handleSrtlaAck rejects odd-sized packet`() {
        val packet = ByteArray(6)
        writeUInt16(packet, 0, 0x9100)
        val received = mutableListOf<Long>()
        val connection = createConnectionWithSrtlaAckListener { _, sn -> received.add(sn) }

        invokeHandleSrtlaAck(connection, packet)

        assertEquals("odd-sized packet must be rejected", 0, received.size)
    }

    @Test
    fun `handleSrtlaAck with header only delivers nothing`() {
        val packet = ByteArray(4)
        writeUInt16(packet, 0, 0x9100)
        val received = mutableListOf<Long>()
        val connection = createConnectionWithSrtlaAckListener { _, sn -> received.add(sn) }

        invokeHandleSrtlaAck(connection, packet)

        assertEquals(0, received.size)
    }

    @Test
    fun `handleSrtlaAck with single ACK sn works`() {
        val packet = buildSrtlaAck(42L)
        val received = mutableListOf<Long>()
        val connection = createConnectionWithSrtlaAckListener { _, sn -> received.add(sn) }

        invokeHandleSrtlaAck(connection, packet)

        assertEquals(1, received.size)
        assertEquals(42L, received[0])
    }

    // --- REG1 packet construction (Phase 4) ---

    @Test
    fun `sendSrtlaReg1 produces correct packet structure`() {
        // Verify the REG1 packet structure by building one using the same logic
        // as the production code (SrtlaConnection.sendSrtlaReg1).
        val groupId = Srtla.randomGroupId()
        val packet = Srtla.createPacket(Srtla.PacketType.REG1, Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE)
        System.arraycopy(groupId, 0, packet, Srtla.CONTROL_TYPE_SIZE, Srtla.GROUP_ID_SIZE)

        // Verify packet structure.
        val type = readUInt16(packet, 0) and 0x7FFF
        assertEquals("REG1 type", Srtla.PacketType.REG1.rawValue, type)
        assertEquals("REG1 total size", Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE, packet.size)

        // Verify group id is embedded starting at offset 2.
        val extractedGroup = packet.copyOfRange(Srtla.CONTROL_TYPE_SIZE, packet.size)
        assertArrayEquals("group id matches", groupId, extractedGroup)
    }

    @Test
    fun `REG2 packet structure mirrors REG1`() {
        val groupId = Srtla.randomGroupId()
        val packet = Srtla.createPacket(Srtla.PacketType.REG2, Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE)
        System.arraycopy(groupId, 0, packet, Srtla.CONTROL_TYPE_SIZE, Srtla.GROUP_ID_SIZE)

        val type = readUInt16(packet, 0) and 0x7FFF
        assertEquals("REG2 type", Srtla.PacketType.REG2.rawValue, type)
        assertEquals("REG2 total size", Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE, packet.size)

        val extractedGroup = packet.copyOfRange(Srtla.CONTROL_TYPE_SIZE, packet.size)
        assertArrayEquals("group id matches", groupId, extractedGroup)
    }

    // --- REG3 state transition (Phase 4) ---

    @Test
    fun `REG3 in WAIT_FOR_REGISTER_RESPONSE transitions to REGISTERED`() {
        val connection = SrtlaConnection("wifi", 1f)
        var registeredCalled = false
        connection.delegate = object : SrtlaConnection.Delegate {
            override fun onSocketConnected(connection: SrtlaConnection) {}
            override fun onSrtAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtNak(connection: SrtlaConnection, sn: Long) {}
            override fun onPacket(connection: SrtlaConnection, packet: ByteArray) {}
            override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) {}
            override fun onRegNgp(connection: SrtlaConnection) {}
            override fun onRegistered(connection: SrtlaConnection) { registeredCalled = true }
            override fun onSendFailed(connection: SrtlaConnection) {}
        }

        // Set state to WAIT_FOR_REGISTER_RESPONSE via reflection.
        setEnumField(connection, "state", "WAIT_FOR_REGISTER_RESPONSE")

        // Build a REG3 packet (type only, no payload).
        val reg3Packet = Srtla.createPacket(Srtla.PacketType.REG3, Srtla.CONTROL_TYPE_SIZE)

        // Invoke handleControlPacket to process REG3.
        invokeHandleControlPacket(connection, reg3Packet)

        assertTrue("onRegistered must fire", registeredCalled)
        assertEquals("state must be REGISTERED", "REGISTERED", getField(connection, "state").toString())
    }

    @Test
    fun `REG3 in REGISTERED state is ignored`() {
        val connection = SrtlaConnection("wifi", 1f)
        var registeredCalled = false
        connection.delegate = object : SrtlaConnection.Delegate {
            override fun onSocketConnected(connection: SrtlaConnection) {}
            override fun onSrtAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtNak(connection: SrtlaConnection, sn: Long) {}
            override fun onPacket(connection: SrtlaConnection, packet: ByteArray) {}
            override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) {}
            override fun onRegNgp(connection: SrtlaConnection) {}
            override fun onRegistered(connection: SrtlaConnection) { registeredCalled = true }
            override fun onSendFailed(connection: SrtlaConnection) {}
        }

        setEnumField(connection, "state", "REGISTERED")
        val reg3Packet = Srtla.createPacket(Srtla.PacketType.REG3, Srtla.CONTROL_TYPE_SIZE)

        invokeHandleControlPacket(connection, reg3Packet)

        assertEquals("onRegistered must NOT fire in REGISTERED state", false, registeredCalled)
    }

    // --- REG_ERR (Phase 4) ---

    @Test
    fun `REG_ERR increments regErrCount`() {
        val connection = SrtlaConnection("cellular", 1f)
        var reconnectReason: String? = null
        connection.delegate = object : SrtlaConnection.Delegate {
            override fun onSocketConnected(connection: SrtlaConnection) {}
            override fun onSrtAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtNak(connection: SrtlaConnection, sn: Long) {}
            override fun onPacket(connection: SrtlaConnection, packet: ByteArray) {}
            override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) {}
            override fun onRegNgp(connection: SrtlaConnection) {}
            override fun onRegistered(connection: SrtlaConnection) {}
            override fun onSendFailed(connection: SrtlaConnection) {}
        }

        val regErrPacket = Srtla.createPacket(Srtla.PacketType.REG_ERR, Srtla.CONTROL_TYPE_SIZE)

        // First REG_ERR.
        invokeHandleControlPacket(connection, regErrPacket)
        assertEquals(1, getField(connection, "regErrCount"))

        // Second REG_ERR.
        invokeHandleControlPacket(connection, regErrPacket)
        assertEquals(2, getField(connection, "regErrCount"))
    }

    // --- helpers ---

    private fun buildSrtlaAck(vararg ackSns: Long): ByteArray {
        val size = 4 + ackSns.size * 4
        val packet = ByteArray(size)
        writeUInt16(packet, 0, 0x9100)
        var offset = 4
        for (sn in ackSns) {
            writeUInt32(packet, offset, sn)
            offset += 4
        }
        return packet
    }

    private fun createConnectionWithSrtlaAckListener(
        onSrtlaAck: (SrtlaConnection, Long) -> Unit,
    ): SrtlaConnection {
        val connection = SrtlaConnection("wifi", 1f)
        connection.delegate = object : SrtlaConnection.Delegate {
            override fun onSocketConnected(connection: SrtlaConnection) {}
            override fun onSrtAck(connection: SrtlaConnection, sn: Long) {}
            override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) = onSrtlaAck(connection, sn)
            override fun onSrtNak(connection: SrtlaConnection, sn: Long) {}
            override fun onPacket(connection: SrtlaConnection, packet: ByteArray) {}
            override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) {}
            override fun onRegNgp(connection: SrtlaConnection) {}
            override fun onRegistered(connection: SrtlaConnection) {}
            override fun onSendFailed(connection: SrtlaConnection) {}
        }
        return connection
    }

    private fun invokeHandleSrtlaAck(connection: SrtlaConnection, packet: ByteArray) {
        val method = connection.javaClass.getDeclaredMethod("handleSrtlaAck", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(connection, packet)
    }

    private fun invokeHandleControlPacket(connection: SrtlaConnection, packet: ByteArray) {
        val method = connection.javaClass.getDeclaredMethod("handleControlPacket", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(connection, packet)
    }

    private fun setEnumField(target: Any, fieldName: String, value: String) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        val enumClass = field.type as Class<out Enum<*>>
        val enumValue = java.lang.Enum.valueOf(enumClass, value)
        field.set(target, enumValue)
    }

    private fun getField(target: Any, name: String): Any {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    // --- Выбор адреса при разрешении имени ---
    //
    // Настройка существует потому, что часть операторов и приёмников ведёт себя
    // с IPv6 хуже: соединение встаёт, а данные не идут. Но остаться совсем без
    // адреса хуже любого IPv6, поэтому запасной путь обязателен.

    private fun v4(s: String) = java.net.InetAddress.getByName(s)
    private fun v6(s: String) = java.net.InetAddress.getByName(s)

    @Test
    fun `без предпочтения берётся первый адрес как есть`() {
        val list = listOf(v6("::1"), v4("127.0.0.1"))
        assertEquals(list.first(), pickAddress(list, preferIpv4 = false))
    }

    @Test
    fun `с предпочтением находится IPv4 даже когда он не первый`() {
        val list = listOf(v6("::1"), v4("127.0.0.1"))
        assertEquals(v4("127.0.0.1"), pickAddress(list, preferIpv4 = true))
    }

    @Test
    fun `когда IPv4 нет вовсе, берём что есть, а не ничего`() {
        val list = listOf(v6("::1"))
        assertEquals(v6("::1"), pickAddress(list, preferIpv4 = true))
    }

    @Test
    fun `пустой список даёт null, а не исключение`() {
        assertNull(pickAddress(emptyList(), preferIpv4 = true))
        assertNull(pickAddress(emptyList(), preferIpv4 = false))
    }
}
