package app.brix.streaming

import android.graphics.Bitmap
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import app.brix.streaming.overlay.DonationAudioPlayer
import app.brix.streaming.overlay.NativeOverlayRenderer
import app.brix.streaming.overlay.OverlayAudioSource
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import com.pedro.library.base.StreamBase
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Всё, что относится к оверлеям в кадре: донат-алерты, живые оверлеи
 * (снимок браузерного виджета), примешивание донат-звука в эфир и признак
 * «виджет на связи» для HUD.
 *
 * Выделено из [SrtlaStreamer], который держал шесть несвязанных обязанностей
 * сразу и вырос до полутора тысяч строк. Наружу ничего не поменялось:
 * [LiveStreamer] остался тем же интерфейсом, стример просто делегирует сюда.
 *
 * Зависимости узкие и все передаются снаружи: медиа-тракт [stream] (фильтровая
 * цепочка GL и смена источника звука), общая корутинная область [scope],
 * [updateState] для полей StreamState, которые видит HUD, и [isReleased] —
 * владелец может быть уже разрушен, пока сюда летит запоздавший вызов.
 */
internal class OverlayHost(
    // StreamBase, а не конкретный транспорт: RtmpStream, WhipStream и
    // SrtlaStream наследуют его все три, и нужное оверлеям (GL-цепочка и смена
    // источника звука) объявлено там. До 03.09 хост знал только SRTLA, поэтому
    // донатные оверлеи в RTMP и WHIP были восемью пустыми заглушками.
    private val stream: StreamBase,
    /** Залатченный мьют микрофона: после каждой смены источника звука его надо
     *  подтвердить заново, иначе включённый оверлей-миксер снимает мьют. */
    private val micMuted: () -> Boolean,
    /** Усиление микрофона из профиля: при смене источника звука его надо
     *  подтверждать так же, как мьют, иначе включение звука донатов молча
     *  сбрасывало бы уровень на единицу. */
    private val micGain: () -> Float,
    private val scope: CoroutineScope,
    private val isReleased: () -> Boolean,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
) {
    private val tag = "BrixStream"

    /** Подтвердить мьют И усиление на текущем источнике звука. */
    private fun applyMicMute() {
        when (val source = stream.audioSource) {
            is OverlayAudioSource -> {
                source.setMicMuted(micMuted())
                source.setMicGain(micGain())
            }
            is MicrophoneSource -> {
                if (micMuted()) source.mute() else source.unMute()
                source.microphoneVolume = micGain()
            }
            else -> Unit
        }
    }

    // На каждый id оверлея свой рендерер и своя анимационная задача, чтобы
    // алерт одного оверлея никогда не отменял и не затирал другой: GlInterface
    // у RootEncoder — настоящая цепочка фильтров (addFilter/removeFilter), так
    // что несколько ImageObjectFilterRender живут одновременно.
    private val renderers = ConcurrentHashMap<String, NativeOverlayRenderer>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private var mixer: OverlayAudioSource? = null

    // Оверлеи, чей донат-звук сейчас подмешивается в эфир, по id оверлея.
    // Их может быть несколько разом (OverlayAudioSource их складывает),
    // поэтому это карта, а не один обнуляемый проигрыватель.
    private val mixPlayers = ConcurrentHashMap<String, DonationAudioPlayer>()

    // Признак «виджет на связи» на каждый оверлей (только донат-виджеты),
    // сворачивается в StreamState.donationWidgetConnected по «хоть один».
    private val connectionStates = ConcurrentHashMap<String, Boolean>()

    fun setAudioInStream(overlayId: String, enabled: Boolean, donationPlayer: DonationAudioPlayer?) {
        if (isReleased()) return
        scope.launch {
            try {
                if (enabled && donationPlayer != null) {
                    mixPlayers[overlayId] = donationPlayer
                } else {
                    mixPlayers.remove(overlayId)
                }
                if (mixPlayers.isNotEmpty() && mixer == null) {
                    val newMixer = OverlayAudioSource()
                    newMixer.startMixing()
                    stream.changeAudioSource(newMixer)
                    mixer = newMixer
                    // Новый источник поднимается незаглушённым — надо заново
                    // применить залипший мьют, иначе включение донат-звука
                    // молча открывает микрофон, который стример считает
                    // выключенным.
                    applyMicMute()
                } else if (mixPlayers.isEmpty() && mixer != null) {
                    mixer = null
                    stream.changeAudioSource(com.pedro.encoder.input.sources.audio.MicrophoneSource())
                    applyMicMute()
                }
                mixer?.setDonationPlayers(mixPlayers.values.toSet())
            } catch (e: Exception) {
                Log.e(tag, "setOverlayAudioInStream failed", e)
            }
        }
    }

    fun show(
        overlayId: String,
        frames: List<OverlayFrame>,
        posX: Float,
        posY: Float,
        size: OverlaySize,
        durationMs: Long,
    ) {
        if (isReleased() || frames.isEmpty()) return
        val gl = stream.getGlInterface()
        val renderer = renderers.getOrPut(overlayId) { NativeOverlayRenderer() }
        renderer.attach(gl)
        renderer.setTransform(posX, posY, size)
        jobs[overlayId]?.cancel()
        jobs[overlayId] = scope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                var idx = 0
                // Короткое появление, потом кадры на запрошенное время, потом
                // затухание: донат-алерт не должен возникать и пропадать рывком.
                renderer.setAlpha(0f)
                while (SystemClock.elapsedRealtime() - start < durationMs) {
                    val frame = frames[idx % frames.size]
                    renderer.setFrame(frame.bitmap)
                    val elapsed = (SystemClock.elapsedRealtime() - start).toFloat()
                    renderer.setAlpha(alphaFor(elapsed, durationMs))
                    delay(frame.durationMs.coerceAtLeast(16L))
                    idx++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Штатно: hide() отменяет эту задачу, когда время показа вышло
                // или алерт сменился другим. Это не сбой, и по правилам
                // структурной конкурентности исключение надо пробросить, а не
                // проглотить как ошибку.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "showOverlay animation failed (overlayId=$overlayId)", e)
            } finally {
                renderer.setAlpha(0f)
                jobs.remove(overlayId)
                updateState { it.copy(overlayVisible = jobs.isNotEmpty()) }
            }
        }
        updateState { it.copy(overlayVisible = true) }
    }

    fun hide(overlayId: String) {
        if (isReleased()) return
        jobs.remove(overlayId)?.cancel()
        val renderer = renderers[overlayId]
        scope.launch {
            renderer?.detach(stream.getGlInterface())
        }
        updateState { it.copy(overlayVisible = jobs.isNotEmpty()) }
    }

    fun videoFrameSize(): Point? =
        if (isReleased()) null else runCatching { stream.getGlInterface().encoderSize }.getOrNull()

    fun setConnectionState(overlayId: String, connected: Boolean) {
        if (isReleased()) return
        connectionStates[overlayId] = connected
        updateState { it.copy(donationWidgetConnected = connectionStates.values.any { v -> v }) }
    }

    // Живые оверлеи (например снимок браузерного виджета) делят ту же карту
    // рендереров, что и донат-алерты — id разные, конфликта нет, — но целиком
    // минуют анимационную задачу: прицепились один раз на полной непрозрачности
    // и просто получают новые кадры до отцепления.
    fun attachLive(overlayId: String, posX: Float, posY: Float, size: OverlaySize) {
        if (isReleased()) return
        val renderer = renderers.getOrPut(overlayId) { NativeOverlayRenderer() }
        renderer.attach(stream.getGlInterface())
        renderer.setTransform(posX, posY, size)
        renderer.setAlpha(1f)
    }

    fun updateLiveFrame(overlayId: String, bitmap: Bitmap) {
        if (isReleased()) return
        renderers[overlayId]?.setFrame(bitmap)
    }

    fun detachLive(overlayId: String) {
        if (isReleased()) return
        val renderer = renderers.remove(overlayId) ?: return
        scope.launch {
            renderer.detach(stream.getGlInterface())
        }
    }

    /** Снять всё: анимации, рендереры из цепочки фильтров, признаки связи.
     *  Зовётся из [SrtlaStreamer.release] до того, как отпускается сам поток. */
    fun release() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        renderers.values.forEach { it.detach(stream.getGlInterface()) }
        renderers.clear()
        connectionStates.clear()
    }

    private fun alphaFor(elapsedMs: Float, totalMs: Long): Float {
        val fade = 250f
        return when {
            elapsedMs < fade -> elapsedMs / fade
            elapsedMs >= totalMs - fade -> ((totalMs - elapsedMs) / fade).coerceAtLeast(0f)
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
