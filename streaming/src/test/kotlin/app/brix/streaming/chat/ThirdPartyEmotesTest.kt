package app.brix.streaming.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ответы сокращены из настоящих (7tv.io, api.betterttv.net, 16.09). */
@OptIn(ExperimentalCoroutinesApi::class)
class ThirdPartyEmotesTest {

    private val seventvUser = """
        {"username":"xqc","emote_set":{"emotes":[{"name":"GAMBA","data":{"animated":true,"host":{
        "url":"//cdn.7tv.app/emote/01G3","files":[
        {"name":"1x.webp","static_name":"1x_static.webp","width":39,"height":32},
        {"name":"2x.webp","static_name":"2x_static.webp","width":78,"height":64}]}}}]}}
    """.trimIndent()

    @Test
    fun `7TV — неподвижная 2x и пропорции`() {
        val e = ThirdPartyEmoteParser.seventvUser(seventvUser).getValue("GAMBA")
        assertEquals("https://cdn.7tv.app/emote/01G3/2x_static.webp", e.url)
        assertEquals(78f / 64f, e.aspect, 0.001f)
    }

    @Test
    fun `BTTV — канальные перекрывают общие`() {
        val body = """{"channelEmotes":[{"id":"c","code":"X"}],"sharedEmotes":[{"id":"s","code":"X"},{"id":"t","code":"Y"}]}"""
        val map = ThirdPartyEmoteParser.bttvUser(body)
        assertEquals("https://cdn.betterttv.net/emote/c/2x", map.getValue("X").url)
        assertEquals("https://cdn.betterttv.net/emote/t/2x", map.getValue("Y").url)
    }

    @Test
    fun `Kick — запрос идёт с номером пользователя, 404 не ошибка`() = runTest {
        val asked = mutableListOf<String>()
        val http = EmoteHttp { url ->
            asked += url
            when {
                url.endsWith("/users/kick/676") && url.contains("7tv") -> seventvUser
                url.contains("emote-sets/global") -> """{"emotes":[]}"""
                url.contains("emotes/global") -> "[]"
                else -> "" // BTTV: канала нет
            }
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val emotes = ThirdPartyEmotes(scope, http)
        emotes.load(EmotePlatform.KICK, "676")
        scope.advanceUntilIdle()
        assertEquals(setOf("GAMBA"), emotes.emotes.value.keys)
        assert(asked.contains("https://7tv.io/v3/users/kick/676"))
        assertEquals(4, asked.size) // без повторов: 404 — не сбой
    }
}
