package app.brix.streaming.kick

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody

object KickOAuth {
    const val AUTHORIZE = "https://id.kick.com/oauth/authorize"
    const val TOKEN = "https://id.kick.com/oauth/token"
    const val REVOKE = "https://id.kick.com/oauth/revoke"
    const val CHANNELS = "https://api.kick.com/public/v1/channels"

    data class Pkce(val verifier: String, val challenge: String)

    fun newPkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifier = randomToken(random)
        return Pkce(verifier, challengeFor(verifier))
    }

    fun randomToken(random: SecureRandom = SecureRandom()): String =
        base64Url(ByteArray(32).also(random::nextBytes))

    fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(clientId: String, redirectUri: String, scopes: List<String>, pkce: Pkce, state: String): HttpUrl =
        AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scopes.joinToString(" "))
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()

    fun exchangeBody(clientId: String, clientSecret: String, redirectUri: String, code: String, verifier: String): RequestBody =
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .add("code", code)
            .build()

    fun refreshBody(clientId: String, clientSecret: String, refreshToken: String): RequestBody =
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .build()

    fun revokeBody(token: String): RequestBody =
        FormBody.Builder().add("token", token).add("token_hint_type", "access_token").build()

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
