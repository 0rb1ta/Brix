package app.brix.streaming

import android.content.Context
import android.media.MediaCodec
import app.brix.bonding.SrtSender
import app.brix.bonding.SrtlaClient
import app.brix.core.MicSource
import app.brix.core.StreamProfile
import app.brix.core.clampSrtLatency
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.socket.base.SocketType
import com.pedro.common.toMediaFrameInfo
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import app.brix.streaming.overlay.OverlayAudioSource
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.StreamBaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.os.SystemClock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.nio.ByteBuffer

class SrtlaStream(
    private val context: Context,
    private val connectChecker: ConnectChecker,
    private val srtlaClient: SrtlaClient,
) : StreamBase(context, Camera2Source(context), MicrophoneSource()) {

    private val srtSender = SrtSender(streamId = null, latency = 2000)
    private val srtlaSender = SrtlaSender(connectChecker, srtSender)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adaptiveBitrate = AdaptiveBitrateSrtBelabox(
        targetBitrate = 6_000_000,
        minimumBitrate = 250_000,
        initialBitrate = 2_500_000,
    )
    // Пишутся с UI/сервисного потока (configure, setAdaptiveBitrate,
    // updateAdaptiveBitrateLimits), читаются в ABR-корутине и в srtDebug().
    // abrRelaxedUntilMs и teardownInProgress ниже уже помечены — приводим
    // остальные поля того же класса к тому же правилу.
    @Volatile
    private var latencyMs = 2000
    @Volatile
    private var defaultAdaptiveEnabled = false
    @Volatile
    private var adaptiveBitrateEnabled = false
    private var adaptiveBitrateJob: Job? = null
    private var timeoutJob: Job? = null
    private var transportJob: Job? = null
    private var handshakeJob: Job? = null
    @Volatile
    private var adaptiveTarget = 6_000_000L
    @Volatile
    private var adaptiveMin = 250_000L
    @Volatile
    private var adaptiveInitial = 2_500_000L

    // Relaxed ABR window (ported from Moblin): for 3s after transport start
    // the fast-decrease threshold is doubled — startup buffer noise must not
    // slam the bitrate to minimum.
    @Volatile
    private var abrRelaxedUntilMs = 0L

    // Raised while tearing down the transport intentionally (reconnect/stop).
    // SrtSender.stop() invokes onDisconnected, which SrtlaStreamer.onDisconnect()
    // would otherwise treat as a real network drop and schedule a redundant
    // reconnect on top of the one already in progress.
    @Volatile
    private var teardownInProgress = false

    var onHostPort: ((String, Int) -> Unit)? = null
    var onAdaptiveBitrateKbps: ((Long) -> Unit)? = null

    fun configure(profile: StreamProfile, srtLatencyMs: Int = 2000) {
        // Ограничиваем здесь, а не только в редакторе: значение приходит ещё из
        // дип-линка и из старых файлов настроек, а уходит в UInt16 рукопожатия.
        latencyMs = clampSrtLatency(srtLatencyMs)
        srtSender.latency = latencyMs
        adaptiveTarget = profile.adaptiveBitrate.targetBitrateKbps.toLong() * 1000
        adaptiveMin = profile.adaptiveBitrate.minimumBitrateKbps.toLong() * 1000
        adaptiveInitial = profile.adaptiveBitrate.initialBitrateKbps.toLong() * 1000
        resetAdaptiveBitrate()
        defaultAdaptiveEnabled = profile.adaptiveBitrate.enabled
        adaptiveBitrateEnabled = defaultAdaptiveEnabled
        adaptiveBitrate.setAlgorithm(profile.adaptiveBitrate.algorithm)
    }

    fun resetAdaptiveBitrate() {
        adaptiveBitrate.configure(adaptiveTarget, adaptiveMin, adaptiveInitial)
    }

    init {
        getGlInterface().autoHandleOrientation = true
        srtSender.onOutput = { packet -> srtlaClient.handleLocalPacket(packet) }
        srtlaClient.onRemotePacket = { packet -> srtSender.input(packet) }
        srtSender.onConnected = {
            handshakeJob?.cancel()
            handshakeJob = null
            scope.launch { srtlaSender.start() }
            connectChecker.onConnectionSuccess()
        }
        srtSender.onDisconnected = {
            if (!teardownInProgress) {
                connectChecker.onDisconnect()
            }
        }
        srtlaClient.onReady = {
            startSrtHandshakeWithRetry()
        }
        srtlaClient.onError = { message ->
            connectChecker.onConnectionFailed(message)
        }
        adaptiveBitrate.onBitrateChange = { bitrate ->
            setVideoBitrateOnFly(bitrate.toInt())
            onAdaptiveBitrateKbps?.invoke(bitrate / 1000)
        }
    }

    fun setAdaptiveBitrate(enabled: Boolean) {
        adaptiveBitrateEnabled = enabled
    }

    /** SRT-layer numbers for the per-second diagnostic line. [rttMs] is the one
     *  to watch: it only ever advances from a "full" ACK, and while it stays 0
     *  AdaptiveBitrate.update() bails on its first line — i.e. ABR is not
     *  running at all, however healthy the picture looks. */
    data class SrtDebug(
        val connected: Boolean,
        val rttMs: Double,
        val inFlight: Int,
        val droppedPackets: Long,
        val sendRateMbps: Double,
        val abrKbps: Long,
        val abrEnabled: Boolean,
    )

    fun srtDebug(): SrtDebug = SrtDebug(
        connected = srtSender.isConnectedOrProvisional(),
        rttMs = srtSender.rttMs(),
        inFlight = srtSender.packetsInFlightCount(),
        droppedPackets = srtSender.droppedPackets(),
        sendRateMbps = srtSender.sendRateMbps(),
        abrKbps = adaptiveBitrate.currentBitrateKbps(),
        abrEnabled = adaptiveBitrateEnabled,
    )

    /** Push ABR ceiling/floor to the running algorithm (live settings change). */
    fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int = -1) {
        adaptiveTarget = targetKbps.toLong() * 1000
        adaptiveMin = minKbps.toLong() * 1000
        if (initialKbps > 0) adaptiveInitial = initialKbps.toLong() * 1000
        adaptiveBitrate.updateLimits(adaptiveTarget, adaptiveMin)
    }

    /**
     * Start the SRT handshake over the SRTLA group. If the server's CONCLUSION
     * response is lost (a real risk on a lossy cellular downlink), first re-send
     * the conclusion within the SAME SRT session, then — if that still does not
     * promote us to CONNECTED — restart ONLY the SRT layer (a fresh induction
     * handshake) while keeping the SRTLA group RUNNING.
     *
     * We deliberately do NOT call srtlaClient.stop()/start() here: that would
     * create a brand-new SRTLA group/transport attempt, and srtla_rec's auth
     * rate-limiter counts each short-lived SRT session torn down before it is
     * established as a failed auth and blocks the source IP for ~60s. SRTLA and
     * SRT are independent layers; a lost SRT handshake does not invalidate the
     * already-registered SRTLA group.
     */
    /**
     * Moblin-style fail-fast handshake: conclusion retransmits within a short
     * window, then FAIL. No in-session recovery ladders — every deeper retry
     * belongs to the upper layer (SrtlaStreamer), which tears the transport
     * down COMPLETELY and starts fresh (new group, new ports). Complex
     * in-session escalation (provisional media, SRT-only restarts, port
     * bounces) was field-proven to create zombie states and supervisor races
     * that pure clean-retry does not have.
     */
    private fun startSrtHandshakeWithRetry() {
        if (srtSender.isConnected()) {
            onConnectedNudge()
            return
        }
        srtSender.start()
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            for (attempt in 1..MAX_CONCLUSION_RETRIES) {
                delay(CONCLUSION_RETRY_INTERVALS[attempt - 1])
                if (!isActive || srtSender.isConnected()) return@launch
                android.util.Log.w("Srtla", "srt-sender: conclusion retransmit $attempt/$MAX_CONCLUSION_RETRIES")
                srtSender.retransmitConclusion()
            }
            if (isActive && !srtSender.isConnected()) {
                connectChecker.onConnectionFailed("SRT handshake timeout")
            }
        }
    }

    private fun onConnectedNudge() {
        scope.launch { srtlaSender.start() }
        connectChecker.onConnectionSuccess()
    }

    companion object {
        private const val SRT_HANDSHAKE_RETRY_INTERVAL_MS = 1000L
        private const val MAX_SRT_HANDSHAKE_RETRIES = 5
        // The receiver keeps a publisher alive for ~15s without media
        // (publisher_first_data_grace in sls.conf), so the conclusion-retry
        // ladder has that long to catch a reply through cellular downlink
        // loss: one retransmit per second for 10s. Every retransmit prompts
        // the receiver to (re-)send its CONCLUSION reply.
        private val CONCLUSION_RETRY_INTERVALS = LongArray(10) { 1000L }
        private const val MAX_CONCLUSION_RETRIES = 10
    }

    // Mute is a latched user decision, not a property of whichever audio source
    // happens to be installed right now. Overlay donation audio swaps the
    // source (mic <-> OverlayAudioSource) mid-session, and both directions used
    // to lose the mute: muting while the mixer was active did nothing (the cast
    // below failed), and switching back installed a fresh, un-muted
    // MicrophoneSource while the UI still showed "muted" — a live mic the
    // streamer believed was off.
    @Volatile
    private var micMuted = false

    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        applyMicMute()
    }

    @Volatile
    private var micGain = 1f

    @Volatile
    private var micSource = MicSource.AUTO

    /** Общие настройки звука: усиление и предпочитаемый микрофон. */
    fun applyAudioSettings(audio: app.brix.core.AudioSettings) {
        // Режим обработки задаётся ДО начала записи: смена на лету требует
        // пересоздания AudioRecord, поэтому вступает в силу со следующего
        // старта — как и всё, что запекается в prepare().
        val wanted = MicDevices.audioSourceOf(audio.processing)
        when (val source = audioSource) {
            is OverlayAudioSource -> source.micSourceForPreference().audioSource = wanted
            is MicrophoneSource -> source.audioSource = wanted
            else -> Unit
        }
        micGain = audio.micGain
        applyMicGain()
        micSource = audio.micSource
        applyMicSource()
    }

    fun setMicSource(source: MicSource) {
        micSource = source
        applyMicSource()
    }

    /** Предпочитаемый микрофон. Подтверждается там же, где мьют и усиление:
     *  после смены источника звука предпочтение сбрасывается вместе с ним. */
    fun applyMicSource() {
        val device = MicDevices.deviceFor(context, micSource)
        val mic = when (val source = audioSource) {
            is OverlayAudioSource -> source.micSourceForPreference()
            is MicrophoneSource -> source
            else -> null
        } ?: return
        // null означает «решай сама, система»: так же ведём себя и когда
        // выбранное устройство отключили — остаться без звука хуже, чем
        // вернуться на встроенный.
        // setPreferredDevice ВОЗВРАЩАЕТ признак успеха, и HAL вправе отказать.
        // Раньше ответ игнорировался, то есть отказ был бы неотличим от успеха:
        // пользователь выбрал «у камеры», а пишет по-прежнему нижний.
        val applied = runCatching { mic.setPreferredDevice(device) }.getOrDefault(false)
        if (!applied) {
            android.util.Log.w(
                "BrixMic",
                "устройство не принято HAL: $micSource — звук идёт с того микрофона, что выбрала система",
            )
        }
    }

    /** Усиление микрофона из профиля. Как и мьют, подтверждается после каждой
     *  смены источника звука — иначе включение микшера донатов сбрасывало бы
     *  уровень на единицу. */
    fun applyMicGain() {
        when (val source = audioSource) {
            is OverlayAudioSource -> source.setMicGain(micGain)
            is MicrophoneSource -> source.microphoneVolume = micGain
            else -> Unit
        }
    }

    /** Re-asserts the latched mute on the current audio source. Must be called
     *  after every changeAudioSource(). */
    fun applyMicMute() {
        when (val source = audioSource) {
            is OverlayAudioSource -> source.setMicMuted(micMuted)
            is MicrophoneSource -> if (micMuted) source.mute() else source.unMute()
            else -> Unit
        }
    }

    private val blackFilter = BlackFilterRender()
    @Volatile
    private var blackScreenOn = false

    /** Blacks out the ENCODER output via a GL filter; the camera keeps running
     *  so restoring is instant and focus/exposure are never disturbed. */
    fun setBlackScreen(enabled: Boolean) {
        if (enabled == blackScreenOn) return
        blackScreenOn = enabled
        val gl = getGlInterface()
        if (enabled) {
            gl.addFilter(blackFilter)
        } else {
            gl.removeFilter(blackFilter)
        }
    }

    /**
     * Передать кодек упаковщику MPEG-TS. Раньше здесь стояло `= Unit`, и
     * `SrtlaSender.videoCodec` навсегда оставался H.264: в служебной таблице
     * потока (PMT) объявлялся H.264, что бы ни стоял в настройках. Соседние
     * `onAudioInfoImp`/`onVideoInfoImp` данные отправителю передают — эта
     * единственная не передавала.
     *
     * Поле 31.08: после переключения профиля на H.265 и обратно в OBS остался
     * чёрный квадрат — плеер разбирал поток по неверному объявлению.
     */
    override fun setVideoCodecImp(codec: VideoCodec) {
        srtlaSender.setVideoCodec(codec)
    }
    override fun setAudioCodecImp(codec: AudioCodec) = Unit

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
        srtlaSender.setAudioInfo(sampleRate, isStereo)
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        srtlaSender.setVideoInfo(sps, pps, vps)
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        srtlaSender.sendMediaFrame(MediaFrame(videoBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.VIDEO))
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        srtlaSender.sendMediaFrame(MediaFrame(audioBuffer.clone(), info.toMediaFrameInfo(), MediaFrame.Type.AUDIO))
    }

    override fun startStreamImp(endPoint: String) {
        abrRelaxedUntilMs = SystemClock.elapsedRealtime() + 3000
        val uri = try {
            URI(endPoint)
        } catch (e: Exception) {
            connectChecker.onConnectionFailed("Malformed srtla URL")
            return
        }
        val host = uri.host
        if (host == null || uri.port <= 0) {
            connectChecker.onConnectionFailed("Malformed srtla URL")
            return
        }
        // Схема выбирает режим, а не отдельный тип сервера — так же устроено в
        // Moblin, откуда приходит часть людей. `srtla://` — бондинг с групповой
        // регистрацией, `srt://` — обычный приёмник, который пакетов SRTLA не
        // знает. Всё остальное (рукопожатие SRT, streamid, окно, переотправка)
        // в обоих режимах одинаково: SRTLA данные не заворачивает.
        srtlaClient.useSrtla = !uri.scheme.equals("srt", ignoreCase = true)
        val streamId = parseStreamId(uri)
        srtSender.streamId = streamId.ifBlank { null }
        connectChecker.onConnectionStarted(endPoint)
        onHostPort?.invoke(host, uri.port)
        resetAdaptiveBitrate()
        startTransport(host, uri.port)
    }

    /**
     * Re-establish the SRTLA transport after a connection drop, keeping the
     * camera/encoder pipeline untouched. Any outstanding transport jobs are
     * cancelled first so a reconnect never leaks duplicate adaptive/timeout
     * loops.
     */
    fun reconnect(host: String, port: Int) {
        resetAdaptiveBitrate()
        startTransport(host, port)
    }

    private fun startTransport(host: String, port: Int) {
        teardownInProgress = true
        srtSender.stop()
        srtlaClient.stop()
        teardownInProgress = false
        transportJob?.cancel()
        transportJob = scope.launch {
            srtlaClient.start(host, port)
        }
        startConnectionTimeout()
        startAdaptiveLoop()
    }

    private fun startConnectionTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(15_000)
            // Provisional CONCLUSION_SENT already streams media; only a session
            // with neither a real nor a provisional connection is dead.
            if (isStreaming && (!srtlaClient.isRunning() || !srtSender.isConnectedOrProvisional())) {
                // Tear the transport down so a failed attempt cannot keep a live
                // socket/read-thread that later injects callbacks into the next
                // attempt.
                teardownInProgress = true
                srtSender.stop()
                srtlaClient.stop()
                teardownInProgress = false
                connectChecker.onConnectionFailed("SRTLA connection timeout")
            }
        }
    }

    private fun startAdaptiveLoop() {
        adaptiveBitrateJob?.cancel()
        adaptiveBitrateJob = scope.launch {
            while (isActive) {
                if (adaptiveBitrateEnabled) {
                    val stats = SrtStats(
                        rttMs = srtSender.rttMs(),
                        packetsInFlight = srtSender.packetsInFlightCount().toDouble(),
                        mbpsSendRate = srtSender.sendRateMbps(),
                        latencyMs = latencyMs,
                        relaxed = SystemClock.elapsedRealtime() < abrRelaxedUntilMs,
                        droppedPackets = srtSender.droppedPackets(),
                    )
                    adaptiveBitrate.update(stats)
                }
                delay(200)
            }
        }
    }

    private fun parseStreamId(uri: URI): String {
        val rawQuery = uri.rawQuery
        val rawPath = uri.rawPath.removePrefix("/")
        val id = when {
            rawQuery != null && rawQuery.startsWith("streamid=") ->
                rawQuery.substringAfter("streamid=")
            rawPath.isNotEmpty() ->
                rawPath + (rawQuery?.let { "?$it" } ?: "")
            else -> rawQuery ?: ""
        }
        // Tolerate a percent-encoded streamid (e.g. an old dashboard URL that
        // had encodeURIComponent applied): SLS parses the decoded form only.
        return try {
            java.net.URLDecoder.decode(id, "UTF-8")
        } catch (_: Exception) {
            id
        }
    }

    override fun stopStreamImp() {
        adaptiveBitrateJob?.cancel()
        adaptiveBitrateJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        transportJob?.cancel()
        transportJob = null
        handshakeJob?.cancel()
        handshakeJob = null
        // Stop the media pump SYNCHRONOUSLY: an async stop let the old
        // session's encoder keep feeding packets into a freshly started
        // session after a rapid Stop->Start (field-verified leak). Bounded so
        // a stuck pump can never block the caller indefinitely.
        try {
            runBlocking { withTimeoutOrNull(500) { srtlaSender.stop(true) } }
        } catch (_: Exception) {
        }
        teardownInProgress = true
        srtSender.stop()
        srtlaClient.stop()
        teardownInProgress = false
    }

    /** Cancel this stream's own coroutine scope and stop the transport.
     *  The scope was previously never torn down, leaking every job that ever
     *  ran on it. Call from SrtlaStreamer.release(); cannot hook
     *  into StreamBase.release() because it is final there. */
    fun destroy() {
        stopStreamImp()
        scope.cancel()
    }

    override fun getStreamClient(): StreamBaseClient = noOpClient
}

