package app.brix.bonding

import app.brix.core.ConnectionStat
import android.util.Log
import java.net.DatagramSocket
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SrtlaClient {
    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onRemotePacket: ((ByteArray) -> Unit)? = null

    private enum class State {
        IDLE,
        WAIT_FOR_REMOTE_SOCKET_CONNECTED,
        WAIT_FOR_PROBE,
        WAIT_FOR_GROUP_ID,
        WAIT_FOR_REGISTERED,
        RUNNING,
    }

    // CopyOnWriteArrayList so onNetworkAvailable/onNetworkLost (adding/removing
    // connections) never races with the forEach loops in start()/stop()/stats
    // and throws ConcurrentModificationException during reconnect.
    private val connections = CopyOnWriteArrayList<SrtlaConnection>()
    @Volatile
    private var state = State.IDLE
    @Volatile
    private var groupId: ByteArray? = null
    @Volatile
    private var host = ""
    @Volatile
    private var port = 0

    // Serializes lifecycle operations (start/stop/add/remove) that must not
    // race with each other when called from different threads (main, IO,
    // network callbacks).
    private val lifecycleLock = Any()

    // Transport-attempt epoch. Incremented on every start(); connection
    // callbacks carry the epoch they belong to and are dropped if it no longer
    // matches.
    private val epoch = AtomicLong(0)

    fun addConnection(
        type: String,
        priority: Float,
        bindSocket: ((DatagramSocket) -> Unit)? = null,
        resolve: ((String) -> java.net.InetAddress?)? = null,
    ): SrtlaConnection =
        synchronized(lifecycleLock) {
            val connection = SrtlaConnection(type, priority, bindSocket, resolve)
            connection.delegate = connectionDelegate
            connections.add(connection)
            connection
        }

    fun removeConnection(connection: SrtlaConnection) {
        synchronized(lifecycleLock) {
            connections.remove(connection)
            connection.stop()
        }
    }

    fun isStarted(): Boolean = state != State.IDLE

    fun connectionCount(): Int = connections.size

    fun startAddedConnection(connection: SrtlaConnection, host: String, port: Int) {
        synchronized(lifecycleLock) {
            val e = epoch.get()
            connection.attachEpoch(e)
            groupId?.let { connection.register(it) }
            connection.start(host, port, e)
        }
    }

    fun start(host: String, port: Int) = synchronized(lifecycleLock) {
        this.host = host
        this.port = port
        epoch.incrementAndGet()
        state = State.WAIT_FOR_REMOTE_SOCKET_CONNECTED
        Log.d(TAG, "srtla-client: start host=$host port=$port conns=${connections.size} epoch=${epoch.get()}")
        val startEpoch = epoch.get()
        connections.forEach {
            if (state == State.IDLE || epoch.get() != startEpoch) return
            // Re-attach unconditionally: a connection added by a network
            // callback before this call may already be started with an older
            // epoch (its own start() is a no-op when started). Without the
            // re-attach every packet it receives would be dropped by the
            // epoch guard and the session would hang in Connecting forever.
            it.attachEpoch(startEpoch)
            it.start(host, port, startEpoch)
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        state = State.IDLE
        connections.forEach { it.stop() }
        epoch.addAndGet(2)
        groupId = null
    }

    /** Tear down every connection: stop its socket/read thread and drop it.
     *  Only for a full session teardown (manual Stop / release); reconnect()
     *  must NOT clear so it can reuse live connections. */
    fun clearConnections() = synchronized(lifecycleLock) {
        // Under lifecycleLock: a queued onNetworkAvailable could otherwise add
        // a connection mid-teardown, leaving a stale entry in the next session
        //.
        connections.forEach { it.stop() }
        connections.clear()
    }

    fun isRunning(): Boolean = state == State.RUNNING

    fun handleLocalPacket(packet: ByteArray) {
        // Media (SRT data) is fanned out across links that the server is actually
        // feeding DOWN (recent inbound). A link with a dead downlink ("no
        // inbound", field evidence 2026-08-28 Galaxy S21) must not carry media:
        // sending there makes it the server's last_address and the ACKs sent back
        // to it are lost → "flow window overflow", stream stuck. Control packets
        // (handshake/ACK/NAK/keepalive) stick to the single primary link so the
        // SRT session establishes cleanly.
        val connection = if (Srt.isDataPacket(packet)) {
            selectDataConnection()
        } else {
            selectConnection()
        } ?: return
        connection.sendSrtPacket(packet)
    }

    /**
     * Weighted pick for a media packet, proportionally to [SrtlaConnection.score]
     * (which folds in the configured channel weight), over links with a live
     * downlink (recent inbound SRT data). Falls back to the single primary link
     * while nothing is confirmed feeding down (handshake/startup).
     */
    private fun selectDataConnection(): SrtlaConnection? {
        val now = nowMillis()
        // Этот выбор делается на КАЖДЫЙ пакет медиа — около 1300 раз в секунду.
        // Прежняя версия на каждый из них аллоцировала ArrayList, затем ещё один
        // список из пар (по объекту на соединение), дважды звала score() — а он
        // берёт блокировку соединения, за которую в этот момент дерутся потоки
        // чтения, — и дёргала java.security.SecureRandom, криптостойкий
        // генератор, ради выбора между wifi и сотой. Замер 31.08: отправка
        // одного пакета обходилась в ~90 мкс.
        //
        // Теперь: один примитивный массив на вызов (без упаковки), score() по
        // разу на соединение — заодно снимок согласован, прежние два прохода
        // могли видеть разные значения, — и обычный ThreadLocalRandom.
        val list = connections
        val n = list.size
        if (n == 0) return null
        val weights = LongArray(n)
        var total = 0L
        for (i in 0 until n) {
            val connection = list[i]
            val score = connection.score()
            val w = if (score >= 0 && connection.hasRecentInbound(now)) {
                score.toLong().coerceAtLeast(1L)
            } else {
                0L
            }
            weights[i] = w
            total += w
        }
        if (total == 0L) return selectConnection()
        var r = ThreadLocalRandom.current().nextLong(total)
        var last: SrtlaConnection? = null
        for (i in 0 until n) {
            if (weights[i] == 0L) continue
            last = list[i]
            r -= weights[i]
            if (r < 0) return last
        }
        return last
    }

    fun connectionStats(): List<ConnectionStat> = connections.map { c ->
        ConnectionStat(c.type, c.score(), c.rtt, c.isEnabled(), c.bytesSent(), c.packetsDropped())
    }

    /** Raw per-link state for the per-second diagnostic line (see
     *  SrtlaStreamer.startStats). Not the UI model — see [SrtlaConnection.LinkDebug]. */
    fun debugLinks(): List<SrtlaConnection.LinkDebug> {
        val now = nowMillis()
        return connections.map { it.debugSnapshot(now) }
    }

    /**
     * Pure decision for handling a failed send on [connection], isolated so it
     * can be unit-tested without sockets/threads.
     *
     * Ни один исход больше не удаляет линк. Раньше при живом соседе делался
     * `REMOVE`, и это было навсегда: вернуться линк мог только через
     * `onNetworkAvailable`, а тот для той же `Network` больше не приходит —
     * `BondingNetworkManager` схлопывает повторный `onAvailable`, а интерфейс
     * никуда не девался. Один транзиентный `ENETUNREACH` стоил канала до конца
     * эфира. Теперь оба исхода ведут в карантин, разница только в том, ждать ли
     * перед первой попыткой:
     *  - есть живой сосед (score >= 0) — [QUARANTINE]: выводим из раздачи и
     *    поднимаем с задержкой, раздача тем временем идёт по соседу;
     *  - линк единственный — [RETRY_NOW]: чинимся немедленно, иначе эфиру не
     *    по чему идти. Дальнейшие неудачи всё равно уходят в лестницу задержек.
     */
    internal fun decideSendFailure(connection: SrtlaConnection): SendFailureAction {
        val hasOtherLive = connections.any { it !== connection && it.score() >= 0 }
        return if (hasOtherLive) SendFailureAction.QUARANTINE else SendFailureAction.RETRY_NOW
    }

    internal enum class SendFailureAction { QUARANTINE, RETRY_NOW }

    private fun selectConnection(): SrtlaConnection? {
        val now = nowMillis()
        // Prefer a path the server is actually feeding with media. The server
        // sends SRT data down to a single connection (group->last_address), so
        // once at least one connection is carrying data, a stale one (dead
        // network, or one the server stopped feeding) must not keep being
        // selected. If no connection carries fresh data yet (handshake phase or
        // the only path died), fall back to any eligible connection so a
        // recoverable path is still chosen.
        val anyFresh = connections.any { it.isDataFresh(now) && it.score() >= 0 }
        var best: SrtlaConnection? = null
        var bestScore = -1
        for (connection in connections) {
            val score = connection.score()
            val fresh = connection.isDataFresh(now)
            if (score < 0) continue
            if (anyFresh && !fresh) continue
            if (score > bestScore) {
                best = connection
                bestScore = score
            }
        }
        return best
    }

    private val connectionDelegate = object : SrtlaConnection.Delegate {
        override fun onSocketConnected(connection: SrtlaConnection) {
            if (!connection.matchesEpoch(epoch.get())) return
            Log.d(TAG, "srtla-client: onSocketConnected state=$state conn=${connection.type}")
            if (state != State.WAIT_FOR_REMOTE_SOCKET_CONNECTED) return
            // Advance the state BEFORE sending probe so a fast REG_NGP cannot
            // race ahead and be dropped.
            state = State.WAIT_FOR_PROBE
            connection.probe()
        }

        override fun onRegNgp(connection: SrtlaConnection) {
            if (!connection.matchesEpoch(epoch.get())) return
            Log.d(TAG, "srtla-client: onRegNgp state=$state conn=${connection.type}")
            if (state != State.WAIT_FOR_PROBE) return
            state = State.WAIT_FOR_GROUP_ID
            connection.sendSrtlaReg1()
        }

        override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) {
            // Epoch-guarded like the other transport callbacks: a stale REG2 from
            // a previous transport attempt must not reassign the group id or
            // advance the state of a newer attempt.
            if (!connection.matchesEpoch(epoch.get())) return
            Log.d(TAG, "srtla-client: onReg2 state=$state conn=${connection.type}")
            if (state != State.WAIT_FOR_GROUP_ID) return
            this@SrtlaClient.groupId = groupId
            state = State.WAIT_FOR_REGISTERED
            connections.forEach { it.register(groupId) }
        }

        override fun onRegistered(connection: SrtlaConnection) {
            if (!connection.matchesEpoch(epoch.get())) return
            Log.d(TAG, "srtla-client: onRegistered state=$state")
            if (state != State.WAIT_FOR_REGISTERED) return
            state = State.RUNNING
            onReady?.invoke()
        }

        override fun onPacket(connection: SrtlaConnection, packet: ByteArray) {
            // Stale read-threads from previous transport attempts must not
            // inject packets into the live session: after a few Stop->Start
            // cycles their interleaved input corrupts ACK/NAK bookkeeping and
            // media routing of the current attempt.
            if (!connection.matchesEpoch(epoch.get())) return
            onRemotePacket?.invoke(packet)
        }

        override fun onSrtAck(connection: SrtlaConnection, sn: Long) {
            if (!connection.matchesEpoch(epoch.get())) return
            connections.forEach { it.handleSrtAckSn(sn) }
        }

        override fun onSrtNak(connection: SrtlaConnection, sn: Long) {
            if (!connection.matchesEpoch(epoch.get())) return
            connections.forEach { it.handleSrtNakSn(sn) }
        }

        override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) {
            if (!connection.matchesEpoch(epoch.get())) return
            connections.forEach { it.handleSrtlaAckSn(sn) }
        }

        override fun onSendFailed(connection: SrtlaConnection) {
            if (!connection.matchesEpoch(epoch.get())) return
            // NEVER handle inline: removeConnection/reconnect join the read
            // thread for up to 300ms, and this callback runs on the media-pump
            // path (send → error) — a dead path froze encoding every time
            //. Dispatch to the scheduler thread.
            SrtlaConnection.backoffExecutor.execute {
                when (decideSendFailure(connection)) {
                    SendFailureAction.QUARANTINE ->
                        connection.quarantine("send failed (other live paths remain)")
                    SendFailureAction.RETRY_NOW ->
                        connection.quarantine("send failed (only path)", firstDelayMs = 0L)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Srtla"
    }
}
