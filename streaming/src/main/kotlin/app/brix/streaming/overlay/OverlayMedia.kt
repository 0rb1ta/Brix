package app.brix.streaming.overlay

/** Relative size of an overlay expressed as a fraction of the video frame (0.0–1.0). */
data class OverlaySize(
    val widthFraction: Float = 0.3f,
    val heightFraction: Float = 0.3f,
)

/** Kind of a cached media file. */
enum class MediaType {
    IMAGE,
    AUDIO,
}
