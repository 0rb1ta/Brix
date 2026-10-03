package app.brix.streaming.twitch

import app.brix.core.TwitchIntegration
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchSessionTest {

    private fun body(r: Request): String = Buffer().also { r.body?.writeTo(it) }.readUtf8()

    private val loggedIn = TwitchIntegration(
        accessToken = "old",
        refreshToken = "r1",
        expiresAtMs = 0,
        userId = "42",
        login = "me",
    )

    @Test
    fun `истёкший токен обновляется до запроса, новая пара сохраняется сразу`() = runTest {
        var stored = loggedIn
        val saved = mutableListOf<TwitchIntegration>()
        val http = TwitchHttp { r ->
            when {
                r.url.encodedPath.endsWith("/token") -> {
                    assertTrue(body(r).contains("refresh_token=r1"))
                    HttpReply(200, """{"access_token":"new","refresh_token":"r2","expires_in":14400}""")
                }
                r.url.encodedPath.endsWith("/streams") -> {
                    assertEquals("Bearer new", r.header("Authorization"))
                    HttpReply(200, """{"data":[{"viewer_count":7}]}""")
                }
                else -> null
            }
        }
        val api = TwitchApi("cid", http)
        val session = TwitchSession(this, { stored }, { stored = it; saved += it }, api, now = { 1_000L })
        val result = session.withToken { t, id -> api.viewerCount(t, id) }
        assertEquals(ApiResult.Ok(7), result)
        assertEquals("r2", stored.refreshToken)
        assertEquals(1, saved.size)
    }

    @Test
    fun `отозванный токен обновления — выход, желаемые права сохраняются`() = runTest {
        var stored = loggedIn.copy(wantStreamKey = true)
        val http = TwitchHttp { HttpReply(400, """{"message":"Invalid refresh token"}""") }
        val session = TwitchSession(this, { stored }, { stored = it }, TwitchApi("cid", http), now = { 1_000L })
        assertNull(session.streamKey())
        assertFalse(stored.loggedIn)
        assertTrue(stored.wantStreamKey)
    }

    @Test
    fun `нужен ли повторный вход — по выданным правам`() {
        val t = loggedIn.copy(grantedScopes = listOf(TwitchIntegration.SCOPE_FOLLOWERS), wantFollows = true)
        assertFalse(t.needsRelogin())
        assertTrue(t.copy(wantStreamKey = true).needsRelogin())
    }

    @Test
    fun `без ключа приложения вход недоступен`() {
        assertFalse(TwitchApi("").configured)
    }
}
