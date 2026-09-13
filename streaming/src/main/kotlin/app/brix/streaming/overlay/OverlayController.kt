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

    private val captionOverlayId get() = "$overlayId#text"

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

            // Ties caption text size to how big the donor configured the
            // donation overlay itself (media.width, set via the placement
            // wizard) — 0.18 is the app-wide default overlay size, so a
            // caption at that size renders at the original fixed textSize;
            // resizing the overlay box scales the caption proportionally.
            val sizeScale = (media.width / 0.18f).coerceIn(0.6f, 3f)
            val captionBitmap = if (media.username != null || media.amount != null || media.text != null) {
                renderCaption(media.username, media.amount, media.text, sizeScale)
            } else {
                null
            }

            streamer.showOverlay(overlayId, frames, media.posX, media.posY, size, totalDurationMs)
            if (captionBitmap != null) {
                val captionWidth = (0.5f * sizeScale).coerceIn(0.2f, 0.95f)
                // captionBitmap.height/width is a PIXEL aspect ratio; posX/posY/
                // width/height everywhere else in this file are fractions of the
                // video frame's own width and height *separately* (OverlaySize),
                // which only equal the same physical scale when the frame is
                // square. A 16:9 frame isn't, so the pixel ratio needs correcting
                // by the frame's own aspect ratio before it's usable as a height
                // fraction — skipping that (as this used to) renders the caption
                // squashed by exactly that factor (~1.78x on a 16:9 frame).
                val frameSize = streamer.videoFrameSize()
                val frameAspect = if (frameSize != null && frameSize.y > 0) {
                    frameSize.x.toFloat() / frameSize.y.toFloat()
                } else {
                    16f / 9f
                }
                val captionHeight = (captionWidth * captionBitmap.height / captionBitmap.width * frameAspect)
                    .coerceIn(0.02f, 0.9f)
                val captionPosY = (media.posY + size.heightFraction / 2f + captionHeight / 2f + 0.02f).coerceIn(0.05f, 0.95f)
                android.util.Log.d(
                    "Overlay",
                    "caption geometry: width=$captionWidth height=$captionHeight posX=${media.posX} posY=$captionPosY " +
                        "bitmap=${captionBitmap.width}x${captionBitmap.height} frameAspect=$frameAspect " +
                        // frameSize is the raw GlInterface.encoderSize. Logged
                        // explicitly because frameAspect alone cannot tell the
                        // two cases apart: a real 1280x720 encoder and the
                        // "no encoder size, fall back to 16/9" branch both
                        // print 1.7777778. Only setEncoderSize() gives the GL
                        // chain a target size and it is called solely from
                        // prepareVideo(), i.e. on Start — so a null here on a
                        // never-started session says the overlay filters had
                        // nothing valid to render into.
                        "frameSize=$frameSize",
                )
                // SrtlaStreamer.showOverlay()'s animation loop re-evaluates
                // alpha (for the fade in/out) once per frame, then delay()s
                // that frame's own durationMs before the next iteration —
                // it's how the GIF path's fade works, since a real GIF has
                // many short frames so the loop runs often. A single frame
                // whose own durationMs equals the *whole* alert duration
                // makes the loop set alpha once (still ~0, at the very start
                // of the 250ms fade-in) and then sleep for the entire
                // duration without ever running again — the caption was
                // provably never becoming visible, staying at that initial
                // near-zero alpha for its whole lifetime. Use a short
                // per-frame duration instead so the loop actually keeps
                // re-evaluating alpha; totalDurationMs (passed separately,
                // below) still controls how long the caption is shown for.
                streamer.showOverlay(
                    captionOverlayId,
                    listOf(OverlayFrame(captionBitmap, 100L)),
                    media.posX,
                    captionPosY,
                    OverlaySize(captionWidth, captionHeight),
                    totalDurationMs,
                )
            }

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
            if (captionBitmap != null) streamer.hideOverlay(captionOverlayId)
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
    private fun renderCaption(username: String?, amount: String?, text: String?, sizeScale: Float): Bitmap? {
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

        val width = 640
        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f * sizeScale
            style = Paint.Style.STROKE
            strokeWidth = 7f * sizeScale
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f * sizeScale
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
        val padding = 8
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
    }
}