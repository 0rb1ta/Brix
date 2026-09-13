package app.brix.streaming.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shapes below match the official schema examples at
 *  dev.live.vkvideo.ru/docs/method/chat ("Получение сообщений из чата"). */
class VkChatParsingTest {

    @Test
    fun `сообщение с текстовой частью`() {
        val body = """
            {"data":{"chat_messages":[{"id":42,"created_at":1700000000,
              "author":{"nick":"foo","nick_color":16711680},
              "parts":[{"text":{"content":"hi there"}}]}]}}
        """.trimIndent()
        val messages = parseVkMessages(body)
        assertEquals(1, messages?.size)
        val m = messages!!.single()
        assertEquals(42L, m.id)
        assertEquals("foo", m.author)
        assertEquals("#FF0000", m.colorHex)
        assertEquals("hi there", m.text)
        assertEquals(1700000000L, m.createdAtSec)
    }

    @Test
    fun `несколько частей склеиваются — текст, упоминание, смайл, ссылка`() {
        val body = """
            {"data":{"chat_messages":[{"id":1,"created_at":0,
              "author":{"nick":"u"},
              "parts":[
                {"text":{"content":"hey "}},
                {"mention":{"id":2,"nick":"bar"}},
                {"text":{"content":" "}},
                {"smile":{"id":"s1","name":"kappa"}},
                {"link":{"url":"https://example.com"}}
              ]}]}}
        """.trimIndent()
        val m = parseVkMessages(body)!!.single()
        assertEquals("hey @bar :kappa:https://example.com", m.text)
    }

    @Test
    fun `nick_color отсутствует — colorHex null`() {
        val body = """{"data":{"chat_messages":[{"id":1,"created_at":0,"author":{"nick":"u"},"parts":[]}]}}"""
        val m = parseVkMessages(body)!!.single()
        assertNull(m.colorHex)
    }

    @Test
    fun `ответ с ошибкой авторизации — не список сообщений, но и не падение`() {
        val body = """{"error":"unauthorized","error_description":"Not authorized"}"""
        assertNull(parseVkMessages(body))
    }

    @Test
    fun `мусорный JSON не роняет парсер`() {
        assertNull(parseVkMessages(""))
        assertNull(parseVkMessages("not json"))
        assertNull(parseVkMessages("{}"))
    }

    @Test
    fun `токен приложения разбирается`() {
        assertEquals("abc123", parseVkAccessToken("""{"access_token":"abc123","expire_time":3600,"token_type":"Bearer"}"""))
        assertNull(parseVkAccessToken("""{"error":"unauthorized"}"""))
        assertNull(parseVkAccessToken("not json"))
    }

    @Test
    fun `vkColorToHex маскирует до 24 бит`() {
        assertEquals("#FF0000", vkColorToHex(16711680L))
        assertEquals("#000000", vkColorToHex(0L))
        assertEquals("#FFFFFF", vkColorToHex(0xFFFFFFL))
    }

    @Test
    fun `normalizeChannelUrl принимает и slug, и полный адрес`() {
        assertEquals("https://live.vkvideo.ru/somechannel", normalizeChannelUrl("somechannel"))
        assertEquals("https://live.vkvideo.ru/somechannel", normalizeChannelUrl("/somechannel"))
        assertEquals("https://live.vkvideo.ru/somechannel", normalizeChannelUrl("https://live.vkvideo.ru/somechannel"))
        assertEquals("", normalizeChannelUrl("  "))
    }
}
