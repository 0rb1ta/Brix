package app.brix.streaming.chat

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Площадка в терминах 7TV и BTTV — это часть их адресов API. */
enum class EmotePlatform(val apiName: String) {
    TWITCH("twitch"),
    KICK("kick"),
}

/** Сеть за интерфейсом — как [KickChannelResolver]: тесты без настоящих запросов.
 *  `null` — ошибка, пустая строка — «такого канала у сервиса нет» (HTTP 404). */
fun interface EmoteHttp {
    suspend fun get(url: String): String?
}

@Serializable
private data class SeventvFile(val name: String, val static_name: String? = null, val width: Int = 0, val height: Int = 0)

@Serializable
private data class SeventvHost(val url: String, val files: List<SeventvFile> = emptyList())

@Serializable
private data class SeventvEmoteData(val host: SeventvHost)

@Serializable
private data class SeventvEmote(val name: String, val data: SeventvEmoteData)

@Serializable
private data class SeventvEmoteSet(val emotes: List<SeventvEmote>? = null)

@Serializable
private data class SeventvUser(val emote_set: SeventvEmoteSet? = null)

@Serializable
private data class BttvEmote(val id: String, val code: String)

@Serializable
private data class BttvChannel(
    val channelEmotes: List<BttvEmote> = emptyList(),
    val sharedEmotes: List<BttvEmote> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Разбор ответов 7TV и BTTV — чистые функции, проверяются на сохранённых ответах.
 */
internal object ThirdPartyEmoteParser {

    private fun seventv(emotes: List<SeventvEmote>?): Map<String, ChatPart.Emote> =
        emotes.orEmpty().mapNotNull { e ->
            // 2x — картинка около 64 px в высоту: строка чата на экране 3x
            // занимает 36–60 px, 1x (32 px) заметно мылится.
            val file = e.data.host.files.firstOrNull { it.name == "2x.webp" }
                ?: e.data.host.files.firstOrNull { it.name.endsWith(".webp") }
                ?: return@mapNotNull null
            val name = file.static_name ?: file.name
            val aspect = if (file.width > 0 && file.height > 0) file.width.toFloat() / file.height else 1f
            e.name to ChatPart.Emote(e.name, "https:${e.data.host.url}/$name", aspect)
        }.toMap()

    fun seventvGlobal(body: String): Map<String, ChatPart.Emote> =
        seventv(json.decodeFromString<SeventvEmoteSet>(body).emotes)

    fun seventvUser(body: String): Map<String, ChatPart.Emote> =
        seventv(json.decodeFromString<SeventvUser>(body).emote_set?.emotes)

    private fun bttvEmote(e: BttvEmote) =
        e.code to ChatPart.Emote(e.code, "https://cdn.betterttv.net/emote/${e.id}/2x")

    fun bttvGlobal(body: String): Map<String, ChatPart.Emote> =
        json.decodeFromString<List<BttvEmote>>(body).associate(::bttvEmote)

    fun bttvUser(body: String): Map<String, ChatPart.Emote> {
        val channel = json.decodeFromString<BttvChannel>(body)
        // Свои эмодзи канала важнее общих — идут последними и перекрывают.
        return (channel.sharedEmotes + channel.channelEmotes).associate(::bttvEmote)
    }
}

/**
 * Наборы 7TV и BTTV для одного канала: глобальные плюс канальные.
 *
 * Включается отдельным переключателем (владелец, 16.09): это запросы к
 * сторонним сервисам, и по ним видно, какой канал открыт.
 *
 * Порядок слияния — как у Moblin: BTTV, затем 7TV, поздние перекрывают ранние.
 * При ошибке повторяем с растущей паузой: сервисы иногда отвечают медленно, а
 * эфир идёт часами.
 *
 * **Отличие от Moblin, найденное проверкой 16.09:** для Kick 7TV ждёт номер
 * ПОЛЬЗОВАТЕЛЯ (`user_id`), а Moblin передаёт номер чатрума. На канале xqc
 * `/v3/users/kick/668` (чатрум) отдаёт 404, `/676` (пользователь) — 968 эмодзи.
 */
class ThirdPartyEmotes(
    private val scope: CoroutineScope,
    private val http: EmoteHttp = OkHttpEmoteHttp(),
) {
    private val tag = "BrixChat"
    private val _emotes = MutableStateFlow<Map<String, ChatPart.Emote>>(emptyMap())
    val emotes: StateFlow<Map<String, ChatPart.Emote>> = _emotes

    private var job: Job? = null
    private var loadedKey: String? = null

    /** Загрузить наборы для канала. Повторный вызов с тем же каналом ничего не делает. */
    fun load(platform: EmotePlatform, channelId: String) {
        val key = "${platform.apiName}/$channelId"
        if (key == loadedKey) return
        loadedKey = key
        job?.cancel()
        _emotes.value = emptyMap()
        job = scope.launch {
            var pause = 30_000L
            while (true) {
                val result = fetchAll(platform, channelId)
                _emotes.value = result.emotes
                if (!result.failed) {
                    Log.i(tag, "эмодзи 7TV/BTTV: ${result.emotes.size} для ${platform.apiName}")
                    return@launch
                }
                Log.w(tag, "эмодзи 7TV/BTTV: не всё загрузилось, повтор через ${pause / 1000} с")
                delay(pause)
                pause = (pause * 2).coerceAtMost(3_600_000L)
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        loadedKey = null
        _emotes.value = emptyMap()
    }

    private class Result(val emotes: Map<String, ChatPart.Emote>, val failed: Boolean)

    private suspend fun fetchAll(platform: EmotePlatform, channelId: String): Result {
        var failed = false
        val merged = LinkedHashMap<String, ChatPart.Emote>()
        suspend fun take(url: String, parse: (String) -> Map<String, ChatPart.Emote>) {
            val body = http.get(url)
            if (body == null) {
                failed = true
                return
            }
            if (body.isEmpty()) return // у сервиса нет такого канала — не ошибка
            runCatching { parse(body) }
                .onSuccess { merged.putAll(it) }
                .onFailure { failed = true }
        }
        val p = platform.apiName
        take("https://api.betterttv.net/3/cached/emotes/global", ThirdPartyEmoteParser::bttvGlobal)
        take("https://api.betterttv.net/3/cached/users/$p/$channelId", ThirdPartyEmoteParser::bttvUser)
        take("https://7tv.io/v3/emote-sets/global", ThirdPartyEmoteParser::seventvGlobal)
        take("https://7tv.io/v3/users/$p/$channelId", ThirdPartyEmoteParser::seventvUser)
        return Result(merged, failed)
    }
}

internal class OkHttpEmoteHttp(
    private val client: OkHttpClient = OkHttpClient(),
) : EmoteHttp {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    response.code == 404 -> ""
                    response.isSuccessful -> response.body?.string()
                    else -> null
                }
            }
        }.getOrNull()
    }
}
