package app.brix.streaming.kick

import app.brix.core.KickIntegration
import app.brix.streaming.twitch.ApiResult
import app.brix.streaming.twitch.OkHttpTwitchHttp
import app.brix.streaming.twitch.TwitchHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

sealed class KickLoginState {
    data object Idle : KickLoginState()
    data class WaitingForBrowser(val url: String) : KickLoginState()
    data class Error(val message: String) : KickLoginState()
}

@Serializable
data class KickTokens(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long = 0,
    val scope: String = "",
)

@Serializable
private data class ChannelsReply(val data: List<ChannelDto> = emptyList())

@Serializable
private data class ChannelDto(
    val broadcaster_user_id: Long = 0,
    val slug: String = "",
    val stream: StreamDto? = null,
)

@Serializable
private data class StreamDto(val url: String = "", val key: String = "")

data class KickStreamTarget(val url: String, val key: String)

class KickSession(
    private val scope: CoroutineScope,
    private val read: () -> KickIntegration,
    private val write: (KickIntegration) -> Unit,
    private val http: TwitchHttp = OkHttpTwitchHttp(),
    private val now: () -> Long = System::currentTimeMillis,
    private val receive: suspend (successHtml: String) -> OAuthCallback? = {
        LoopbackReceiver.awaitCallback(KickIntegration.REDIRECT_PORT, 300_000, it)
    },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    private var loginJob: Job? = null

    private val _login = MutableStateFlow<KickLoginState>(KickLoginState.Idle)
    val login: StateFlow<KickLoginState> = _login

    fun startLogin(successHtml: String) {
        val cfg = read()
        if (!cfg.configured) return
        loginJob?.cancel()
        loginJob = scope.launch {
            val pkce = KickOAuth.newPkce()
            val state = KickOAuth.randomToken()
            val scopes = cfg.wantedScopes()
            val url = KickOAuth.authorizeUrl(cfg.clientId, KickIntegration.REDIRECT_URI, scopes, pkce, state)
            _login.value = KickLoginState.WaitingForBrowser(url.toString())
            val cb = runCatching { receive(successHtml) }.getOrNull()
            when {
                cb == null -> _login.value = KickLoginState.Error("timeout")
                cb.error != null -> _login.value = KickLoginState.Error(cb.error)
                cb.state != state || cb.code == null -> _login.value = KickLoginState.Error("state")
                else -> exchange(cb.code, pkce.verifier, scopes)
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _login.value = KickLoginState.Idle
    }

    private suspend fun exchange(code: String, verifier: String, requested: List<String>) {
        val cfg = read()
        val body = KickOAuth.exchangeBody(cfg.clientId, cfg.clientSecret, KickIntegration.REDIRECT_URI, code, verifier)
        val reply = http.send(Request.Builder().url(KickOAuth.TOKEN).post(body).build())
        val tokens = reply?.takeIf { it.code == 200 }
            ?.let { runCatching { json.decodeFromString<KickTokens>(it.body) }.getOrNull() }
        if (tokens == null) {
            _login.value = KickLoginState.Error("token ${reply?.code ?: "network"}")
            return
        }
        val granted = tokens.scope.split(' ').filter { it.isNotBlank() }.ifEmpty { requested }
        var updated = read().copy(
            accessToken = tokens.access_token,
            refreshToken = tokens.refresh_token,
            expiresAtMs = now() + tokens.expires_in * 1000,
            grantedScopes = granted,
        )
        val me = channel(tokens.access_token)
        if (me is ApiResult.Ok) updated = updated.copy(userId = me.value.broadcaster_user_id.toString(), login = me.value.slug)
        write(updated)
        _login.value = KickLoginState.Idle
    }

    fun logout() {
        val current = read()
        write(current.loggedOut())
        if (current.accessToken.isNotBlank()) {
            scope.launch {
                http.send(
                    Request.Builder().url(KickOAuth.REVOKE).post(KickOAuth.revokeBody(current.accessToken)).build(),
                )
            }
        }
    }

    suspend fun streamTarget(): KickStreamTarget? {
        val result = withToken { channel(it) }
        val stream = (result as? ApiResult.Ok)?.value?.stream ?: return null
        if (stream.url.isBlank() || stream.key.isBlank()) return null
        return KickStreamTarget(stream.url, stream.key)
    }

    private suspend fun channel(token: String): ApiResult<ChannelDto> {
        val reply = http.send(
            Request.Builder().url(KickOAuth.CHANNELS).header("Authorization", "Bearer $token").build(),
        ) ?: return ApiResult.Failed
        return when (reply.code) {
            200 -> runCatching { json.decodeFromString<ChannelsReply>(reply.body).data.first() }
                .map { ApiResult.Ok(it) as ApiResult<ChannelDto> }
                .getOrDefault(ApiResult.Failed)
            401 -> ApiResult.Unauthorized
            else -> ApiResult.Failed
        }
    }

    private suspend fun <T> withToken(call: suspend (String) -> ApiResult<T>): ApiResult<T> {
        val state = read()
        if (!state.loggedIn || !state.configured) return ApiResult.Unauthorized
        val token = if (state.expiresAtMs - now() < 60_000) refresh(state.refreshToken) else state.accessToken
        token ?: return ApiResult.Unauthorized
        val first = call(token)
        if (first !is ApiResult.Unauthorized) return first
        val renewed = refresh(state.refreshToken) ?: return ApiResult.Unauthorized
        return call(renewed)
    }

    private suspend fun refresh(usedToken: String): String? = refreshLock.withLock {
        val current = read()
        if (!current.loggedIn) return null
        if (current.refreshToken != usedToken && current.expiresAtMs - now() > 60_000) return current.accessToken
        val body = KickOAuth.refreshBody(current.clientId, current.clientSecret, current.refreshToken)
        val reply = http.send(Request.Builder().url(KickOAuth.TOKEN).post(body).build()) ?: return null
        when (reply.code) {
            200 -> {
                val t = runCatching { json.decodeFromString<KickTokens>(reply.body) }.getOrNull() ?: return null
                write(
                    current.copy(
                        accessToken = t.access_token,
                        refreshToken = t.refresh_token,
                        expiresAtMs = now() + t.expires_in * 1000,
                    ),
                )
                t.access_token
            }
            400, 401 -> {
                write(current.loggedOut())
                null
            }
            else -> null
        }
    }
}
