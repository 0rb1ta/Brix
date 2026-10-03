package app.brix.streaming.kick

import app.brix.core.KickIntegration
import app.brix.streaming.twitch.HttpReply
import app.brix.streaming.twitch.TwitchHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KickSessionTest {

    private val cfg = KickIntegration(clientId = "cid", clientSecret = "sec", wantStreamKey = true)

    @Test
    fun `возврат из браузера — обмен кода с секретом и PKCE, затем канал`() = runTest {
        var stored = cfg
        val callback = CompletableDeferred<OAuthCallback?>()
        val bodies = mutableListOf<String>()
        val http = TwitchHttp { r ->
            when (r.url.toString()) {
                KickOAuth.TOKEN -> {
                    bodies += Buffer().also { r.body!!.writeTo(it) }.readUtf8()
                    HttpReply(200, """{"access_token":"a","refresh_token":"r","expires_in":3600,"scope":"user:read channel:read streamkey:read"}""")
                }
                KickOAuth.CHANNELS -> HttpReply(
                    200,
                    """{"data":[{"broadcaster_user_id":676,"slug":"me","stream":{"url":"rtmps://x/app/","key":"k"}}]}""",
                )
                else -> null
            }
        }
        val session = KickSession(this, { stored }, { stored = it }, http, now = { 0L }, receive = { callback.await() })
        session.startLogin("ok")
        advanceUntilIdle()
        val url = (session.login.value as KickLoginState.WaitingForBrowser).url.toHttpUrl()
        callback.complete(OAuthCallback("code1", url.queryParameter("state"), null))
        advanceUntilIdle()

        assertTrue(stored.loggedIn)
        assertEquals("me", stored.login)
        assertEquals("676", stored.userId)
        val body = bodies.single()
        assertTrue(body.contains("client_secret=sec"))
        assertTrue(body.contains("code_verifier="))
        assertTrue(body.contains("code=code1"))
        assertEquals(KickStreamTarget("rtmps://x/app/", "k"), session.streamTarget())
    }

    @Test
    fun `чужой state — вход отклоняется`() = runTest {
        var stored = cfg
        val session = KickSession(
            this, { stored }, { stored = it },
            http = { null }, now = { 0L },
            receive = { OAuthCallback("code", "forged", null) },
        )
        session.startLogin("ok")
        advanceUntilIdle()
        assertFalse(stored.loggedIn)
        assertEquals(KickLoginState.Error("state"), session.login.value)
    }

    @Test
    fun `разбор строки запроса на локальный адрес`() {
        assertEquals(
            OAuthCallback("abc", "s1", null),
            LoopbackReceiver.parseRequestLine("GET /callback?code=abc&state=s1 HTTP/1.1"),
        )
        assertNull(LoopbackReceiver.parseRequestLine("GET /favicon.ico HTTP/1.1"))
        assertNull(LoopbackReceiver.parseRequestLine("POST /callback HTTP/1.1"))
        // Аудит 23.09: голый заход на /callback не должен завершать приём.
        assertNull(LoopbackReceiver.parseRequestLine("GET /callback HTTP/1.1"))
        assertEquals(
            OAuthCallback(null, "s1", "access_denied"),
            LoopbackReceiver.parseRequestLine("GET /callback?error=access_denied&state=s1 HTTP/1.1"),
        )
    }
}
