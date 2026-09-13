package app.brix.bonding

object Srtla {
    const val CONTROL_PACKET_TYPE_BIT = 0x8000
    const val CONTROL_TYPE_SIZE = 2
    const val GROUP_ID_SIZE = 256

    enum class PacketType(val rawValue: Int) {
        KEEPALIVE(0x1000),
        ACK(0x1100),
        REG1(0x1200),
        REG2(0x1201),
        REG3(0x1202),
        REG_ERR(0x1210),
        REG_NGP(0x1211),
        REG_NAK(0x1212),
    }

    fun createPacket(type: PacketType, length: Int): ByteArray {
        val packet = ByteArray(length)
        writeUInt16(packet, 0, type.rawValue or CONTROL_PACKET_TYPE_BIT)
        return packet
    }

    fun randomGroupId(): ByteArray {
        val bytes = ByteArray(GROUP_ID_SIZE)
        SecureRandomSource.fill(bytes)
        return bytes
    }
}

object Srt {
    const val DATA_PACKET_HEADER_SIZE = 16
    const val CONTROL_PACKET_TYPE_BIT = 0x8000

    enum class PacketType(val rawValue: Int) {
        HANDSHAKE(0x0000),
        KEEPALIVE(0x0001),
        ACK(0x0002),
        NAK(0x0003),
        SHUTDOWN(0x0005),
        ACKACK(0x0006),
    }

    fun isDataPacket(packet: ByteArray): Boolean = (packet[0].toInt() and 0x80) == 0

    fun getControlPacketType(packet: ByteArray): Int =
        readUInt16(packet, 0) and 0x7FFF

    fun getSequenceNumber(packet: ByteArray, offset: Int = 0): Long =
        readUInt32(packet, offset)

    /**
     * Подтверждён ли пакет `sn` квитанцией с номером `ackSn`.
     *
     * Номер в квитанции SRT — это СЛЕДУЮЩИЙ ОЖИДАЕМЫЙ пакет: подтверждено всё
     * строго меньше него, а сам он ещё не получен. Раньше здесь стояло
     * `sn == ackSn -> true`, то есть мы удаляли из буфера ровно тот пакет,
     * которого приёмнику и не хватало.
     *
     * Полевое доказательство 31.08, независимое от чтения спецификации: сервер
     * присылал NAK на номер, который ВСЕГДА оказывался ровно на единицу меньше
     * первого пакета в нашем буфере — `sn=8421 окно=[8422..8661]`,
     * `sn=11719 окно=[11720..11955]`, и так каждый раз. Мы этот номер у себя не
     * находили (80-90% всех NAK уходило в никуда), повтор не отправлялся, и
     * потеря становилась вечной: картинка на приёме сыпалась до следующего
     * опорного кадра, то есть до двух секунд.
     *
     * Сравнение делается по кольцу: номера 31-битные и переполняются.
     */
    fun isSnAcked(sn: Long, ackSn: Long): Boolean =
        if (sn == ackSn) false
        else if (sn < ackSn) ackSn - sn < 100_000_000
        else sn - ackSn > 100_000_000

    fun isSnRange(sn: Long): Boolean = (sn and 0x8000_0000L) == 0x8000_0000L

    const val MAX_NAK_RANGE_SIZE = 10_000

    fun processNak(packet: ByteArray, onNak: (Long) -> Unit) {
        var offset = 16
        var totalIterations = 0
        while (offset <= packet.size - 4) {
            val nakSn = readUInt32(packet, offset)
            offset += 4
            if (isSnRange(nakSn)) {
                if (offset > packet.size - 4) return
                val upToNakSn = readUInt32(packet, offset)
                offset += 4
                val start = nakSn and 0x7FFF_FFFF
                if (start <= upToNakSn) {
                    val count = (upToNakSn - start + 1).coerceAtMost(MAX_NAK_RANGE_SIZE.toLong())
                    var sn = start
                    var i = 0L
                    while (i < count) {
                        onNak(sn)
                        sn += 1
                        i += 1
                        totalIterations += 1
                        if (totalIterations > MAX_NAK_RANGE_SIZE) return
                    }
                } else {
                    // Wrap-around: start > end → iterate start..MAX, then 0..end
                    var sn = start
                    var remaining = MAX_NAK_RANGE_SIZE - totalIterations
                    while (sn <= 0x7FFF_FFFFL && remaining > 0) {
                        onNak(sn)
                        sn += 1
                        remaining -= 1
                        totalIterations += 1
                    }
                    sn = 0
                    while (sn <= upToNakSn && remaining > 0) {
                        onNak(sn)
                        sn += 1
                        remaining -= 1
                        totalIterations += 1
                    }
                }
            } else {
                onNak(nakSn)
            }
        }
    }
}

internal object SecureRandomSource {
    private val random = java.security.SecureRandom()

    fun fill(bytes: ByteArray) {
        random.nextBytes(bytes)
    }

    fun nextUInt32(): Long = random.nextLong() and 0xFFFF_FFFFL
}

internal fun nowMicros(): Long = System.nanoTime() / 1000
internal fun nowMillis(): Long = System.nanoTime() / 1_000_000

internal fun readUInt16(packet: ByteArray, offset: Int): Int =
    ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

internal fun readUInt32(packet: ByteArray, offset: Int): Long =
    ((packet[offset].toLong() and 0xFF) shl 24) or
        ((packet[offset + 1].toLong() and 0xFF) shl 16) or
        ((packet[offset + 2].toLong() and 0xFF) shl 8) or
        (packet[offset + 3].toLong() and 0xFF)

internal fun readInt64(packet: ByteArray, offset: Int): Long =
    ((packet[offset].toLong() and 0xFF) shl 56) or
        ((packet[offset + 1].toLong() and 0xFF) shl 48) or
        ((packet[offset + 2].toLong() and 0xFF) shl 40) or
        ((packet[offset + 3].toLong() and 0xFF) shl 32) or
        ((packet[offset + 4].toLong() and 0xFF) shl 24) or
        ((packet[offset + 5].toLong() and 0xFF) shl 16) or
        ((packet[offset + 6].toLong() and 0xFF) shl 8) or
        (packet[offset + 7].toLong() and 0xFF)

internal fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value shr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

internal fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value shr 24).toByte()
    bytes[offset + 1] = (value shr 16).toByte()
    bytes[offset + 2] = (value shr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

internal fun writeInt64(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value shr 56).toByte()
    bytes[offset + 1] = (value shr 48).toByte()
    bytes[offset + 2] = (value shr 40).toByte()
    bytes[offset + 3] = (value shr 32).toByte()
    bytes[offset + 4] = (value shr 24).toByte()
    bytes[offset + 5] = (value shr 16).toByte()
    bytes[offset + 6] = (value shr 8).toByte()
    bytes[offset + 7] = value.toByte()
}

/**
 * Какой из разрешённых адресов брать.
 *
 * Смысл настройки: некоторые операторы и приёмники ведут себя с IPv6 хуже, чем
 * с IPv4 — соединение устанавливается, а данные не идут. Moblin держит для
 * этого отдельный переключатель стратегии разрешения имён; здесь то же самое.
 *
 * @param preferIpv4 брать первый IPv4, если он есть. Если IPv4 нет вовсе,
 *  возвращается первый доступный: остаться без адреса хуже, чем пойти по IPv6.
 */
fun pickAddress(addresses: List<java.net.InetAddress>, preferIpv4: Boolean): java.net.InetAddress? {
    if (addresses.isEmpty()) return null
    if (!preferIpv4) return addresses.first()
    return addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.first()
}
