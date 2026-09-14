package app.brix.bonding

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SrtDataPacket(payload: ByteArray) {
    var data: ByteArray = ByteArray(Srt.DATA_PACKET_HEADER_SIZE + payload.size).also { dst ->
        System.arraycopy(payload, 0, dst, Srt.DATA_PACKET_HEADER_SIZE, payload.size)
    }
    var sequenceNumber: Long = 0
    var createdAt: Long = 0
    var retransmittedAt: Long = 0

    fun setHeader(sequenceNumber: Long, now: Long, timestamp: Long, destinationSocketId: Long) {
        this.sequenceNumber = sequenceNumber
        createdAt = now
        writeUInt32(data, 0, sequenceNumber)
        writeUInt32(data, 4, 0xE000_0001L)
        writeUInt32(data, 8, timestamp)
        writeUInt32(data, 12, destinationSocketId)
    }

    fun setRetransmissionBit() {
        data[4] = (data[4].toInt() or 0x4).toByte()
    }
}

enum class SrtSenderState { CONNECTING, CONCLUSION_SENT, CONNECTED, DISCONNECTED }

class SrtSender(
    var streamId: String?,
    var latency: Int,
) {
    var onOutput: ((ByteArray) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private val handshakeVersion4 = 4L
    private val handshakeVersion5 = 5L
    private val destinationSocket = 0L
    // Advertised MSS: matches the sender's real datagram budget (IP 20 + UDP 8
    // + SRT 16 + payload), so the peer and we never exceed a cellular MTU (H1).
    private val maximumTransmissionUnitSize = 1400L
    private val maximumFlowWindowSizeInPackets = 8192L
    private val socketId = SecureRandomSource.nextUInt32().let { if (it == 0L) 1L else it }

    private var nextSequenceNumber = SecureRandomSource.nextUInt32() % 10000
    private var peerDestinationSocketId = 0L
    private var lastConclusionPacket: ByteArray? = null
    private var provisionalPeerSocketId = 0L
    private var connectedFired = false
    private var conclusionSentEnteredTime = 0L
    private var lastProvisionalRetransmitUs = 0L
    // Пишется только под монитором, но читается без него в isConnected() и
    // isConnectedOrProvisional() — из корутины статистики и из сторожа таймаута
    // соединения. Соседние геттеры (rttMs, packetsInFlightCount, sendRateMbps,
    // droppedPackets) все @Synchronized, эти два из правила выпадали.
    @Volatile
    private var state = SrtSenderState.DISCONNECTED
    private val packetsToSend = ArrayDeque<SrtDataPacket>()
    private val packetsInFlight = ArrayDeque<SrtDataPacket>()
    private val packetsInFlightBySequenceNumber = HashMap<Long, SrtDataPacket>()
    private val retransmitSequenceNumbers = LinkedHashSet<Long>()
    private var flowWindowOverflowCount = 0L
    private var rttUs = 0L
    private var latestReceivedPacketTime = 0L
    private var latestOutputPacketsTime = 0L

    /**
     * Пакеты, готовые к отправке. Собираются под монитором SrtSender, а
     * записываются в сокет уже без него.
     *
     * Зачем: запись шла прямо из синхронизированных методов, то есть системный
     * вызов отправки делался, держа общий монитор. За него дерутся поток медиа
     * (`enqueue` ~1300 раз/с, `send` ~75 раз/с) и оба потока чтения (`input`).
     * Пока один пишет в сокет, остальные стоят. Замер 31.08: потоки чтения по
     * ~25% ядра каждый при кодировании в 22%.
     *
     * Два буфера меняются местами, чтобы не аллоцировать на каждый сброс.
     */
    private var pendingOutput = ArrayList<ByteArray>()
    private var flushBuffer = ArrayList<ByteArray>()

    /** Отдельная блокировка сброса: сериализует запись в сокет (порядок пакетов
     *  обязан сохраниться), не блокируя при этом работу с состоянием. */
    private val outputLock = Any()
    private var startTime = 0L
    private var numberOfBytesSent = 0L
    private var mbpsSendRate = 0.0
    private var latestNumberOfBytesSentTime = 0L

    fun start() {
        synchronized(this) { startLocked() }
        flushOutput()
    }

    private fun startLocked() {
        // streamId НЕ логируем целиком: у SRTLA туда уходит ?srtauth=<ключ>,
        // то есть ключ трансляции. Тег «Srtla» входит в белый список логов,
        // которые попадают в выгрузку диагностики, а её человек отдаёт чужим
        // людям — 14.09 мы вычистили ключи из настроек, а этот путь пропустили.
        // Для отладки хватает факта наличия и длины: по ним видно, подставился
        // ли streamId вообще и не обрезался ли он.
        android.util.Log.w(
            "Srtla",
            "srt-sender: start() streamId: есть=${!streamId.isNullOrEmpty()} длина=${streamId?.length ?: 0}",
        )
        startTime = nowMicros()
        latestReceivedPacketTime = nowMicros()
        latestOutputPacketsTime = 0
        latestNumberOfBytesSentTime = nowMicros()
        packetsToSend.clear()
        packetsInFlight.clear()
        packetsInFlightBySequenceNumber.clear()
        retransmitSequenceNumbers.clear()
        nextSequenceNumber = SecureRandomSource.nextUInt32() % 10000
        peerDestinationSocketId = 0
        provisionalPeerSocketId = 0
        connectedFired = false
        conclusionSentEnteredTime = 0
        lastProvisionalRetransmitUs = 0
        rttUs = 0
        numberOfBytesSent = 0
        mbpsSendRate = 0.0
        setState(SrtSenderState.CONNECTING)
        outputPacket(createInductionHandshakePacket())
    }

    fun stop() {
        synchronized(this) { stopLocked() }
        flushOutput()
    }

    private fun stopLocked() {
        // Say goodbye properly: without an SRT SHUTDOWN the server-side
        // publisher lingers for its idle timeout, and a rapid Stop->Start
        // then looks like a duplicate publisher for the same stream key —
        // the server kills the NEW session with a SHUTDOWN ~4ms after its
        // handshake (field-verified: every restart within ~10s died).
        if (state == SrtSenderState.CONNECTED || state == SrtSenderState.CONCLUSION_SENT) {
            sendShutdown()
        }
        setDisconnected()
    }

    private fun sendShutdown() {
        val writer = PacketWriter()
        writer.write(createCommonControlPacketHeader(
            Srt.PacketType.SHUTDOWN.rawValue, 0, timestamp(nowMicros()), peerDestinationSocketId,
        ))
        // Shutdown carries two uint32 fields (first/last sequence in flight);
        // zeros are accepted by libsrt-based receivers.
        writer.writeUInt32(0)
        writer.writeUInt32(0)
        outputPacket(writer.toByteArray())
    }

    fun newDataPacket(payload: ByteArray): SrtDataPacket = SrtDataPacket(payload)

    fun enqueue(packet: SrtDataPacket, now: Long) {
        synchronized(this) { enqueueLocked(packet, now) }
        flushOutput()
    }

    private fun enqueueLocked(packet: SrtDataPacket, now: Long) {
        if (state != SrtSenderState.CONNECTED && state != SrtSenderState.CONCLUSION_SENT) return
        packet.setHeader(
            sequenceNumber = getNextSequenceNumber(),
            now = now,
            timestamp = timestamp(now),
            destinationSocketId = peerDestinationSocketId,
        )
        packetsToSend.addLast(packet)
    }

    fun send(now: Long) {
        synchronized(this) { sendLocked(now) }
        flushOutput()
    }

    private fun sendLocked(now: Long) {
        checkIfConnected(now)
        keepConclusionRetransmit(now)
        if (state != SrtSenderState.CONNECTED && state != SrtSenderState.CONCLUSION_SENT) return
        // dropOldPackets ДО насоса: раньше насос выпускал только часть очереди,
        // и до отбраковки доживало то, что он не успел отправить. Теперь он
        // опустошает очередь целиком, поэтому после него отбраковывать нечего —
        // устаревшее медиа просто ушло бы в эфир. Сначала выбрасываем то, что
        // приёмнику уже бесполезно, потом отправляем остальное.
        dropOldPackets(now)
        outputPackets(now)
        enforceFlowWindow()
        updateSendRate(now)
    }

    fun input(packet: ByteArray) {
        synchronized(this) { inputLocked(packet) }
        flushOutput()
    }

    private fun inputLocked(packet: ByteArray) {
        latestReceivedPacketTime = nowMicros()
        handleControlPacket(packet, latestReceivedPacketTime)
    }

    private fun setDisconnected() {
        if (state == SrtSenderState.DISCONNECTED) return
        setState(SrtSenderState.DISCONNECTED)
        onDisconnected?.invoke()
    }

    private fun setState(newState: SrtSenderState) {
        state = newState
    }

    private fun timestamp(now: Long): Long = (now - startTime) and 0xFFFF_FFFFL

    private fun getNextSequenceNumber(): Long {
        val sn = nextSequenceNumber
        nextSequenceNumber = (nextSequenceNumber + 1) and 0x7FFF_FFFFL
        return sn
    }

    private fun checkIfConnected(now: Long) {
        // CONCLUSION_SENT expects inbound silence until the server reacts to
        // our provisional media: the no-inbound grace window starts from the
        // moment we enter the mode, not from the last received packet.
        if (state == SrtSenderState.CONCLUSION_SENT) {
            if (conclusionSentEnteredTime == 0L) conclusionSentEnteredTime = now
            if (latestReceivedPacketTime < conclusionSentEnteredTime) {
                latestReceivedPacketTime = conclusionSentEnteredTime
            }
        } else {
            conclusionSentEnteredTime = 0L
        }
        // A dead provisional path must be abandoned FAST: the receiver reaps
        // its publisher after ~5s without media anyway, so waiting the full
        // 5s only delays the (bounce-based) recovery. ACKs on a live path
        // arrive within one RTT (~0.1-0.3s on cellular); 2.5s covers even
        // heavy loss.
        val inboundTimeoutUs =
            if (state == SrtSenderState.CONCLUSION_SENT) 2_500_000L else 5_000_000L
        if (now - latestReceivedPacketTime > inboundTimeoutUs) {
            android.util.Log.w("Srtla", "srt-sender: DISCONNECTED (no packets ${(now - latestReceivedPacketTime) / 1000}ms) state=$state")
            setDisconnected()
        }
    }

    /**
     * Safety net: enforce the flow window declared in the handshake so memory
     * cannot grow unbounded if ACKs stop entirely on a dead path. Every forced
     * drop is counted (see [droppedPackets]) so the loss is visible to the ABR
     * and telemetry, rather than a silent media gap (H6).
     */
    private fun enforceFlowWindow() {
        while (packetsInFlight.size >= maximumFlowWindowSizeInPackets) {
            val dropped = packetsInFlight.removeFirst()
            packetsInFlightBySequenceNumber.remove(dropped.sequenceNumber)
            flowWindowOverflowCount++
            if (flowWindowOverflowCount % 100 == 1L) {
                android.util.Log.w("Srtla", "flow window overflow: $flowWindowOverflowCount packets force-dropped")
            }
        }
    }

    private fun dropOldPackets(now: Long) {
        // NOTE: un-ACKed in-flight packets must NOT be evicted by age. They stay
        // available for retransmission until the receiver ACKs them (SRT
        // semantics); dropping them turns recoverable loss into permanent loss
        // (H6). Memory is bounded by the flow window (enforceFlowWindow), which
        // counts every forced drop so it can be surfaced to the ABR. Only the
        // not-yet-sent queue is aged out.
        val toSendThreshold = latency * 1000L * 5 / 4
        while (packetsToSend.isNotEmpty() &&
            now - packetsToSend.first().createdAt > toSendThreshold
        ) {
            packetsToSend.removeFirst()
        }
    }

    /**
     * Выпустить всё, что накопилось: сначала назревшие повторы, затем новые
     * пакеты.
     *
     * Прежняя версия выпускала не больше десяти пакетов за проход
     * (`count = maxOf(count / 10, minOf(count, 10))`) и держала таймер на 2 мс.
     * `send()` зовётся из потока медиа около 75 раз в секунду, а пакетов на
     * такой битрейт приходится ~1300 в секунду, то есть по 17 на вызов —
     * десяти не хватало, и насос дополнительно дёргали из ОБОИХ потоков чтения,
     * на каждый входящий управляющий пакет. Своё медиа отправлялось в такт
     * чужим подтверждениям, а запись в сокет шла под общим монитором
     * SrtSender, за который дрались три потока (замер 31.08: отправка одного
     * пакета ~90 мкс, потоки чтения по ~25% ядра каждый).
     *
     * Объём ограничен снимком очередей на входе: если что-то попадёт в очередь
     * повторов уже во время прохода, оно уйдёт следующим, а не закольцует нас.
     */
    private fun outputPackets(now: Long) {
        // Повторы идут первыми и все за один проход по очереди.
        //
        // Раньше это стоило квадрата. `retransmitPacketIfNeeded` отправлял ОДИН
        // пакет, но ради этого мог просканировать очередь целиком (до 1000),
        // выложить пропущенные в новый ArrayList с упаковкой в Long и вернуть
        // обратно через addAll — и так на каждый пакет за проход насоса. Пока
        // `isSnAcked` был сломан (до e591dfe), повторы фактически не работали и
        // дефект спал: пересылалось 0–3 пакета в секунду. После починки стало
        // 3–95, то есть путь стал горячим, а стреляет он ровно при потерях —
        // когда запаса и так нет.
        //
        // Теперь один проход: пропущенные остаются в очереди НА СВОИХ МЕСТАХ
        // (порядок номеров больше не перемешивается), лишних аллокаций нет.
        retransmitEligible(now)
        // Объём ограничен снимком очереди на входе: то, что попадёт в неё уже
        // во время прохода, уйдёт следующим, а не закольцует нас.
        var budget = packetsToSend.size
        while (budget-- > 0) {
            val packet = packetsToSend.removeFirstOrNull() ?: return
            sendPacket(packet)
        }
    }

    /**
     * Переслать все номера, которым это уже можно, за один проход.
     *
     * Номера, чей повтор ещё внутри окна RTT, остаются в очереди и НЕ
     * выбрасываются: иначе повторный NAK, пришедший внутри одного RTT, молча
     * терял бы пакет из очереди и его не переслали бы никогда (C4). Номер,
     * которого больше нет в полёте, уже подтверждён — запрос протух, снимаем.
     */
    private fun retransmitEligible(now: Long) {
        if (retransmitSequenceNumbers.isEmpty()) return
        val it = retransmitSequenceNumbers.iterator()
        while (it.hasNext()) {
            val sn = it.next()
            val packet = packetsInFlightBySequenceNumber[sn]
            if (packet == null) {
                it.remove()
                continue
            }
            if (packet.retransmittedAt != 0L && now - packet.retransmittedAt < rttUs) continue
            it.remove()
            packet.retransmittedAt = now
            packet.setRetransmissionBit()
            outputPacket(packet.data)
        }
    }

    private fun sendPacket(packet: SrtDataPacket) {
        outputPacket(packet.data)
        packetsInFlight.addLast(packet)
        packetsInFlightBySequenceNumber[packet.sequenceNumber] = packet
    }

    private fun outputPacket(packet: ByteArray) {
        pendingOutput.add(packet)
        numberOfBytesSent += packet.size + 28
    }

    /** Отправить накопленное. Вызывается ТОЛЬКО вне монитора SrtSender. */
    private fun flushOutput() {
        synchronized(outputLock) {
            while (true) {
                synchronized(this) {
                    if (pendingOutput.isEmpty()) return
                    val tmp = pendingOutput
                    pendingOutput = flushBuffer
                    flushBuffer = tmp
                }
                val out = onOutput
                for (i in flushBuffer.indices) out?.invoke(flushBuffer[i])
                flushBuffer.clear()
            }
        }
    }

    private fun createCommonControlPacketHeader(
        type: Int,
        typeSpecificInformation: Long,
        timestamp: Long,
        destinationSocketId: Long,
    ): ByteArray {
        val out = ByteArray(16)
        writeUInt16(out, 0, 0x8000 or type)
        writeUInt16(out, 2, 0)
        writeUInt32(out, 4, typeSpecificInformation)
        writeUInt32(out, 8, timestamp)
        writeUInt32(out, 12, destinationSocketId)
        return out
    }

    private fun createInductionHandshakePacket(): ByteArray {
        val writer = PacketWriter()
        writer.write(createCommonControlPacketHeader(
            Srt.PacketType.HANDSHAKE.rawValue, 0, timestamp(nowMicros()), destinationSocket,
        ))
        writer.writeUInt32(handshakeVersion4)
        writer.writeUInt16(0)
        writer.writeUInt16(2)
        writer.writeUInt32(nextSequenceNumber)
        writer.writeUInt32(maximumTransmissionUnitSize)
        writer.writeUInt32(maximumFlowWindowSizeInPackets)
        writer.writeUInt32(1) // induction
        writer.writeUInt32(socketId)
        writer.writeUInt32(0)
        writer.write(byteArrayOf(1, 0, 0, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        return writer.toByteArray()
    }

    private fun createConclusionHandshakePacket(peerSocketId: Long, synCookie: Long): ByteArray {
        val writer = PacketWriter()
        writer.write(createCommonControlPacketHeader(
            Srt.PacketType.HANDSHAKE.rawValue, 0, timestamp(nowMicros()), destinationSocket,
        ))
        writer.writeUInt32(handshakeVersion5)
        writer.writeUInt16(0)
        writer.writeUInt16(5)
        writer.writeUInt32(nextSequenceNumber)
        writer.writeUInt32(maximumTransmissionUnitSize)
        writer.writeUInt32(maximumFlowWindowSizeInPackets)
        writer.writeUInt32(0xFFFF_FFFFL) // conclusion
        writer.writeUInt32(peerSocketId)
        writer.writeUInt32(synCookie)
        writer.write(byteArrayOf(1, 0, 0, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        writer.writeUInt16(1) // extension type (HSREQ)
        writer.writeUInt16(3) // extension length in 4-byte blocks
        writer.writeUInt32(0x0001_0503)
        writer.writeUInt32(0xBF)
        writer.writeUInt16(latency)
        writer.writeUInt16(latency)
        streamId?.let { id ->
            val encoded = encodeStreamId(id)
            writer.writeUInt16(5) // extension type (SID)
            writer.writeUInt16(encoded.size / 4)
            writer.write(encoded)
        }
        return writer.toByteArray()
    }

    private fun encodeStreamId(streamId: String): ByteArray {
        var data = streamId.toByteArray(Charsets.UTF_8)
        val padding = 4 - (data.size % 4)
        if (padding < 4) data = data + ByteArray(padding)
        for (i in data.indices step 4) {
            val t = data[i]
            data[i] = data[i + 3]
            data[i + 3] = t
            val u = data[i + 1]
            data[i + 1] = data[i + 2]
            data[i + 2] = u
        }
        return data
    }

    private fun handleControlPacket(packet: ByteArray, now: Long) {
        if (packet.size < 16) return
        val type = Srt.getControlPacketType(packet)
        // Validate destination socket ID for non-handshake control packets.
        // Stale/spoofed packets with wrong ID must not mutate transport state.
        //
        // The field at offset 12 is the SRT "Destination Socket ID": the socket
        // the packet is addressed TO. In a packet the receiver sends US that is
        // OUR [socketId] — never [peerDestinationSocketId], which is the id we
        // put in packets going the other way.
        //
        // Comparing against the peer's id therefore rejected EVERY non-handshake
        // control packet. The handshake is exempt, so a session still connected
        // and then went permanently deaf: ACKs never reached handleAckPacket, so
        // packetsInFlight was never drained, filled the 8192 flow window in ~15s,
        // and enforceFlowWindow began force-dropping ~550 packets/s — i.e. the
        // whole stream. Field capture 2026-08-31: 213 SRT-ACKs received by the
        // SRTLA layer, 0 processed here, 86 848 packets force-dropped, black
        // picture in OBS. rttUs also stayed 0, so ABR never ran either.
        if (type != Srt.PacketType.HANDSHAKE.rawValue) {
            val dstSocketId = readUInt32(packet, 12)
            // 0 is spec-legal on some control packets (no session assumed yet).
            if (dstSocketId != 0L && dstSocketId != socketId) {
                rejectedControlPackets++
                if (rejectedControlPackets % 100 == 1L) {
                    android.util.Log.w(
                        "Srtla",
                        "srt-sender: dropped control packet dst=$dstSocketId (ours=$socketId) " +
                            "type=$type total=$rejectedControlPackets",
                    )
                }
                return
            }
        }
        handleControlPacketByType(type, packet, now)
    }

    /** Control packets rejected by the destination-socket-id check. Logged
     *  rather than silently discarded: silence here is exactly what hid the bug
     *  above for weeks. */
    private var rejectedControlPackets = 0L

    private fun handleControlPacketByType(type: Int, packet: ByteArray, now: Long) {
        when (type) {
            Srt.PacketType.HANDSHAKE.rawValue -> handleHandshakePacket(packet)
            Srt.PacketType.KEEPALIVE.rawValue -> handleKeepAlivePacket()
            Srt.PacketType.ACK.rawValue -> handleAckPacket(packet, now)
            Srt.PacketType.NAK.rawValue -> handleNakPacket(packet)
            Srt.PacketType.SHUTDOWN.rawValue -> {
                // Приёмник закрыл сессию сам. Разбирая лог, это надо отличать
                // от нашего таймаута по тишине: причины разные. Чаще всего так
                // выглядит занятый ключ потока — на сервере уже есть издатель
                // с тем же streamid, и новую сессию он убивает сразу после
                // рукопожатия.
                android.util.Log.w("Srtla", "srt-sender: SHUTDOWN от приёмника state=$state")
                setDisconnected()
            }
        }
        // Насос отсюда НЕ запускается: см. outputPackets. Отправка идёт в такт
        // медиа, а не в такт входящим подтверждениям.
    }

    private fun handleHandshakePacket(packet: ByteArray) {
        if (packet.size < 48) {
            android.util.Log.w("Srtla", "srt-sender: handshake packet too small size=${packet.size}")
            return
        }
        val handshakeType = readUInt32(packet, 36)
        val peerSocketId = readUInt32(packet, 40)
        val synCookie = readUInt32(packet, 44)
        android.util.Log.w(
            "Srtla",
            "srt-sender: HANDSHAKE recv size=${packet.size} hsType=0x${handshakeType.toString(16)} peerSocketId=$peerSocketId synCookie=$synCookie",
        )
        when (handshakeType) {
            1L -> handleHandshakeInduction(peerSocketId, synCookie)
            0xFFFF_FFFFL -> handleHandshakeConclusion(peerSocketId)
        }
    }

    private fun handleHandshakeInduction(peerSocketId: Long, synCookie: Long) {
        android.util.Log.w("Srtla", "srt-sender: handshake INDUCTION -> send conclusion")
        provisionalPeerSocketId = peerSocketId
        val conclusion = createConclusionHandshakePacket(peerSocketId, synCookie)
        lastConclusionPacket = conclusion
        outputPacket(conclusion)
    }

    /**
     * Re-send the SRT conclusion handshake packet without starting a new
     * induction. Used when the server's CONCLUSION response (which promotes us
     * to CONNECTED) is lost on a lossy downlink. Sending the conclusion again
     * keeps the SAME SRT session alive and makes the server re-answer CONCLUSION
     * — it does not create a fresh session, so it avoids tripping the server's
     * auth rate-limiter (unlike a full transport restart).
     */
    fun retransmitConclusion() {
        synchronized(this) { retransmitConclusionLocked() }
        flushOutput()
    }

    private fun retransmitConclusionLocked() {
        val conclusion = lastConclusionPacket ?: return
        if (state == SrtSenderState.CONNECTED) return
        android.util.Log.w("Srtla", "srt-sender: retransmit conclusion (state=$state)")
        outputPacket(conclusion)
    }

    /**
     * Enter provisional CONCLUSION_SENT mode: the server accepted the publisher
     * (we received INDUCTION and sent CONCLUSION), but its CONCLUSION reply was
     * lost. Allow early media to flow using the peer socket id learned from
     * INDUCTION. Fires onConnected so the media pipeline starts immediately —
     * the receiver reaps a publisher after ~5s without media, so waiting for a
     * CONCLUSION reply that may never arrive would lose the group anyway.
     *
     * NOTE: the induction-reply socket id is usually NOT the id the receiver's
     * SRT endpoint expects for media after full establishment (field-verified:
     * the CONCLUSION reply carries a different, correct id). Provisional media
     * may therefore be dropped by the receiver until the real CONCLUSION
     * arrives; keepConclusionRetransmit() keeps re-asking for it every second.
     */
    fun enterConclusionSent() {
        synchronized(this) { enterConclusionSentLocked() }
        flushOutput()
    }

    private fun enterConclusionSentLocked() {
        if (state != SrtSenderState.CONNECTING) return
        if (provisionalPeerSocketId == 0L) return
        android.util.Log.w("Srtla", "srt-sender: enter CONCLUSION_SENT (provisional media, dst=$provisionalPeerSocketId)")
        peerDestinationSocketId = provisionalPeerSocketId
        setState(SrtSenderState.CONCLUSION_SENT)
        fireConnectedOnce()
    }

    /**
     * While in provisional mode, re-send our CONCLUSION once per second: each
     * copy prompts the server to (re-)send its CONCLUSION reply carrying the
     * CORRECT media socket id. On arrival handleHandshakeConclusion upgrades
     * the destination and real media flow begins.
     */
    private fun keepConclusionRetransmit(now: Long) {
        if (state != SrtSenderState.CONCLUSION_SENT) return
        val conclusion = lastConclusionPacket ?: return
        if (lastProvisionalRetransmitUs == 0L) {
            // Anchor on the first media-pump tick of the provisional window.
            lastProvisionalRetransmitUs = now
            return
        }
        if (now - lastProvisionalRetransmitUs < 1_000_000) return
        lastProvisionalRetransmitUs = now
        android.util.Log.w("Srtla", "srt-sender: provisional re-ask conclusion (dst=$peerDestinationSocketId)")
        outputPacket(conclusion)
    }

    private fun handleHandshakeConclusion(peerSocketId: Long) {
        if (connectedFired && state == SrtSenderState.CONNECTED) {
            android.util.Log.w("Srtla", "srt-sender: handshake CONCLUSION after CONCLUSION_SENT upgrade -> CONNECTED (no double onConnected)")
            peerDestinationSocketId = peerSocketId
            setState(SrtSenderState.CONNECTED)
            lastConclusionPacket = null
            return
        }
        android.util.Log.w("Srtla", "srt-sender: handshake CONCLUSION -> CONNECTED")
        peerDestinationSocketId = peerSocketId
        setState(SrtSenderState.CONNECTED)
        lastConclusionPacket = null
        fireConnectedOnce()
    }

    private fun fireConnectedOnce() {
        if (connectedFired) return
        connectedFired = true
        onConnected?.invoke()
    }

    private fun handleKeepAlivePacket() {
        val keepAlive = createCommonControlPacketHeader(
            Srt.PacketType.KEEPALIVE.rawValue, 0, timestamp(nowMicros()), peerDestinationSocketId,
        )
        outputPacket(keepAlive)
    }

    private fun handleAckPacket(packet: ByteArray, now: Long) {
        if (packet.size < 20) return
        val lastAcknowledgedSequenceNumber = readUInt32(packet, 16)
        val before = packetsInFlight.size
        removeAckedPackets(lastAcknowledgedSequenceNumber)
        val typeSpecificInformation = readUInt32(packet, 4)
        // Only a "full" ACK (typeSpecificInformation != 0) carries RTT/
        // bandwidth stats and feeds updateSendRate()/rttUs — if the receiver
        // only ever sends light ACKs (or none at all), rttMs() stays 0
        // forever and AdaptiveBitrate.update() bails on its first line, so
        // ABR silently never runs.
        //
        // Эти две строки писались безусловно, пока искали баг с socket id. Баг
        // найден (5953995), а строки остались: ~83 ACK/с × 2 строки со сборкой
        // текста и вызовом в системный лог. Замер 31.08 показал, что обработка
        // пакетов жжёт втрое больше процессора, чем сам энкод, — оставлять
        // такое в горячем пути нельзя. Под флагом диагностика доступна как была.
        if (android.util.Log.isLoggable("Srtla", android.util.Log.VERBOSE)) {
            android.util.Log.v(
                "Srtla",
                "srt-ack ackSn=$lastAcknowledgedSequenceNumber before=$before " +
                    "after=${packetsInFlight.size} tsi=$typeSpecificInformation rttUsBefore=$rttUs",
            )
        }
        if (typeSpecificInformation != 0L) {
            if (packet.size < 24) return
            rttUs = readUInt32(packet, 20)
            updateSendRate(now)
            val ackAck = createAckAckPacket(typeSpecificInformation)
            outputPacket(ackAck)
        }
    }

    private fun updateSendRate(now: Long) {
        val durationUs = now - latestNumberOfBytesSentTime
        if (durationUs <= 200_000) return
        latestNumberOfBytesSentTime = now
        val durationSeconds = durationUs / 1_000_000.0
        var latestMbpsSendRate = 8.0 * numberOfBytesSent / durationSeconds / 1_000_000
        numberOfBytesSent = 0
        // If the byte counter drops to ~0 between samples we would report 0,
        // collapsing the ABR's throughput (and its th2 threshold) so the bitrate
        // spirals down to the minimum. Floor the measurement to keep ABR healthy.
        if ((state == SrtSenderState.CONNECTED || state == SrtSenderState.CONCLUSION_SENT) &&
            latestMbpsSendRate < 1.0
        ) {
            latestMbpsSendRate = 1.0
        }
        // Отдаём интервальную скорость как есть, без своего EWMA.
        //
        // Раньше здесь стояло `0.7 * mbpsSendRate + 0.3 * latest`, и ряд
        // фильтровался ДВАЖДЫ: сначала тут, потом ещё раз в
        // AdaptiveBitrate.updateThroughput. У эталона (belacoder) ступень одна —
        // `srt_bstats(sock, &stats, 1)` отдаёт мгновенную скорость за интервал,
        // а сглаживает уже сам регулятор. Наш расчёт выше и есть тот самый
        // интервальный замер (гейт durationUs <= 200_000 задаёт интервал), так
        // что второй фильтр был лишним: он добавлял запаздывание, из-за
        // которого throughput отставал вниз, прижимал порог Th2 и подталкивал
        // регулятор резать битрейт.
        //
        // Клампа в 1.0 Мбит/с выше ОСТАЁТСЯ: она ставилась, чтобы разорвать эту
        // же петлю, и снимать её заодно означало бы менять две вещи разом.
        mbpsSendRate = latestMbpsSendRate
    }

    fun isConnected(): Boolean = state == SrtSenderState.CONNECTED

    /** CONNECTED or provisional CONCLUSION_SENT (media may already flow). */
    fun isConnectedOrProvisional(): Boolean =
        state == SrtSenderState.CONNECTED || state == SrtSenderState.CONCLUSION_SENT

    @Synchronized
    fun rttMs(): Double = rttUs / 1000.0

    @Synchronized
    fun packetsInFlightCount(): Int = packetsInFlight.size

    @Synchronized
    fun sendRateMbps(): Double = mbpsSendRate

    /** Datagrams force-dropped by the flow window (buffer felt on a dead path).
     *  Exposed so the ABR can see hard-loss signal vs. RTT-only inference. */
    @Synchronized
    fun droppedPackets(): Long = flowWindowOverflowCount

    private fun createAckAckPacket(ackNumber: Long): ByteArray {
        val out = ByteArray(20)
        val header = createCommonControlPacketHeader(
            Srt.PacketType.ACKACK.rawValue, 0, 0, peerDestinationSocketId,
        )
        System.arraycopy(header, 0, out, 0, 16)
        writeUInt32(out, 4, ackNumber)
        writeUInt32(out, 8, timestamp(nowMicros()))
        return out
    }

    private fun removeAckedPackets(lastAcknowledgedSequenceNumber: Long) {
        val index = packetsInFlight.indexOfFirst {
            !Srt.isSnAcked(it.sequenceNumber, lastAcknowledgedSequenceNumber)
        }
        val removeCount = if (index >= 0) index else packetsInFlight.size
        repeat(removeCount) {
            val p = packetsInFlight.removeFirst()
            packetsInFlightBySequenceNumber.remove(p.sequenceNumber)
        }
    }

    private fun handleNakPacket(packet: ByteArray) {
        Srt.processNak(packet) { sn ->
            if (retransmitSequenceNumbers.size < 1000 &&
                packetsInFlightBySequenceNumber.containsKey(sn)
            ) {
                retransmitSequenceNumbers.add(sn)
            }
        }
    }
}

private class PacketWriter {
    private val bos = ByteArrayOutputStream()
    private val dos = DataOutputStream(bos)

    fun write(bytes: ByteArray) {
        dos.write(bytes)
    }

    fun writeUInt16(value: Int) {
        dos.writeShort(value)
    }

    fun writeUInt32(value: Long) {
        dos.writeInt(value.toInt())
    }

    fun toByteArray(): ByteArray = bos.toByteArray()
}
