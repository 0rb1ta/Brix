package app.brix.bonding

import android.util.Log
import app.brix.core.diagnostics.PeriodicTasks
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SrtlaConnection(
    val type: String,
    private val priority: Float,
    val bindSocket: ((DatagramSocket) -> Unit)? = null,
    /**
     * Разрешение имени в адрес. Нужно снаружи, потому что здесь мы не знаем про
     * `Network`, а `InetAddress.getByName` резолвит через сеть ПО УМОЛЧАНИЮ —
     * даже когда сокет привязан к другой. При двух линках это значит, что
     * сотовый пойдёт на адрес, разрешённый по Wi-Fi, а если у Wi-Fi нет
     * интернета, то и не поднимется вовсе. С IP-литералом в адресе дефект спит,
     * поэтому и не всплывал. null — прежнее поведение.
     */
    val resolve: ((String) -> InetAddress?)? = null,
    /**
     * Говорить ли по SRTLA. `true` — как раньше: групповая регистрация
     * (PROBE, REG1, REG2, REG3) и keepalive раз в секунду.
     *
     * `false` — обычный SRT-приёмник на том конце. Он не знает пакетов SRTLA:
     * регистрацию проигнорирует, а keepalive с типом 0x1000 получит как мусор.
     * Поэтому в этом режиме соединение после открытия сокета сразу считается
     * готовым, и служебных пакетов SRTLA не отправляется вовсе.
     *
     * Данные при этом одинаковы: SRTLA их не заворачивает, `sendSrtPacket`
     * кладёт пакет SRT в сокет как есть.
     */
    private val useSrtla: Boolean = true,
) {
    interface Delegate {
        fun onSocketConnected(connection: SrtlaConnection)
        fun onRegNgp(connection: SrtlaConnection)
        fun onReg2(connection: SrtlaConnection, groupId: ByteArray)
        fun onRegistered(connection: SrtlaConnection)
        fun onPacket(connection: SrtlaConnection, packet: ByteArray)
        fun onSrtAck(connection: SrtlaConnection, sn: Long)
        fun onSrtNak(connection: SrtlaConnection, sn: Long)
        fun onSrtlaAck(connection: SrtlaConnection, sn: Long)
        fun onSendFailed(connection: SrtlaConnection)
    }

    // Ставится снаружи (SrtlaClient.addConnection), читается из потока чтения
    // "SrtlaRead-$type" на каждый входящий пакет — разные потоки, общего лока нет.
    @Volatile
    var delegate: Delegate? = null

    private enum class State { IDLE, SHOULD_SEND_REGISTER_REQUEST, WAIT_FOR_REGISTER_RESPONSE, REGISTERED }

    private val lock = Any()
    private var packetsDropped = 0L
    private var socket: DatagramSocket? = null
    private var readThread: Thread? = null
    private var state = State.IDLE
    private var groupId = ByteArray(0)
    private var hasFullGroupId = false
    private var host = ""
    private var port = 0
    private var running = false
    private var started = false
    /** Номера отправленных, но ещё не подтверждённых пакетов. Примитивное
     *  множество, а не HashSet<Long>: тот упаковывал каждый номер в объект при
     *  добавлении, поиске и на каждом элементе полного обхода, а через него
     *  проходит весь трафик. См. LongHashSet. */
    private val packetsInFlight = LongHashSet()
    private var windowSize = windowDefault * windowMultiply
    private var latestReceivedTime = nowMillis()
    private var keepAliveBaseTime = nowMillis()

    /** Метки времени наших последних keepalive: по ним отличаем эхо от
     *  keepalive, который приёмник сочинил сам. */
    private val sentKeepAliveStamps = ArrayDeque<Long>()

    /** Log.isLoggable читает системное свойство, а в горячем пути он звался по
     *  три раза на каждый принятый пакет — при 600 пак/с это заметная работа.
     *  Кэшируем и обновляем в тике: логирование по-прежнему можно включить на
     *  ходу, просто с задержкой до полусекунды. */
    @Volatile
    private var verboseLogging = Log.isLoggable(TAG, Log.VERBOSE)

    /** Когда по этому каналу в последний раз ПОДТВЕРДИЛИ доставку нашего пакета.
     *  Отдельно от latestReceivedTime: тот обновляется любым входящим, включая
     *  ответы сервера, которые продолжают идти по каналу, уже мёртвому на
     *  передачу — именно так линк 31.08 числился живым 24 секунды. */
    private var lastDeliveryAckTime = nowMillis()

    /** Сколько всего было отправлено по каналу на момент последнего
     *  подтверждения доставки. Разница с текущим — объём, ушедший без ответа. */
    private var bytesSentAtLastDeliveryAck = 0L

    /** Сколько раз сработал сторож доставки. Только для тестов: по переходу
     *  состояния этот сторож не отличить от сторожа по входящему трафику. */
    @Volatile
    var deliveryWatchdogTrips = 0
        private set
    private var lastKeepAliveSendTime = 0L
    private var totalBytesSent = 0L
    // Пишется потоком чтения из handleSrtlaKeepalive ВНЕ lock (строкой выше
    // sentKeepAliveStamps трогается под локом, а сам rtt — уже нет), читается
    // корутиной статистики через SrtlaClient.connectionStats() тоже без лока.
    // Int не рвётся, но без @Volatile UI может сколь угодно долго показывать
    // устаревшее значение — в том числе старый rtt после stop(), который его
    // обнуляет. Соседи по классу (deliveryWatchdogTrips, regErrCount) уже
    // помечены, rtt из этого правила выпадал.
    @Volatile
    var rtt = 0
        private set
    private var epoch = 0L
    private var lifecycleGeneration = 0L
    private var pendingReconnect: ScheduledFuture<*>? = null
    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)

    // Карантин: отказ отправки больше не выбрасывает линк из бондинга навсегда,
    // а переводит его в IDLE (score() = -1, из раздачи выпал) и ставит
    // отложенный подъём. Засов quarantinePending нужен потому, что
    // onSendFailed прилетает на КАЖДЫЙ неудавшийся пакет — при мёртвой сети это
    // сотни раз в секунду, и без него мы бы планировали сотни подъёмов.
    // Снимается прямо перед запуском отложенной попытки, чтобы неудачная
    // попытка могла запланировать следующую (это и закрывает старый цикл
    // «reconnect провалился -> onSendFailed -> reconnect» без задержки).
    private var quarantinePending = false
    private var quarantineAttempts = 0

    // Recency of the last inbound SRT *data* packet (not control/ACK/keepalive).
    // The server sends media down to a single connection (group->last_address),
    // so a connection that stops receiving data is a path the server is not
    // feeding. selectConnection() uses this to prefer the path actually carrying
    // media, so a dead-but-registered connection cannot starve the stream
    // (the server keeps routing media to a stale endpoint while the client
    // keeps sending into it).
    private var lastDataReceivedAt = nowMillis()
    private var everReceivedData = false

    // Registration retry state. REG3 (the server's confirmation that this
    // connection joined the group) can be lost on a lossy cellular downlink; the
    // old code sent REG2 once and waited forever, hanging until the 15s stream
    // timeout. We re-send REG2 in-place (same groupId, same source port) and,
    // after a bounded number of retries, bounce the socket (Moblin connect-timer
    // behaviour) to re-register from a fresh source port.
    private var lastReg2SendTime = 0L
    private var reg2Retries = 0
    @Volatile
    private var regErrCount = 0
    private var recoveryAttempts = 0

    // The group-handshake phase BEFORE we own a group id: probe (REG2 with a
    // random id, answered by REG_NGP) then REG1 (answered by REG2 carrying the
    // real group id). Three packets across a lossy cellular downlink, and this
    // phase had no retry at all — the connection just sat in
    // SHOULD_SEND_REGISTER_REQUEST while onTick ignored that state, so a single
    // lost packet stalled the whole session until SrtlaStream's 15s watchdog
    // tore the transport down. Every later phase (REG3 wait, registered
    // watchdog) already retried; this one now does too, in-place and with the
    // SAME group id, so a retry never orphans a group server-side.
    private enum class HandshakeStep { PROBE, REG1 }
    private var handshakeStep: HandshakeStep? = null
    private var handshakeSentAt = 0L
    private var handshakeRetries = 0

    fun attachEpoch(e: Long) {
        synchronized(lock) {
            // Strictly-greater only for a STARTED connection (its stop() may
            // have bumped epoch past the client's). An IDLE/stopped connection
            // accepts any newer epoch, otherwise it becomes a zombie: counted
            // in connectionCount(), never selectable, never restartable
            //.
            if (e > epoch || (!started && e >= epoch - 1)) epoch = e
        }
    }

    fun matchesEpoch(e: Long): Boolean = synchronized(lock) { epoch == e }

    fun start(host: String, port: Int, epoch: Long = this.epoch) {
        val generation: Long
        synchronized(lock) {
            if (started || this.epoch != epoch) return
            started = true
            lifecycleGeneration += 1
            generation = lifecycleGeneration
            this.host = host
            this.port = port
            this.epoch = epoch
        }
        // В реестр периодических задач — по линку, а не общей строкой:
        // тик приёмного цикла единственная периодика, чей вес растёт с числом
        // каналов, и при разборе лога надо видеть, сколько их было включено.
        // Заявка снимается в stop().
        tickRegistration = PeriodicTasks.register("srtla-$type", TICK_INTERVAL_MS)
        if (startInternal(generation)) {
            handleReady()
        }
    }

    private var tickRegistration: PeriodicTasks.Registration? = null

    private fun startInternal(generation: Long): Boolean {
        var s: DatagramSocket? = null
        try {
            s = DatagramSocket()
            bindSocket?.invoke(s)
            val address = resolve?.invoke(host) ?: InetAddress.getByName(host)
            s.connect(address, port)
            s.soTimeout = 500
            val installed = synchronized(lock) {
                if (!started || lifecycleGeneration != generation) {
                    false
                } else {
                    socket = s
                    running = true
                    true
                }
            }
            if (!installed) {
                s.close()
                return false
            }
            Log.d(TAG, "srtla: $type socket started localPort=${s.localPort} host=$host port=$port")
            startReadLoop(s)
            return true
        } catch (e: Exception) {
            // Close the half-built socket on any failure, otherwise repeated
            // network errors leak file descriptors across reconnect attempts.
            try {
                s?.close()
            } catch (_: Exception) {
            }
            Log.e(TAG, "srtla: $type socket setup failed: ${e.javaClass.simpleName}: ${e.message}", e)
            synchronized(lock) {
                if (lifecycleGeneration == generation) {
                    state = State.IDLE
                    started = false
                }
            }
            return false
        }
    }

    fun stop() {
        Log.d(TAG, "srtla: $type STOP id=${System.identityHashCode(this)}")
        val thread: Thread?
        val socketToClose: DatagramSocket?
        synchronized(lock) {
            lifecycleGeneration += 1
            epoch += 1
            pendingReconnect?.cancel(false)
            pendingReconnect = null
            running = false
            started = false
            hasFullGroupId = false
            groupId = ByteArray(0)
            // Snapshot under the lock: a concurrent reconnect()/start() may
            // install a NEW socket/thread right after this block — closing
            // the field outside the lock would kill the new session's socket
            //.
            socketToClose = socket
            thread = readThread
            readThread = null
        }
        tickRegistration?.close()
        tickRegistration = null
        try {
            socketToClose?.close()
        } catch (_: Exception) {
        }
        synchronized(lock) {
            if (socket === socketToClose) socket = null
            state = State.IDLE
            quarantinePending = false
            quarantineAttempts = 0
            packetsInFlight.clear()
            sentKeepAliveStamps.clear()
            lastDeliveryAckTime = nowMillis()
            bytesSentAtLastDeliveryAck = 0
            windowSize = windowDefault * windowMultiply
            totalBytesSent = 0
            rtt = 0
        }
        // The read loop exits on socket close; give it a bounded window so a
        // follow-up start() cannot overlap with the dying reader of the old
        // socket (stale packets from it would poison the new attempt).
        thread?.let {
            try {
                it.join(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun handleReady() {
        var sendReg2 = false
        val st = synchronized(lock) {
            packetsInFlight.clear()
            windowSize = windowDefault * windowMultiply
            latestReceivedTime = nowMillis()
            reg2Retries = 0
            recoveryAttempts = 0
            handshakeStep = null
            handshakeRetries = 0
            if (hasFullGroupId) {
                state = State.WAIT_FOR_REGISTER_RESPONSE
                sendReg2 = true
            } else {
                state = State.SHOULD_SEND_REGISTER_REQUEST
            }
            state
        }
        Log.w(TAG, "srtla: $type handleReady state=$st hasFull=$hasFullGroupId sendReg2=$sendReg2")
        if (sendReg2) sendSrtlaReg2()
        delegate?.onSocketConnected(this)
    }

    /**
     * Объявить соединение готовым без регистрации SRTLA — для режима обычного
     * SRT. Вызывается вместо связки probe/register, когда [useSrtla] выключен.
     */
    fun markReadyWithoutSrtla() {
        synchronized(lock) {
            if (!running) return
            state = State.REGISTERED
            // Время последнего входящего заводим сейчас: сторож «зарегистрирован,
            // но трафика нет» иначе сработает сразу, не дождавшись рукопожатия SRT.
            latestReceivedTime = nowMillis()
        }
        Log.d(TAG, "srtla: $type готов без регистрации (обычный SRT)")
        delegate?.onRegistered(this)
    }

    fun probe() {
        val st = synchronized(lock) {
            groupId = Srtla.randomGroupId()
            handshakeStep = HandshakeStep.PROBE
            handshakeSentAt = nowMillis()
            handshakeRetries = 0
            state
        }
        Log.w(TAG, "srtla: $type probe state=$st")
        sendSrtlaReg2()
    }

    fun register(groupId: ByteArray) {
        var send = false
        val st = synchronized(lock) {
            this.groupId = groupId
            hasFullGroupId = true
            // The pre-group handshake is over for this connection.
            handshakeStep = null
            handshakeRetries = 0
            if (state == State.SHOULD_SEND_REGISTER_REQUEST) {
                state = State.WAIT_FOR_REGISTER_RESPONSE
                send = true
            }
            state
        }
        Log.w(TAG, "srtla: $type register state=$st sendReg2=$send running=$running started=$started hasFull=$hasFullGroupId")
        if (send) sendSrtlaReg2()
    }

    fun sendSrtlaReg1() {
        synchronized(lock) {
            groupId = Srtla.randomGroupId()
            handshakeStep = HandshakeStep.REG1
            handshakeSentAt = nowMillis()
            handshakeRetries = 0
        }
        sendReg1Packet()
    }

    /** Re-sends REG1 for the group id we already asked for. A retry must NOT
     *  mint a new random id: if the server did create the group and only its
     *  REG2 reply was lost, a fresh id leaves that group orphaned and starts
     *  over from scratch. */
    private fun sendReg1Packet() {
        val packet: ByteArray
        val localPort: Int?
        synchronized(lock) {
            packet = Srtla.createPacket(Srtla.PacketType.REG1, Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE)
            if (groupId.size == Srtla.GROUP_ID_SIZE) {
                System.arraycopy(groupId, 0, packet, Srtla.CONTROL_TYPE_SIZE, Srtla.GROUP_ID_SIZE)
            }
            localPort = socket?.localPort
        }
        Log.d(TAG, "srtla: $type send REG1 localPort=$localPort")
        sendPacket(packet)
    }

    private fun sendSrtlaReg2() {
        val packet = Srtla.createPacket(Srtla.PacketType.REG2, Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE)
        synchronized(lock) {
            if (groupId.size == Srtla.GROUP_ID_SIZE) {
                System.arraycopy(groupId, 0, packet, Srtla.CONTROL_TYPE_SIZE, Srtla.GROUP_ID_SIZE)
            }
            lastReg2SendTime = nowMillis()
        }
        Log.d(TAG, "srtla: $type send REG2 localPort=${synchronized(lock) { socket?.localPort }}")
        sendPacket(packet)
    }

    fun sendSrtPacket(packet: ByteArray) {
        sendPacket(packet)
    }

    private fun sendPacket(packet: ByteArray) {
        if (Srt.isDataPacket(packet)) {
            synchronized(lock) {
                packetsInFlight.add(Srt.getSequenceNumber(packet))
            }
        }
        val s = synchronized(lock) { socket } ?: return
        try {
            if (verboseLogging) {
                val kind = if (Srt.isDataPacket(packet)) "SRT-DATA" else {
                    val t = Srt.getControlPacketType(packet)
                    when (t) {
                        Srt.PacketType.HANDSHAKE.rawValue -> "SRT-HANDSHAKE"
                        Srt.PacketType.KEEPALIVE.rawValue -> "SRT-KEEPALIVE"
                        Srt.PacketType.ACK.rawValue -> "SRT-ACK"
                        Srt.PacketType.NAK.rawValue -> "SRT-NAK"
                        else -> "SRT-CTRL-$t"
                    }
                }
                Log.v(TAG, "srtla: $type SEND $kind size=${packet.size} localPort=${s.localPort} dst=$host:$port")
            }
            s.send(DatagramPacket(packet, packet.size))
            synchronized(lock) { totalBytesSent += packet.size }
        } catch (e: Exception) {
            // Network-bound socket whose interface is gone raises e.g.
            // ENETUNREACH/EHOSTUNREACH/ENETDOWN. Swallowing it kept the
            // connection REGISTERED and selectable, so the client kept sending
            // into the dead path and the server never moved last_address to a
            // live one. Report it so the client can stop selecting it (audit:
            // matches Moblin stopping connections whose interface left the path).
            Log.e(TAG, "srtla: $type send failed: ${e.message}")
            if (synchronized(lock) { running && this.socket === s }) {
                delegate?.onSendFailed(this)
            }
        }
    }

    fun bytesSent(): Long = synchronized(lock) { totalBytesSent }

    fun packetsDropped(): Long = synchronized(lock) { packetsDropped }

    fun handleSrtAckSn(ackSn: Long) {
        synchronized(lock) {
            // ВНИМАНИЕ: снятие с полёта здесь НЕ признак доставки по этому
            // каналу. SRT-ACK накопительный — он подтверждает всё до указанного
            // номера. Когда SRT пересылает потерянное по здоровому каналу,
            // накопительное подтверждение снимает с полёта и пакеты умершего
            // канала, которые на самом деле сгинули. Полевая проверка 31.08:
            // сторож, поверивший этому сигналу, не сработал ни разу. Признак
            // доставки per-link только один — SRTLA-ACK.
            packetsInFlight.removeIf { sn -> Srt.isSnAcked(sn, ackSn) }
        }
    }

    fun handleSrtNakSn(sn: Long) {
        synchronized(lock) {
            if (packetsInFlight.remove(sn)) {
                windowSize = maxOf(windowSize - windowDecrement, windowMinimum * windowMultiply)
                packetsDropped++
            }
        }
    }

    fun handleSrtlaAckSn(sn: Long) {
        synchronized(lock) {
            if (packetsInFlight.remove(sn)) {
                lastDeliveryAckTime = nowMillis()
                bytesSentAtLastDeliveryAck = totalBytesSent
                if (packetsInFlight.size * windowMultiply > windowSize) {
                    windowSize += windowIncrement - 1
                }
            }
            windowSize = minOf(windowSize + 1, windowMaximum * windowMultiply)
        }
    }

    fun score(): Int {
        synchronized(lock) {
            if (state != State.REGISTERED) return -1
            if (priority == 0f) return -1
            val score = windowSize / (packetsInFlight.size + 1)
            return when {
                windowSize > windowStableMaximum * windowMultiply ->
                    (score * priority).toInt()
                windowSize > windowStableMinimum * windowMultiply -> {
                    var factor = (windowSize - windowStableMinimum * windowMultiply).toFloat()
                    factor /= ((windowStableMaximum - windowStableMinimum) * windowMultiply).toFloat()
                    val scaledPriority = 1 + (priority - 1) * factor
                    (score * scaledPriority).toInt()
                }
                // Weight always applies, even for a degraded link (window below
                // the stable floor), so a configured heavy link never competes
                // equally with a light one when both are losing packets.
                else -> (score * priority).toInt()
            }
        }
    }

    fun isEnabled(): Boolean = priority > 0f

    /** True when this connection is carrying inbound SRT data (or has not yet
     *  received any data, i.e. a fresh handshake where the server has not
     *  started sending media). Used by selectConnection() to route outbound
     *  traffic to the path the server is actually feeding. */
    fun isDataFresh(now: Long): Boolean = synchronized(lock) {
        !everReceivedData || (now - lastDataReceivedAt) <= dataRecencyMs
    }

    /** True when THIS link's own return path is confirmed alive — any inbound
     *  packet on its socket, not just SRT-DATA. SRT-DATA/ACK only ever flow
     *  on the server's single chosen last_address (see isDataFresh/
     *  selectConnection) — gating on it here made every non-primary link look
     *  permanently dead in the overwhelmingly common upload-only case (the
     *  server never sends media down at all), collapsing selectDataConnection
     *  to a single link and silently defeating weighted aggregation. The
     *  per-connection SRTLA keepalive round trip (onTick REGISTERED branch)
     *  answers per-link reachability independently of which link is the SRT
     *  last_address, so latestReceivedTime — updated on any received packet,
     *  keepalives included — is the correct signal here. */
    fun hasRecentInbound(now: Long): Boolean = synchronized(lock) {
        (now - latestReceivedTime) <= dataRecencyMs
    }

    /** One link's raw state for the per-second diagnostic line. Deliberately
     *  separate from [ConnectionStat] (a UI-facing model): this carries the
     *  fields that matter when a link misbehaves — registration state, how long
     *  the return path has been silent, congestion window and in-flight depth —
     *  which is exactly what neither the HUD nor an event log can show. */
    data class LinkDebug(
        val type: String,
        val state: String,
        val rttMs: Int,
        val windowSize: Int,
        val inFlight: Int,
        val bytesSent: Long,
        val msSinceInbound: Long,
        val score: Int,
    )

    fun debugSnapshot(now: Long): LinkDebug = synchronized(lock) {
        LinkDebug(
            type = type,
            state = when (state) {
                State.IDLE -> "IDLE"
                State.SHOULD_SEND_REGISTER_REQUEST -> "PREREG"
                State.WAIT_FOR_REGISTER_RESPONSE -> "WAITREG"
                State.REGISTERED -> "REG"
            },
            rttMs = rtt,
            windowSize = windowSize,
            inFlight = packetsInFlight.size,
            bytesSent = totalBytesSent,
            msSinceInbound = now - latestReceivedTime,
            // Reentrant monitor — score() takes the same lock.
            score = score(),
        )
    }

    private fun startReadLoop(socket: DatagramSocket) {
        // Имя обязательно: замер 31.08 упёрся в то, что горячие потоки в
        // /proc видны как Thread-11/Thread-12 и их не с чем сопоставить.
        val thread = Thread({ runReadLoop(socket) }, "SrtlaRead-$type")
        // Publish under the lock BEFORE start so stop()/reconnect() (reading it
        // under lock) can never observe a stale null and miss joining this
        // reader (data race).
        synchronized(lock) { readThread = thread }
        thread.start()
    }

    private fun runReadLoop(socket: DatagramSocket) {
        val buffer = ByteArray(65536)
        var lastTick = nowMillis()
        // Тик раньше наступал ТОЛЬКО по таймауту сокета, то есть исключительно
        // в паузах входящего потока. При плотном входящем receive() возвращался
        // штатно, и периодические проверки не выполнялись вовсе — а во время
        // отказа канала сервер продолжает по нему слать, так что пауз не было
        // именно тогда, когда сторож нужнее всего. Полевой тест 31.08: канал,
        // мёртвый на передачу, отключался через 23 секунды при пороге сторожа
        // в 3. Теперь тик привязан ко времени, а не к тишине.
        fun tickIfDue() {
            val now = nowMillis()
            if (now - lastTick >= TICK_INTERVAL_MS) {
                lastTick = now
                onTick()
            }
        }
        while (true) {
            if (!isCurrent(socket)) return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                if (!isCurrent(socket)) return
                val data = buffer.copyOfRange(packet.offset, packet.offset + packet.length)
                handlePacket(data)
                tickIfDue()
            } catch (e: SocketTimeoutException) {
                if (!isCurrent(socket)) return
                lastTick = nowMillis()
                onTick()
            } catch (e: Exception) {
                if (!isCurrent(socket)) return
                lastTick = nowMillis()
                onTick()
            }
        }
    }

    private fun isCurrent(socket: DatagramSocket): Boolean = synchronized(lock) {
        running && this.socket === socket
    }

    private fun onTick() {
        val now = nowMillis()
        verboseLogging = Log.isLoggable(TAG, Log.VERBOSE)
        var sendReg2Retry = false
        var doReconnect = false
        var keepalivePacket: ByteArray? = null
        var retryHandshake: HandshakeStep? = null
        synchronized(lock) {
            when (state) {
                State.REGISTERED -> {
                    // В простом SRT keepalive не шлём: у приёмника нет разбора
                    // пакетов SRTLA, и тип 0x1000 для него мусор.
                    if (useSrtla && (lastKeepAliveSendTime == 0L || now - lastKeepAliveSendTime >= 1000)) {
                        lastKeepAliveSendTime = now
                        // Build the packet under lock but send it OUTSIDE to
                        // avoid blocking on socket.send() while holding the
                        // lock (dead network → send() blocks → stop()/reconnect()
                        // hang trying to acquire the same lock).
                        keepalivePacket = Srtla.createPacket(Srtla.PacketType.KEEPALIVE, Srtla.CONTROL_TYPE_SIZE + 8)
                        val elapsed = nowMillis() - keepAliveBaseTime
                        writeInt64(keepalivePacket!!, Srtla.CONTROL_TYPE_SIZE, elapsed)
                        // Помним, что именно отправили: приёмник шлёт и свои
                        // собственные keepalive, а их payload — не наша метка.
                        sentKeepAliveStamps.addLast(elapsed)
                        while (sentKeepAliveStamps.size > KEEPALIVE_STAMP_HISTORY) {
                            sentKeepAliveStamps.removeFirst()
                        }
                    }
                    // Канал может исправно отвечать и при этом ничего не
                    // доставлять. В поле 31.08 разрыв был только на передачу:
                    // сервер продолжал слать нам ACK, latestReceivedTime
                    // оставался свежим, и линк числился живым 24 секунды, всё
                    // это время сливая ~100-300 kbps в никуда.
                    //
                    // Меряем именно «льём и не получаем ответа». По полёту
                    // судить нельзя: SRT замечает потерю, шлёт NAK, и обработчик
                    // NAK снимает пакеты с полёта — набор пустеет ровно тогда,
                    // когда канал умер. Полевая проверка на этом и провалилась.
                    val unconfirmedBytes = totalBytesSent - bytesSentAtLastDeliveryAck
                    val noDelivery = unconfirmedBytes > DELIVERY_PROBE_BYTES &&
                        now - lastDeliveryAckTime > DELIVERY_WATCHDOG_MS
                    // В простом SRT этот сторож не применим. Он судит о жизни
                    // канала по подтверждениям доставки уровня SRTLA, а их там
                    // нет по определению — значит `noDelivery` срабатывает через
                    // пару секунд эфира на ровном месте. Дальше он
                    // перерегистрирует соединение, которого приёмник не ждёт, и
                    // эфир умирает. Полевой разбор 14.09: канал уходил в
                    // WAITREG, входящие прекращались, неподтверждённые росли.
                    //
                    // За живучесть в этом режиме отвечает сам SRT: у него свой
                    // таймаут по тишине, и он отрабатывает штатно.
                    if (useSrtla && (now - latestReceivedTime > RECOVERY_WATCHDOG_MS || noDelivery)) {
                        // Эталон belabox: 4с без входящих = соединение мертво ->
                        // полный сброс состояния и повторная регистрация ТЕМ ЖЕ
                        // сокетом (порт не меняется — смена порта плодит NAT-
                        // маппинги). Bounce новым портом — только как эскалация
                        // после нескольких неудачных циклов восстановления.
                        if (noDelivery) deliveryWatchdogTrips++
                        if (recoveryAttempts < MAX_IN_PLACE_RECOVERY) {
                            recoveryAttempts++
                            val why = if (noDelivery) {
                                "no delivery ${now - lastDeliveryAckTime}ms (${unconfirmedBytes}B без ответа)"
                            } else {
                                "no inbound ${now - latestReceivedTime}ms"
                            }
                            Log.w(TAG, "srtla: $type $why -> in-place re-register ($recoveryAttempts/$MAX_IN_PLACE_RECOVERY)")
                            synchronized(lock) {
                                state = State.WAIT_FOR_REGISTER_RESPONSE
                                reg2Retries = 0
                            }
                            sendReg2Retry = true
                        } else {
                            doReconnect = true
                        }
                    }
                }
                State.WAIT_FOR_REGISTER_RESPONSE -> {
                    if (now - lastReg2SendTime >= REG2_RETRY_INTERVAL_MS) {
                        if (reg2Retries < MAX_REG2_RETRIES) {
                            reg2Retries++
                            Log.w(TAG, "srtla: $type REG2 retry $reg2Retries/$MAX_REG2_RETRIES")
                            sendReg2Retry = true
                        } else {
                            doReconnect = true
                        }
                    }
                }
                State.SHOULD_SEND_REGISTER_REQUEST -> {
                    // Pre-group handshake (probe / REG1) — see HandshakeStep.
                    val step = handshakeStep
                    if (step != null && now - handshakeSentAt >= REG2_RETRY_INTERVAL_MS) {
                        if (handshakeRetries < MAX_HANDSHAKE_RETRIES) {
                            handshakeRetries++
                            handshakeSentAt = now
                            Log.w(TAG, "srtla: $type $step retry $handshakeRetries/$MAX_HANDSHAKE_RETRIES")
                            retryHandshake = step
                        }
                        // No bounce on exhaustion: the client-level state
                        // machine only ever probes ONE connection (see
                        // SrtlaClient.onSocketConnected), so a fresh socket
                        // here would never be re-probed. SrtlaStream's
                        // connection watchdog owns that escalation.
                    }
                }
                else -> Unit
            }
        }
        // Send outside the lock to avoid blocking on dead networks.
        keepalivePacket?.let { sendPacket(it) }
        if (sendReg2Retry) sendSrtlaReg2()
        when (retryHandshake) {
            HandshakeStep.PROBE -> sendSrtlaReg2()
            HandshakeStep.REG1 -> sendReg1Packet()
            null -> Unit
        }
        if (doReconnect) {
            if (state == State.REGISTERED) {
                reconnect("no inbound traffic for ${15_000}ms")
            } else {
                reconnect("registration timeout after $MAX_REG2_RETRIES REG2 retries")
            }
        }
    }

    /** Reopen the socket and re-register with the current groupId (if any).
     *  Mirrors Moblin's per-connection self-heal: a new source port re-registers
     *  the SAME group with the server (which adds/updates a connection for the
     *  new address), instead of tearing down the whole client. */
    /** Delayed reconnect for server-refused registrations: bounce from a
     *  scheduler thread so the read loop is never blocked. */
    private fun scheduleReconnect(delayMs: Long, reason: String, clearQuarantine: Boolean = false) {
        val generation = synchronized(lock) {
            pendingReconnect?.cancel(false)
            lifecycleGeneration
        }
        val future = backoffExecutor.schedule({
            val shouldReconnect = synchronized(lock) {
                // Засов снимаем ДО попытки: если она провалится, следующий отказ
                // отправки должен суметь запланировать новую с большей задержкой.
                if (clearQuarantine) quarantinePending = false
                started && running && lifecycleGeneration == generation
            }
            if (!shouldReconnect) return@schedule
            try {
                reconnect(reason)
            } catch (e: Exception) {
                Log.e(TAG, "srtla: $type scheduled reconnect failed: ${e.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (started && running && lifecycleGeneration == generation) {
                pendingReconnect = future
            } else {
                future.cancel(false)
            }
        }
    }

    /**
     * Увести линк в карантин: он остаётся в бондинге, но перестаёт выбираться
     * (`state = IDLE` даёт `score() = -1`), и через [firstDelayMs] — а при
     * повторах через растущую задержку — делается попытка поднять его заново.
     *
     * Пришло на смену безусловному `removeConnection`: удалённый линк вернуться
     * не мог вообще. `onNetworkAvailable` для него больше не придёт, потому что
     * `BondingNetworkManager` схлопывает повторный `onAvailable` для той же
     * `Network`, а сам интерфейс никуда не девался — типичный случай Wi-Fi с
     * умершим аплинком или соты на хендовере. Один транзиентный `ENETUNREACH`
     * стоил канала до конца эфира.
     *
     * Насовсем не сдаёмся: задержка упирается в [QUARANTINE_MAX_BACKOFF_MS] и
     * попытки продолжаются, пока живёт сессия. Туннель, лифт и глухая зона
     * кончаются, а эфир продолжается.
     */
    fun quarantine(reason: String, firstDelayMs: Long = QUARANTINE_BASE_BACKOFF_MS) {
        val attempt = synchronized(lock) {
            if (!started || !running) return
            // Дебаунс: подъём уже запланирован, второй не нужен.
            if (quarantinePending) return
            quarantinePending = true
            state = State.IDLE
            quarantineAttempts += 1
            quarantineAttempts
        }
        val delayMs = if (attempt <= 1) {
            firstDelayMs
        } else {
            minOf(
                QUARANTINE_MAX_BACKOFF_MS,
                QUARANTINE_BASE_BACKOFF_MS shl minOf(QUARANTINE_MAX_SHIFT, attempt - 1),
            )
        }
        Log.w(TAG, "srtla: $type quarantined attempt=$attempt retry in ${delayMs}ms — $reason")
        scheduleReconnect(delayMs, "quarantine retry #$attempt", clearQuarantine = true)
    }

    fun reconnect(reason: String) {
        Log.d(TAG, "srtla: $type RECONNECT id=${System.identityHashCode(this)} reason=$reason")
        // Serialize: reconnect is reachable from the read loop, the backoff
        // executor and races with stop(); two overlapping swaps would leave a
        // "registered but no traffic" state.
        if (!reconnecting.compareAndSet(false, true)) {
            Log.w(TAG, "srtla: $type reconnect already in progress — skipping")
            return
        }
        try {
            // Wait for the old read thread to exit before creating a replacement:
            // otherwise two readers race on the same Connection object and the
            // stale one may inject packets from the dead socket into the new one.
            val oldThread = synchronized(lock) { readThread }
            try { synchronized(lock) { socket }?.close() } catch (_: Exception) {}
            // Never join self: reconnect() called from the read loop would
            // otherwise block the caller for up to 300ms on its own monitor.
            if (oldThread != null && oldThread !== Thread.currentThread()) {
                oldThread.join(300)
            }
            val gen = synchronized(lock) { lifecycleGeneration }
            if (startInternal(gen)) {
                handleReady()
            } else {
                // Раньше здесь звался onSendFailed, и на единственном линке
                // получался цикл без задержки: reconnect провалился -> клиент
                // решает RECONNECT -> reconnect провалился -> ... Теперь неудача
                // уходит в карантин, то есть в ту же отложенную попытку, но с
                // растущей задержкой. state = IDLE ставит сам quarantine().
                quarantine("socket setup failed")
            }
        } finally {
            reconnecting.set(false)
        }
    }



    private fun handlePacket(packet: ByteArray) {
        if (packet.size < Srtla.CONTROL_TYPE_SIZE) return
        synchronized(lock) {
            latestReceivedTime = nowMillis()
        }
        if (Srt.isDataPacket(packet)) {
            synchronized(lock) {
                lastDataReceivedAt = nowMillis()
                everReceivedData = true
            }
            if (verboseLogging) {
                Log.v(TAG, "srtla: $type RECV SRT-DATA size=${packet.size} localPort=${synchronized(lock) { socket?.localPort }}")
            }
            delegate?.onPacket(this, packet)
        } else {
            val t = Srt.getControlPacketType(packet)
            if (verboseLogging) {
                Log.v(TAG, "srtla: $type RECV SRT-CTRL-$t size=${packet.size} localPort=${synchronized(lock) { socket?.localPort }}")
            }
            handleControlPacket(packet)
        }
    }

    private fun handleControlPacket(packet: ByteArray) {
        val type = Srt.getControlPacketType(packet)
        if (verboseLogging) {
            Log.v(TAG, "srtla: $type recv ${ctrlName(type)} state=$state")
        }
        when (type) {
            Srtla.PacketType.KEEPALIVE.rawValue -> handleSrtlaKeepalive(packet)
            Srtla.PacketType.ACK.rawValue -> handleSrtlaAck(packet)
            Srtla.PacketType.REG2.rawValue -> handleSrtlaReg2(packet)
            Srtla.PacketType.REG3.rawValue -> handleSrtlaReg3()
            Srtla.PacketType.REG_NGP.rawValue -> delegate?.onRegNgp(this)
            Srtla.PacketType.REG_ERR.rawValue, Srtla.PacketType.REG_NAK.rawValue -> {
                // Registration was refused/not-acknowledged by the server. Two
                // distinct cases matter: a transient group race (retry soon) and
                // the receiver's auth rate-limiter ("source throttled"), where
                // EVERY further attempt counts as another failure and extends
                // the ban indefinitely (field-verified vicious circle). Back off
                // exponentially instead of bouncing immediately.
                Log.w(TAG, "srtla: $type registration refused ctrl=0x${type.toString(16)} state=$state")
                regErrCount++
                val delayMs = minOf(
                    60_000L,
                    5_000L * (1L shl minOf(4, regErrCount - 1)),
                )
                scheduleReconnect(delayMs, "registration refused ctrl=0x${type.toString(16)}")
            }
            Srt.PacketType.ACK.rawValue -> {
                handleSrtAck(packet)
                delegate?.onPacket(this, packet)
            }
            Srt.PacketType.NAK.rawValue -> {
                handleSrtNak(packet)
                delegate?.onPacket(this, packet)
            }
            else -> {
                // SRT control packets we do not interpret at the SRTLA layer
                // (handshake, keepalive, shutdown, etc.) are forwarded to the SRT
                // sender. Log the size so we can correlate which SRT packets reach
                // the client (e.g. the CONCLUSION handshake) vs get lost on a
                // lossy cellular downlink.
                delegate?.onPacket(this, packet)
            }
        }
    }

    private fun handleSrtlaKeepalive(packet: ByteArray) {
        if (packet.size < Srtla.CONTROL_TYPE_SIZE + 8) return
        val sendTime = readInt64(packet, Srtla.CONTROL_TYPE_SIZE)
        // Не всякий входящий keepalive — эхо нашего. Приёмник (bbox srtla_rec)
        // шлёт и свои собственные, особенно когда перестал нас слышать; их
        // payload нашей меткой времени не является. Раньше мы вычитали её из
        // чего попало: в поле 31.08 это давало rtt=10000 (потолок coerceIn) с
        // момента разрыва канала и НАВСЕГДА — значение больше ничем не
        // сбрасывалось. Считаем RTT только по метке, которую сами отправляли.
        val ours = synchronized(lock) { sentKeepAliveStamps.remove(sendTime) }
        if (!ours) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "srtla: $type keepalive не наш (payload=$sendTime) — не эхо, rtt не трогаем")
            }
            return
        }
        val now = nowMillis() - keepAliveBaseTime
        rtt = (now - sendTime).coerceIn(0, 10000).toInt()
    }

    private fun handleSrtlaAck(packet: ByteArray) {
        if (packet.size % 4 != 0) return
        var offset = 4
        while (offset <= packet.size - 4) {
            delegate?.onSrtlaAck(this, readUInt32(packet, offset))
            offset += 4
        }
    }

    private fun handleSrtlaReg2(packet: ByteArray) {
        if (packet.size != Srtla.CONTROL_TYPE_SIZE + Srtla.GROUP_ID_SIZE) return
        val group = packet.copyOfRange(Srtla.CONTROL_TYPE_SIZE, packet.size)
        val local = synchronized(lock) { groupId }
        if (local.size != Srtla.GROUP_ID_SIZE) return
        val half = Srtla.GROUP_ID_SIZE / 2
        for (i in 0 until half) {
            if (group[i] != local[i]) return
        }
        delegate?.onReg2(this, group)
    }

    private fun handleSrtlaReg3() {
        var becameRegistered = false
        val st = synchronized(lock) { state }
        Log.d(TAG, "srtla: $type REG3 recv state=$st")
        synchronized(lock) {
            if (state == State.WAIT_FOR_REGISTER_RESPONSE) {
                state = State.REGISTERED
                becameRegistered = true
                regErrCount = 0
                recoveryAttempts = 0
                // Линк снова в группе — карантин снят, лестница задержек с нуля.
                quarantinePending = false
                quarantineAttempts = 0
            }
        }
        if (becameRegistered) {
            keepAliveBaseTime = nowMillis()
            lastKeepAliveSendTime = 0
            lastDeliveryAckTime = nowMillis()
            bytesSentAtLastDeliveryAck = totalBytesSent
            delegate?.onRegistered(this)
        }
    }

    private fun handleSrtAck(packet: ByteArray) {
        if (packet.size < 20) return
        delegate?.onSrtAck(this, readUInt32(packet, 16))
    }

    private fun handleSrtNak(packet: ByteArray) {
        Srt.processNak(packet) { sn -> delegate?.onSrtNak(this, sn) }
    }

    companion object {
        private const val TAG = "Srtla"

        /** Human-readable packet name for logs: a mystery 'ctrl=0x5' hid the
         *  server's SHUTDOWN-kill in plain sight for days. */
        fun ctrlName(type: Int): String = when (type) {
            0x0000 -> "SRT-HANDSHAKE"
            0x0001 -> "SRT-KEEPALIVE"
            0x0002 -> "SRT-ACK"
            0x0003 -> "SRT-NAK"
            0x0005 -> "SRT-SHUTDOWN"
            0x0006 -> "SRT-ACKACK"
            0x1000 -> "SRTLA-KEEPALIVE"
            0x1100 -> "SRTLA-ACK"
            0x1200 -> "REG1"
            0x1201 -> "REG2"
            0x1202 -> "REG3"
            0x1210 -> "REG_ERR"
            0x1211 -> "REG_NGP"
            0x1212 -> "REG_NAK"
            else -> "ctrl=0x${type.toString(16)}"
        }
        internal val backoffExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "SrtlaBackoff").apply { isDaemon = true }
        }
        private const val windowDefault = 20
        private const val windowMinimum = 1
        private const val windowMaximum = 60
        private const val windowStableMinimum = 10
        private const val windowStableMaximum = 20
        private const val windowMultiply = 1000
        private const val windowDecrement = 100
        private const val windowIncrement = 30
        private const val dataRecencyMs = 4000L
        // Field evidence (2026-08-22 pcap): srtla_rec DOES send REG3 for every
        // REG2, but the cellular NAT path of the first socket after a stop can
        // go one-way deaf — replies never reach the app until the source port
        // changes. Patience cannot fix a dead mapping; fast rotation can.
        // Bounce after ~4s (2 x 2s); reg2Retries resets on every new socket,
        // so repeated bounces remain available.
        private const val REG2_RETRY_INTERVAL_MS = 2000L
        private const val MAX_REG2_RETRIES = 2
        // Pre-group handshake retries (probe / REG1), at the same 2s cadence.
        // 5 tries ≈ 10s of cover, deliberately inside SrtlaStream's 15s
        // connection watchdog so the retries get a full run before the
        // transport is torn down and restarted from scratch.
        private const val MAX_HANDSHAKE_RETRIES = 5
        // belabox-эталон: CONN_TIMEOUT=4с. Наш вариант: 5с без входящих ->
        // in-place re-register; после MAX_IN_PLACE_RECOVERY неудач — bounce.
        private const val RECOVERY_WATCHDOG_MS = 5000L

        /** Keepalive уходит раз в секунду, RTT заведомо меньше; четырёх меток
         *  с запасом хватает, чтобы опознать эхо и не копить мусор. */
        private const val KEEPALIVE_STAMP_HISTORY = 4

        /** Сколько ждать подтверждения доставки, прежде чем считать канал
         *  мёртвым на передачу. RTT здесь — десятки миллисекунд, так что три
         *  секунды с большим запасом отделяют «медленно» от «никуда». */
        private const val DELIVERY_WATCHDOG_MS = 3000L

        /** Сколько надо влить без единого подтверждения, чтобы считать это
         *  сигналом, а не паузой в раскладке. Около восьми полных пакетов. */
        private const val DELIVERY_PROBE_BYTES = 10_000L

        /** Как часто прогонять периодические проверки внутри цикла чтения.
         *  Совпадает с soTimeout, чтобы поведение не зависело от того, пришёл
         *  пакет или наступила тишина. */
        private const val TICK_INTERVAL_MS = 500L
        private const val MAX_IN_PLACE_RECOVERY = 2

        /** Лестница задержек карантина: 1с → 2 → 4 → 8 → 16 → 32 → кап 60с.
         *  Та же схема, что уже стоит на отказ регистрации (REG_ERR), только
         *  начинается с секунды: отказ отправки чаще транзиентный, чем отказ
         *  сервера, и первую попытку имеет смысл делать быстро. */
        private const val QUARANTINE_BASE_BACKOFF_MS = 1_000L
        private const val QUARANTINE_MAX_BACKOFF_MS = 60_000L
        private const val QUARANTINE_MAX_SHIFT = 5
    }
}
