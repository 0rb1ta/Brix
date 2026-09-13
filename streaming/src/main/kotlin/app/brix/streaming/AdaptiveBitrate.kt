package app.brix.streaming

import android.os.SystemClock
import android.util.Log
import app.brix.core.AbrAlgorithm

data class SrtStats(
    val rttMs: Double,
    val packetsInFlight: Double,
    val mbpsSendRate: Double,
    val latencyMs: Int,
    /** Startup grace window (Moblin "relaxed"): doubles the fast-decrease
     *  buffer threshold so initial buffer noise doesn't slam to minimum. */
    val relaxed: Boolean = false,
    /** Datagrams force-dropped by the SRT flow window (hard loss signal). */
    val droppedPackets: Long = 0,
)

/** Внутреннее состояние регулятора, открытое тестам. См. [AdaptiveBitrateSrtBelabox.debugState]. */
internal data class AbrDebugState(
    val rttAverage: Double,
    val rttMin: Double,
    val rttJitter: Double,
    val rttAverageDelta: Double,
    val throughput: Double,
    val sendBufferSizeAverage: Double,
    val sendBufferSizeJitter: Double,
)

class AdaptiveBitrateSrtBelabox(
    private var targetBitrate: Long,
    private var minimumBitrate: Long = 250_000,
    private var initialBitrate: Long = 2_500_000,
    // Clock is injectable so unit tests can drive time deterministically.
    // Production uses elapsedRealtime (monotonic uptime) instead of
    // currentTimeMillis: a system clock jump (NTP correction, user changing
    // the time) would otherwise shift the rate-limit windows
    // (nextBitrateIncrTime/DecrTime) and stall or spike the bitrate.
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Aggressiveness profile (Moblin-aligned): BELABOX (default), FAST_IRL
     *  cuts harder/faster, SLOW_IRL gentler. CUSTOM keeps BELABOX reaction but
     *  honors the user's target/min (already applied). */
    private var algorithm: AbrAlgorithm = AbrAlgorithm.BELABOX,
) {
    // Читается вне монитора (см. update) — подписка ставится с другого потока.
    @Volatile
    var onBitrateChange: ((Long) -> Unit)? = null

    private fun cutFactor(): Double = when (algorithm) {
        AbrAlgorithm.FAST_IRL -> 1.5
        AbrAlgorithm.SLOW_IRL -> 0.5
        else -> 1.0
    }

    /** Live algorithm switch without resetting the running bitrate. */
    @Synchronized
    fun setAlgorithm(a: AbrAlgorithm) {
        algorithm = a
    }

    private var currentBitrate = initialBitrate

    // Called from encoder thread (configure) and ABR loop (update) — plain
    // Double accumulators tear without a lock.
    @Synchronized
    fun configure(target: Long, min: Long, initial: Long) {
        targetBitrate = target
        minimumBitrate = min
        initialBitrate = initial
        currentBitrate = initial
        sendBufferSizeAverage = 0.0
        sendBufferSizeJitter = 0.0
        prevSendBufferSize = 0.0
        rttAverage = 0.0
        rttAverageDelta = 0.0
        prevRtt = 300.0
        rttMin = 200.0
        rttJitter = 0.0
        throughput = 0.0
        nextBitrateIncrTime = 0L
        nextBitrateDecrTime = 0L
        consecutiveBadHigh = 0
        consecutiveBadMid = 0
        prevDroppedPackets = 0L
    }

    /** Live update of ceiling/floor without resetting the running bitrate
     *  (so a settings change mid-stream does not cause a bitrate jump). */
    @Synchronized
    fun updateLimits(target: Long, min: Long) {
        targetBitrate = target
        minimumBitrate = min.coerceAtMost(target)
    }

    private var sendBufferSizeAverage = 0.0
    private var sendBufferSizeJitter = 0.0
    private var prevSendBufferSize = 0.0
    private var rttAverage = 0.0
    private var rttAverageDelta = 0.0
    private var prevRtt = 300.0
    private var rttMin = 200.0
    private var rttJitter = 0.0
    private var throughput = 0.0
    private var nextBitrateIncrTime = 0L
    private var lastAbrLogTime = 0L
    private var nextBitrateDecrTime = 0L
    // Consecutive samples above the hard "slam" threshold. A single RTT blip is
    // common on cellular; requiring several consecutive samples before cutting
    // hard removes the saw-tooth between minimum and target.
    private var consecutiveBadHigh = 0
    // То же для средней ветки. packetsInFlight снимается мгновенным снимком раз
    // в 200 мс, а сама величина пилит между подтверждениями: в поле 31.08
    // наблюдалась пила 15 -> 1055 при неподвижном RTT (47-58 мс) и нуле потерь,
    // потому что одна квитанция снимает с полёта сразу сотни пакетов
    // (srt-ack before=419 after=247). Один такой снимок выше порога срезал
    // битрейт вдвое, и на резком движении картинка сыпалась артефактами при
    // полностью здоровой сети. Требуем, чтобы превышение держалось.
    private var consecutiveBadMid = 0
    private var prevDroppedPackets = 0L

    /**
     * Прогоняет один такт регулятора и, если битрейт изменился, зовёт
     * [onBitrateChange] — **уже вне монитора**. Подписчик уходит в
     * `setVideoBitrateOnFly()`, то есть в энкодер, и держать на этом вызове
     * лок регулятора незачем: соседний поток на это время не мог даже
     * прочитать `currentBitrateKbps()`.
     */
    fun update(stats: SrtStats) {
        val newBitrate = updateLocked(stats) ?: return
        onBitrateChange?.invoke(newBitrate)
    }

    /** @return новый битрейт, если он изменился, иначе null. */
    @Synchronized
    private fun updateLocked(stats: SrtStats): Long? {
        if (stats.rttMs == 0.0) return null
        val now = clock()

        val sendBufferSize = stats.packetsInFlight
        updateSendBufferSizeAverage(sendBufferSize)
        updateSendBufferSizeJitter(sendBufferSize)
        val rtt = stats.rttMs
        updateRttAverage(rtt)
        val deltaRtt = updateAverageRttDelta(rtt)
        updateRttMin(rtt)
        updateRttJitter(deltaRtt)
        updateThroughput(stats.mbpsSendRate)
        val srtLatency = stats.latencyMs.toDouble()

        var bitrate = currentBitrate
        val sendBufferSizeTh3 = (sendBufferSizeAverage + sendBufferSizeJitter) * 4
        var sendBufferSizeTh2 = maxOf(
            50.0,
            sendBufferSizeAverage + maxOf(sendBufferSizeJitter * 3.0, sendBufferSizeAverage),
        )
        sendBufferSizeTh2 = minOf(
            sendBufferSizeTh2,
            rttToSendBufferSize(srtLatency / 2, throughput),
        )
        if (stats.relaxed) sendBufferSizeTh2 *= 2
        val sendBufferSizeTh1 = maxOf(50.0, sendBufferSizeAverage + sendBufferSizeJitter * 2.5)
        val rttThMax = rttAverage + maxOf(rttJitter * 4, rttAverage * 15 / 100)
        val rttThMin = rttMin + maxOf(1.0, rttJitter * 2)

        var branch = "none"
        // Hard-congestion: several consecutive samples above the high threshold,
        // or an actual forced flow-window drop. Cut proportionally (half), not to
        // the floor, so a blip can't snap the stream to minimum (H7).
        val highBad = rtt >= srtLatency / 3 || sendBufferSize > sendBufferSizeTh3
        consecutiveBadHigh = if (highBad) consecutiveBadHigh + 1 else 0
        val midBad = rtt > srtLatency / 5 || sendBufferSize > sendBufferSizeTh2
        consecutiveBadMid = if (midBad) consecutiveBadMid + 1 else 0
        val flowDrop = stats.droppedPackets - prevDroppedPackets
        prevDroppedPackets = stats.droppedPackets
        if (bitrate > minimumBitrate && (consecutiveBadHigh >= 3 || flowDrop > 0)) {
            val cut = if (flowDrop > 0) bitrate / 4 else bitrate / 2
            bitrate = maxOf(bitrate - (cut * cutFactor()).toLong(), minimumBitrate)
            nextBitrateDecrTime = now + 250
            branch = if (flowDrop > 0) "drop" else "halfBig"
        } else if (now > nextBitrateDecrTime && midBad && consecutiveBadMid >= CONSECUTIVE_BAD_MID) {
            bitrate -= ((100_000 + bitrate / 10) * cutFactor()).toLong()
            nextBitrateDecrTime = now + 250
            branch = "decBig"
        } else if (now > nextBitrateDecrTime && (rtt > rttThMax || sendBufferSize > sendBufferSizeTh1)) {
            bitrate -= (100_000 * cutFactor()).toLong()
            nextBitrateDecrTime = now + 200
            branch = "dec"
        } else if (now > nextBitrateIncrTime && rtt < rttThMin && rttAverageDelta < 0.01) {
            bitrate += 100_000 + bitrate / 30
            nextBitrateIncrTime = now + 400
            branch = "inc"
        }

        bitrate = bitrate.coerceIn(minimumBitrate, targetBitrate)
        // Log only actual bitrate changes, throttled to one line per second:
        // a 200ms debug flood rotates the whole logcat buffer in minutes and
        // buries real events (field finding, C1).
        if (bitrate != currentBitrate && now - lastAbrLogTime >= 1000) {
            lastAbrLogTime = now
            android.util.Log.d(
                "Srtla",
                "abr $branch kbps=${currentBitrate / 1000}->${bitrate / 1000} " +
                    "rtt=${rtt.toInt()} pif=${sendBufferSize.toInt()} " +
                    "mbps=${stats.mbpsSendRate.toInt()}",
            )
        }
        if (bitrate == currentBitrate) return null
        currentBitrate = bitrate
        return bitrate
    }

    @Synchronized
    fun currentBitrateKbps(): Long = currentBitrate / 1000

    /**
     * Снимок внутреннего состояния — только для тестов. Без него постоянные
     * времени проверить нечем: наружу торчал один `currentBitrateKbps()`, и
     * рассогласование периода (коэффициенты от цикла в 20 мс на цикле в 200 мс)
     * прожило незамеченным именно поэтому.
     */
    @Synchronized
    internal fun debugState(): AbrDebugState = AbrDebugState(
        rttAverage = rttAverage,
        rttMin = rttMin,
        rttJitter = rttJitter,
        rttAverageDelta = rttAverageDelta,
        throughput = throughput,
        sendBufferSizeAverage = sendBufferSizeAverage,
        sendBufferSizeJitter = sendBufferSizeJitter,
    )

    private companion object {
        /** Сколько замеров подряд должно превышать средний порог, прежде чем
         *  резать. Опрос идёт раз в 200 мс, значит два замера — 400 мс: одиночный
         *  зубец пилы отсекается, а настоящий затор держится и режется быстрее,
         *  чем жёсткой веткой (там три замера). */
        const val CONSECUTIVE_BAD_MID = 2

        /*
         * Коэффициенты сглаживания пересчитаны под НАШ период опроса.
         *
         * Алгоритм — порт belacoder, а он крутит `update_bitrate()` каждые
         * 20 мс (`BITRATE_UPDATE_INT 20`). Наш цикл в `SrtlaStream.startAdaptiveLoop`
         * тикает раз в 200 мс, то есть в десять раз реже, но коэффициенты были
         * скопированы дословно — значит КАЖДАЯ постоянная времени оказалась
         * длиннее задуманной в десять раз: усреднение RTT 20 с вместо 2,
         * дельта RTT 1 с вместо 0.1, пропускная способность 6.6 с вместо 0.66.
         *
         * Чтобы сохранить постоянную времени при периоде в N раз больше,
         * затухание берётся в N-й степени: d_new = d_old^N. Отсюда 0.99^10 ≈ 0.90,
         * 0.8^10 ≈ 0.11, 0.97^10 ≈ 0.74, 1.001^10 ≈ 1.01.
         *
         * Дороже всего обходился именно `RTT_MIN_CREEP`: он ползёт вверх и
         * задаёт `rttThMin` — единственный порог ветки роста `inc`. Прижатый
         * `rttMin` держал ветку роста закрытой.
         */
        private const val BUFFER_AVG_DECAY = 0.90
        private const val RTT_AVG_DECAY = 0.90
        private const val RTT_DELTA_DECAY = 0.11
        private const val THROUGHPUT_DECAY = 0.74
        private const val RTT_MIN_CREEP = 1.01

        /** Затухание пиковых оценок джиттера (буфера и RTT) — тоже потиковое,
         *  и тоже было в десять раз медленнее задуманного: всплеск рассасывался
         *  двадцать секунд вместо двух. */
        private const val JITTER_DECAY = 0.90
    }



    private fun rttToSendBufferSize(rtt: Double, throughput: Double): Double =
        (throughput / 8) * rtt / 1316

    private fun updateSendBufferSizeAverage(v: Double) {
        sendBufferSizeAverage = sendBufferSizeAverage * BUFFER_AVG_DECAY + v * (1 - BUFFER_AVG_DECAY)
    }

    private fun updateSendBufferSizeJitter(v: Double) {
        sendBufferSizeJitter *= JITTER_DECAY
        val delta = v - prevSendBufferSize
        if (delta > sendBufferSizeJitter) sendBufferSizeJitter = delta
        prevSendBufferSize = v
    }

    private fun updateRttAverage(rtt: Double) {
        rttAverage = if (rttAverage == 0.0) {
            rtt
        } else {
            rttAverage * RTT_AVG_DECAY + (1 - RTT_AVG_DECAY) * rtt
        }
    }

    private fun updateAverageRttDelta(rtt: Double): Double {
        val delta = rtt - prevRtt
        rttAverageDelta = rttAverageDelta * RTT_DELTA_DECAY + delta * (1 - RTT_DELTA_DECAY)
        prevRtt = rtt
        return delta
    }

    private fun updateRttMin(rtt: Double) {
        rttMin *= RTT_MIN_CREEP
        if (rtt != 100.0 && rtt < rttMin && rttAverageDelta < 1.0) rttMin = rtt
    }

    private fun updateRttJitter(deltaRtt: Double) {
        rttJitter *= JITTER_DECAY
        if (deltaRtt > rttJitter) rttJitter = deltaRtt
    }

    private fun updateThroughput(mbps: Double) {
        throughput *= THROUGHPUT_DECAY
        throughput += (mbps * 1000.0 * 1000.0 / 1024.0) * (1 - THROUGHPUT_DECAY)
    }
}
