package app.brix.streaming.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

const val TWITCH_CLIENT_ID = ""

const val TWITCH_RTMP_INGEST = "rtmp://live.twitch.tv/app"

data class HttpReply(val code: Int, val body: String)

fun interface TwitchHttp {
    suspend fun send(request: Request): HttpReply?
}

internal class OkHttpTwitchHttp(private val client: OkHttpClient = OkHttpClient()) : TwitchHttp {
    override suspend fun send(request: Request): HttpReply? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request).execute().use { HttpReply(it.code, it.body?.string().orEmpty()) }
        }.getOrNull()
    }
}

@Serializable
data class DeviceCode(
    val device_code: String,
    val user_code: String,
    val verification_uri: String,
    val expires_in: Int,
    val interval: Int = 5,
)

@Serializable
data class TwitchTokens(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long = 0,
    val scope: List<String> = emptyList(),
)

@Serializable
private data class ValidateReply(val login: String = "", val user_id: String = "", val scopes: List<String> = emptyList())

@Serializable
private data class ErrorReply(val message: String = "")

@Serializable
private data class StreamsReply(val data: List<StreamDto> = emptyList())

@Serializable
private data class StreamDto(val viewer_count: Int = 0)

@Serializable
private data class KeyReply(val data: List<KeyDto> = emptyList())

@Serializable
private data class KeyDto(val stream_key: String = "")

data class TwitchUser(val userId: String, val login: String, val scopes: List<String>)

sealed class PollResult {
    data class Done(val tokens: TwitchTokens) : PollResult()
    data object Pending : PollResult()
    data object SlowDown : PollResult()
    data object Expired : PollResult()
    data class Failed(val message: String) : PollResult()
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data object Unauthorized : ApiResult<Nothing>()
    data object Failed : ApiResult<Nothing>()
}

class TwitchApi(
    private val clientId: String = TWITCH_CLIENT_ID,
    private val http: TwitchHttp = OkHttpTwitchHttp(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    val configured: Boolean get() = clientId.isNotBlank()

    suspend fun startDeviceLogin(scopes: List<String>): DeviceCode? {
        val form = FormBody.Builder().add("client_id", clientId)
        if (scopes.isNotEmpty()) form.add("scopes", scopes.joinToString(" "))
        val reply = http.send(Request.Builder().url("$ID/device").post(form.build()).build()) ?: return null
        if (reply.code != 200) return null
        return runCatching { json.decodeFromString<DeviceCode>(reply.body) }.getOrNull()
    }

    suspend fun pollDeviceLogin(deviceCode: String, scopes: List<String>): PollResult {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        if (scopes.isNotEmpty()) form.add("scopes", scopes.joinToString(" "))
        val reply = http.send(Request.Builder().url("$ID/token").post(form.build()).build())
            ?: return PollResult.Failed("network")
        if (reply.code == 200) {
            return runCatching { PollResult.Done(json.decodeFromString<TwitchTokens>(reply.body)) }
                .getOrElse { PollResult.Failed("bad reply") }
        }
        val message = runCatching { json.decodeFromString<ErrorReply>(reply.body).message }.getOrDefault("")
        return when (message) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "expired_token", "invalid device code" -> PollResult.Expired
            else -> PollResult.Failed(message.ifBlank { "HTTP ${reply.code}" })
        }
    }

    suspend fun refresh(refreshToken: String): ApiResult<TwitchTokens> {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val reply = http.send(Request.Builder().url("$ID/token").post(form).build()) ?: return ApiResult.Failed
        return when (reply.code) {
            200 -> runCatching { ApiResult.Ok(json.decodeFromString<TwitchTokens>(reply.body)) }
                .getOrDefault(ApiResult.Failed)
            400, 401 -> ApiResult.Unauthorized
            else -> ApiResult.Failed
        }
    }

    suspend fun validate(accessToken: String): ApiResult<TwitchUser> {
        val request = Request.Builder().url("$ID/validate").header("Authorization", "OAuth $accessToken").build()
        val reply = http.send(request) ?: return ApiResult.Failed
        return when (reply.code) {
            200 -> runCatching {
                val v = json.decodeFromString<ValidateReply>(reply.body)
                ApiResult.Ok(TwitchUser(v.user_id, v.login, v.scopes))
            }.getOrDefault(ApiResult.Failed)
            401 -> ApiResult.Unauthorized
            else -> ApiResult.Failed
        }
    }

    suspend fun revoke(accessToken: String) {
        val form = FormBody.Builder().add("client_id", clientId).add("token", accessToken).build()
        http.send(Request.Builder().url("$ID/revoke").post(form).build())
    }

    suspend fun viewerCount(accessToken: String, userId: String): ApiResult<Int?> =
        helix(accessToken, "streams?user_id=$userId") {
            json.decodeFromString<StreamsReply>(it).data.firstOrNull()?.viewer_count
        }

    suspend fun streamKey(accessToken: String, userId: String): ApiResult<String> =
        helix(accessToken, "streams/key?broadcaster_id=$userId") {
            json.decodeFromString<KeyReply>(it).data.first().stream_key
        }

    private suspend fun <T> helix(accessToken: String, path: String, parse: (String) -> T): ApiResult<T> {
        val request = Request.Builder()
            .url("$HELIX/$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Id", clientId)
            .build()
        val reply = http.send(request) ?: return ApiResult.Failed
        return when (reply.code) {
            200 -> runCatching { ApiResult.Ok(parse(reply.body)) }.getOrDefault(ApiResult.Failed)
            401 -> ApiResult.Unauthorized
            else -> ApiResult.Failed
        }
    }

    private companion object {
        const val ID = "https://id.twitch.tv/oauth2"
        const val HELIX = "https://api.twitch.tv/helix"
    }
}
