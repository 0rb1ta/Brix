package app.brix.streaming.chat

enum class ChatPlatform {
    TWITCH,
    KICK,
    VK,
}

/** One rendered chat line — platform-tagged so a merged multi-platform feed
 *  can still tell messages apart (colored badge/dot per source in the UI).
 *  Only what the streamer's screen needs; never fed to the encoder. */
data class ChatMessage(
    val id: String,
    val platform: ChatPlatform,
    val author: String,
    /** "#RRGGBB" from the platform, or null when the user has no color set —
     *  the UI falls back to its own default in that case. */
    val colorHex: String?,
    val text: String,
    val timestampMs: Long,
)
