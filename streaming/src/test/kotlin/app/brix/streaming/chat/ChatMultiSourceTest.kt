package app.brix.streaming.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun msg(id: String, platform: ChatPlatform, t: Long) =
    ChatMessage(id = id, platform = platform, author = "a", colorHex = null, text = id, timestampMs = t)

class ChatMultiSourceTest {

    @Test
    fun `сообщения нескольких источников сортируются по времени`() = runTest {
        val twitch = MutableStateFlow(listOf(msg("t1", ChatPlatform.TWITCH, 100)))
        val kick = MutableStateFlow(listOf(msg("k1", ChatPlatform.KICK, 50)))
        val merged = mergeChatMessages(listOf(twitch, kick), maxMessages = 250).first()

        assertEquals(listOf("k1", "t1"), merged.map { it.id })
    }

    @Test
    fun `хвост общего фида тоже ограничен`() = runTest {
        val many = (1..300).map { msg("m$it", ChatPlatform.TWITCH, it.toLong()) }
        val source = MutableStateFlow(many)
        val merged = mergeChatMessages(listOf(source), maxMessages = 250).first()

        assertEquals(250, merged.size)
        assertEquals("m300", merged.last().id)
    }

    @Test
    fun `пустой список источников не падает`() = runTest {
        assertEquals(emptyList<ChatMessage>(), mergeChatMessages(emptyList(), 250).first())
        assertEquals(false, mergeConnected(emptyList()).first())
    }

    @Test
    fun `connected — true если хотя бы один источник на связи`() = runTest {
        val a = MutableStateFlow(false)
        val b = MutableStateFlow(true)
        assertTrue(mergeConnected(listOf(a, b)).first())
    }

    @Test
    fun `connected — false если все отключены`() = runTest {
        val a = MutableStateFlow(false)
        val b = MutableStateFlow(false)
        assertEquals(false, mergeConnected(listOf(a, b)).first())
    }
}
