package app.brix.streaming.chat

/**
 * Разбор эмодзи в куски сообщения — чистые функции, без сети.
 *
 * Картинки берём НЕподвижные (static-варианты у Twitch и 7TV, первый кадр у
 * остальных): чат рисуется на телефоне, который одновременно ведёт эфир и
 * греется, а анимация десятков эмодзи — это декодирование кадров на каждом
 * обновлении экрана. Подход подсмотрен у Moblin (`Integrations/Emotes`), у него
 * тоже есть «неподвижный» адрес на каждую картинку.
 */
object EmoteParts {

    /**
     * Twitch присылает эмодзи в теге `emotes`: `25:0-4,12-16/1902:6-10`.
     * Позиции — в **кодовых точках** Unicode, не в символах Kotlin: эмодзи-смайл
     * из двух суррогатов раньше в строке сдвинул бы все позиции после себя.
     */
    fun twitch(text: String, emotesTag: String?): List<ChatPart> {
        if (emotesTag.isNullOrBlank()) return listOf(ChatPart.Text(text))
        val codePoints = text.codePoints().toArray()
        val ranges = mutableListOf<Triple<Int, Int, String>>()
        for (definition in emotesTag.split('/')) {
            val id = definition.substringBefore(':', "")
            if (id.isEmpty()) continue
            for (range in definition.substringAfter(':').split(',')) {
                val start = range.substringBefore('-').toIntOrNull() ?: continue
                val end = range.substringAfter('-').toIntOrNull() ?: continue
                if (start < 0 || end < start || end >= codePoints.size) continue
                ranges += Triple(start, end, id)
            }
        }
        if (ranges.isEmpty()) return listOf(ChatPart.Text(text))
        ranges.sortBy { it.first }
        fun slice(from: Int, toExclusive: Int) = String(codePoints, from, toExclusive - from)
        val parts = mutableListOf<ChatPart>()
        var cursor = 0
        for ((start, end, id) in ranges) {
            if (start < cursor) continue // перекрытие — битый тег, берём первое
            if (start > cursor) parts += ChatPart.Text(slice(cursor, start))
            parts += ChatPart.Emote(
                name = slice(start, end + 1),
                url = "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/2.0",
            )
            cursor = end + 1
        }
        if (cursor < codePoints.size) parts += ChatPart.Text(slice(cursor, codePoints.size))
        return parts
    }

    private val kickEmote = Regex("""\[emote:(\d+):([^]]+)]""")

    /** Kick вставляет эмодзи прямо в текст: `[emote:37226:KEKW]`. */
    fun kick(content: String): List<ChatPart> {
        val parts = mutableListOf<ChatPart>()
        var cursor = 0
        for (match in kickEmote.findAll(content)) {
            if (match.range.first > cursor) parts += ChatPart.Text(content.substring(cursor, match.range.first))
            parts += ChatPart.Emote(
                name = match.groupValues[2],
                url = "https://files.kick.com/emotes/${match.groupValues[1]}/fullsize",
            )
            cursor = match.range.last + 1
        }
        if (cursor < content.length) parts += ChatPart.Text(content.substring(cursor))
        return parts.ifEmpty { listOf(ChatPart.Text(content)) }
    }

    /** Плоский текст для [ChatMessage.text]: эмодзи — своими именами. */
    fun plain(parts: List<ChatPart>): String = parts.joinToString("") {
        when (it) {
            is ChatPart.Text -> it.value
            is ChatPart.Emote -> it.name
        }
    }

    /**
     * Эмодзи 7TV и BTTV: у них нет разметки в сообщении, это просто слова,
     * совпавшие с именем из набора канала. Режем только текстовые куски и
     * только по пробелам — пробелы сохраняем, чтобы строка не слиплась.
     */
    fun applyWordEmotes(parts: List<ChatPart>, emotes: Map<String, ChatPart.Emote>): List<ChatPart> {
        if (emotes.isEmpty()) return parts
        val out = mutableListOf<ChatPart>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) {
                out += ChatPart.Text(pending.toString())
                pending.clear()
            }
        }
        for (part in parts) {
            if (part !is ChatPart.Text) {
                flush()
                out += part
                continue
            }
            for (token in splitKeepingSpaces(part.value)) {
                val emote = if (token.isBlank()) null else emotes[token]
                if (emote == null) {
                    pending.append(token)
                } else {
                    flush()
                    out += emote
                }
            }
        }
        flush()
        return out
    }

    private fun splitKeepingSpaces(s: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val space = s[i].isWhitespace()
            var j = i
            while (j < s.length && s[j].isWhitespace() == space) j++
            tokens += s.substring(i, j)
            i = j
        }
        return tokens
    }
}
