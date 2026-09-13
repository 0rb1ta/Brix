package app.brix.streaming.overlay

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.gifdecoder.GifDecoder as GlideGifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** A single still frame of an overlay, with its display duration. */
data class OverlayFrame(
    val bitmap: Bitmap,
    val durationMs: Long,
)

/**
 * Decodes an overlay's image file into a list of [OverlayFrame]s. GIFs are
 * expanded into their animation frames via Glide's low-level
 * `gifdecoder` module (spec-correct per-frame delay from the Graphic Control
 * Extension) — `android.graphics.Movie` was tried first but its
 * `duration()` is known to return 0 for many real-world GIFs, which made
 * this fall back to sampling just 1-2 near-duplicate frames instead of the
 * real animation. Static images become a single frame. All frames returned
 * are recycled by the caller when done.
 */
class GifDecoder(
    private val context: Context,
) {
    suspend fun decode(file: File, targetWidth: Int, targetHeight: Int): List<OverlayFrame> =
        withContext(Dispatchers.Default) {
            if (isGif(file)) {
                decodeGif(file, targetWidth, targetHeight) ?: listOf(decodeImage(file, targetWidth, targetHeight))
            } else {
                listOf(decodeImage(file, targetWidth, targetHeight))
            }
        }

    /** Sniffs the GIF87a/GIF89a magic bytes instead of trusting the file
     *  name — [MediaCache] names every downloaded image `*.img` regardless
     *  of its real type, so a name-based check here never matched and this
     *  animated path was silently unreachable. */
    private fun isGif(file: File): Boolean {
        val header = ByteArray(6)
        val read = file.inputStream().use { it.read(header) }
        if (read < 6) return false
        val sig = String(header, Charsets.US_ASCII)
        return sig == "GIF87a" || sig == "GIF89a"
    }

    private fun decodeGif(file: File, targetWidth: Int, targetHeight: Int): List<OverlayFrame>? {
        val buffer = ByteBuffer.wrap(file.readBytes())
        val header = GifHeaderParser().setData(buffer).parseHeader()
        if (header.numFrames <= 0 || header.status != GlideGifDecoder.STATUS_OK) return null

        val decoder = StandardGifDecoder(SimpleBitmapProvider)
        decoder.setData(header, buffer)

        val frames = ArrayList<OverlayFrame>(header.numFrames)
        // Every frame is a full uncompressed ARGB_8888 bitmap held for the whole
        // alert, and nothing recycles them (the GL filter's ownership of the
        // last bitmap makes that unsafe). A long, large donation GIF could
        // therefore pin hundreds of megabytes on a device already close to its
        // thermal/memory ceiling mid-broadcast. Cap the retained animation by
        // total pixel budget; the playback loop cycles `frames[idx % size]`, so
        // a truncated GIF simply loops its opening frames instead of OOM-ing
        // the stream.
        var budgetPixels = MAX_FRAME_PIXELS
        for (i in 0 until header.numFrames) {
            decoder.advance()
            val frame = decoder.nextFrame ?: continue
            budgetPixels -= frame.width.toLong() * frame.height.toLong()
            if (budgetPixels < 0 && frames.isNotEmpty()) {
                android.util.Log.w(
                    "Overlay",
                    "gif truncated at ${frames.size}/${header.numFrames} frames (memory budget)",
                )
                break
            }
            val delayMs = decoder.nextDelay.toLong().coerceAtLeast(20L)
            val scaled = if (targetWidth > 0 && targetHeight > 0 &&
                (frame.width != targetWidth || frame.height != targetHeight)
            ) {
                Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
            } else {
                frame
            }
            frames.add(OverlayFrame(scaled, delayMs))
        }
        return frames.ifEmpty { null }
    }

    private fun decodeImage(file: File, width: Int, height: Int): OverlayFrame {
        var bitmap = Glide.with(context).asBitmap().load(file).submit().get()
        if (width > 0 && height > 0 && (bitmap.width != width || bitmap.height != height)) {
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return OverlayFrame(bitmap, 0L)
    }

    private companion object {
        /** ~64 MB of ARGB_8888 (4 bytes/pixel) worth of retained GIF frames. */
        const val MAX_FRAME_PIXELS = 16L * 1024 * 1024
    }

    /** Minimal, non-pooling [GlideGifDecoder.BitmapProvider] — each frame gets
     *  its own fresh allocation, so there's no reuse/recycle bookkeeping to
     *  worry about (the real pooling providers live in Glide's internal
     *  `load.resource.gif` package, not exposed by the `gifdecoder` module). */
    private object SimpleBitmapProvider : GlideGifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) = Unit

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)

        override fun release(bytes: ByteArray) = Unit

        override fun obtainIntArray(size: Int): IntArray = IntArray(size)

        override fun release(array: IntArray) = Unit
    }
}
