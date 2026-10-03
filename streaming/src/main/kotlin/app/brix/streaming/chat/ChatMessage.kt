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
    /** Текст, разбитый на куски «слова / эмодзи». [text] остаётся рядом как
     *  плоская версия — эмодзи там своими именами, так её и читать, и искать. */
    val parts: List<ChatPart> = listOf(ChatPart.Text(text)),
)

sealed class ChatPart {
    data class Text(val value: String) : ChatPart()

    /**
     * Картинка в строке. [aspect] — ширина к высоте; у широких эмодзи 7TV
     * бывает 3:1, и квадратное место сплющило бы их. Где площадка размер не
     * сообщает (Twitch, Kick, BTTV), остаётся квадрат.
     */
    data class Emote(val name: String, val url: String, val aspect: Float = 1f) : ChatPart()
}
