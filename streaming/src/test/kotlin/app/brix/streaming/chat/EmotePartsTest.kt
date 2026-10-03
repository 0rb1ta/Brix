package app.brix.streaming.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotePartsTest {

    private fun emote(name: String, url: String) = ChatPart.Emote(name, url)
    private fun twitchUrl(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/2.0"

    @Test
    fun `twitch — эмодзи по позициям, повтор одного id`() {
        val parts = EmoteParts.twitch("LUL hi LUL", "25:0-2,7-9")
        assertEquals(
            listOf(emote("LUL", twitchUrl("25")), ChatPart.Text(" hi "), emote("LUL", twitchUrl("25"))),
            parts,
        )
    }

    @Test
    fun `twitch — позиции в кодовых точках, смайл из суррогатов не сдвигает`() {
        // «😀» — два char, но одна кодовая точка: Twitch считает его за одну позицию.
        val parts = EmoteParts.twitch("😀 LUL", "25:2-4")
        assertEquals(listOf(ChatPart.Text("😀 "), emote("LUL", twitchUrl("25"))), parts)
    }

    @Test
    fun `twitch — битый тег оставляет текст как есть`() {
        assertEquals(listOf(ChatPart.Text("hi")), EmoteParts.twitch("hi", "25:0-99"))
        assertEquals(listOf(ChatPart.Text("hi")), EmoteParts.twitch("hi", null))
    }

    @Test
    fun `kick — вставки заменяются картинками, имя сохраняется`() {
        val parts = EmoteParts.kick("gg [emote:37226:KEKW]!")
        assertEquals(
            listOf(
                ChatPart.Text("gg "),
                emote("KEKW", "https://files.kick.com/emotes/37226/fullsize"),
                ChatPart.Text("!"),
            ),
            parts,
        )
        assertEquals("gg KEKW!", EmoteParts.plain(parts))
    }

    @Test
    fun `слова-эмодзи режутся только в тексте и с пробелами`() {
        val set = mapOf("GAMBA" to emote("GAMBA", "u1"))
        val parts = EmoteParts.applyWordEmotes(
            listOf(ChatPart.Text("go  GAMBA now"), emote("x", "u2"), ChatPart.Text("GAMBA")),
            set,
        )
        assertEquals(
            listOf(
                ChatPart.Text("go  "),
                emote("GAMBA", "u1"),
                ChatPart.Text(" now"),
                emote("x", "u2"),
                emote("GAMBA", "u1"),
            ),
            parts,
        )
    }

    @Test
    fun `часть слова — не эмодзи`() {
        val set = mapOf("GG" to emote("GG", "u"))
        assertEquals(listOf(ChatPart.Text("GGWP gg")), EmoteParts.applyWordEmotes(listOf(ChatPart.Text("GGWP gg")), set))
    }
}
