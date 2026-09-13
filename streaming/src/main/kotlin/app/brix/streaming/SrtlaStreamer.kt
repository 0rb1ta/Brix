package app.brix.streaming

import android.content.Context
import android.graphics.Bitmap
import android.net.Network
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.View
import app.brix.bonding.BondingNetworkManager
import app.brix.bonding.SrtlaClient
import app.brix.bonding.SrtlaConnection
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.utils.CodecUtil
import app.brix.core.AudioSettings
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import app.brix.core.Codec
import app.brix.bonding.pickAddress
import app.brix.core.MicSource
import app.brix.core.SceneAudio
import app.brix.core.MoblinkSettings
import app.brix.core.StreamError
import app.brix.core.StreamErrorCode
import app.brix.core.StreamProfile
import app.brix.core.VideoSettings
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

class SrtlaStreamer(
    context: Context,
    private val restartGuard: StartRestartGuard,
) : ConnectChecker, LiveStreamer {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(StreamState())
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    // Тик часов эфира — отдельно от состояния, чтобы ежесекундная отметка
    // времени не перерисовывала весь экран стримера. Публикуется из того же
    // цикла, что уже крутится, второго таймера не появляется.
    private val _uptimeTick = MutableStateFlow(0L)
    override val uptimeTick: StateFlow<Long> = _uptimeTick.asStateFlow()

    private val tag = "BrixStream"

    private val srtlaClient = SrtlaClient()
    private val stream = SrtlaStream(appContext, this, srtlaClient)
    private val networkManager = BondingNetworkManager(appContext)
    private val connections = ConcurrentHashMap<Network, SrtlaConnection>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Moblink (Этап F): relay devices join as extra SRTLA bonding channels,
    // exactly like a wifi/cellular connection — see onRelayTunnelReady below.
    // No changes to SrtlaClient/SrtlaConnection were needed for this: they
    // already support a per-connection host/port via startAddedConnection.
    // Камера и превью целиком в CameraController.
    private val camera = CameraController(
        appContext = appContext,
        stream = stream,
        scope = scope,
        profileFps = { profile?.video?.fps ?: VideoSettings().fps },
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )
    private val moblink = MoblinkHost(
        appContext = appContext,
        srtlaClient = srtlaClient,
        sessionActive = { session.isActive() },
        currentHost = { currentHost },
        currentPort = { currentPort },
        updateState = { transform -> _state.update(transform) },
    )

    @Volatile
    private var currentHost = ""
    @Volatile
    private var currentPort = 0
    private val session = StreamSession()
    private val reconnectPolicy = ReconnectPolicy()
    private var reconnectJob: Job? = null
    private var fallbackJob: Job? = null
    private var currentUrl = ""
    @Volatile
    private var isReconnectAttempt = false

    // Generation that owns the currently-running transport. Captured when
    // stream.startStream() is called; every transport callback (onConnection*,
    // onDisconnect, onAuth*) checks session.matches(activeGen) so a stale event
    // from a previous Stop->Start session is dropped instead of corrupting the
    // new session.
    @Volatile
    private var activeGen = 0L

    // Client-side guard against rapid Stop->Start. srtla_rec throttles a source
    // after a handful of SRT sessions that are torn down before establishing
    // (each quick stop looks like an auth failure to its rate limiter), so a
    // Stop->Start burst trips the server throttle and bricks every following
    // connect for ~60s. We enforce a small gap after stop and back off when the
    // user spams Start, surfacing a clear reason instead of silently ignoring it.
    // The guard must share SrtlaStreamer's time domain (elapsed realtime), so the
    // explicit clock is injected rather than the guard's wall-clock default.
    init {
        networkManager.listener = object : BondingNetworkManager.Listener {
            override fun onNetworkAvailable(type: String, network: Network) {
                Log.w(tag, "onNetworkAvailable type=$type network=$network isActive=${session.isActive()} conns=${srtlaClient.connectionCount()}")
                if (!session.isActive()) return
                val gen = session.generation
                val weight = channelWeightFor(type)
                // A disabled channel (weight <= 0) must not be bonded at all.
                if (weight <= 0) {
                    Log.w(tag, "onNetworkAvailable type=$type weight=$weight — channel disabled, skipping")
                    return@onNetworkAvailable
                }
                val connection = srtlaClient.addConnection(
                    type = type,
                    priority = weight.toFloat(),
                    bindSocket = { socket ->
                        try {
                            network.bindSocket(socket)
                        } catch (e: Exception) {
                            // A VPN (PCAPdroid, ad-blockers, corporate) owns the
                            // default network and forbids binding to the physical
                            // one (EPERM). Fall back to the routing-table default:
                            // bonding loses explicit path pinning for this
                            // connection, but streaming keeps working.
                            Log.w(tag, "bindSocket to $type failed (${e.message}); using default route")
                        }
                    },
                    // Резолвим ЧЕРЕЗ СВОЮ сеть, а не через сеть по умолчанию:
                    // Network.getAllByName спрашивает резолвер именно этого
                    // линка. Иначе сотовый шёл бы на адрес, разрешённый по
                    // Wi-Fi, а без интернета на Wi-Fi не поднялся бы вовсе.
                    resolve = { host ->
                        runCatching { pickAddress(network.getAllByName(host).toList(), preferIpv4) }
                            .onFailure { Log.w(tag, "DNS через $type не ответил: ${it.message}") }
                            .getOrNull()
                    },
                )
                connections[network] = connection
                // Re-check ownership that stop()/release() may have cleared while
                // we added the connection. Use the reference, not the
                // map key, because stop() already did connections.clear().
                if (!session.matches(gen) || !session.isActive() ||
                    connections[network] !== connection
                ) {
                    connections.remove(network)
                    srtlaClient.removeConnection(connection)
                    return@onNetworkAvailable
                }
                if (srtlaClient.isStarted() && currentHost.isNotEmpty()) {
                    srtlaClient.startAddedConnection(connection, currentHost, currentPort)
                }
            }

            override fun onNetworkLost(network: Network) {
                Log.w(tag, "onNetworkLost network=$network removed=${connections[network]?.type}")
                connections.remove(network)?.let { srtlaClient.removeConnection(it) }
            }
        }
        stream.onHostPort = { host, port ->
            currentHost = host
            currentPort = port
            moblink.startTunnels(host, port)
            networkManager.start()
            fallbackJob?.cancel()
            fallbackJob = scope.launch {
                val gen = session.generation
                delay(3000)
                if (session.matches(gen) && srtlaClient.connectionCount() == 0) {
                    val fallback = srtlaClient.addConnection("default", 100f)
                    if (srtlaClient.isStarted() && session.matches(gen)) {
                        srtlaClient.startAddedConnection(fallback, currentHost, currentPort)
                    }
                }
            }
        }
        stream.onAdaptiveBitrateKbps = { kbps ->
            _state.update { it.copy(adaptiveBitrateKbps = kbps) }
        }
    }

    // Секундная телеметрия (HUD + строка BrixStat) живёт в StatsReporter.
    private val stats = StatsReporter(
        appContext = appContext,
        srtlaClient = srtlaClient,
        stream = stream,
        scope = scope,
        phase = { session.phase },
        updateState = { transform -> _state.update(transform) },
        publishTick = { tick -> _uptimeTick.value = tick },
    )

    private fun startStats() = stats.start()

    private fun stopStats() = stats.stop()

    override val isStreaming: Boolean get() = stream.isStreaming

    private var profile: StreamProfile? = null

    /** Weight for a network [type] from the active stream profile's channel
     *  priorities. Missing/unknown type (the "default" fallback) gets a full
     *  weight so a lone carrier is always usable; a disabled channel returns 0. */
    private fun channelWeightFor(type: String): Int {
        val key = when (type) {
            "wifi" -> "WIFI"
            "cellular" -> "CELLULAR"
            "ethernet" -> "ETHERNET"
            else -> null
        }
        if (key == null) return 100
        val entry = profile?.srtConnectionPriorities?.firstOrNull {
            it.name.equals(key, ignoreCase = true)
        }
        return if (entry != null && entry.enabled) entry.weight else 0
    }

    /** Настройка сервера, а не профиля потока: у неё нет иного места. */
    fun setPreferIpv4(enabled: Boolean) {
        preferIpv4 = enabled
    }

    override fun configure(profile: StreamProfile, srtLatencyMs: Int) {
        this.profile = profile
        stream.configure(profile, srtLatencyMs)
        // Do NOT overwrite adaptiveBitrateEnabled here: it is a runtime toggle
        // controlled by setAdaptiveBitrate(). Overwriting it from the saved
        // profile resets the user's manual choice on every Start press.
    }

    override fun configureMoblink(settings: MoblinkSettings) = moblink.configure(settings)

    override fun configureCamera(defaults: CameraDefaults) = camera.configure(defaults)

    @Volatile
    private var audioSettings = AudioSettings()

    override fun configureAudio(audio: AudioSettings) {
        audioSettings = audio
        stream.applyAudioSettings(audio)
        _state.update { it.copy(micSource = audio.micSource) }
    }

    private val recorder = StreamRecorder(appContext, stream)

    override fun setRecordStream(enabled: Boolean) {
        recorder.enabled = enabled
    }

    override fun setDebugLog(enabled: Boolean) {
        stats.debugLog = enabled
    }

    override fun setMicSource(source: MicSource) {
        _state.update { it.copy(micSource = source) }
        stream.setMicSource(source)
    }

    private fun startMoblink() = moblink.start()

    private fun stopMoblink() = moblink.stop()

    /** Брать при разрешении имени только IPv4 (настройка сервера). */
    @Volatile
    private var preferIpv4 = false

    @Volatile
    private var prepared = false

    // Bitrate actually baked into the encoder at the last prepare() (a
    // one-shot latch — see @Volatile comment below). If the user edits the
    // profile's bitrate afterward, this drifts from profile?.video?.bitrateKbps;
    // corrected once the NEXT session actually connects (onConnectionSuccess),
    // not from configure() — a first attempt pushed setVideoBitrateOnFly()
    // right before every start() and visibly corrupted the stream (field-
    // reported: blocky video from the first frame, on any resolution/bitrate,
    // regardless of video effects) — RootEncoder does not like a bitrate
    // change at that exact moment. Once actually live is a safe time; ABR's
    // own onBitrateChange already calls the same API continuously while live
    // with no such issue.
    @Volatile
    private var appliedBitrateKbps: Int? = null

    /** Кодек, запечённый в энкодер последним prepare(). Битрейт можно менять на
     *  лету, кодек — нет, поэтому его смена требует пересборки. */
    @Volatile
    private var appliedCodec: Codec? = null

    /** Интервал ключевых кадров, запечённый последним prepare(). Как и кодек,
     *  на лету не меняется, поэтому его правка тоже требует пересборки —
     *  иначе настройка молча не применялась бы до перезапуска приложения. */
    @Volatile
    private var appliedKeyframeSec: Int? = null

    override fun prepare(): Boolean {
        // Кодек, в отличие от битрейта, на лету не меняется: он запекается в
        // энкодер при prepare(). Ранний выход по `prepared` означал, что смена
        // кодека в настройках не применялась вовсе — поле 31.08: профиль
        // переключён на H.264, а в эфир продолжал идти HEVC
        // (ExynosC2HevcEncComponent в логах).
        val wantCodec = profile?.video?.codec ?: VideoSettings().codec
        val wantKeyframe = profile?.video?.keyframeIntervalSec ?: VideoSettings().keyframeIntervalSec
        if (prepared && appliedCodec == wantCodec && appliedKeyframeSec == wantKeyframe) return true
        var restorePreview = false
        if (prepared) {
            // Пересобирать энкодер можно только на остановленном эфире и только
            // освободив прежний: иначе prepareVideo падает с «Failed to prepare»
            // (поле 31.08 — смена кодека при живом превью роняла старт).
            if (stream.isStreaming) {
                Log.w(tag, "prepare: смена кодека $appliedCodec -> $wantCodec отложена до остановки эфира")
                return true
            }
            Log.w(tag, "prepare: кодек $appliedCodec -> $wantCodec, пересобираю энкодер")
            restorePreview = stream.isOnPreview
            runCatching { if (stream.isOnPreview) stream.stopPreview() }
            prepared = false
        }
        return try {
            val p = profile?.video ?: VideoSettings()
            val a = audioSettings
            // RootEncoder defaults to CodecType.FIRST_COMPATIBLE_FOUND, i.e.
            // whatever encoder MediaCodecList happens to list first for the
            // mime type — not guaranteed to be the hardware one on every
            // device. Force hardware explicitly for VIDEO only: software
            // AVC/HEVC encoders handle high-motion frames far worse under a
            // fixed bitrate, producing blocky artifacts exactly on fast
            // camera movement. Leave audio on the default — AAC hardware
            // encoders are rare-to-nonexistent (confirmed: this forced to
            // HARDWARE for audio too breaks prepare entirely on a real
            // Exynos device with "0 encoders found").
            stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
            stream.setVideoCodec(if (p.codec == Codec.HEVC) VideoCodec.H265 else VideoCodec.H264)
            val videoOk = stream.prepareVideo(
                width = p.width,
                height = p.height,
                bitrate = p.bitrateKbps * 1000,
                fps = p.fps,
                // Поле keyframeIntervalSec жило в модели с самого начала и не
                // читалось НИГДЕ: писалось на диск и не делало ничего. Интервал
                // ключевых кадров важен для зрителя — от него зависит, как
                // быстро подхватывается картинка при подключении и после
                // потери, — поэтому доводим до энкодера, а не выбрасываем.
                iFrameInterval = p.keyframeIntervalSec,
                // Профиль кодирования. По умолчанию RootEncoder передаёт -1 и
                // не выставляет ключ вовсе, то есть энкодер берёт свой —
                // обычно самый простой. High/Main дают около 10-15% битрейта
                // при том же качестве за счёт CABAC, а мы бьём в фиксированный
                // битрейт, значит выигрыш достаётся картинкой.
                // Спрашиваем железо, а не ставим константу: см.
                // EncoderCapabilities.bestProfile. Уровень (level) намеренно
                // не трогаем — энкодер подберёт его сам под разрешение.
                profile = EncoderCapabilities.bestProfile(p.codec),
            )
            val audioOk = stream.prepareAudio(
                sampleRate = a.sampleRate,
                isStereo = a.stereo,
                bitrate = a.bitrateKbps * 1000,
            )
            prepared = videoOk && audioOk
            if (prepared) {
                appliedBitrateKbps = p.bitrateKbps
                appliedCodec = p.codec
                appliedKeyframeSec = p.keyframeIntervalSec
            }
            if (restorePreview) {
                // Превью гасили ради пересборки — вернуть, иначе экран замрёт
                // на последнем кадре и пользователь решит, что камера умерла.
                camera.resumePreviewIfDetached()
            }
            if (!videoOk || !audioOk) {
                _state.update {
                    it.copy(
                        status = StreamStatus.Failed,
                        phase = null,
                        error = StreamError(
                            code = if (!videoOk) StreamErrorCode.CAMERA_OPEN_FAILED
                            else StreamErrorCode.ENCODER_PREPARE_FAILED,
                            message = if (!videoOk) "Failed to open camera" else "Failed to prepare encoder",
                            retryable = true,
                        ),
                    )
                }
                return false
            }
            true
        } catch (e: SecurityException) {
            Log.e(tag, "prepare failed: нет разрешения камеры", e)
            _state.update {
                it.copy(
                    status = StreamStatus.Failed,
                    phase = null,
                    error = StreamError(
                        code = StreamErrorCode.CAMERA_PERMISSION_DENIED,
                        message = "Camera permission denied",
                        technical = e.message,
                    ),
                )
            }
            false
        } catch (e: Exception) {
            // Текст исключения раньше уходил ТОЛЬКО в StreamState.technical, то
            // есть в лог не попадало ничего: на устройстве «Failed to prepare
            // stream» и полная тишина в logcat, разбирать нечем.
            Log.e(tag, "prepare failed", e)
            _state.update {
                it.copy(
                    status = StreamStatus.Failed,
                    phase = null,
                    error = StreamError(
                        code = StreamErrorCode.ENCODER_PREPARE_FAILED,
                        message = "Failed to prepare stream",
                        technical = e.message,
                    ),
                )
            }
            false
        }
    }

    override fun startPreview(surfaceView: SurfaceView) = camera.startPreview(surfaceView)

    override fun stopPreview() = camera.stopPreview()

    @Synchronized
    override fun start(url: String) {
        if (stream.isStreaming) return
        val now = SystemClock.elapsedRealtime()

        // Guard against rapid Stop->Start bursts that trip the receiver's
        // per-source SRT throttle (see field comment on restartGuard).
        when (restartGuard.checkGate(now)) {
            StartRestartGuard.Decision.TOO_SOON_AFTER_STOP ->
                rejectStart("Wait before restarting")
            StartRestartGuard.Decision.IN_COOLDOWN -> {
                val waitSec = (restartGuard.remainingCooldownMs(now) + 999) / 1000
                rejectStart("Too many restart attempts, wait ${waitSec}s")
            }
            StartRestartGuard.Decision.RATE_LIMITED ->
                rejectStart("Too many restart attempts, wait ${restartGuard.rapidStartBackoffMs / 1000}s")
            StartRestartGuard.Decision.ALLOWED -> {
                // After a Failed session the UI may have run releaseAll()
                // (screen left, service stop): camera/encoder are gone. Start
                // must self-heal instead of crashing inside RootEncoder.
                if (!prepared && !prepare()) {
                    rejectStart("Failed to prepare camera/encoder")
                    return
                }
                val gen = session.start() ?: return
                // Record the rapid-start slot only once the start actually
                // happened: a rejected/busy start must not advance
                // the counter.
                restartGuard.recordStart(now)
                activeGen = gen
                currentUrl = url
                reconnectPolicy.reset()
                _state.update { it.copy(reconnectAttempt = 0) }
                isReconnectAttempt = false
                startStats()
                recorder.start()
                startMoblink()
                camera.applyTorchOnStart()
                _state.update {
                    it.copy(
                        status = StreamStatus.Connecting,
                        phase = session.phase,
                        error = null,
                        connectedAtElapsedMs = null,
                    )
                }
                stream.startStream(url)
                // RootEncoder's stopStream() (called by user Stop) also stops
                // the camera capture, freezing the SurfaceView on its last
                // frame. If a preview surface was attached before, restart it
                // so the user sees live camera even while Connecting.
                camera.resumePreviewIfDetached()
            }
        }
    }


    private fun rejectStart(message: String) {
        // The session was never started (rejectStart runs before session.start()),
        // so only surface the rejection in the state and leave session.phase
        // untouched (still Idle/Released). Using a dedicated Rejected status keeps
        // the real session machine consistent instead of faking a Failed phase.
        _state.update {
            it.copy(
                status = StreamStatus.Rejected,
                phase = null,
                message = message,
                error = null,
            )
        }
    }

    // Кто позвал stop(): несколько кадров стека выше нас, без наших же рамок.
    private fun stopCaller(): String =
        Throwable().stackTrace
            .filterNot { it.className.startsWith("app.brix.streaming.SrtlaStreamer") }
            .take(3)
            .joinToString(" <- ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }

    @Synchronized
    override fun stop() {
        val gen = session.generation
        // Полевой прогон 31.08 дал обрыв эфира, который не удалось объяснить:
        // SHUTDOWN ушёл на сервер, а в логе ни строки о причине. Автоматических
        // остановок в коде нет — значит stop() кто-то позвал, но восстановить
        // кто именно было уже нечем. Пишем вызывающего сразу.
        Log.w("BrixStream", "stop() gen=$gen by=${stopCaller()}")
        restartGuard.onStop()
        activeGen = 0
        reconnectJob?.cancel()
        reconnectJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        isReconnectAttempt = false
        if (stream.isStreaming) {
            try {
                stream.stopStream()
            } catch (_: Exception) {
            }
        }
        stopStats()
        recorder.stop()
        stopMoblink()
        networkManager.stop()
        connections.clear()
        srtlaClient.clearConnections()
        // RootEncoder keeps the camera session (and its torch) alive across
        // stopStream() — without this, a torchOnStart session (or a manual
        // toggle) leaves the flashlight physically lit after Stop, draining
        // battery/heat on a device that already fights thermal throttling.
        if (_state.value.torchOn) setTorch(false)
        updateSession(SessionPhase.Stopping, gen)
        updateSession(SessionPhase.Released, gen)
        _state.update {
            it.copy(
                status = StreamStatus.Idle,
                message = null,
                bitrateKbps = 0,
                error = null,
                connectedAtElapsedMs = null,
            )
        }
    }

    /** Force a transport re-establishment (e.g. notification "Reconnect" action). */
    @Synchronized
    override fun reconnect() {
        reconnectJob?.cancel()
        reconnectPolicy.reset()
        _state.update { it.copy(reconnectAttempt = 0) }
        isReconnectAttempt = true
        when (session.phase) {
            SessionPhase.Live -> if (!updateSession(SessionPhase.Reconnecting)) return
            SessionPhase.Preparing,
            SessionPhase.Connecting,
            SessionPhase.Reconnecting,
            -> Unit
            SessionPhase.Failed -> {
                session.start()
                activeGen = session.generation
                if (!updateSession(SessionPhase.Connecting)) return
            }
            else -> return
        }
        if (currentHost.isNotEmpty() && currentPort > 0) {
            stream.reconnect(currentHost, currentPort)
        }
    }

    override fun switchCamera() = camera.switchCamera()

    override fun setCameraSide(side: CameraSide) = camera.setCameraSide(side)

    override fun showStillImage(uri: android.net.Uri): Boolean = camera.showStillImage(uri)

    override fun showScreen(projection: android.media.projection.MediaProjection): Boolean =
        camera.showScreen(projection)

    override fun showCamera() = camera.showCamera()

    /**
     * Звук сцены: микрофон, звук телефона или оба.
     *
     * Осторожно с оверлеями: когда включён звук донатов, источником звука
     * работает наш микшер [OverlayAudioSource], и подмена источника здесь его
     * снимает — донаты в эфире замолчат до следующего переключения. Это
     * известное ограничение, а не случайность: оба механизма претендуют на
     * один и тот же слот источника звука.
     */
    override fun setSceneAudio(
        mode: SceneAudio,
        projection: android.media.projection.MediaProjection?,
    ): Boolean {
        val source = when (mode) {
            SceneAudio.MIC -> MicrophoneSource()
            SceneAudio.INTERNAL -> {
                val p = projection ?: return false
                com.pedro.encoder.input.sources.audio.InternalAudioSource(p, null)
            }
            SceneAudio.BOTH -> {
                val p = projection ?: return false
                com.pedro.encoder.input.sources.audio.MixAudioSource(p, null, 0)
            }
        }
        return runCatching {
            stream.changeAudioSource(source)
            // Мьют, усиление и выбор устройства сбрасываются вместе с
            // источником — подтверждаем их заново, как и везде.
            configureAudio(audioSettings)
            true
        }.getOrElse {
            Log.e(tag, "звук сцены не переключён: ${it.message}")
            false
        }
    }


    override fun setAdaptiveBitrate(enabled: Boolean) {
        _state.update { it.copy(adaptiveBitrateEnabled = enabled) }
        stream.setAdaptiveBitrate(enabled)
    }

    override fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int) {
        stream.updateAdaptiveBitrateLimits(targetKbps, minKbps, initialKbps)
    }

    override fun setVideoBitrate(bitrate: Int) {
        stream.setVideoBitrateOnFly(bitrate)
    }

    override fun setMuted(muted: Boolean) {
        _state.update { it.copy(micMuted = muted) }
        stream.setMicMuted(muted)
    }

    override fun setTorch(enabled: Boolean) = camera.setTorch(enabled)

    override fun setBlackScreen(enabled: Boolean) {
        if (isReleased) return
        scope.launch { stream.setBlackScreen(enabled) }
        _state.update { it.copy(blackScreenOn = enabled) }
    }

    // Tracks the one currently-attached effect filter so switching effects
    // (or turning back to NONE) removes the old one first — RootEncoder's
    // filter chain is additive (addFilter appends), it won't replace on its
    // own.
    private var activeVideoEffectFilter: com.pedro.encoder.input.gl.render.filters.BaseFilterRender? = null

    override fun setVideoEffect(effect: VideoEffect) {
        if (isReleased) return
        scope.launch {
            try {
                val gl = stream.getGlInterface()
                activeVideoEffectFilter?.let { gl.removeFilter(it) }
                activeVideoEffectFilter = null
                val filter = when (effect) {
                    VideoEffect.NONE -> null
                    VideoEffect.GRAYSCALE -> com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender()
                    VideoEffect.SEPIA -> com.pedro.encoder.input.gl.render.filters.SepiaFilterRender()
                }
                if (filter != null) {
                    gl.addFilter(filter)
                    activeVideoEffectFilter = filter
                }
                _state.update { it.copy(videoEffect = effect) }
            } catch (e: Exception) {
                Log.e(tag, "setVideoEffect failed (effect=$effect)", e)
            }
        }
    }

    // Оверлеи целиком живут в OverlayHost (донат-алерты, живые оверлеи,
    // подмешивание донат-звука, признак «виджет на связи»). Здесь остаются
    // только реализации LiveStreamer, которые туда делегируют: интерфейс
    // наружу не менялся, поменялось только то, кто внутри делает работу.
    private val overlays = OverlayHost(
        stream = stream,
        micMuted = { _state.value.micMuted },
        micGain = { audioSettings.micGain },
        scope = scope,
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )

    override fun setOverlayAudioInStream(
        overlayId: String,
        enabled: Boolean,
        donationPlayer: app.brix.streaming.overlay.DonationAudioPlayer?,
    ) = overlays.setAudioInStream(overlayId, enabled, donationPlayer)

    override fun showOverlay(
        overlayId: String,
        frames: List<OverlayFrame>,
        posX: Float,
        posY: Float,
        size: OverlaySize,
        durationMs: Long,
    ) = overlays.show(overlayId, frames, posX, posY, size, durationMs)

    override fun hideOverlay(overlayId: String) = overlays.hide(overlayId)

    override fun videoFrameSize(): android.graphics.Point? = overlays.videoFrameSize()

    override fun setOverlayConnectionState(overlayId: String, connected: Boolean) =
        overlays.setConnectionState(overlayId, connected)

    override fun attachLiveOverlay(overlayId: String, posX: Float, posY: Float, size: OverlaySize) =
        overlays.attachLive(overlayId, posX, posY, size)

    override fun updateLiveOverlayFrame(overlayId: String, bitmap: Bitmap) =
        overlays.updateLiveFrame(overlayId, bitmap)

    override fun detachLiveOverlay(overlayId: String) = overlays.detachLive(overlayId)

    /**
     * Selects a specific rear lens (wide/ultra-wide/tele), flipping back from
     * the front camera first if needed — picking a rear lens from the pill bar
     * IS a request to be on that lens, exactly as the lens picker behaves in
     * Moblin.
     *
     * The facing check used to run synchronously in the caller and bail with
     * `false`, so the UI's "flip, then pick a lens" path never applied the
     * lens: switchCamera() is asynchronous, the camera was still front-facing
     * at that instant, and setLens returned before it ever ran. The check now
     * lives inside the camera job, after the flip, on the serialized camera
     * dispatcher where every other HAL round-trip happens.
     */
    override fun setLens(cameraId: String, zoom: Float): Boolean = camera.setLens(cameraId, zoom)

    override fun currentLensId(): String? = camera.currentLensId()

    override fun zoomByScale(scale: Float) = camera.zoomByScale(scale)

    override fun getZoomRange(): ClosedFloatingPointRange<Float>? = camera.getZoomRange()

    override fun tapFocus(view: View, x: Float, y: Float) = camera.tapFocus(view, x, y)

    override var isReleased: Boolean = false
        private set

    // Same monitor as start/stop/reconnect: a concurrent ACTION_START must
    // never run against a released instance.
    @Synchronized
    override fun release() {
        if (isReleased) return
        isReleased = true
        prepared = false
        appliedCodec = null
        appliedKeyframeSec = null
        session.reset()
        activeGen = 0
        reconnectJob?.cancel()
        reconnectJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        isReconnectAttempt = false
        scope.cancel()
        overlays.release()
        activeVideoEffectFilter?.let { runCatching { stream.getGlInterface().removeFilter(it) } }
        activeVideoEffectFilter = null
        stopStats()
        recorder.stop()
        stopMoblink()
        networkManager.stop()
        try {
            if (stream.isStreaming) stream.stopStream()
            (stream as? SrtlaStream)?.destroy()
            stream.release()
        } catch (_: Exception) {
        }
        srtlaClient.clearConnections()
    }

    override fun onConnectionStarted(url: String) {
        if (!session.matches(activeGen)) return
        updateSession(SessionPhase.Connecting)
    }

    override fun onConnectionSuccess() {
        if (!session.matches(activeGen)) return
        reconnectPolicy.reset()
        _state.update { it.copy(reconnectAttempt = 0) }
        isReconnectAttempt = false
        reconnectJob?.cancel()
        reconnectJob = null
        // Correct a stale encoder bitrate now that we're actually live (see
        // appliedBitrateKbps comment) — a profile edited since the encoder
        // was prepared previously had no effect until an app restart.
        profile?.video?.bitrateKbps?.let { target ->
            if (appliedBitrateKbps != target) {
                stream.setVideoBitrateOnFly(target * 1000)
                appliedBitrateKbps = target
            }
        }
        updateSession(SessionPhase.Live)
    }

    override fun onConnectionFailed(reason: String) {
        if (!session.matches(activeGen)) return
        Log.w(tag, "onConnectionFailed reason=$reason isReconnectAttempt=$isReconnectAttempt phase=${session.phase}")
        if (session.phase == SessionPhase.Failed) {
            Log.w(tag, "onConnectionFailed ignored: session already Failed")
            return
        }
        if (isReconnectAttempt) {
            updateSession(SessionPhase.Reconnecting)
            scheduleReconnect()
            return
        }
        if (updateSession(SessionPhase.Failed)) {
            val error = errorCodeFor(reason)
            _state.update { it.copy(error = error) }
            // Keep the encode pipeline alive for retryable failures (network /
            // timeout / registration): a transport-only reconnect() then just
            // re-establishes SRT/SRTLA and media keeps flowing, instead of a
            // 15s dead-sender timeout (C3). Only stop the pipeline for hard,
            // non-retryable errors (auth / rejected) where reconnect is moot.
            if (!error.retryable && stream.isStreaming) {
                try { stream.stopStream() } catch (_: Exception) {}
            }
        }
    }

    private fun errorCodeFor(reason: String): StreamError {
        val code = when {
            // A malformed URL is a configuration mistake, not a transient
            // transport failure: retrying it can only fail again, so it must
            // not be lumped in with (retryable) registration errors.
            reason.contains("Malformed", ignoreCase = true) -> StreamErrorCode.SERVER_REJECTED
            reason.contains("registration", ignoreCase = true) ||
                reason.contains("REG", ignoreCase = true) -> StreamErrorCode.SRTLA_REGISTRATION_FAILED
            reason.contains("timeout", ignoreCase = true) -> StreamErrorCode.SRT_TIMEOUT
            reason.contains("rejected", ignoreCase = true) ||
                reason.contains("forbidden", ignoreCase = true) -> StreamErrorCode.SERVER_REJECTED
            reason.contains("auth", ignoreCase = true) ||
                reason.contains("unauthorized", ignoreCase = true) -> StreamErrorCode.AUTH_FAILED
            reason.contains("network", ignoreCase = true) ||
                reason.contains("no route", ignoreCase = true) ||
                reason.contains("unreachable", ignoreCase = true) -> StreamErrorCode.NETWORK_UNAVAILABLE
            else -> StreamErrorCode.UNKNOWN
        }
        return StreamError(
            code = code,
            message = reason,
            technical = reason,
            retryable = code == StreamErrorCode.NETWORK_UNAVAILABLE ||
                code == StreamErrorCode.SRT_TIMEOUT ||
                code == StreamErrorCode.SRTLA_REGISTRATION_FAILED,
        )
    }

    override fun onDisconnect() {
        if (!session.matches(activeGen)) return
        Log.w(tag, "onDisconnect phase=${session.phase} activeGen=$activeGen gen=${session.generation}")
        // Moblin-style recovery: no in-session tricks. Full clean teardown +
        // fresh attempt (new group, new ports) after a short backoff. This is
        // the model the belabox receiver ecosystem is battle-tested against.
        if (session.phase == SessionPhase.Live || session.phase == SessionPhase.Connecting) {
            if (updateSession(SessionPhase.Reconnecting)) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val gen = session.generation
            val delayMs = reconnectPolicy.nextDelayMs()
            Log.w(tag, "scheduleReconnect delay=$delayMs attempt=${reconnectPolicy.attempts} phase=${session.phase}")
            _state.update { it.copy(reconnectAttempt = reconnectPolicy.attempts) }
            if (delayMs == null) {
                updateSession(SessionPhase.Failed, gen)
                _state.update {
                    it.copy(error = StreamError(
                        code = StreamErrorCode.NETWORK_UNAVAILABLE,
                        message = "Reconnect attempts exhausted",
                        retryable = true,
                    ))
                }
                if (stream.isStreaming) {
                    try { stream.stopStream() } catch (_: Exception) {}
                }
                return@launch
            }
            delay(delayMs)
            if (updateSession(SessionPhase.Connecting, gen)) {
                isReconnectAttempt = true
                if (session.matches(gen) && currentHost.isNotEmpty() && currentPort > 0) {
                    stream.reconnect(currentHost, currentPort)
                }
            }
        }
    }

    override fun onAuthError() {
        if (!session.matches(activeGen)) return
        if (updateSession(SessionPhase.Failed)) {
            _state.update {
                it.copy(error = StreamError(
                    code = StreamErrorCode.AUTH_FAILED,
                    message = "Auth error",
                ))
            }
        }
    }

    override fun onAuthSuccess() = Unit

    override fun onNewBitrate(bitrate: Long) {
        _state.update { it.copy(bitrateKbps = bitrate / 1000) }
    }

    private fun updateSession(to: SessionPhase, gen: Long = session.generation): Boolean {
        if (!session.transition(to, gen)) return false
        val status = to.toStatus()
        _state.update {
            val connectedAt = when {
                status == StreamStatus.Connected && it.connectedAtElapsedMs == null ->
                    SystemClock.elapsedRealtime()
                status == StreamStatus.Idle || status == StreamStatus.Failed -> null
                else -> it.connectedAtElapsedMs
            }
            it.copy(status = status, phase = session.phase, connectedAtElapsedMs = connectedAt)
        }
        Log.i(tag, "updateSession $to (from gen=$gen)")
        return true
    }
}

internal fun SessionPhase.toStatus(): StreamStatus = when (this) {
    SessionPhase.Idle -> StreamStatus.Idle
    SessionPhase.Preparing,
    SessionPhase.Connecting,
    SessionPhase.Reconnecting,
    -> StreamStatus.Connecting
    SessionPhase.Live -> StreamStatus.Connected
    SessionPhase.Stopping -> StreamStatus.Disconnected
    SessionPhase.Failed -> StreamStatus.Failed
    SessionPhase.Released -> StreamStatus.Idle
}
