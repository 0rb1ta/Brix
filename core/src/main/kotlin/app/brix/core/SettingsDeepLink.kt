package app.brix.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/** The shareable subset of [AppSettings] carried by a `brix://` link —
 *  stream/server profiles and the quick-button layout. Deliberately not the
 *  whole [AppSettings] (no per-device appearance settings) — this is meant for
 *  moving *stream setup*, not the whole app state.
 *
 *  Здесь раньше стоял комментарий, обещавший, что пароли чужих серверов не
 *  импортируются молча. Он описывал импорт, а дыра была в ЭКСПОРТЕ: в ссылку
 *  уходил весь [ServerProfile] целиком, вместе с ключами вещания владельца.
 *  Теперь секреты вычищаются в [SettingsDeepLink.encode], если их не попросили
 *  явно. */
@Serializable
data class SharedConfig(
    val streamProfiles: List<StreamProfile> = emptyList(),
    val serverProfiles: List<ServerProfile> = emptyList(),
    val quickButtons: QuickButtonConfig? = null,
)

/**
 * `brix://settings?config=<JSON>` — mirrors Moblin's own deep-link design
 * (`MoblinSettingsUrl.swift`: `moblin://...?<raw JSON in query>`, no base64)
 * rather than inventing a new format: plain JSON in a query parameter, so a
 * generated link stays readable in share sheets/browser history.
 *
 * Also does a best-effort **import** of `moblin://` links (one-way — we
 * don't export in Moblin's schema, just read it) by mapping their
 * `streams[]` entries onto our [StreamProfile]/[ServerProfile] pair. Unknown
 * or incompatible fields are simply skipped, not treated as an error.
 *
 * Works on plain strings, not `android.net.Uri` — the latter isn't mockable
 * in plain JVM unit tests without Robolectric (unconfigured here), and the
 * only real dependency on the platform type is at the very edge (MainActivity
 * turning an incoming `Intent.data` into a string, or building an
 * `android.net.Uri` from what [encode] returns to hand to a share sheet).
 */
object SettingsDeepLink {
    const val SCHEME = "brix"
    private const val HOST = "settings"
    private const val PARAM = "config"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Убирает из профиля всё, чем можно вещать от имени владельца.
     *
     * Ключ прячется в трёх местах, и упустить хоть одно — значит не сделать
     * ничего: `passphrase` (пароль шифрования SRT), `streamId` (у SRTLA туда
     * уходит `?srtauth=<ключ>`) и сам `baseUrl` — у RTMP ключ трансляции лежит
     * прямо в пути (`rtmp://host/live/<ключ>`).
     *
     * Из `baseUrl` срезаем строку запроса целиком и последний сегмент пути,
     * если сегментов больше одного. Это эвристика, и она намеренно грубая:
     * лучше отдать получателю адрес, который придётся дописать руками, чем
     * тихо разослать ключ. Ради этого же остаётся имя, тип и порт — переносится
     * настройка, а не доступ.
     */
    fun ServerProfile.withoutSecrets(): ServerProfile = copy(
        passphrase = "",
        streamId = "",
        baseUrl = baseUrl.substringBefore('?').let { noQuery ->
            val schemeEnd = noQuery.indexOf("://")
            val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
            val pathStart = noQuery.indexOf('/', authorityStart)
            if (pathStart < 0) return@let noQuery
            val path = noQuery.substring(pathStart).trim('/')
            if (path.isEmpty() || !path.contains('/')) return@let noQuery
            noQuery.substring(0, pathStart) + "/" + path.substringBeforeLast('/')
        },
    )

    /**
     * Builds a full `brix://settings?config=...` URI string.
     *
     * @param includeSecrets класть ли в ссылку ключи вещания. По умолчанию НЕТ:
     *  ссылка уходит в шер-шит, историю браузера и переписку, а кто её получил,
     *  тот может вещать на ваш канал. Включать осознанно и только когда
     *  переносишь настройки на своё же второе устройство.
     */
    fun encode(settings: AppSettings, includeSecrets: Boolean = false): String {
        val shared = SharedConfig(
            streamProfiles = settings.streamProfiles,
            serverProfiles = if (includeSecrets) {
                settings.serverProfiles
            } else {
                settings.serverProfiles.map { it.withoutSecrets() }
            },
            quickButtons = settings.quickButtons,
        )
        val payload = json.encodeToString(shared)
        val encoded = URLEncoder.encode(payload, "UTF-8")
        return "$SCHEME://$HOST?$PARAM=$encoded"
    }

    /** Decodes a `brix://` link produced by [encode]. Returns null on any
     *  scheme mismatch, missing payload, or malformed JSON — callers should
     *  treat null as "not a config link", not crash. */
    fun decode(uri: String): SharedConfig? {
        if (!uri.startsWith("$SCHEME://")) return null
        val payload = queryParam(uri, PARAM) ?: return null
        return try {
            json.decodeFromString<SharedConfig>(payload)
        } catch (_: Exception) {
            null
        }
    }

    /** Best-effort import of a Moblin-shaped settings link — the raw
     *  `{"streams":[...]}` JSON directly in the query, no `config=` key.
     *  Accepts it under either `moblin://` (Moblin's own scheme) or
     *  `brix://` (a hand-built/copy-pasted link using our scheme with
     *  Moblin's payload shape — [decode] already tried and failed on it by
     *  the time callers reach this). See class doc — one-way, lossy by
     *  design (only the fields we have an equivalent for). */
    fun decodeMoblin(uri: String): SharedConfig? {
        if (!uri.startsWith("moblin://") && !uri.startsWith("brix://")) return null
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        return try {
            val decoded = URLDecoder.decode(query, "UTF-8")
            val root = json.parseToJsonElement(decoded).jsonObject
            val streams = (root["streams"] as? JsonArray) ?: return null
            val serverProfiles = mutableListOf<ServerProfile>()
            val streamProfiles = mutableListOf<StreamProfile>()
            for (entry in streams) {
                val stream = entry as? JsonObject ?: continue
                val name = stream["name"]?.jsonPrimitive?.content ?: continue
                val url = stream["url"]?.jsonPrimitive?.content ?: continue
                val type = if (url.startsWith("srt://") || url.startsWith("srtla://")) {
                    ServerType.SRTLA
                } else {
                    ServerType.RTMP
                }
                serverProfiles += ServerProfile(
                    id = Ids.newId(),
                    name = name,
                    type = type,
                    baseUrl = url,
                )
                val video = stream["video"] as? JsonObject
                val bitrateBps = video?.get("bitrate")?.jsonPrimitive?.longOrNull
                val fps = video?.get("fps")?.jsonPrimitive?.intOrNull
                val audio = stream["audio"] as? JsonObject
                val audioBitrateBps = audio?.get("bitrate")?.jsonPrimitive?.longOrNull
                streamProfiles += StreamProfile(
                    id = Ids.newId(),
                    name = name,
                    video = VideoSettings(
                        fps = fps ?: 30,
                        bitrateKbps = ((bitrateBps ?: 5_000_000L) / 1000).toInt(),
                    ),
                    audio = AudioSettings(
                        bitrateKbps = ((audioBitrateBps ?: 128_000L) / 1000).toInt(),
                    ),
                )
            }
            if (serverProfiles.isEmpty()) null else SharedConfig(streamProfiles, serverProfiles)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryParam(uri: String, name: String): String? {
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx < 0) continue
            val key = pair.substring(0, idx)
            if (key == name) return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
        return null
    }
}
