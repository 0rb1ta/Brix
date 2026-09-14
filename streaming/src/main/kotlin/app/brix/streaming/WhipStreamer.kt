package app.brix.streaming

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.View
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender
import com.pedro.encoder.input.gl.render.filters.SepiaFilterRender
import com.pedro.library.whip.WhipStream
import app.brix.core.AudioSettings
import app.brix.core.ConnectionStat
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import app.brix.core.MicSource
import app.brix.core.SceneAudio
import app.brix.core.Codec
import app.brix.core.StreamError
import app.brix.core.StreamErrorCode
import app.brix.core.StreamProfile
import app.brix.core.VideoSettings
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * WHIP (WebRTC-HTTP Ingestion Protocol) output — mirrors [Streamer] (RTMP)
 * structurally, on RootEncoder's [WhipStream] instead of `RtmpStream`. Both
 * extend the same `StreamBase`, so nearly the whole surface (prepare/preview/
 * camera/overlay no-ops) is identical; WHIP forces Opus audio internally
 * (`WhipStream`'s own init), which is compatible with our default 48kHz
 * `AudioSettings` without any change here.
 */
class WhipStreamer(context: Context) : ConnectChecker, LiveStreamer {

    private val appContext = context.applicationContext
    private val tag = "BrixStream"
    private val _state = MutableStateFlow(StreamState())
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    // Тик часов эфира — отдельно от состояния, чтобы ежесекундная отметка
    // времени не перерисовывала весь экран стримера. Публикуется из того же
    // цикла, что уже крутится, второго таймера не появляется.
    private val _uptimeTick = MutableStateFlow(0L)
    override val uptimeTick: StateFlow<Long> = _uptimeTick.asStateFlow()

    private val stream = WhipStream(appContext, this).apply {
        getGlInterface().autoHandleOrientation = true
    }

    private var profile: StreamProfile? = null
    private val session = StreamSession()
    private var currentUrl: String? = null
    private var activeGen = 0L

    /** Энкодер уже собран этим prepare(). Без этого флага повторный prepare()
     *  при живом превью гарантированно падал — см. комментарий в [prepare]. */
    /** Накопленные байты для единственной строки канала в HUD. */
    @Volatile
    private var sentBytes = 0L

    @Volatile
    private var prepared = false

    /** Кодек, запечённый в энкодер последним prepare(): битрейт меняется на
     *  лету, кодек — нет, его смена требует пересборки. */
    @Volatile
    private var appliedCodec: Codec? = null

    /** Интервал ключевых кадров, запечённый последним prepare(). Как и кодек,
     *  на лету не меняется, поэтому его правка тоже требует пересборки —
     *  иначе настройка молча не применялась бы до перезапуска приложения. */
    @Volatile
    private var appliedKeyframeSec: Int? = null

    private val opsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // См. тот же комментарий в [Streamer]: камера общая на три транспорта,
    // а чинилась до 03.09 только в SRTLA-пути.
    private val camera = CameraController(
        appContext = appContext,
        stream = stream,
        scope = opsScope,
        profileFps = { profile?.video?.fps ?: 30 },
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )

    // Отличить «не смогли подключиться с первого раза» от «оборвалось посреди
    // эфира» иначе нечем: транспорт в обоих случаях зовёт onConnectionFailed.
    // Первое — ошибка пользователю, второе — повод продолжать лестницу.
    @Volatile
    private var isReconnectAttempt = false

    private val reconnector = StreamReconnector(
        scope = opsScope,
        onScheduled = { attempt -> _state.update { it.copy(reconnectAttempt = attempt) } },
        onExhausted = {
            if (updateSession(SessionPhase.Failed)) {
                _state.update {
                    it.copy(
                        error = StreamError(
                            code = StreamErrorCode.NETWORK_UNAVAILABLE,
                            message = "Reconnect attempts exhausted",
                            retryable = true,
                        ),
                    )
                }
            }
            runCatching { if (stream.isStreaming) stream.stopStream() }
        },
    ) { attemptReconnect() }

    private val uptime = UptimeTicker(
        scope = opsScope,
        publishTick = { tick -> _uptimeTick.value = tick },
    )

    private val overlays = OverlayHost(
        stream = stream,
        micMuted = { _state.value.micMuted },
        micGain = { audioSettings.micGain },
        scope = opsScope,
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )

    /**
     * Одна попытка лестницы. Транспортного переподключения, как у SRT, здесь
     * нет — у WHIP это полный перезапуск потока.
     */
    @Synchronized
    private fun attemptReconnect() {
        val url = currentUrl ?: return
        if (!session.matches(activeGen)) return
        if (!updateSession(SessionPhase.Connecting)) return
        isReconnectAttempt = true
        runCatching { stream.stopStream() }
        stream.startStream(url)
        // stopStream() выше гасит захват с камеры, иначе эфир вернулся бы с
        // замороженной картинкой.
        camera.resumePreviewIfDetached()
    }

    override val isStreaming: Boolean get() = stream.isStreaming

    override fun configure(profile: StreamProfile, srtLatencyMs: Int) {
        this.profile = profile
        // adaptiveBitrateEnabled здесь НЕ трогаем: это рантаймовый тумблер,
        // которым владеет setAdaptiveBitrate(). Перезапись из сохранённого
        // профиля сбрасывала ручной выбор на каждом нажатии «Старт» — тумблер
        // сам собой включался при старте эфира. В SrtlaStreamer этот запрет
        // стоит явным комментарием с самого начала, сюда не доехал.
        // Регулятор по глубине очереди сюда НЕ подключён, и это осознанно.
        // Он построен на посылке «TCP пакеты не теряет, при нехватке полосы
        // растёт очередь отправки» — верной для RTMP. WHIP это WebRTC:
        // сигнализация по HTTP, медиа поверх UDP/SRTP. Там пакеты теряются, а
        // очередь может не расти вовсе, потому что сокет не упирается. То есть
        // регулятор досидел бы до целевого битрейта и никогда не сбросил, лишь
        // создавая видимость работающего ABR.
        // Правильный вход для WHIP надо выбирать отдельно — у RootEncoder есть
        // собственный признак затора `StreamBaseClient.hasCongestion(float)`.
        //
    }

    override fun prepare(): Boolean {
        // `StreamBase.prepareVideo` бросает IllegalStateException, если идёт
        // эфир, запись ИЛИ ПРЕВЬЮ. А UI зовёт prepare() дважды: один раз при
        // подключении превью (тогда его ещё нет, и всё проходит) и второй раз
        // по нажатию «Старт» — уже при живом превью. В SRTLA-пути от этого
        // защищают ранний выход по `prepared` и остановка превью на время
        // пересборки; сюда, как и остальные правки, они не доехали, поэтому
        // старт RTMP/WHIP падал с «Failed to prepare stream» ВСЕГДА.
        // Знание не новое: тот же отказ ловили в поле 31.08 на смене кодека.
        val wantCodec = profile?.video?.codec ?: VideoSettings().codec
        val wantKeyframe = profile?.video?.keyframeIntervalSec ?: VideoSettings().keyframeIntervalSec
        if (prepared && appliedCodec == wantCodec && appliedKeyframeSec == wantKeyframe) return true
        if (prepared && stream.isStreaming) {
            Log.w(tag, "prepare: смена кодека $appliedCodec -> $wantCodec отложена до остановки эфира")
            return true
        }
        val restorePreview = stream.isOnPreview
        runCatching { if (stream.isOnPreview) stream.stopPreview() }
        prepared = false
        return try {
            val p = profile?.video ?: VideoSettings()
            val a = audioSettings
            // See SrtlaStreamer.prepare() — force hardware for video only;
            // forcing it for audio too breaks prepare on devices with no
            // hardware AAC encoder (confirmed on a real device).
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
                // Профиль по возможностям железа — см. SrtlaStreamer.prepare().
                profile = EncoderCapabilities.bestProfile(p.codec),
            )
            val audioOk = stream.prepareAudio(
                sampleRate = a.sampleRate,
                isStereo = a.stereo,
                bitrate = a.bitrateKbps * 1000,
            )
            prepared = videoOk && audioOk
            if (prepared) appliedCodec = p.codec
            appliedKeyframeSec = p.keyframeIntervalSec
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

    override fun configureCamera(defaults: CameraDefaults) = camera.configure(defaults)

    @Volatile
    private var audioSettings = AudioSettings()

    override fun configureAudio(audio: AudioSettings) {
        audioSettings = audio
        (stream.audioSource as? MicrophoneSource)?.let { mic ->
            mic.microphoneVolume = audio.micGain
            mic.audioSource = MicDevices.audioSourceOf(audio.processing)
        }
        preferredMic = audio.micSource
        preferredMicName = audio.micDeviceName
        _state.update { it.copy(micSource = audio.micSource) }
        applyMicSource()
    }

    private val recorder = StreamRecorder(appContext, stream)

    override fun setRecordStream(enabled: Boolean) {
        recorder.enabled = enabled
    }

    @Volatile
    private var preferredMic = MicSource.AUTO
    private var preferredMicName = ""

    override fun setMicSource(source: MicSource, deviceName: String) {
        preferredMic = source
        preferredMicName = deviceName
        _state.update { it.copy(micSource = source) }
        applyMicSource()
    }

    /** Предпочтение сбрасывается вместе с источником звука, поэтому вызывается и
     *  из configure(), и при переключении кнопкой. */
    private fun applyMicSource() {
        val mic = stream.audioSource as? MicrophoneSource ?: return
        // HAL вправе отказать, и отказ надо видеть: иначе выбор «у камеры»
        // молча остаётся выбором системы.
        val applied = runCatching {
            mic.setPreferredDevice(MicDevices.deviceFor(appContext, preferredMic, preferredMicName))
        }.getOrDefault(false)
        if (!applied) {
            Log.w("BrixMic", "устройство не принято HAL: $preferredMic — звук с микрофона по выбору системы")
        }
    }

    // Один монитор на весь жизненный цикл — ровно то, что комментарий у
    // release() обещал с самого начала, но чего в коде не было: released-флаг
    // проверялся без него, поэтому ACTION_START из уведомления мог зайти в
    // start() параллельно с release() и поднять эфир на уже освобождённом
    // потоке. В SrtlaStreamer эта защита стоит с самого аудита (P0-2).
    @Synchronized
    override fun start(url: String) {
        if (stream.isStreaming) return
        val gen = session.start() ?: return
        activeGen = gen
        currentUrl = url
        reconnector.reset()
        isReconnectAttempt = false
        sentBytes = 0L
        // Стартовый битрейт берём из настроек ABR, а не из видеопрофиля:
        // initialBitrateKbps существует ровно затем, чтобы начинать осторожнее
        // целевого и подниматься по мере того, как сеть себя покажет.
        _state.update {
            it.copy(status = StreamStatus.Connecting, phase = session.phase, error = null, reconnectAttempt = 0)
        }
        stream.startStream(url)
        uptime.start()
        recorder.start()
        // См. Streamer.start(): stopStream() гасит захват с камеры, и без этого
        // после Stop->Start превью замирает на последнем кадре.
        camera.resumePreviewIfDetached()
        camera.applyTorchOnStart()
    }

    @Synchronized
    override fun stop() {
        val gen = session.generation
        activeGen = 0
        reconnector.cancel()
        isReconnectAttempt = false
        uptime.stop()
        recorder.stop()
        if (stream.isStreaming) stream.stopStream()
        updateSession(SessionPhase.Stopping, gen)
        updateSession(SessionPhase.Released, gen)
        _state.update { it.stopped() }
    }

    @Synchronized
    override fun reconnect() {
        // Гвард раньше требовал фазу Live/Connecting, а сюда приходят именно
        // из Failed — кнопка «Переподключить» в уведомлении после обрыва не
        // делала вообще ничего, и лечился обрыв только Stop→Start руками.
        // Переход Failed→Reconnecting таблицей ЗАПРЕЩЁН (Failed ведёт только в
        // Idle/Released), поэтому из отказа поднимаемся через session.start(),
        // как это делает SrtlaStreamer.
        val url = currentUrl ?: return
        // Ручное нажатие — явное «попробуй сейчас», поэтому лестница начинается
        // заново, а не продолжается с тридцати секунд.
        reconnector.reset()
        _state.update { it.copy(reconnectAttempt = 0) }
        when (session.phase) {
            SessionPhase.Live -> if (!updateSession(SessionPhase.Reconnecting)) return
            SessionPhase.Failed -> {
                session.start() ?: return
                activeGen = session.generation
            }
            SessionPhase.Preparing, SessionPhase.Connecting, SessionPhase.Reconnecting -> Unit
            else -> return
        }
        if (session.phase != SessionPhase.Connecting && !updateSession(SessionPhase.Connecting)) return
        isReconnectAttempt = true
        runCatching { stream.stopStream() }
        _state.update { it.copy(status = StreamStatus.Connecting, phase = session.phase, error = null) }
        stream.startStream(url)
        camera.resumePreviewIfDetached()
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
    }

    override fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int) {
        // Пусто намеренно: адаптивного битрейта на WHIP пока нет, см. комментарий
        // в configure(). Заглушка честнее регулятора, который не увидит затора.
    }

    override fun setMuted(muted: Boolean) {
        _state.update { it.copy(micMuted = muted) }
        (stream.audioSource as? MicrophoneSource)?.let { source ->
            if (muted) source.mute() else source.unMute()
        }
    }

    private var blackFilter: BaseFilterRender? = null

    override fun setTorch(enabled: Boolean) = camera.setTorch(enabled)

    override fun setBlackScreen(enabled: Boolean) {
        if (isReleased) return
        opsScope.launch {
            val gl = stream.getGlInterface()
            val filter = blackFilter ?: BlackFilterRender().also { blackFilter = it }
            if (enabled) gl.addFilter(filter) else gl.removeFilter(filter)
        }
        _state.update { it.copy(blackScreenOn = enabled) }
    }

    private var activeVideoEffectFilter: BaseFilterRender? = null

    override fun setVideoEffect(effect: VideoEffect) {
        if (isReleased) return
        opsScope.launch {
            val gl = stream.getGlInterface()
            activeVideoEffectFilter?.let { gl.removeFilter(it) }
            activeVideoEffectFilter = null
            val filter = when (effect) {
                VideoEffect.NONE -> null
                VideoEffect.GRAYSCALE -> GreyScaleFilterRender()
                VideoEffect.SEPIA -> SepiaFilterRender()
            }
            if (filter != null) {
                gl.addFilter(filter)
                activeVideoEffectFilter = filter
            }
            _state.update { it.copy(videoEffect = effect) }
        }
    }

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

    override fun takeSnapshot(onResult: (android.graphics.Bitmap?) -> Unit) {
        if (isReleased) {
            onResult(null)
            return
        }
        runCatching { stream.getGlInterface().takePhoto { bitmap -> onResult(bitmap) } }
            .onFailure { onResult(null) }
    }

    override fun setOverlayConnectionState(overlayId: String, connected: Boolean) =
        overlays.setConnectionState(overlayId, connected)

    override fun attachLiveOverlay(overlayId: String, posX: Float, posY: Float, size: OverlaySize) =
        overlays.attachLive(overlayId, posX, posY, size)

    override fun updateLiveOverlayFrame(overlayId: String, bitmap: Bitmap) =
        overlays.updateLiveFrame(overlayId, bitmap)

    override fun detachLiveOverlay(overlayId: String) = overlays.detachLive(overlayId)

    override fun setLens(cameraId: String, zoom: Float): Boolean = camera.setLens(cameraId, zoom)

    override fun currentLensId(): String? = camera.currentLensId()

    override fun zoomByScale(scale: Float) = camera.zoomByScale(scale)

    override fun getZoomRange(): ClosedFloatingPointRange<Float>? = camera.getZoomRange()

    override fun tapFocus(view: View, x: Float, y: Float) = camera.tapFocus(view, x, y)

    override fun setVideoBitrate(bitrate: Int) {
        stream.setVideoBitrateOnFly(bitrate)
    }

    override var isReleased: Boolean = false
        private set

    @Synchronized
    override fun release() {
        if (isReleased) return
        isReleased = true
        prepared = false
        appliedCodec = null
        appliedKeyframeSec = null
        uptime.stop()
        recorder.stop()
        overlays.release()
        reconnector.cancel()
        opsScope.cancel()
        session.reset()
        activeGen = 0
        try {
            if (stream.isStreaming) stream.stopStream()
            stream.release()
        } catch (_: Exception) {
        }
    }

    override fun onConnectionStarted(url: String) {
        if (!session.matches(activeGen)) return
        updateSession(SessionPhase.Connecting)
    }

    override fun onConnectionSuccess() {
        if (!session.matches(activeGen)) return
        reconnector.reset()
        isReconnectAttempt = false
        _state.update { it.copy(reconnectAttempt = 0) }
        updateSession(SessionPhase.Live)
    }

    override fun onConnectionFailed(reason: String) {
        if (!session.matches(activeGen)) return
        // Провалилась очередная попытка лестницы — не повод останавливаться:
        // туннель, лифт и глухая зона кончаются, а эфир продолжается.
        if (isReconnectAttempt) {
            if (updateSession(SessionPhase.Reconnecting)) reconnector.schedule()
            return
        }
        if (updateSession(SessionPhase.Failed)) {
            _state.update { it.copy(error = StreamError.unknown(reason, technical = reason)) }
        }
    }

    override fun onDisconnect() {
        if (!session.matches(activeGen)) return
        // До 03.09 обрыв сразу переводил сессию в Failed, и на этом всё
        // заканчивалось: автоматических попыток не было, а ручная кнопка из
        // Failed не работала. Теперь — та же лестница, что у SRTLA.
        if (session.phase == SessionPhase.Live || session.phase == SessionPhase.Connecting) {
            if (updateSession(SessionPhase.Reconnecting)) {
                _state.update {
                    it.copy(error = StreamError(
                        code = StreamErrorCode.NETWORK_UNAVAILABLE,
                        message = "Connection lost",
                        retryable = true,
                    ))
                }
                reconnector.schedule()
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
        // Одна строка канала вместо пустого блока в HUD. Бондинга у WHIP нет —
        // сокет ровно один, поэтому доля всегда 100%, и это честно: блок
        // показывает, куда идёт поток, а не сравнение путей. Байты копим сами:
        // счётчика отправленного RootEncoder наружу не даёт, а onNewBitrate
        // прилетает раз в секунду и несёт биты в секунду.
        sentBytes += bitrate / 8
        val link = ConnectionStat(
            type = "whip",
            score = 100,
            rtt = 0,
            enabled = true,
            bytesSent = sentBytes,
        )
        _state.update { it.copy(bitrateKbps = bitrate / 1000, connections = listOf(link)) }
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
        return true
    }
}
