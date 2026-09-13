package app.brix.streaming.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class VkMessagesResult {
    data class Success(val messages: List<VkChatMessage>) : VkMessagesResult()
    data object Unauthorized : VkMessagesResult()
    data object Failure : VkMessagesResult()
}

/** Behind interfaces for the same reason [ChatSocket] is: [VkChatClient] gets
 *  driven by fakes in tests, no real network, no waiting on VK's servers. */
fun interface VkTokenProvider {
    suspend fun fetchToken(clientId: String, clientSecret: String): String?
}

fun interface VkMessagesFetcher {
    suspend fun fetchMessages(accessToken: String, channelUrl: String, limit: Int): VkMessagesResult
}

private const val VK_API_BASE = "https://api.live.vkvideo.ru"

/**
 * ClientCredentials — "the only allowed way to call DevAPI from a browser or
 * app on the app's own behalf, without a user login" per VK's own docs
 * (dev.live.vkvideo.ru/docs/main/authorization). No per-user OAuth redirect;
 * just the app's own client_id/secret, registered once on that portal.
 */
internal class OkHttpVkTokenProvider(
    private val client: OkHttpClient = OkHttpClient(),
) : VkTokenProvider {
    override suspend fun fetchToken(clientId: String, clientSecret: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .build()
                val request = Request.Builder()
                    .url("$VK_API_BASE/oauth/server/token")
                    .header("Authorization", Credentials.basic(clientId, clientSecret))
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.let(::parseVkAccessToken)
                }
            }.getOrNull()
        }
}

internal class OkHttpVkMessagesFetcher(
    private val client: OkHttpClient = OkHttpClient(),
) : VkMessagesFetcher {
    override suspend fun fetchMessages(
        accessToken: String,
        channelUrl: String,
        limit: Int,
    ): VkMessagesResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$VK_API_BASE/v1/chat/messages"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("channel_url", channelUrl)
                .addQueryParameter("limit", limit.toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> VkMessagesResult.Unauthorized
                    !response.isSuccessful -> VkMessagesResult.Failure
                    else -> {
                        val body = response.body?.string()
                        val messages = body?.let(::parseVkMessages)
                        if (messages != null) VkMessagesResult.Success(messages) else VkMessagesResult.Failure
                    }
                }
            }
        }.getOrElse { VkMessagesResult.Failure }
    }
}
