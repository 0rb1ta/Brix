package app.brix.streaming.overlay

import android.graphics.Bitmap
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.view.GlInterface

/**
 * Renders an in-stream overlay natively: an [ImageObjectFilterRender] is added
 * to the stream's GL pipeline and its bitmap is updated per frame. `setImage`
 * is safe to call from any thread (the texture is uploaded on the GL thread
 * during the filter's draw pass).
 */
class NativeOverlayRenderer {
    private val filter = ImageObjectFilterRender()
    @Volatile
    private var applied = false

    val isApplied: Boolean get() = applied

    fun attach(gl: GlInterface) {
        if (applied) return
        try {
            gl.addFilter(filter)
            applied = true
            android.util.Log.d("NativeOverlay", "attach: addFilter ok, filtersCount=${gl.filtersCount()}")
        } catch (e: Exception) {
            android.util.Log.e("NativeOverlay", "attach: addFilter failed", e)
        }
    }

    fun detach(gl: GlInterface) {
        if (!applied) return
        try {
            gl.removeFilter(filter)
            android.util.Log.d("NativeOverlay", "detach: removeFilter ok, filtersCount=${gl.filtersCount()}")
        } catch (e: Exception) {
            android.util.Log.e("NativeOverlay", "detach: removeFilter failed", e)
        } finally {
            applied = false
        }
    }

    fun setFrame(bitmap: Bitmap) {
        filter.setImage(bitmap)
    }

    fun setAlpha(alpha: Float) {
        filter.setAlpha(alpha.coerceIn(0f, 1f))
    }

    /**
     * [posX]/[posY] are the box's center as fractions (0..1) of the video
     * frame — the same convention [OverlayConfig]/the placement wizard use.
     * RootEncoder's `Sprite` (which backs this filter) takes scale and
     * position in *percent* (0..100, not 0..1) and position is the box's
     * *top-left* corner, not its center — see `Sprite.java`/
     * `BaseObjectFilterRender.java` upstream. Scale must be set before
     * position: `Sprite.scale()` rescales the already-stored position as a
     * side effect, which would clobber a position set beforehand.
     */
    fun setTransform(posX: Float, posY: Float, size: OverlaySize) {
        val widthPct = (size.widthFraction.coerceAtLeast(0.01f) * 100f)
        val heightPct = (size.heightFraction.coerceAtLeast(0.01f) * 100f)
        filter.setScale(widthPct, heightPct)
        val leftPct = (posX * 100f - widthPct / 2f).coerceIn(0f, (100f - widthPct).coerceAtLeast(0f))
        val topPct = (posY * 100f - heightPct / 2f).coerceIn(0f, (100f - heightPct).coerceAtLeast(0f))
        filter.setPosition(leftPct, topPct)
    }

    fun clear() {
        filter.setImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    }
}
