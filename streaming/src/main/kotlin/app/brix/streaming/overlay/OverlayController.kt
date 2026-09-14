package app.brix.streaming.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import app.brix.streaming.LiveStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrates an in-stream overlay alert end-to-end:
 * 1. Download + decode the alert media, render frames via [LiveStreamer.showOverlay].
 * 2. Route alert audio to the device and/or the stream per the two switches.
 *
 * The trigger is alert media supplied by the caller — in practice always the
 * JS bridge in the streamer-facing WebView ([OverlayJsBridge.onAlert]), which
 * reads the widget's own DOM/JS state instead of us re-fetching and parsing
 * the page ourselves (a static HTML parse was tried and removed: the widgets
 * are JS-rendered, so it never had a real caller besides its own tests).
 */
class OverlayController(
    private val context: Context,
    private val streamer: LiveStreamer,
    private val overlayId: String,
    private val cache: MediaCache = MediaCache(context),
    private val gifDecoder: GifDecoder = GifDecoder(context),
    private val audioPlayer: DonationAudioPlayer = DonationAudioPlayer(),
    // DonationAudioPlayer holds one shared PCM buffer + a single "am I
    // playing" flag — playOnDevice() silently no-ops if that flag is still
    // set. A late TTS clip (playAdditionalAudio, arriving ~3.5s after the
    // alert) can land while the main clip sequence's own last clip is still
    // finishing (confirmed live: the chat-chime clip is ~2.7s, close enough
    // to the late-audio window that image/caption decode overhead alone can
    // push them into overlapping) — sharing audioPlayer meant the late clip
    // just got dropped, silently, with the TTS never audible at all. A
    // dedicated instance keeps the two paths from colliding.
    private val lateAudioPlayer: DonationAudioPlayer = DonationAudioPlayer(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Audio options for a single alert. */
    data class AudioOptions(
        val onDevice: Boolean,
        val inStream: Boolean,
        val volume: Float = 0.7f,
    )

    /** Media + audio for one alert, as reported by the widget's own JS via
     *  [OverlayJsBridge.onAlert]. [audioUrls] can hold more than one clip — a single donation can play
     *  more than one sound (chat chime, then a paid TTS readout of the
     *  message, then possibly a recorded voice message) — played back to
     *  back, not mixed together. [text] is the donor's message (if found,
     *  with the alert's title line already stripped), [username] a
     *  best-effort guess at the donor's name, and [amount] the amount +
     *  currency — all shown as a caption under the image, username/amount
     *  bolded above the message. */
    data class AlertMedia(
        val imageUrl: String,
        val audioUrls: List<String>,
        val durationMs: Long,
        val text: String? = null,
        val username: String? = null,
        val amount: String? = null,
        val posX: Float,
        val posY: Float,
        val width: Float,
        val height: Float,
        /** Множитель размера подписи, задаётся человеком. 1.0 — исходный. */
        val captionScale: Float = 1f,
        /** Показывать ли подпись. */
        val captionVisible: Boolean = true,
    )

    /**
     * Arm/disarm this overlay's contribution to the stream's donation-audio
     * mix. Call once when the "audio in stream" setting toggles, not
     * per-alert — swapping the audio source mid-stream causes a brief mic
     * dropout. Other overlays' armed players are unaffected.
     */
    private val lateAudioOverlayId get() = "$overlayId#late"

    fun setInStreamMixing(enabled: Boolean) {
        streamer.setOverlayAudioInStream(overlayId, enabled, if (enabled) audioPlayer else null)
        streamer.setOverlayAudioInStream(lateAudioOverlayId, enabled, if (enabled) lateAudioPlayer else null)
    }


    /** Shows an alert from media already resolved (e.g. from the JS bridge). */
    fun show(media: AlertMedia, audio: AudioOptions) {
        scope.launch {
            val imageFile = cache.getOrDownload(media.imageUrl, MediaType.IMAGE) ?: return@launch
            val frames = gifDecoder.decode(imageFile, 0, 0)
            if (frames.isEmpty()) return@launch

            val size = OverlaySize(
                widthFraction = media.width.coerceIn(0.05f, 1f),
                heightFraction = media.height.coerceIn(0.05f, 1f),
            )

            // Every sound clip the widget fired for this alert (order
            // matters — chat chime first, then TTS/voice) gets played back
            // to back through the single audioPlayer instance, since that's
            // the exact instance object armed with the stream's mixer in
            // setInStreamMixing(); using separate instances per clip would
            // never reach the mixer for anything but the first one. We need
            // each clip's real duration before the image/caption are shown
            // (so they don't fade out mid-clip on a long voice message /
            // TTS readout), which means decoding every file twice — once
            // here just to read durationMs, once for real below — an
            // acceptable cost for short, infrequent donation clips.
            val audioFiles = media.audioUrls.mapNotNull { cache.getOrDownload(it, MediaType.AUDIO) }
            var audioMs = 0L
            for (file in audioFiles) {
                if (audioPlayer.load(file)) audioMs += audioPlayer.durationMs
            }
            val totalDurationMs = maxOf(media.durationMs, audioMs)

            // Размер подписи задаётся ОТДЕЛЬНОЙ настройкой, а не выводится из
            // ширины картинки. Раньше было `media.width / 0.18f`, и чтобы изменить
            // текст, приходилось менять размер окна оверлея — единственную ручку,
            // которая на него влияла (владелец, 14.09).
            val sizeScale = media.captionScale.coerceIn(0.5f, 3f)

            // Подпись рисуется СРАЗУ В ТОМ разрешении, в каком уйдёт в кадр.
            // Раньше она всегда была шириной 640 пикселей, а блок потом
            // растягивался GL-фильтром до размера окна — на большом окне буквы
            // размывались и «ломались» (владелец, 14.09). Считаем целевую
            // ширину блока в пикселях кадра и рисуем текст под неё; масштаб
            // всех размеров — тот же множитель, поэтому вид не меняется,
            // меняется только чёткость.
            val frameSize = streamer.videoFrameSize()
            val targetPx = ((frameSize?.x ?: 1920) * size.widthFraction)
                .toInt().coerceIn(320, 3840)
            val renderScale = targetPx / CAPTION_BASE_WIDTH

            val captionBitmap = if (media.captionVisible &&
                (media.username != null || media.amount != null || media.text != null)
            ) {
                renderCaption(media.username, media.amount, media.text, sizeScale, targetPx, renderScale)
            } else {
                null
            }

            // КАРТИНКА И ПОДПИСЬ — ОДИН БЛОК, склеенный в одно изображение.
            //
            // Раньше это были два независимых оверлея со своей геометрией, и они
            // расходились: подпись считала ширину от своего масштаба, положение —
            // от картинки, у края кадра её приходилось сужать или сдвигать, и
            // центр переставал совпадать. Каждая правка чинила очередной симптом.
            //
            // Владелец 14.09 сформулировал модель: «картинка, ник, сумма и текст
            // должны быть одним блоком, от изменения размера окна они все вместе
            // увеличиваются или уменьшаются». Склеенный блок это и даёт даром:
            // центр совпадает по построению, у краёв ничего не разъезжается,
            // размер окна масштабирует всё разом.
            val blockFrames = if (captionBitmap != null) {
                frames.map { OverlayFrame(compose(it.bitmap, captionBitmap, renderScale), it.durationMs) }
            } else {
                frames
            }

            // Высота выводится из пропорций склеенного блока, а не берётся из
            // настройки: иначе перетаскивание угла растянуло бы текст.
            val blockBitmap = blockFrames.first().bitmap
            val frameAspect = if (frameSize != null && frameSize.y > 0) {
                frameSize.x.toFloat() / frameSize.y.toFloat()
            } else {
                16f / 9f
            }
            // Блок ВПИСЫВАЕТСЯ в окно, заданное человеком, а не просто берёт его
            // ширину. Раньше высота выводилась из ширины, и если окно было
            // вытянутым по вертикали, блок занимал четверть от него — человек
            // тянет рамку, а картинка не растёт (владелец, 14.09). Теперь
            // масштаб — наибольший, при котором блок целиком влезает в рамку:
            // одна из сторон совпадает с окном, вторая меньше.
            val blockAspect = blockBitmap.height.toFloat() / blockBitmap.width * frameAspect
            val fitWidth = minOf(size.widthFraction, size.heightFraction / blockAspect)
            val blockWidth = fitWidth.coerceIn(0.02f, 1f)
            val blockHeight = (blockWidth * blockAspect).coerceIn(0.02f, 0.95f)
            android.util.Log.d(
                "Overlay",
                "блок: ${blockBitmap.width}x${blockBitmap.height} width=${size.widthFraction} " +
                    "-> $blockWidth x $blockHeight (окно ${size.widthFraction}x${size.heightFraction}) " +
                    "frameAspect=$frameAspect frameSize=$frameSize",
            )
            streamer.showOverlay(
                overlayId,
                blockFrames,
                media.posX,
                media.posY,
                OverlaySize(blockWidth, blockHeight),
                totalDurationMs,
            )

            // Audio runs ALONGSIDE the display, not instead of it. Awaiting
            // playAudioSequence() made the visible lifetime equal the clip
            // length: a 2.66s chime tore the overlay down 2.3s into its 5s
            // window, and since showOverlay's fade ramp is computed against
            // totalDurationMs it was still at full alpha, so the alert did not
            // fade out — it vanished. Wait for whichever finishes last, so a
            // readout longer than the window still keeps its picture up.
            if (audioFiles.isEmpty()) {
                delay(totalDurationMs)
            } else {
                val audioJob = launch { playAudioSequence(audioFiles, audio, audioPlayer) }
                delay(totalDurationMs)
                audioJob.join()
            }

            streamer.hideOverlay(overlayId)
        }
    }

    /** Plays sound clip(s) that arrived too late to be part of the original
     *  [show] call — specifically a paid TTS/voice readout, generated
     *  server-side and observed to appear seconds after the alert image
     *  itself. Audio-only: doesn't touch the image/caption overlay. */
    fun playAdditionalAudio(urls: List<String>, audio: AudioOptions) {
        if (urls.isEmpty()) return
        scope.launch {
            val files = urls.mapNotNull { cache.getOrDownload(it, MediaType.AUDIO) }
            if (files.isNotEmpty()) playAudioSequence(files, audio, lateAudioPlayer)
        }
    }

    private suspend fun playAudioSequence(files: List<java.io.File>, audio: AudioOptions, player: DonationAudioPlayer) {
        for (file in files) {
            if (!player.load(file)) continue
            if (audio.onDevice) player.playOnDevice()
            if (audio.inStream) player.beginStreamMix()
            delay(player.durationMs.coerceAtLeast(200L))
            player.stop()
        }
    }

    /** Renders a donation's username + amount (bold, accent-colored header)
     *  and message as a standalone bitmap — no background box, text-only
     *  with a black outline for legibility directly over the camera feed
     *  (matches the look of typical donation-alert widgets) — so it can be
     *  shown through the same native overlay pipeline as the alert image. */
    /**
     * Склеивает кадр картинки и подпись в одно изображение: картинка сверху,
     * подпись под ней, обе по центру общей ширины.
     *
     * Так блок масштабируется целиком и не может разъехаться — это и есть
     * решение, которое искали правками геометрии двух отдельных оверлеев.
     */
    private fun compose(image: Bitmap, caption: Bitmap, renderScale: Float): Bitmap {
        // Картинка на 20% мельче собственного размера: в склеенном блоке она
        // забивала подпись, потому что типовой донат-гиф шире 640 пикселей, в
        // которые рисуется текст (владелец, 14.09 — «картинка очень крупная по
        // отношению к тексту»). Пропорция картинки к тексту задаётся здесь, а
        // ручка captionScale по-прежнему двигает текст в другую сторону.
        val imageWidth = (image.width * IMAGE_TO_CAPTION * renderScale).toInt().coerceAtLeast(1)
        val imageHeight = (image.height.toFloat() * imageWidth / image.width).toInt().coerceAtLeast(1)
        val imageScaled = Bitmap.createScaledBitmap(image, imageWidth, imageHeight, true)

        val gap = (imageHeight * 0.04f).toInt().coerceAtLeast(4)
        // Подпись рисуется в фиксированные 640 пикселей ширины; вписываем её в
        // ширину блока, сохраняя пропорции.
        val width = maxOf(imageWidth, caption.width)
        val captionScaled = if (caption.width == width) {
            caption
        } else {
            Bitmap.createScaledBitmap(
                caption,
                width,
                (caption.height.toFloat() * width / caption.width).toInt().coerceAtLeast(1),
                true,
            )
        }
        val out = Bitmap.createBitmap(width, imageHeight + gap + captionScaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(imageScaled, ((width - imageWidth) / 2f), 0f, null)
        canvas.drawBitmap(captionScaled, ((width - captionScaled.width) / 2f), (imageHeight + gap).toFloat(), null)
        return out
    }

    private fun renderCaption(
        username: String?,
        amount: String?,
        text: String?,
        sizeScale: Float,
        width: Int,
        renderScale: Float,
    ): Bitmap? {
        if (username.isNullOrBlank() && amount.isNullOrBlank() && text.isNullOrBlank()) return null
        val header = listOfNotNull(username?.takeIf { it.isNotBlank() }, amount?.takeIf { it.isNotBlank() })
            .joinToString(" — ")

        // StaticLayout draws with a single Paint, so an outlined look (fill
        // + stroke) needs two separate layouts over the same text, drawn on
        // top of each other — the stroke one first (as the outline), then
        // the fill one (colors, via spans) on top.
        fun buildContent(withColor: Boolean): SpannableStringBuilder {
            val builder = SpannableStringBuilder()
            if (header.isNotBlank()) {
                val start = builder.length
                builder.append(header)
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (withColor) {
                    builder.setSpan(ForegroundColorSpan(HEADER_COLOR), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (!text.isNullOrBlank()) builder.append("\n")
            }
            if (!text.isNullOrBlank()) builder.append(text)
            return builder
        }

        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f * sizeScale * renderScale
            style = Paint.Style.STROKE
            strokeWidth = 7f * sizeScale * renderScale
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f * sizeScale * renderScale
            color = Color.WHITE
        }
        val strokeContent = buildContent(withColor = false)
        val fillContent = buildContent(withColor = true)
        val strokeLayout = StaticLayout.Builder.obtain(strokeContent, 0, strokeContent.length, strokePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val fillLayout = StaticLayout.Builder.obtain(fillContent, 0, fillContent.length, fillPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        // A little vertical breathing room so the stroke isn't clipped at
        // the very top/bottom of the bitmap — not a visible box, since
        // there's no background fill anymore.
        val padding = (8 * renderScale).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, strokeLayout.height + padding * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, padding.toFloat())
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        return bitmap
    }

    fun release() {
        audioPlayer.release()
        lateAudioPlayer.release()
        scope.cancel()
    }

    private companion object {
        // DonationAlerts' actual brand orange (donationalerts.com/brand:
        // "Наш оранжевый" #F57D07) — used for the username+amount header
        // since the caption is drawn by us, not scraped as a styled image,
        // and the goal is to be indistinguishable from their own widget.
        const val HEADER_COLOR = 0xFFF57D07.toInt()

        // Во сколько раз картинка мельче своего исходного размера внутри блока.
        const val IMAGE_TO_CAPTION = 0.8f

        // Разрешение, в котором подпись рисовалась раньше всегда; теперь это
        // опорная точка, от которой считается множитель чёткости.
        const val CAPTION_BASE_WIDTH = 640f
    }
}