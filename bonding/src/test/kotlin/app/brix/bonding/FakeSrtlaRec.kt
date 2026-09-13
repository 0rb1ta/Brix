package app.brix.bonding

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * A minimal in-JVM stand-in for belabox's srtla_rec + sls, so the FULL client
 * flow (probe -> REG1/REG2/REG3 -> SRT induction/conclusion -> media) can be
 * exercised in unit tests without a device or network.
 *
 * Deviations from a real receiver are deliberate and configurable per test:
 *  - dropReg3Count: swallow the first N REG2s (one-way-deaf first port)
 *  - dropConclusionReplies: never answer CONCLUSION (lost downlink)
 *  - dropProbeReplies: swallow the first N REG_NGP answers to the probe
 *  - dropReg1Replies: swallow the first N REG2 answers to REG1 (and do not
 *    create the group), i.e. the reply is lost on the way back
 *  - keepaliveEchoDelayMs: hold the keepalive echo back this long, so a test
 *    can assert the client turns the round trip into a real per-link RTT
 *  - originateKeepalives: also send keepalives the client never asked for, with
 *    a payload that is NOT the client's timestamp — the bbox srtla_rec does
 *    exactly this when it stops hearing from a link
 *  - ackData: answer media with an ACK. Turn it off to model a link that still
 *    talks back (keepalive echoes keep the inbound side fresh) but delivers
 *    nothing — the shape of the 31.08 field failure.
 *  - floodInboundEveryMs: keep the client's socket busy so recv() never times
 *    out. A real receiver keeps talking down a link that has died upstream,
 *    which is exactly when the client's periodic checks are needed most.
 */
private const val FOREIGN_KEEPALIVE_STAMP = -777_000L

