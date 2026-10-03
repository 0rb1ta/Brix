package app.brix.streaming.twitch

import app.brix.core.TwitchIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class TwitchLoginState {
    data object Idle : TwitchLoginState()
    data object Starting : TwitchLoginState()
    data class WaitingForUser(val userCode: String, val verificationUri: String) : TwitchLoginState()
    data class Error(val message: String) : TwitchLoginState()
}

class TwitchSession(
    private val scope: CoroutineScope,
    private val read: () -> TwitchIntegration,
    private val write: (TwitchIntegration) -> Unit,
    private val api: TwitchApi = TwitchApi(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Mutex()
    private var loginJob: Job? = null
    private var viewersJob: Job? = null

    private val _login = MutableStateFlow<TwitchLoginState>(TwitchLoginState.Idle)
    val login: StateFlow<TwitchLoginState> = _login

    private val _viewers = MutableStateFlow<Int?>(null)
    val viewers: StateFlow<Int?> = _viewers

    val configured: Boolean get() = api.configured

    fun startLogin() {
        loginJob?.cancel()
        loginJob = scope.launch {
            _login.value = TwitchLoginState.Starting
            val scopes = read().wantedScopes()
            val code = api.startDeviceLogin(scopes)
            if (code == null) {
                _login.value = TwitchLoginState.Error("device")
                return@launch
            }
            _login.value = TwitchLoginState.WaitingForUser(code.user_code, code.verification_uri)
            val deadline = now() + code.expires_in * 1000L
            var interval = code.interval.coerceAtLeast(1) * 1000L
            while (isActive && now() < deadline) {
                delay(interval)
                when (val r = api.pollDeviceLogin(code.device_code, scopes)) {
                    PollResult.Pending -> Unit
                    PollResult.SlowDown -> interval += 5000
                    PollResult.Expired -> break
                    is PollResult.Failed -> {
                        _login.value = TwitchLoginState.Error(r.message)
                        return@launch
                    }
                    is PollResult.Done -> {
                        finishLogin(r.tokens)
                        return@launch
                    }
                }
            }
            _login.value = TwitchLoginState.Error("expired")
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _login.value = TwitchLoginState.Idle
    }

    private suspend fun finishLogin(tokens: TwitchTokens) {
        when (val user = api.validate(tokens.access_token)) {
            is ApiResult.Ok -> {
                write(
                    read().copy(
                        accessToken = tokens.access_token,
                        refreshToken = tokens.refresh_token,
                        expiresAtMs = now() + tokens.expires_in * 1000,
                        userId = user.value.userId,
                        login = user.value.login,
                        grantedScopes = user.value.scopes,
                    ),
                )
                _login.value = TwitchLoginState.Idle
            }
            else -> _login.value = TwitchLoginState.Error("validate")
        }
    }

    fun logout() {
        val current = read()
        write(current.loggedOut())
        _viewers.value = null
        if (current.accessToken.isNotBlank()) scope.launch { api.revoke(current.accessToken) }
    }

    suspend fun <T> withToken(call: suspend (token: String, userId: String) -> ApiResult<T>): ApiResult<T> {
        val state = read()
        if (!state.loggedIn || !api.configured) return ApiResult.Unauthorized
        val token = if (state.expiresAtMs - now() < 60_000) refresh(state.refreshToken) else state.accessToken
        token ?: return ApiResult.Unauthorized
        val first = call(token, state.userId)
        if (first !is ApiResult.Unauthorized) return first
        val renewed = refresh(state.refreshToken) ?: return ApiResult.Unauthorized
        return call(renewed, read().userId)
    }

    private suspend fun refresh(usedToken: String): String? = refreshLock.withLock {
        val current = read()
        if (!current.loggedIn) return null
        if (current.refreshToken != usedToken && current.expiresAtMs - now() > 60_000) return current.accessToken
        when (val r = api.refresh(current.refreshToken)) {
            is ApiResult.Ok -> {
                write(
                    current.copy(
                        accessToken = r.value.access_token,
                        refreshToken = r.value.refresh_token,
                        expiresAtMs = now() + r.value.expires_in * 1000,
                    ),
                )
                r.value.access_token
            }
            ApiResult.Unauthorized -> {
                write(current.loggedOut())
                null
            }
            ApiResult.Failed -> null
        }
    }

    suspend fun validateNow() {
        if (!read().loggedIn) return
        val result = withToken { token, _ -> api.validate(token) }
        if (result is ApiResult.Ok) {
            val user = result.value
            val current = read()
            if (user.login != current.login || user.scopes != current.grantedScopes) {
                write(current.copy(login = user.login, grantedScopes = user.scopes))
            }
        }
    }

    fun startBackground(pollViewers: () -> Boolean) {
        viewersJob?.cancel()
        viewersJob = scope.launch {
            var lastValidate = 0L
            while (isActive) {
                if (read().loggedIn) {
                    if (now() - lastValidate >= 3_600_000) {
                        validateNow()
                        lastValidate = now()
                    }
                    _viewers.value = if (pollViewers()) {
                        (withToken { token, id -> api.viewerCount(token, id) } as? ApiResult.Ok)?.value
                    } else {
                        null
                    }
                } else {
                    _viewers.value = null
                }
                delay(60_000)
            }
        }
    }

    suspend fun streamKey(): String? =
        (withToken { token, id -> api.streamKey(token, id) } as? ApiResult.Ok)?.value
}
