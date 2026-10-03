package app.brix.streaming.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class KickChatroomInfo(
    val chatroomId: String,
    val chatroomChannelId: String,
    /** Номер пользователя-владельца канала — его, а не чатрум, ждёт 7TV. */
    val userId: String? = null,
)

/** Resolves a channel slug (`kick.com/<slug>`) to the numeric ids Pusher
 *  subscriptions need. Behind an interface for the same reason [ChatSocket]
 *  is: [KickChatClient] gets driven by a fake in tests, no real network. */
fun interface KickChannelResolver {
    suspend fun resolve(slug: String): KickChatroomInfo?
}

@Serializable
private data class KickChatroomDto(val id: Long, val channel_id: Long)

@Serializable
private data class KickLivestreamDto(val viewer_count: Int = 0, val is_live: Boolean = false)

@Serializable
private data class KickChannelResponse(
    val chatroom: KickChatroomDto,
    val user_id: Long? = null,
    val livestream: KickLivestreamDto? = null,
)

internal fun parseKickViewers(rawBody: String): Int? =
    runCatching { json.decodeFromString<KickChannelResponse>(rawBody) }
        .getOrNull()
        ?.livestream
        ?.takeIf { it.is_live }
        ?.viewer_count

private val json = Json { ignoreUnknownKeys = true }

/** Pure — no I/O — so the parsing itself is unit-testable without a fake
 *  HTTP layer. */
internal fun parseKickChatroomInfo(rawBody: String): KickChatroomInfo? =
    runCatching { json.decodeFromString<KickChannelResponse>(rawBody) }
        .getOrNull()
        ?.let {
            KickChatroomInfo(
                chatroomId = it.chatroom.id.toString(),
                chatroomChannelId = it.chatroom.channel_id.toString(),
                userId = it.user_id?.toString(),
            )
        }

/**
 * `kick.com/api/v1/channels/<slug>` sits behind Cloudflare, but — verified
 * 04.09 — a normal browser User-Agent is enough; no cookie/challenge dance
 * needed for this specific read-only endpoint (confirmed live with `curl`
 * and cross-checked against Moblin's own iOS client, which sends no special
 * headers here either).
 */
class OkHttpKickChannelResolver(
    private val client: OkHttpClient = OkHttpClient(),
) : KickChannelResolver {
    override suspend fun resolve(slug: String): KickChatroomInfo? = fetch(slug)?.let(::parseKickChatroomInfo)

    suspend fun viewers(slug: String): Int? = fetch(slug)?.let(::parseKickViewers)

    private suspend fun fetch(slug: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://kick.com/api/v1/channels/${slug.trim().lowercase()}")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                )
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }
}