private val noOpClient = object : StreamBaseClient() {
    override fun setAuthorization(user: String?, password: String?) = Unit
    override fun reTry(delay: Long, reason: String, backupUrl: String?): Boolean = false
    override fun setReTries(reTries: Int) = Unit
    override fun hasCongestion(percentUsed: Float): Boolean = false
    override fun setLogs(enabled: Boolean) = Unit
    override fun setCheckServerAlive(enabled: Boolean) = Unit
    override fun resizeCache(newSize: Int) = Unit
    override fun clearCache() = Unit
    override fun getCacheSize(): Int = 0
    override fun getItemsInCache(): Int = 0
    override fun getSentAudioFrames(): Long = 0
    override fun getSentVideoFrames(): Long = 0
    override fun getBytesSend(): Long = 0
    override fun getDroppedAudioFrames(): Long = 0
    override fun getDroppedVideoFrames(): Long = 0
    override fun resetSentAudioFrames() = Unit
    override fun resetSentVideoFrames() = Unit
    override fun resetDroppedAudioFrames() = Unit
    override fun resetDroppedVideoFrames() = Unit
    override fun resetBytesSend() = Unit
    override fun setOnlyAudio(onlyAudio: Boolean) = Unit
    override fun setOnlyVideo(onlyVideo: Boolean) = Unit
    override fun setBitrateExponentialFactor(factor: Float) = Unit
    override fun getBitrateExponentialFactor(): Float = 1f
    override fun setSocketType(type: SocketType) = Unit
    override fun setSocketTimeout(timeout: Long) = Unit
    override fun setDelay(millis: Long) = Unit
}