class FakeSrtlaRec(
    private val dropReg3Count: Int = 0,
    private val dropConclusionReplies: Boolean = false,
    private val dropProbeReplies: Int = 0,
    private val dropReg1Replies: Int = 0,
    private val keepaliveEchoDelayMs: Long = 0,
    private val originateKeepalives: Boolean = false,
    private val ackData: Boolean = true,
    private val floodInboundEveryMs: Long = 0,
) {
    val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    private var reg3Dropped = 0
    private var probeDropped = 0
    private var reg1Dropped = 0
    private var groupIssued = false
    private var lastEchoedGroup: ByteArray? = null
    private var listenerSocketId = 0x5A5A_0001L
    private var synCookie = 0x0C0FFEE0L
    private val received = ConcurrentLinkedQueue<ByteArray>()

    /** How many SRTLA keepalives we bounced back, for tests that assert the
     *  round trip happened at all. */
    @Volatile
    var keepalivesEchoed = 0
        private set

    /** Сколько REG2 пришло: повторная регистрация «на месте» шлёт именно его,
     *  поэтому рост счётчика — детерминированный признак того, что клиент счёл
     *  канал мёртвым (гоняться за кратким уходом из REGISTERED бесполезно, он
     *  возвращается за миллисекунды). */
    @Volatile
    var reg2Received = 0
        private set

    @Volatile
    var running = false
        private set

    private var worker: Thread? = null

    fun start() {
        running = true
        socket.soTimeout = 100
        if (floodInboundEveryMs > 0) startFlood()
        worker = thread(name = "FakeSrtlaRec") {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    lastPeer = packet.socketAddress
                    onPacket(packet.data.copyOf(packet.length))
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!running) return@thread
                }
            }
        }
    }

    /** Поток, забивающий сокет клиента входящими пакетами: SRTLA-ACK на номер,
     *  который клиент не отправлял. Вход плотный, но доставки не подтверждает —
     *  ровно то сочетание, при котором тик по таймауту сокета не наступает. */
    private fun startFlood() {
        thread(name = "FakeFlood") {
            while (running) {
                val peer = lastPeer
                if (peer != null) reply(srtlaAck(0xFFFF_FF00L))
                try {
                    Thread.sleep(floodInboundEveryMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        socket.close()
    }

    /** Forget all per-session state (group issued etc.). Call between
     *  simulated Stop->Start cycles so old clients cannot pollute new ones. */
    fun reset() {
        groupIssued = false
        lastEchoedGroup = null
        reg3Dropped = 0
        probeDropped = 0
        reg1Dropped = 0
        keepalivesEchoed = 0
        reg2Received = 0
    }

    private fun onPacket(packet: ByteArray) {
        try {
            onPacketInner(packet)
        } catch (e: Exception) {
            println("FAKE ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** SRTLA-ACK: тип, два байта отступа, дальше номера по uint32. */
    private fun srtlaAck(sn: Long): ByteArray {
        val p = Srtla.createPacket(Srtla.PacketType.ACK, 8)
        writeUInt32(p, 4, sn)
        return p
    }

    private fun onPacketInner(packet: ByteArray) {
        if (packet.isEmpty()) return
        received.add(packet)
        if (Srt.isDataPacket(packet)) {
            if (ackData) {
                // Настоящий приёмник подтверждает медиа дважды и по-разному:
                // SRT-ACK (накопительный, сквозной) и SRTLA-ACK (пономерной, по
                // тому каналу, который пакет принёс). Раньше фейк слал только
                // первый, и из-за этого сторож доставки был построен на неверном
                // сигнале — в поле он не сработал ни разу.
                reply(packetTo(packet, Srt.PacketType.ACK.rawValue))
                reply(srtlaAck(Srt.getSequenceNumber(packet)))
            }
            return
        }
        when (val type = Srt.getControlPacketType(packet)) {
            Srtla.PacketType.REG1.rawValue -> {
                if (reg1Dropped < dropReg1Replies) {
                    // The group is never created and no REG2 comes back: the
                    // client must re-send REG1 (with the same group id) on its
                    // own or the session stalls until the stream watchdog.
                    reg1Dropped++
                    return
                }
                // Echo the caller's group id (first half must match what the
                // caller generated; belabox servers append their own half).
                val reg2 = packetTo(packet, Srtla.PacketType.REG2.rawValue)
                // belabox protocol: the client regenerates its random group in
                // REG1; the receiver echoes THOSE bytes back (first half must
                // match on the client).
                System.arraycopy(
                    packet,
                    Srtla.CONTROL_TYPE_SIZE,
                    reg2,
                    Srtla.CONTROL_TYPE_SIZE,
                    Srtla.GROUP_ID_SIZE,
                )
                groupIssued = true
                reply(reg2)
            }
            Srtla.PacketType.REG2.rawValue -> {
                reg2Received++
                if (!groupIssued) {
                    // Probe phase.
                    if (probeDropped < dropProbeReplies) {
                        probeDropped++
                        return
                    }
                    reply(packetTo(packet, Srtla.PacketType.REG_NGP.rawValue))
                } else if (reg3Dropped < dropReg3Count) {
                    // Simulate one-way-deaf source port: swallow REG3.
                    reg3Dropped++
                } else {
                    reply(packetTo(packet, Srtla.PacketType.REG3.rawValue))
                }
            }
            Srtla.PacketType.KEEPALIVE.rawValue -> {
                // A real srtla_rec bounces the keepalive back untouched; the
                // payload is the client's own send timestamp, which is how the
                // client measures per-link RTT. The fake ignored keepalives
                // entirely, so no test ever exercised that path — and the field
                // run of 31.08 found per-link rtt was never measured at all.
                if (originateKeepalives) {
                    // Приёмник сочиняет свой keepalive: тип тот же, payload —
                    // не метка клиента. Клиент не должен принять это за эхо.
                    val own = Srtla.createPacket(
                        Srtla.PacketType.KEEPALIVE,
                        Srtla.CONTROL_TYPE_SIZE + 8,
                    )
                    writeInt64(own, Srtla.CONTROL_TYPE_SIZE, FOREIGN_KEEPALIVE_STAMP)
                    reply(own)
                    return
                }
                val echo = packet.copyOf()
                keepalivesEchoed++
                if (keepaliveEchoDelayMs > 0) {
                    thread(name = "FakeKeepaliveEcho") {
                        try {
                            Thread.sleep(keepaliveEchoDelayMs)
                            reply(echo)
                        } catch (_: InterruptedException) {
                        }
                    }
                } else {
                    reply(echo)
                }
            }
            Srt.PacketType.HANDSHAKE.rawValue -> onHandshake(packet)
            else -> Unit
        }
    }

    private fun onHandshake(packet: ByteArray) {
        // Parse hs type from the handshake payload: version@16, type@20.
        if (packet.size < 24) return
        val hsType = readUInt32(packet, 20).toInt()
        when (hsType) {
            0x00000001 -> { // INDUCTION request -> INDUCTION response (cookie)
                val resp = handshakeResponse(
                    request = packet,
                    hsVersion = 5,
                    hsTypeRaw = 0x00000001.toInt(),
                    cookie = ++synCookie,
                    socketId = listenerSocketId,
                )
                reply(resp)
            }
            0xFFFFFFFF.toInt() -> { // CONCLUSION request -> CONCLUSION response
                if (dropConclusionReplies) return
                val callerId = readUInt32(packet, 12)
                val resp = handshakeResponse(
                    request = packet,
                    hsVersion = 5,
                    hsTypeRaw = 0xFFFFFFFF.toInt(),
                    cookie = readUInt32(packet, 24),
                    socketId = ++listenerSocketId,
                )
                // dst = caller's socket id per spec 4.3.1.2.2.
                writeUInt32(resp, 12, callerId)
                reply(resp)
            }
        }
    }

    private fun matchesLastEchoed(packet: ByteArray): Boolean {
        val echoed = lastEchoedGroup ?: return false
        for (i in 0 until Srtla.GROUP_ID_SIZE) {
            if (packet[Srtla.CONTROL_TYPE_SIZE + i] != echoed[i]) return false
        }
        return true
    }

    private fun handshakeResponse(
        request: ByteArray,
        hsVersion: Long,
        hsTypeRaw: Int,
        cookie: Long,
        socketId: Long,
    ): ByteArray {
        val resp = ByteArray(maxOf(48, request.size))
        // Control header: type HANDSHAKE, ts=0, dst=0 for induction.
        writeUInt16(resp, 0, 0x8000 or Srt.PacketType.HANDSHAKE.rawValue)
        writeUInt32(resp, 8, 0)
        writeUInt32(resp, 12, 0)
        // Handshake structure.
        writeUInt32(resp, 16, hsVersion)
        writeUInt32(resp, 20, hsTypeRaw.toLong() and 0xFFFF_FFFFL)
        writeUInt32(resp, 24, cookie)
        writeUInt32(resp, 28, socketId)
        return resp
    }

    private fun packetTo(request: ByteArray, type: Int): ByteArray {
        // Reply echoing src addr via send(); header mirrors request size class.
        val size = if (request.size >= 16) request.size else 16
        val out = ByteArray(size)
        writeUInt16(out, 0, 0x8000 or type)
        return out
    }

    private fun reply(payload: ByteArray) {
        // Sent to the peer recorded from the last received datagram.
        val peer = lastPeer
        println("FAKE tx peer=$peer type=${if (payload.size >= 2 && Srt.isDataPacket(payload).not()) Srt.getControlPacketType(payload) else "data"}")
        if (peer == null) return
        socket.send(DatagramPacket(payload, payload.size, peer))
    }

    private var lastPeer: java.net.SocketAddress? = null

    // --- helpers ---

    private fun readUInt32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun writeUInt32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v shr 24) and 0xFF).toByte()
        b[off + 1] = ((v shr 16) and 0xFF).toByte()
        b[off + 2] = ((v shr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeUInt16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }
}
