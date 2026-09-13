package app.brix.streaming.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One message as returned by `GET /v1/chat/messages` — already flattened
 *  from the API's `parts[]` shape (text/smile/mention/link) into one string,
 *  and `nick_color` (a signed int, e.g. 0) converted to "#RRGGBB". */
data class VkChatMessage(
    val id: Long,
    val author: String,
    val colorHex: String?,
    val text: String,
    val createdAtSec: Long,
)

@Serializable
private data class VkMessagesResponse(val data: VkMessagesData? = null)

@Serializable
private data class VkMessagesData(@SerialName("chat_messages") val chatMessages: List<VkMessageDto> = emptyList())

@Serializable
private data class VkMessageDto(
    val id: Long,
    val author: VkAuthorDto,
    @SerialName("created_at") val createdAt: Long = 0,
    val parts: List<VkMessagePartDto> = emptyList(),
)

@Serializable
private data class VkAuthorDto(
    val nick: String,
    @SerialName("nick_color") val nickColor: Long? = null,
)

@Serializable
private data class VkMessagePartDto(
    val text: VkTextPart? = null,
    val smile: VkSmilePart? = null,
    val mention: VkMentionPart? = null,
    val link: VkLinkPart? = null,
)

@Serializable
private data class VkTextPart(val content: String = "")

@Serializable
private data class VkSmilePart(val name: String = "")

@Serializable
private data class VkMentionPart(val nick: String = "")

@Serializable
private data class VkLinkPart(val url: String = "")

@Serializable
private data class VkTokenResponse(@SerialName("access_token") val accessToken: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/** Pure — no I/O — so it's unit-testable without a fake HTTP layer. */
internal fun parseVkMessages(rawBody: String): List<VkChatMessage>? =
    runCatching { json.decodeFromString<VkMessagesResponse>(rawBody) }
        .getOrNull()
        ?.data
        ?.chatMessages
        ?.map { dto ->
            VkChatMessage(
                id = dto.id,
                author = dto.author.nick,
                colorHex = dto.author.nickColor?.let(::vkColorToHex),
                text = dto.parts.joinToString(separator = "") { renderPart(it) },
                createdAtSec = dto.createdAt,
            )
        }

internal fun parseVkAccessToken(rawBody: String): String? =
    runCatching { json.decodeFromString<VkTokenResponse>(rawBody) }
        .getOrNull()
        ?.accessToken
        ?.takeIf { it.isNotBlank() }

private fun renderPart(part: VkMessagePartDto): String = when {
    part.text != null -> part.text.content
    part.mention != null -> "@${part.mention.nick}"
    part.smile != null -> if (part.smile.name.isNotBlank()) ":${part.smile.name}:" else ""
    part.link != null -> part.link.url
    else -> ""
}

/** `nick_color` comes back as a plain int (0xRRGGBB), not a "#RRGGBB" string
 *  like Twitch/Kick — masked to 24 bits since the sign bit is otherwise
 *  meaningless for a color. */
internal fun vkColorToHex(color: Long): String = "#%06X".format(color and 0xFFFFFF)
