package app.brix.bonding

class SrtSenderHarness(
    streamId: String? = null,
    latency: Int = 2000,
) {
    val sender = SrtSender(streamId, latency)
    val output = mutableListOf<ByteArray>()

    var connected = false
    var disconnected = false

    init {
        sender.onOutput = { output.add(it) }
        sender.onConnected = { connected = true }
        sender.onDisconnected = { disconnected = true }
    }

    fun connect() {
        sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0x1234_5678L))
        setFieldLong("latestOutputPacketsTime", 0L)
        setFieldLong("latestReceivedPacketTime", SYNTHETIC_LAST_RECEIVED)
    }

    fun enqueueAndSend(count: Int, nowUs: Long = 10_000L) {
        repeat(count) {
            sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), nowUs + it * 1_000L)
        }
        sender.send(nowUs + count * 1_000L)
    }

    fun clearOutput() {
        output.clear()
    }

    fun dataPackets(): List<ByteArray> = output.filter { Srt.isDataPacket(it) }

    fun readSn(bytes: ByteArray): Long? = if (Srt.isDataPacket(bytes)) readUInt32(bytes, 0) else null

    fun isRetransmitted(bytes: ByteArray): Boolean = (bytes[4].toInt() and 0x04) != 0

    fun setNextSequenceNumber(value: Long) {
        setFieldLong("nextSequenceNumber", value)
    }

    fun controlPacketsOfType(type: Int): List<ByteArray> =
        output.filter { !Srt.isDataPacket(it) && (readUInt16(it, 0) and 0x7FFF) == type }

    fun handshakeOutput(): List<ByteArray> =
        output.filter { !Srt.isDataPacket(it) && (readUInt16(it, 0) and 0x7FFF) == Srt.PacketType.HANDSHAKE.rawValue }

    // Packets FROM the receiver are addressed to OUR socket id — that is what
    // the SRT "Destination Socket ID" field means, and what a real receiver
    // actually sends (field capture 2026-08-31: 213 ACKs, all carrying our id).
    //
    // These helpers used to fill in peerDestinationSocketId() — the id we put in
    // packets going the OTHER way. That mirrored the same wrong assumption the
    // implementation made, so the suite certified a bug that silently discarded
    // every ACK and NAK in production. Address them correctly here, and the
    // tests fail against that bug instead of blessing it.
    fun createAck(lastAckSn: Long, rttUs: Long = 0L): ByteArray =
        SrtTestPackets.ack(lastAckSn, rttUs, dstSocketId = ourSocketId())

    fun createNak(single: List<Long> = emptyList(), ranges: List<NakRange> = emptyList()): ByteArray =
        SrtTestPackets.nak(single, ranges, dstSocketId = ourSocketId())

    /** The id WE advertised in the handshake; the receiver addresses us by it. */
    fun ourSocketId(): Long {
        val field = sender.javaClass.getDeclaredField("socketId")
        field.isAccessible = true
        return field.getLong(sender)
    }

    /** Direct access to the private lastConclusionPacket field (test-only). */
    fun lastConclusionPacket(): ByteArray? {
        val field = sender.javaClass.getDeclaredField("lastConclusionPacket")
        field.isAccessible = true
        return field.get(sender) as ByteArray?
    }

    private fun setFieldLong(name: String, value: Long) {
        val field = sender.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(sender, value)
    }

    companion object {
        private const val SYNTHETIC_LAST_RECEIVED = 1_000_000_000_000L
    }
}