package app.brix.bonding

data class NakRange(val from: Long, val upTo: Long)

object SrtTestPackets {
    fun controlHeader(type: Int, typeSpecificInfo: Long, timestamp: Long, destinationSocketId: Long): ByteArray {
        val out = ByteArray(16)
        writeUInt16(out, 0, 0x8000 or type)
        writeUInt32(out, 4, typeSpecificInfo)
        writeUInt32(out, 8, timestamp)
        writeUInt32(out, 12, destinationSocketId)
        return out
    }

    fun handshakeConclusion(peerSocketId: Long, synCookie: Long = 0L): ByteArray {
        val out = ByteArray(48)
        val header = controlHeader(Srt.PacketType.HANDSHAKE.rawValue, 0, 0, 0)
        System.arraycopy(header, 0, out, 0, 16)
        writeUInt32(out, 16, 5L)
        writeUInt16(out, 20, 0)
        writeUInt16(out, 22, 5)
        writeUInt32(out, 24, 0L)
        writeUInt32(out, 28, 1500L)
        writeUInt32(out, 32, 8192L)
        writeUInt32(out, 36, 0xFFFF_FFFFL)
        writeUInt32(out, 40, peerSocketId)
        writeUInt32(out, 44, synCookie)
        return out
    }

    /** Server-side INDUCTION response (handshake type 1) sent after our induction. */
    fun handshakeInduction(peerSocketId: Long, synCookie: Long): ByteArray {
        val out = ByteArray(48)
        val header = controlHeader(Srt.PacketType.HANDSHAKE.rawValue, 0, 0, 0)
        System.arraycopy(header, 0, out, 0, 16)
        writeUInt32(out, 16, 4L)
        writeUInt16(out, 20, 0)
        writeUInt16(out, 22, 2)
        writeUInt32(out, 24, 0L)
        writeUInt32(out, 28, 1500L)
        writeUInt32(out, 32, 8192L)
        writeUInt32(out, 36, 1L) // induction
        writeUInt32(out, 40, peerSocketId)
        writeUInt32(out, 44, synCookie)
        return out
    }

    fun ack(lastAckSn: Long, rttUs: Long, dstSocketId: Long = 0L): ByteArray {
        val out = ByteArray(24)
        val header = controlHeader(Srt.PacketType.ACK.rawValue, lastAckSn, 0, dstSocketId)
        System.arraycopy(header, 0, out, 0, 16)
        writeUInt32(out, 16, lastAckSn)
        writeUInt32(out, 20, rttUs)
        return out
    }

    fun nak(single: List<Long> = emptyList(), ranges: List<NakRange> = emptyList(), dstSocketId: Long = 0L): ByteArray {
        val payloadSize = single.size * 4 + ranges.sumOf { 8 }
        val out = ByteArray(16 + payloadSize)
        val header = controlHeader(Srt.PacketType.NAK.rawValue, 0, 0, dstSocketId)
        System.arraycopy(header, 0, out, 0, 16)
        var offset = 16
        single.forEach {
            writeUInt32(out, offset, it)
            offset += 4
        }
        ranges.forEach {
            writeUInt32(out, offset, it.from or 0x8000_0000L)
            writeUInt32(out, offset + 4, it.upTo)
            offset += 8
        }
        return out
    }

    fun shutdown(dstSocketId: Long = 0L): ByteArray {
        val header = controlHeader(Srt.PacketType.SHUTDOWN.rawValue, 0, 0, dstSocketId)
        return header
    }

    fun keepAlive(): ByteArray = controlHeader(Srt.PacketType.KEEPALIVE.rawValue, 0, 0, 0)
}