package app.brix.streaming

import android.graphics.Bitmap
import android.graphics.Point
import android.view.SurfaceView
import android.view.View
import app.brix.core.AudioSettings
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import app.brix.core.MicSource
import app.brix.core.MoblinkSettings
import app.brix.core.SceneAudio
import app.brix.core.StreamProfile
import app.brix.streaming.overlay.DonationAudioPlayer
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import kotlinx.coroutines.flow.StateFlow

interface LiveStreamer {
    val state: StateFlow<StreamState>

    /**
     * Отметка `elapsedRealtime` раз в секунду — только для счётчика времени
     * эфира в HUD.
     *
     * **Почему отдельным потоком, а не полем [StreamState].** Раньше это было
     * поле `statsTickElapsedMs`, и оно меняло общее состояние ежесекундно.
     * Compose отслеживает чтение на уровне объекта состояния, а экран стримера
     * собирает `state` целиком в самом верху — значит отметка времени, которая
     * не интересна никому, кроме часов, помечала устаревшим всё тело
     * composable на две с лишним тысячи строк: пересобирались списки кнопок,
     * заново создавались обработчики, заново считались производные значения.
     * Раз в секунду, весь эфир, ради того чтобы `00:29` стало `00:30`.
     *
     * Отдельный поток читается только тем куском интерфейса, который часы и
     * показывает, поэтому перерисовывается тоже только он. Второго таймера при
     * этом не заводится: тик публикуют те же циклы, что уже крутятся
     * (`StatsReporter` в SRTLA, `UptimeTicker` в RTMP и WHIP).
     */
    val uptimeTick: StateFlow<Long>
    val isStreaming: Boolean

    fun prepare(): Boolean
    fun startPreview(surfaceView: SurfaceView)
    fun stopPreview()
    fun configure(profile: StreamProfile, srtLatencyMs: Int = 2000)
    /** Apply Moblink (relay bonding) settings. No-op for transports other than
     *  SRTLA — Moblink relays only make sense as extra SRTLA bonding channels. */
    fun configureMoblink(settings: MoblinkSettings) = Unit
    /** Apply camera defaults (default lens, torch-on-start, stabilization,
     *  mirror front preview, tap-to-focus). Default no-op — only SrtlaStreamer
     *  implements this today. */
    fun configureCamera(defaults: CameraDefaults) = Unit
    /** Общие настройки звука. Отдельным вызовом, а не через профиль: с 03.09
     *  звук один на всё приложение, а не копия в каждом профиле. */
    fun configureAudio(audio: AudioSettings) = Unit
    /** Писать ли эфир в MP4 параллельно вещанию. По умолчанию no-op — до 03.09
     *  тумблер «Запись эфира» в настройках не был подключён ни к чему. */
    fun setRecordStream(enabled: Boolean) = Unit
    /** Писать ли CSV-журнал сессии (мощность, температура, транспорт) на
     *  телефон. Реализовано только для SRTLA: журнал собирается из тех же
     *  цифр, что и `BrixStat`, а те есть лишь на этом тракте. */
    fun setDebugLog(enabled: Boolean) = Unit
    /** Переключить предпочитаемый микрофон на лету (быстрая кнопка). */
    fun setMicSource(source: MicSource) = Unit
    fun start(url: String)
    fun stop()
    fun reconnect()
    fun switchCamera()
    /** Перейти на конкретную сторону камеры (нужно сценам). */
    fun setCameraSide(side: CameraSide) = Unit
    /** Заставка вместо камеры. @return false, если картинку не прочитать. */
    fun showStillImage(uri: android.net.Uri): Boolean = false
    /** Экран телефона вместо камеры. @return false, если захват не начался. */
    fun showScreen(projection: android.media.projection.MediaProjection): Boolean = false
    /** Вернуть камеру после заставки. */
    fun showCamera() = Unit
    /**
     * Откуда брать звук в этой сцене.
     *
     * [projection] нужен для всего, кроме [SceneAudio.MIC], и это тот же токен,
     * что и для захвата экрана. @return false, если переключить не вышло —
     * вызывающий обязан остаться на микрофоне, а не уйти в тишину.
     */
    fun setSceneAudio(
        mode: SceneAudio,
        projection: android.media.projection.MediaProjection?,
    ): Boolean = false
    fun setAdaptiveBitrate(enabled: Boolean)
    /** Live-update ABR target/minimum when the profile changes mid-stream. */
    fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int)
    fun setVideoBitrate(bitrate: Int)
    fun setMuted(muted: Boolean)
    fun setTorch(enabled: Boolean)
    fun setBlackScreen(enabled: Boolean)
    /** Apply a whole-frame color effect (grayscale, sepia, ...) via a native
     *  GL filter — reuses RootEncoder's own built-in filter renders, not a
     *  custom shader. [VideoEffect.NONE] removes any active effect filter. */
    fun setVideoEffect(effect: VideoEffect)
    /** Enable/disable [overlayId]'s contribution to the stream's donation-audio
     *  mix (several overlays can be armed at once — their chunks are summed).
     *  [donationPlayer] is the source of donation PCM chunks; required when
     *  [enabled] is true. */
    fun setOverlayAudioInStream(overlayId: String, enabled: Boolean, donationPlayer: DonationAudioPlayer?)
    /** Play a native in-stream overlay (GIF frames) for [overlayId] on the GL
     *  pipeline. [posX]/[posY] are the box's center as fractions (0..1) of the
     *  video frame. Each overlay id renders independently. */
    fun showOverlay(overlayId: String, frames: List<OverlayFrame>, posX: Float, posY: Float, size: OverlaySize, durationMs: Long)
    /** Hide/clear the native in-stream overlay for [overlayId]. */
    fun hideOverlay(overlayId: String)
    /** Current encoder video frame size in px, or null if not yet known (e.g.
     *  no stream/preview prepared yet) — used to convert a bitmap's own pixel
     *  aspect ratio into a correct on-frame size fraction (the frame usually
     *  isn't square, so width-fraction and height-fraction aren't the same
     *  physical scale). */
    fun videoFrameSize(): Point?
    /** Report whether a donation overlay's widget is currently connected to
     *  its alert server, aggregated (any-connected) into [StreamState.donationWidgetConnected]. */
    fun setOverlayConnectionState(overlayId: String, connected: Boolean)
    /** Show a persistent, manually-updated overlay (e.g. a live browser
     *  widget snapshot) — unlike [showOverlay] this has no built-in
     *  duration/fade; it stays until [detachLiveOverlay]. */
    fun attachLiveOverlay(overlayId: String, posX: Float, posY: Float, size: OverlaySize)
    /** Push a new frame to an overlay attached via [attachLiveOverlay]. */
    fun updateLiveOverlayFrame(overlayId: String, bitmap: Bitmap)
    /** Remove an overlay attached via [attachLiveOverlay]. */
    fun detachLiveOverlay(overlayId: String)
    /** Switch to a specific rear lens (see Context.rearLenses()); zoom>1
     *  applies a zoom ratio after opening (virtual tele). False if unavailable. */
    fun setLens(cameraId: String, zoom: Float = 1f): Boolean
    /** Current rear lens id, null when unknown or front camera is active. */
    fun currentLensId(): String?
    fun zoomByScale(scale: Float)
    fun getZoomRange(): ClosedFloatingPointRange<Float>?
    fun tapFocus(view: View, x: Float, y: Float)
    val isReleased: Boolean

    fun release()
}
