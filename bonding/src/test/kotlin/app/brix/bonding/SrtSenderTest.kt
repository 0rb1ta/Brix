package app.brix.bonding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtSenderTest {

    private fun setFieldLong(target: Any, name: String, value: Long) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(target, value)
    }

    @Test
    fun `handshake conclusion connects and data packets get increasing seq`() {
        val h = SrtSenderHarness()
        h.connect()

        assertTrue("expected onConnected after handshake conclusion", h.connected)
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(3)

        assertEquals(3, h.dataPackets().size)
        assertEquals(listOf(0L, 1L, 2L), h.dataPackets().map { h.readSn(it) })
        assertEquals(3, h.sender.packetsInFlightCount())
    }

    @Test
    fun `ack with last seq clears the whole in-flight window`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        assertEquals(5, h.sender.packetsInFlightCount())
        h.clearOutput()

        h.sender.input(h.createAck(lastAckSn = 5L))

        assertEquals(0, h.sender.packetsInFlightCount())
    }

    @Test
    fun `future ack ahead of all in-flight also clears everything`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        h.sender.input(h.createAck(lastAckSn = 100_000L))

        assertEquals(0, h.sender.packetsInFlightCount())
    }

    @Test
    fun `ack with rtt triggers ackack and updates rtt`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        h.sender.input(h.createAck(lastAckSn = 4L, rttUs = 30_000L))

        assertEquals(30.0, h.sender.rttMs(), 0.001)
        assertEquals(1, h.controlPacketsOfType(Srt.PacketType.ACKACK.rawValue).size)
    }

    @Test
    fun `nak schedules retransmission of the lost packet`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(3)
        h.clearOutput()

        h.sender.input(h.createNak(single = listOf(1L)))
        // input() больше не запускает насос: отправка идёт в такт медиа, а не в
        // такт входящим подтверждениям (см. outputPackets). Повтор уходит
        // следующим же вызовом send(), то есть в пределах кадра.
        h.sender.send(20_000L)

        val retransmits = h.dataPackets()
        assertEquals(1, retransmits.size)
        assertEquals(1L, h.readSn(retransmits[0]))
        assertTrue("expected retransmission bit set", h.isRetransmitted(retransmits[0]))
        assertTrue("lost packet stays in flight after retransmit", h.sender.packetsInFlightCount() >= 3)
    }

    @Test
    fun `nak within rtt keeps packet queued and retransmits again after rtt`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(3)
        h.clearOutput()
        // Force a non-zero RTT so the within-RTT guard is exercised (C4).
        setFieldLong(h.sender, "rttUs", 1_000_000L)

        // First NAK → packet is retransmitted immediately.
        h.sender.input(h.createNak(single = listOf(1L)))
        h.sender.send(20_000L)
        assertEquals(1, h.dataPackets().size)
        h.clearOutput()

        // A second NAK arriving within one RTT must NOT drop the SN: it stays
        // queued (no retransmit yet, because we just sent it).
        h.sender.input(h.createNak(single = listOf(1L)))
        h.sender.send(21_000L)
        assertEquals("no retransmit within RTT window", 0, h.dataPackets().size)
        h.clearOutput()

        // After the RTT elapses the still-queued SN is retransmitted again.
        Thread.sleep(1100)
        h.sender.input(h.createNak(single = listOf(1L)))
        h.sender.send(nowMicros())
        val retransmits = h.dataPackets()
        assertEquals("retransmit again once RTT elapsed", 1, retransmits.size)
        assertEquals(1L, h.readSn(retransmits[0]))
        assertTrue("retransmission bit set", h.isRetransmitted(retransmits[0]))
    }

    /**
     * Проход насоса пересылает ВСЕ подходящие номера разом и в порядке очереди.
     *
     * Прежняя реализация отправляла по одному пакету за вызов и ради каждого
     * заново сканировала всю очередь повторов, перекладывая пропущенные через
     * временный список. Поведение здесь то же — тест держит его, чтобы правка
     * на стоимость не съехала в правку на смысл.
     */
    @Test
    fun `все запрошенные повторы уходят за один проход и по порядку`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(200)
        h.clearOutput()

        val lost = listOf(3L, 17L, 42L, 99L, 150L, 199L)
        h.sender.input(h.createNak(single = lost))
        h.sender.send(20_000L)

        val sent = h.dataPackets().mapNotNull { h.readSn(it) }
        assertEquals(lost, sent)
        assertTrue("на всех должен стоять бит повтора", h.dataPackets().all { h.isRetransmitted(it) })
    }

    /**
     * Очередь повторов ограничена тысячей номеров (см. handleNakPacket). На
     * полной очереди один проход насоса обязан отработать целиком: раньше он
     * стоил квадрата от её длины — сканирование плюс перекладывание списка на
     * КАЖДЫЙ отправленный пакет. Стреляло это ровно при потерях, то есть когда
     * запаса и так нет.
     */
    @Test
    fun `полная очередь повторов расходится за один проход`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(1000)
        h.clearOutput()

        h.sender.input(h.createNak(ranges = listOf(NakRange(from = 0L, upTo = 999L))))
        h.sender.send(20_000L)

        val sent = h.dataPackets().mapNotNull { h.readSn(it) }
        assertEquals(1000, sent.size)
        assertEquals((0L until 1000L).toList(), sent)
    }

    /**
     * Пропущенные по окну RTT не выбрасываются и не уезжают в конец очереди:
     * они остаются на своих местах и уходят следующим проходом, когда окно
     * истечёт. Раньше их вынимали и возвращали через addAll, то есть порядок
     * номеров перемешивался.
     */
    @Test
    fun `пропущенные по RTT остаются в очереди и уходят следующим проходом`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(10)
        h.clearOutput()
        setFieldLong(h.sender, "rttUs", 1_000_000L)

        // Первый проход: 1 и 2 пересылаются и выпадают из очереди.
        h.sender.input(h.createNak(single = listOf(1L, 2L)))
        h.sender.send(20_000L)
        assertEquals(listOf(1L, 2L), h.dataPackets().mapNotNull { h.readSn(it) })
        h.clearOutput()

        // Второй проход внутри того же RTT: 1 и 2 запрошены снова и обязаны
        // остаться в очереди, а свежие 5 и 6 — уйти.
        h.sender.input(h.createNak(single = listOf(1L, 2L, 5L, 6L)))
        h.sender.send(21_000L)
        assertEquals(listOf(5L, 6L), h.dataPackets().mapNotNull { h.readSn(it) })
        h.clearOutput()

        // Окно RTT истекло — придержанные 1 и 2 уходят, и именно в своём порядке.
        h.sender.send(20_000L + 1_100_000L)
        assertEquals(listOf(1L, 2L), h.dataPackets().mapNotNull { h.readSn(it) })
    }

    /** Номер, который приёмник успел подтвердить, из очереди повторов просто
     *  выпадает: пакета больше нет в полёте, пересылать нечего. */
    @Test
    fun `подтверждённый номер выпадает из очереди повторов`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(10)
        h.clearOutput()
        setFieldLong(h.sender, "rttUs", 1_000_000L)

        h.sender.input(h.createNak(single = listOf(1L, 7L)))
        // Квитанция снимает с полёта всё до 5-го включительно, значит 1 уже не
        // нужен, а 7 ещё в полёте.
        h.sender.input(h.createAck(lastAckSn = 6L))
        h.sender.send(20_000L)

        assertEquals(listOf(7L), h.dataPackets().mapNotNull { h.readSn(it) })
    }

    @Test
    fun `nak range expands into each sequence number`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        h.sender.input(h.createNak(ranges = listOf(NakRange(from = 1L, upTo = 3L))))
        h.sender.send(20_000L)

        val packets = h.dataPackets()
        val retransmits = packets.map { h.readSn(it) }
        assertEquals(setOf(1L, 2L, 3L), retransmits.toSet())
        assertTrue(packets.all { h.isRetransmitted(it) })
    }

    @Test
    fun `nak range hard-limits at MAX_NAK_RANGE_SIZE`() {
        val received = mutableListOf<Long>()
        val packet = SrtTestPackets.nak(ranges = listOf(NakRange(from = 0L, upTo = 100_000L)))
        Srt.processNak(packet) { sn -> received.add(sn) }

        assertTrue(
            "must not exceed MAX_NAK_RANGE_SIZE (${Srt.MAX_NAK_RANGE_SIZE})",
            received.size <= Srt.MAX_NAK_RANGE_SIZE,
        )
    }

    @Test
    fun `nak range wrap-around iterates both sides of boundary`() {
        val received = mutableListOf<Long>()
        val packet = SrtTestPackets.nak(
            ranges = listOf(NakRange(from = 0x7FFF_FFF0L, upTo = 0x10L)),
        )
        Srt.processNak(packet) { sn -> received.add(sn) }

        assertTrue("should contain sns from start side", received.any { it >= 0x7FFF_FFF0L })
        assertTrue("should contain sns from end side", received.any { it <= 0x10L })
        assertTrue(
            "total must not exceed limit",
            received.size <= Srt.MAX_NAK_RANGE_SIZE,
        )
    }

    @Test
    fun `isSnAcked handles wrap-around at the 31-bit boundary`() {
        assertTrue("max sn acked by 0 (wrapped)", Srt.isSnAcked(0x7FFF_FFFFL, 0L))
        assertFalse("0 not acked by max sn (future direction)", Srt.isSnAcked(0L, 0x7FFF_FFFFL))
        assertFalse("номер из квитанции ещё НЕ получен", Srt.isSnAcked(0L, 0L))
        assertTrue("recently incremented seq is acked", Srt.isSnAcked(4L, 5L))
        assertFalse("next seq not acked by previous", Srt.isSnAcked(5L, 4L))
    }

    @Test
    fun `isSnAcked exclusive semantics — номер из квитанции ещё не подтверждён`() {
        // Прежняя версия этого теста называлась «inclusive semantics — exact ack
        // sn is acked» и закрепляла ошибку: номер в квитанции SRT это СЛЕДУЮЩИЙ
        // ОЖИДАЕМЫЙ пакет, подтверждено всё строго меньше него.
        //
        // Из-за включительного сравнения мы удаляли из буфера ровно тот пакет,
        // которого приёмнику не хватало. Поле 31.08: NAK всегда приходил на
        // номер на единицу меньше первого в нашем буфере (sn=8421 при окне
        // [8422..8661]), мы его не находили, повтор не уходил, и потеря
        // становилась вечной.
        assertFalse("сам номер квитанции НЕ подтверждён", Srt.isSnAcked(100L, 100L))
        assertFalse("то же на больших значениях", Srt.isSnAcked(0x7FFF_FFFFL, 0x7FFF_FFFFL))
        assertTrue("всё строго меньше — подтверждено", Srt.isSnAcked(99L, 100L))
        assertFalse("всё больше — ещё нет", Srt.isSnAcked(101L, 100L))
    }

    @Test
    fun `sequence number wraps to zero at 31-bit boundary`() {
        val h = SrtSenderHarness()
        setFieldLong(h.sender, "nextSequenceNumber", 0x7FFF_FFFEL)
        h.connect()
        h.clearOutput()

        h.enqueueAndSend(3)

        assertEquals(listOf(0x7FFF_FFFEL, 0x7FFF_FFFFL, 0L), h.dataPackets().map { h.readSn(it) })
    }

    @Test
    fun `no incoming packets for 5s disconnects`() {
        val h = SrtSenderHarness()
        h.connect()
        assertFalse(h.disconnected)

        setFieldLong(h.sender, "latestReceivedPacketTime", 1_000_000L)
        h.sender.send(7_000_000L)

        assertTrue("expected disconnect after timeout", h.disconnected)
    }

    @Test
    fun `control packet with wrong destination socket ID is ignored`() {
        val h = SrtSenderHarness()
        h.connect()
        h.clearOutput()

        // peerDestinationSocketId is 0x1234_5678 after connect().
        // Send an ACK with wrong destination socket ID — must be ignored.
        val wrongAck = ByteArray(24)
        writeUInt16(wrongAck, 0, 0x8000 or Srt.PacketType.ACK.rawValue)
        writeUInt32(wrongAck, 12, 0xDEAD_BEEFL) // wrong dst socket ID
        writeUInt32(wrongAck, 16, 5L) // lastAckSn
        h.sender.input(wrongAck)

        // RTT must not be updated (packet was rejected).
        assertEquals(0.0, h.sender.rttMs(), 0.001)
    }

    @Test
    fun `conclusion_sent gets a fresh no-inbound grace window`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }

        h.sender.start()
        // Induction reply received at t=1s; conclusion reply lost.
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xAAAA_0007L, synCookie = 0xBBBB_0008L))
        setFieldLong(h.sender, "latestReceivedPacketTime", 1_000_000L)

        // Ladder exhausted at t=5.5s -> provisional mode. Inbound silence is
        // expected here, so the watchdog must not count from the last packet.
        h.sender.enterConclusionSent()
        assertEquals("provisional media keeps the session alive", 1, connectedCalls)
        assertFalse(h.disconnected)

        // Grace starts at the first watchdog check after entering (~t=9.5s):
        // still alive at +4s...
        h.sender.send(9_500_000L)
        assertFalse("within the CONCLUSION_SENT grace window", h.disconnected)

        // ...but a truly dead path is cut once the grace elapses.
        h.sender.send(15_000_000L)
        assertTrue("expected disconnect once the grace window expires", h.disconnected)
    }

    @Test
    fun `shutdown control packet disconnects`() {
        val h = SrtSenderHarness()
        h.connect()
        assertFalse(h.disconnected)

        h.sender.input(SrtTestPackets.shutdown(dstSocketId = h.ourSocketId()))

        assertTrue("expected disconnect on shutdown", h.disconnected)
    }

    @Test
    fun `send rate is floored above zero so ABR throughput stays healthy`() {
        val h = SrtSenderHarness()
        h.connect()

        var now = 1_000_000L
        repeat(40) {
            h.sender.send(now)
            now += 300_000L
        }

        assertTrue(
            "expected floored send rate near 1.0, got ${h.sender.sendRateMbps()}",
            h.sender.sendRateMbps() >= 0.9,
        )
    }

    // --- SRT handshake (Phase 4 / Stage 3) ---

    @Test
    fun `start sends an induction handshake`() {
        val h = SrtSenderHarness()
        h.sender.start()
        val handshakes = h.controlPacketsOfType(Srt.PacketType.HANDSHAKE.rawValue)
        assertEquals(1, handshakes.size)
        // Induction packet is 64 bytes.
        assertEquals(64, handshakes[0].size)
    }

    @Test
    fun `induction response triggers a conclusion and stores it`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.clearOutput()

        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0x1111_2222L, synCookie = 0x3333_4444L))

        val handshakes = h.controlPacketsOfType(Srt.PacketType.HANDSHAKE.rawValue)
        assertEquals("expected one conclusion after induction", 1, handshakes.size)
        assertEquals("conclusion is 80 bytes (no stream id extension)", 80, handshakes[0].size)
        // Conclusion must be stored for later retransmit.
        assertNotNull(h.lastConclusionPacket())
        // We are still CONNECTING (server CONCLUSION not yet received).
        assertFalse(h.connected)
    }

    @Test
    fun `retransmitConclusion re-sends the stored conclusion without new induction`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0x1111_2222L, synCookie = 0x3333_4444L))
        h.clearOutput()

        h.sender.retransmitConclusion()

        val handshakes = h.controlPacketsOfType(Srt.PacketType.HANDSHAKE.rawValue)
        assertEquals("retransmit sends one conclusion, not a new induction", 1, handshakes.size)
        assertEquals(80, handshakes[0].size)
    }

    @Test
    fun `retransmitConclusion is a no-op after CONNECTED`() {
        val h = SrtSenderHarness()
        h.connect() // -> CONNECTED
        h.clearOutput()
        h.sender.retransmitConclusion()
        assertEquals(0, h.output.size)
    }

    @Test
    fun `server conclusion promotes to CONNECTED and clears stored conclusion`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0x1111_2222L, synCookie = 0x3333_4444L))
        assertFalse(h.connected)

        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0x5555_6666L))

        assertTrue("expected CONNECTED after server conclusion", h.connected)
        assertNull("stored conclusion cleared once connected", h.lastConclusionPacket())
    }

    @Test
    fun `onConnected and onDisconnected fire exactly once each`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        var disconnectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }
        h.sender.onDisconnected = { disconnectedCalls++ }

        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 1L, synCookie = 2L))
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 3L))
        h.sender.input(SrtTestPackets.shutdown(dstSocketId = h.ourSocketId()))

        assertEquals(1, connectedCalls)
        assertEquals(1, disconnectedCalls)
    }

    @Test
    fun `stop sends a shutdown packet so the server releases the publisher`() {
        val h = SrtSenderHarness()
        h.connect()
        h.clearOutput()

        h.sender.stop()

        val shutdowns = h.controlPacketsOfType(Srt.PacketType.SHUTDOWN.rawValue)
        assertEquals("expected exactly one SHUTDOWN on graceful stop", 1, shutdowns.size)
    }

    @Test
    fun `stop without connection does not send a shutdown packet`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.clearOutput()

        // Still CONNECTING: the server has no publisher session to release.
        h.sender.stop()

        assertEquals(0, h.controlPacketsOfType(Srt.PacketType.SHUTDOWN.rawValue).size)
    }

    @Test
    fun `provisional mode keeps re-asking for the conclusion once per second`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xAAAA_0009L, synCookie = 0xBBBB_0010L))
        h.sender.enterConclusionSent()
        h.clearOutput()

        // First tick only anchors the retransmit timer; too early (<1s): none.
        h.sender.send(10_500_000L)
        assertEquals(0, h.handshakeOutput().size)

        // After >=1s since the anchor: exactly one conclusion re-ask.
        h.sender.send(12_000_000L)
        assertEquals(1, h.handshakeOutput().size)

        // The real CONCLUSION arrives -> upgrade, no further re-asks.
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0xDDDD_0011L))
        h.clearOutput()
        h.sender.send(13_500_000L)
        assertEquals(0, h.handshakeOutput().size)
    }

    @Test
    fun `enterConclusionSent fires onConnected exactly once and late conclusion does not refire`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }

        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xAAAA_0001L, synCookie = 0xBBBB_0002L))
        assertEquals("not connected before enterConclusionSent", 0, connectedCalls)

        h.sender.enterConclusionSent()

        assertEquals("expected provisional connection (media may flow)", 1, connectedCalls)
        assertEquals(
            "peer socket id taken from induction reply",
            0xAAAA_0001L,
            getField(h.sender, "peerDestinationSocketId") as Long,
        )

        // Late server CONCLUSION arrives after provisional media started.
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0xCCCC_0003L))

        assertEquals("onConnected must not fire twice", 1, connectedCalls)
        assertEquals(0xCCCC_0003L, getField(h.sender, "peerDestinationSocketId") as Long)
    }

    @Test
    fun `enterConclusionSent is a no-op without a stored peer socket id`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }

        h.sender.start()
        h.sender.enterConclusionSent()

        assertEquals(0, connectedCalls)
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

    // --- SRT stream id encoding (Stage 4) ---

    private fun encodeStreamId(id: String): ByteArray {
        val method = SrtSender::class.java.getDeclaredMethod("encodeStreamId", String::class.java)
        method.isAccessible = true
        return method.invoke(SrtSender(streamId = null, latency = 2000), id) as ByteArray
    }

    private fun decodeStreamId(encoded: ByteArray): String {
        // Moblin/our encoding swaps each 4-byte group: [0<->3, 1<->2].
        val bytes = encoded.copyOf()
        for (i in bytes.indices step 4) {
            val t = bytes[i]
            bytes[i] = bytes[i + 3]
            bytes[i + 3] = t
            val u = bytes[i + 1]
            bytes[i + 1] = bytes[i + 2]
            bytes[i + 2] = u
        }
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    @Test
    fun `stream id ascii round trips`() {
        val id = "live/stream/test?srtauth=secret"
        assertEquals(id, decodeStreamId(encodeStreamId(id)))
    }

    @Test
    fun `stream id utf8 round trips`() {
        val id = "channel/привет?srtauth=ключ"
        assertEquals(id, decodeStreamId(encodeStreamId(id)))
    }

    @Test
    fun `stream id is padded to a 4-byte boundary`() {
        assertEquals(4, encodeStreamId("a").size)
        assertEquals(4, encodeStreamId("ab").size)
        assertEquals(4, encodeStreamId("abc").size)
        assertEquals(4, encodeStreamId("abcd").size)
        assertEquals(8, encodeStreamId("abcde").size)
    }

    // --- ACK edge cases (Stage 4) ---

    @Test
    fun `old ack does not clear a newer window`() {
        val h = SrtSenderHarness()
        h.connect()
        // Send seq 10..14.
        h.setNextSequenceNumber(10)
        h.enqueueAndSend(5)
        assertEquals(5, h.sender.packetsInFlightCount())
        h.clearOutput()

        // A stale ACK far below the window start must not clear anything.
        h.sender.input(h.createAck(lastAckSn = 0L))

        assertEquals("old ack must not clear in-flight", 5, h.sender.packetsInFlightCount())
    }

    @Test
    fun `ack before window start after partial ack keeps newer packets`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(10)
        h.enqueueAndSend(5) // в полёте: 10,11,12,13,14
        // Квитанция с номером 12 означает «получено всё ДО 12», то есть 10 и 11.
        // Сам пакет 12 ещё не получен и обязан остаться: иначе NAK на него нам
        // будет нечем удовлетворить (полевой баг 31.08, см. Srt.isSnAcked).
        h.sender.input(h.createAck(lastAckSn = 12L))
        assertEquals("остаются 12,13,14", 3, h.sender.packetsInFlightCount())
        h.clearOutput()

        // Устаревшая квитанция не должна снимать более новые пакеты.
        h.sender.input(h.createAck(lastAckSn = 5L))

        assertEquals("remaining newer packets must survive stale ack", 3, h.sender.packetsInFlightCount())
    }

    // --- 31-bit wrap-around within window (Phase 4) ---

    @Test
    fun `removeAckedPackets handles entire window at 31-bit wrap boundary`() {
        val h = SrtSenderHarness()
        h.connect()
        // Place all 8 in-flight packets right at the 31-bit boundary:
        // 0x7FFF_FFF8 .. 0x7FFFFFFF
        h.setNextSequenceNumber(0x7FFF_FFF8L)
        h.enqueueAndSend(8)
        assertEquals(8, h.sender.packetsInFlightCount())
        h.clearOutput()

        // Квитанция 0x7FFFFFFF снимает всё ДО неё — семь пакетов; сам
        // 0x7FFFFFFF ещё не получен и остаётся.
        h.sender.input(h.createAck(lastAckSn = 0x7FFF_FFFFL))
        assertEquals("остаётся сам пограничный пакет", 1, h.sender.packetsInFlightCount())

        // Квитанция 0 — это переход через границу: «жду 0», значит 0x7FFFFFFF
        // получен. Здесь как раз проверяется кольцевое сравнение.
        h.sender.input(h.createAck(lastAckSn = 0L))
        assertEquals(0, h.sender.packetsInFlightCount())
    }

    // --- Duplicate INDUCTION in CONNECTED state (Phase 4) ---

    @Test
    fun `duplicate induction in CONNECTED state sends conclusion but no double onConnected`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++; h.connected = true }
        h.connect()
        assertEquals(1, connectedCalls)
        h.clearOutput()

        // A stale INDUCTION arrives (e.g. from a delayed server probe).
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xBBBB_0001L, synCookie = 0xCCCC_0002L))

        // Sender responds with a conclusion (current behavior — does not ignore it).
        val conclusions = h.handshakeOutput()
        assertEquals("duplicate induction triggers a conclusion response", 1, conclusions.size)
        assertEquals("no double onConnected from duplicate induction", 1, connectedCalls)

        // If the server answers that conclusion, session continues without error.
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0xDDDD_0003L))
        assertTrue("sender remains connected after late conclusion", h.connected)
        assertEquals("onConnected still fired only once", 1, connectedCalls)
    }

    // --- Pacing / flow window (Phase 4) ---

    @Test
    fun `dropOldPackets keeps unacked in-flight packets so they stay retransmittable`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5) // in flight: createdAt=10_000..14_000
        assertEquals(5, h.sender.packetsInFlightCount())
        h.clearOutput()

        // Default latency=2000ms → old inFlight threshold was 3_000_000 us.
        // Advance clock 4s past creation: in-flight packets are stale by time
        // but must NOT be dropped — they stay available for retransmission
        // until the receiver ACKs them (dropping = permanent loss, H6).
        h.sender.send(14_000L + 4_000_000L)

        assertEquals("unacked in-flight packets retained", 5, h.sender.packetsInFlightCount())
    }

    @Test
    fun `dropOldPackets выбрасывает медиа, устаревшее сверх окна задержки`() {
        // Прежняя версия теста проходила НЕ по заявленной причине: пакеты не
        // выходили в эфир из-за таймера в насосе, а вовсе не потому, что их
        // отбраковали как устаревшие. В комментарии стояло «3M > порога 2.5M»,
        // хотя по числам самого теста разница выходила 2000 микросекунд.
        // Здесь пакеты действительно старше окна: latency 2000 мс даёт порог
        // 2.5 с, отправляем через 3 с после постановки в очередь.
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        val queuedAt = 10_000L
        repeat(5) {
            h.sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), queuedAt + it)
        }
        h.clearOutput()
        h.sender.send(queuedAt + 3_000_000L)
        assertEquals("устаревшее медиа не должно уходить в эфир", 0, h.dataPackets().size)
    }

    @Test
    fun `send выпускает всю накопленную очередь за один проход`() {
        // Прежний насос выпускал не больше десяти пакетов за проход и держал
        // таймер на 2 мс. Поскольку send() зовётся из потока медиа лишь ~75 раз
        // в секунду, а пакетов на рабочий битрейт приходится ~1300 в секунду,
        // одного его не хватало — и насос дополнительно дёргали из обоих потоков
        // чтения, на каждый входящий управляющий пакет. Своё медиа уходило в
        // такт чужим подтверждениям, а запись в сокет шла под общим монитором.
        //
        // Прежний тест на этом месте назывался «rate-limits to at most once per
        // 2s» и называл 1000 микросекунд «одной секундой»: единица времени здесь
        // микросекунды (nowMicros, параметр nowUs в харнессе), то есть порог был
        // 2 мс, а не 2 с. Тест закреплял неверное понимание единицы.
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        val batch = 40
        repeat(batch) {
            h.sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), 10_000L + it)
        }
        h.sender.send(10_000L + batch)
        assertEquals("насос обязан выпустить весь кадр за один проход", batch, h.dataPackets().size)
    }

    @Test
    fun `flow window does not enforce hard limit — documents current behavior`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        // Enqueue way more than the declared 8192 window.
        repeat(100) {
            h.sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), 10_000L + it * 1_000L)
        }
        h.sender.send(20_000L)

        // outputPackets sends max 10 per call (count/10 capped at 10).
        // All 100 are accepted into the queue — no enforcement of 8192 limit.
        // Verify that at least 10 moved to inFlight (no drop at enqueue).
        assertTrue(
            "flow window not enforced (100 packets accepted, 10 in flight)",
            h.sender.packetsInFlightCount() >= 10,
        )
    }
}