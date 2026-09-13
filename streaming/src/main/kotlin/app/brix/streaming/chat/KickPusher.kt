package app.brix.streaming.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kick chat rides Pusher (a third-party WS pub/sub service), same as it did
 * when several community tools were written against it — but Kick has since
 * reshuffled the channel-name scheme at least twice. Verified live against
 * `wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679` on 04.09: a plain
 * `chatroom.{id}` subscribe "succeeds" (Pusher acks any channel name whether
 * or not anyone publishes to it) but delivers nothing. The channel that
 * actually carries `App\Events\ChatMessageEvent` today is `chatrooms.{id}.v2`.
 *
 * [KickChatClient] subscribes to every variant below at once — cheap (a few
 * extra JSON frames on connect), and it's what keeps a real client (Moblin,
 * github.com/eerimoq/moblin) working across Kick's naming churn instead of
 * going quiet the next time they rename it again.
 */
fun kickSubscribeChannels(chatroomId: String, chatroomChannelId: String): List<String> = listOf(
    "chatrooms.$chatroomId.v2",
    "chatroom_$chatroomId",
    "chatrooms.$chatroomId",
    "channel_$chatroomChannelId",
)

sealed class KickPusherEvent {
    data class ChatMessage(
        val id: String,
        val author: String,
        val colorHex: String?,
        val text: String,
    ) : KickPusherEvent()

    data object Other : KickPusherEvent()
}

@Serializable
private data class PusherEnvelope(
    val event: String,
    val data: String? = null,
)

@Serializable
private data class KickBadge(
    val type: String,
    val text: String? = null,
    val count: Int? = null,
)

@Serializable
private data class KickIdentity(
    val color: String,
    val badges: List<KickBadge> = emptyList(),
)

@Serializable
private data class KickSender(
    val id: Long? = null,
    val username: String,
    val identity: KickIdentity,
)

@Serializable
private data class KickChatMessageData(
    val id: String? = null,
    @SerialName("chatroom_id") val chatroomId: Long? = null,
    val content: String,
    val sender: KickSender,
)

private val json = Json { ignoreUnknownKeys = true }

/** `[emote:37226:KEKW]` -> `KEKW` — readable text instead of a placeholder
 *  nobody outside their own web client can render. */
private val emoteTag = Regex("""\[emote:\d+:([^]]+)]""")

object KickPusherParser {

    fun parse(rawFrame: String): KickPusherEvent {
        val envelope = runCatching { json.decodeFromString<PusherEnvelope>(rawFrame) }.getOrNull()
            ?: return KickPusherEvent.Other
        if (envelope.event != "App\\Events\\ChatMessageEvent") return KickPusherEvent.Other
        val data = envelope.data ?: return KickPusherEvent.Other
        val message = runCatching { json.decodeFromString<KickChatMessageData>(data) }.getOrNull()
            ?: return KickPusherEvent.Other
        return KickPusherEvent.ChatMessage(
            id = message.id ?: "",
            author = message.sender.username,
            colorHex = message.sender.identity.color.takeIf { it.isNotBlank() },
            text = emoteTag.replace(message.content) { it.groupValues[1] },
        )
    }
}
