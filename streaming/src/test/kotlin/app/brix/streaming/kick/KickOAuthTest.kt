package app.brix.streaming.kick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KickOAuthTest {

    @Test
    fun `PKCE S256 совпадает с примером из RFC 7636`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            KickOAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `ссылка авторизации несёт PKCE и права`() {
        val pkce = KickOAuth.newPkce()
        val url = KickOAuth.authorizeUrl("cid", "http://127.0.0.1:8765/cb", listOf("user:read", "streamkey:read"), pkce, "st")
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(pkce.challenge, url.queryParameter("code_challenge"))
        assertEquals("user:read streamkey:read", url.queryParameter("scope"))
        assertTrue(pkce.verifier.length >= 43)
    }
}
