package app.brix.core

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType {
    RTMP,
    SRTLA,
    WHIP,
}

@Serializable
enum class Codec {
    H264,
    HEVC,
}

@Serializable
enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
}

/** Normalizes a stored [AppearanceSettings.language] value into a BCP-47
 *  locale tag ("ru", "en", ...) or "" for "follow the system language".
 *  Handles the legacy closed-enum values ("AUTO"/"RU"/"EN") this field used
 *  to store before language selection became an open, resource-driven list —
 *  any other value is assumed to already be a lowercase tag. */
fun normalizeLanguageTag(raw: String): String = when (raw.uppercase()) {
    "", "AUTO" -> ""
    "RU" -> "ru"
    "EN" -> "en"
    else -> raw.lowercase()
}

@Serializable
enum class CameraSide {
    BACK,
    FRONT,
}

@Serializable
enum class ButtonAction {
    MUTE,
    TORCH,
    FLIP,
    INFO,
    LOCK,
    BLACK_SCREEN,
    RECONNECT,
    SETTINGS,
    ADAPTIVE_BITRATE,
    OVERLAY,
    VIDEO_EFFECT,
    MASCOT,
    POWER_SAVE,
    MIC,
    SCENE,
}

/**
 * Откуда брать звук. Храним ТИП устройства, а не его id: id живёт до
 * переподключения, и после того как гарнитуру вынули и вставили обратно,
 * настройка указывала бы в пустоту. Тип переживает и это, и перезагрузку.
 */
/**
 * Как система обрабатывает звук до нас.
 *
 * Это не громкость и не выбор капсюля, а режим тракта: система по-разному
 * душит шум, давит эхо и жмёт динамику в зависимости от того, для чего, по её
 * мнению, идёт запись.
 */
@Serializable
enum class AudioProcessing {
    /** Для видео. Разумное умолчание: ровный уровень, без агрессивной чистки. */
    CAMCORDER,

    /** Обычный микрофон. */
    MIC,

    /** Без обработки вообще. Для музыки и когда всё чистят снаружи. */
    UNPROCESSED,

    /** Для разговора: давит эхо и шум сильнее всего, но и голос обрабатывает. */
    VOICE_COMMUNICATION,
}

@Serializable
enum class MicSource {
    /** Как решит система — обычно нижний, но гарнитура перехватывает. */
    AUTO,

    /** Встроенный, когда телефон не различает капсюли. */
    BUILTIN,

    /** Нижний капсюль — тот, в который говорят по телефону. */
    BUILTIN_BOTTOM,

    /** Капсюль у камеры. Замер на S21 03.09: `getDevices()` отдаёт его
     *  отдельным устройством того же типа `TYPE_BUILTIN_MIC`, различая по
     *  `address` («bottom» против «back»). Значит выбрать конкретный капсюль
     *  можно, и это ровно то, что даёт Moblin на iOS через data sources. */
    BUILTIN_BACK,

    /** Верхний капсюль. На S21 не встречается, но многие телефоны его отдают. */
    BUILTIN_TOP,

    WIRED,
    BLUETOOTH,
    USB,
}

@Serializable
enum class QuickButtonWidth {
    SMALL,
    LARGE,
}

@Serializable
enum class QuickButtonColumn {
    LEFT,
    RIGHT,
    NULL,
}

@Serializable
data class VideoSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 2500,
    val codec: Codec = Codec.H264,
    val keyframeIntervalSec: Int = 2,
)

@Serializable
data class AudioSettings(
    val sampleRate: Int = 48_000,
    val bitrateKbps: Int = 128,
    val stereo: Boolean = true,
    /** Усиление микрофона: 1.0 — как есть, 2.0 — вдвое громче. Тихая петличка
     *  или ветрозащита легко режут уровень так, что в эфире шёпот. */
    val micGain: Float = 1f,
    val micSource: MicSource = MicSource.AUTO,
    /**
     * Какое именно устройство выбранного типа, по человеческому имени
     * (`AudioDeviceInfo.productName` — «WH-1000XM4»).
     *
     * Тип сам по себе не различает два устройства: при двух Bluetooth-гарнитурах
     * бралась первая попавшаяся. Имя переживает переподключение, в отличие от
     * `id`, и — в отличие от `address` у Bluetooth — не является MAC-адресом,
     * поэтому его можно хранить и показывать.
     *
     * Пусто — «любое устройство этого типа». Если названного устройства сейчас
     * нет, откатываемся на тип: остаться без звука хуже, чем взять соседний.
     */
    val micDeviceName: String = "",
    val processing: AudioProcessing = AudioProcessing.CAMCORDER,
)

@Serializable
data class StreamPreset(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int = 128,
)

val defaultStreamPresets = listOf(
    StreamPreset("720p30 · 2.5M", 1280, 720, 30, 2500),
    StreamPreset("720p60 · 3.5M", 1280, 720, 60, 3500),
    StreamPreset("1080p30 · 4M", 1920, 1080, 30, 4000),
    StreamPreset("1080p60 · 6M", 1920, 1080, 60, 6000),
    StreamPreset("4K30 · 20M", 3840, 2160, 30, 20000),
)

@Serializable
enum class AbrAlgorithm {
    BELABOX,
    FAST_IRL,
    SLOW_IRL,
    CUSTOM,
}

@Serializable
data class AdaptiveBitrateSettings(
    val enabled: Boolean = false,
    val targetBitrateKbps: Int = 6000,
    val minimumBitrateKbps: Int = 1000,
    val initialBitrateKbps: Int = 2500,
    val algorithm: AbrAlgorithm = AbrAlgorithm.BELABOX,
)

@Serializable
data class ConnectionPriority(
    /** Canonical network key: WIFI / CELLULAR / ETHERNET. */
    val name: String,
    val enabled: Boolean = true,
    /**
     * Ранг канала, 1…10. Доля канала — weight / Σ(весов включённых), 0 — выключен.
     *
     * **Почему 1…10, а не 0…100 как было.** Вес уходит в `SrtlaConnection.score()`,
     * а там формула `scaledPriority = 1 + (priority - 1) * factor` — она пришла из
     * srtla/BELABOX и рассчитана на РАНГ, небольшое число около единицы. Мы же
     * подставляли туда 0–100: при весе 41 получался разброс в сорок раз там, где
     * алгоритм ожидал разы. Сто ступеней на палец вдобавок никому не нужны, а само
     * число ни о чём не говорит — 4 против 10 и 41 против 98 дают одно и то же.
     *
     * Старые значения переводятся в `migrate()`.
     */
    val weight: Int = 0,
)

@Serializable
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    /** BCP-47 locale tag ("ru", "en", ...) or "" for "follow the system
     *  language". The available options are discovered at runtime from the
     *  app's own resources (see LanguageScreen), not a fixed list — a
     *  translator adding a new values-xx/ folder needs no code change to
     *  make that language selectable. */
    val language: String = "",
    val autoHideHud: Boolean = true,
)

@Serializable
data class MoblinkSettings(
    val enabled: Boolean = false,
    val port: Int = 7777,
    /** Пароль релея. Генерируется при первой установке — см. [generateMoblinkPassword]. */
    val password: String = "1234",
    /** Weight applied to every connected relay's channel in the SRTLA aggregate
     *  (same scale as [ConnectionPriority.weight]). Relays are dynamic in count,
     *  unlike the fixed WIFI/CELLULAR/ETHERNET slots, so they share one weight
     *  rather than each getting a dedicated priority entry. */
    val relayWeight: Int = 50,
)

/**
 * Чат Twitch, показанный на экране стримера (не в потоке — см. HudConfig).
 *
 * Анонимное чтение не требует токена: логин `justinfanNNNNN` без пароля.
 * Отправка сообщений сюда сознательно не входит — второй заход, отдельный
 * OAuth-поток.
 */
@Serializable
data class ChatSettings(
    /** Имя поля осталось от версии с одним Twitch — переименовывать нельзя,
     *  сериализованные настройки на телефонах уже используют этот ключ. */
    val enabled: Boolean = false,
    /** Без "#" и без домена — как вводится на twitch.tv/<канал>. */
    val twitchChannel: String = "",
    val kickEnabled: Boolean = false,
    /** Slug канала — часть адреса kick.com/<slug>. */
    val kickChannel: String = "",
    val vkEnabled: Boolean = false,
    /** Полный адрес канала, как в parameter channel_url их API (например
     *  https://live.vkvideo.ru/<канал>). */
    val vkChannelUrl: String = "",
    /** Ключи Приложения с dev.live.vkvideo.ru — обязательны для VK: в отличие
     *  от Twitch/Kick, у них нет анонимного чтения, только ClientCredentials
     *  (приложение, без входа пользователя). Хранятся в открытом виде, как
     *  пароль Moblink — риск ограничен чтением чата этим приложением. */
    val vkClientId: String = "",
    val vkClientSecret: String = "",
    /** Якорь панели на экране, доли 0..1 (0.5,0.5 — центр) — та же схема,
     *  что у [OverlayConfig.posX]/[posY]. */
    val posX: Float = 0.16f,
    val posY: Float = 0.5f,
    /** Размер панели как доли экрана. */
    val width: Float = 0.28f,
    val height: Float = 0.42f,
    /** Множитель размера шрифта сообщений. */
    val fontScale: Float = 1f,
)

/**
 * ЗАМЕНЯЕТ прежний комментарий, который к 03.09 устарел дважды.
 *
 * Он говорил «не подключено к рендерингу» — теперь подключено. И он объявлял,
 * что пустой список слоёв означает «взять глобальный набор». Сделано иначе:
 * **пустой список означает чистую картинку.** Иначе сцену нельзя было бы
 * использовать ровно для того, ради чего её чаще всего заводят — убрать всё
 * лишнее из кадра. Поведение закреплено тестом и объяснено человеку прямо в
 * диалоге сцены. «Как было раньше» при этом сохранено там, где это важно: пока
 * не создано НИ ОДНОЙ сцены, показывается всё включённое.
 */
/**
 * Что сцена показывает в кадре.
 *
 * Проверено 03.09 по библиотеке и по железу:
 * - двух камер одновременно S21 не даёт (камеры перечисляют друг друга как
 *   конфликтующие), поэтому «картинки в картинке» из двух камер не будет;
 * - UVC-камеры в RootEncoder нет вовсе, для неё нужна сторонняя библиотека;
 * - а вот экран, видеофайл и растр есть из коробки.
 */
@Serializable
enum class SceneSource {
    /** Камера телефона, сторона берётся из [Scene.camera]. */
    CAMERA,

    /** Заставка: неподвижная картинка вместо камеры. То, что IRL-стримеру
     *  нужно постоянно — «сейчас вернусь», не обрывая эфир. */
    IMAGE,

    /** Экран телефона: карта, игра, чат — всё, что видит сам стример.
     *  Требует согласия пользователя на захват, и оно спрашивается заново при
     *  каждом запуске приложения: сохранить его Android не даёт. */
    SCREEN,
}

/**
 * Откуда сцена берёт звук.
 *
 * Захват звука самого телефона идёт через тот же токен, что и захват экрана,
 * поэтому требует того же согласия пользователя. И разрешает его не всякое
 * приложение: игры обычно да, музыкальные сервисы почти всегда нет — это их
 * флаг, обойти нельзя.
 */
@Serializable
enum class SceneAudio {
    /** Только микрофон — как было всегда. */
    MIC,

    /** Только звук телефона: игра, видео, музыка. */
    INTERNAL,

    /** И то и другое: комментарий поверх звука игры. */
    BOTH,
}

@Serializable
data class Scene(
    val id: String,
    val name: String,
    val source: SceneSource = SceneSource.CAMERA,
    val audio: SceneAudio = SceneAudio.MIC,
    /** Картинка заставки: content-URI из системного выбора файлов. */
    val imageUri: String = "",
    val camera: CameraSide = CameraSide.BACK,
    val overlayIds: List<String> = emptyList(),
    val browserWidgetIds: List<String> = emptyList(),
)

@Serializable
data class AdvancedSettings(
    val recordStream: Boolean = false,
    /** Запись сессии эфира в CSV на телефоне: мощность, температура, тепловой
     *  статус и цифры транспорта раз в секунду. Нужна там, где logcat
     *  недоступен — двухчасовой выход с пауэрбанком без кабеля. */
    val debugLog: Boolean = false,
)

@Serializable
data class HudConfig(
    val showBattery: Boolean = true,
    val showThermal: Boolean = true,
    val showNetworks: Boolean = true,
    val showBitrate: Boolean = true,
    val showUptime: Boolean = true,
    val showVersion: Boolean = true,
    /** Название текущей сцены. Когда сцен больше одной, по кадру не всегда
     *  видно, какая включена — особенно если две сцены отличаются только
     *  набором слоёв. Строка идёт только на экран стримера, в поток не
     *  попадает. */
    val showScene: Boolean = true,
)

@Serializable
data class CameraDefaults(
    val torchOnStart: Boolean = false,
    val defaultCamera: CameraSide = CameraSide.BACK,
    /** Электронная стабилизация: подрезает кадр, зато гасит крупную тряску. */
    val stabilization: Boolean = false,
    /** Оптическая: качает линзу, кадр не режет, но справляется только с мелким
     *  дрожанием. Отдельный переключатель, потому что это разные механизмы с
     *  разной ценой — библиотека даёт их раздельно, а мы до 04.09 использовали
     *  только электронную. */
    val opticalStabilization: Boolean = false,
    /** Зеркалить фронталку в ПРЕВЬЮ — так ведут себя камерные приложения. */
    val mirrorFront: Boolean = true,
    /** Зеркалить фронталку В ЭФИРЕ. Отдельно от превью: стримеру привычно
     *  видеть себя зеркально, а зрителю — как в жизни, иначе весь текст в кадре
     *  читается наоборот. По умолчанию выключено именно поэтому. */
    val mirrorFrontInStream: Boolean = false,
    /** false = continuous autofocus only (default); true = tap-to-focus mode
     *  (tap a point on the preview to lock focus there briefly). */
    val tapToFocus: Boolean = false,
)

@Serializable
data class QuickButtonSlot(
    val action: ButtonAction,
    val width: QuickButtonWidth,
    val rowIndex: Int,
    val columnPosition: QuickButtonColumn?,
)

@Serializable
data class QuickButtonConfig(
    val slots: List<QuickButtonSlot> = defaultQuickButtonSlots(),
    val cornerSlotAction: ButtonAction? = ButtonAction.MUTE,
)

@Serializable
data class StreamProfile(
    val id: String,
    val name: String,
    val video: VideoSettings = VideoSettings(),
    @Deprecated("Осталось только для миграции в AppSettings.audio.")
    val audio: AudioSettings = AudioSettings(),
    val adaptiveBitrate: AdaptiveBitrateSettings = AdaptiveBitrateSettings(),
    val srtConnectionPriorities: List<ConnectionPriority> = defaultConnectionPriorities(),
)

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val type: ServerType,
    @Deprecated("Kept only for schema migration (url -> baseUrl).")
    val url: String? = null,
    val baseUrl: String = "",
    val streamId: String = "",
    val latencyMs: Int = 2000,
    /** Брать при разрешении имени только IPv4. Некоторые операторы и приёмники
     *  ведут себя с IPv6 хуже: соединение встаёт, а данные не идут. */
    val preferIpv4: Boolean = false,
    val enabled: Boolean = true,
) {
    /**
     * Адрес, по которому реально подключаемся.
     *
     * У RTMP ключ трансляции — это последний сегмент пути: площадки выдают
     * отдельно адрес ингеста и отдельно ключ, а протокол ждёт их склеенными.
     * Поэтому [streamId] хранится отдельно и приклеивается здесь: в форме он
     * скрыт точками, а в [baseUrl] остаётся только адрес, который можно
     * показать кому угодно.
     *
     * Профили, заведённые до появления отдельного поля, держат ключ прямо в
     * [baseUrl] и имеют пустой [streamId] — для них ничего не меняется.
     *
     * У SRTLA ключ устроен иначе (параметр `streamid` в запросе) и разбирается
     * транспортом из самого адреса, поэтому здесь он не трогается.
     */
    fun connectUrl(): String {
        val key = streamId.trim()
        if (key.isEmpty()) return baseUrl
        return when (type) {
            // У RTMP ключ — последний сегмент пути.
            ServerType.RTMP -> baseUrl.trimEnd('/') + "/" + key.trimStart('/')
            // У SRTLA — параметр `streamid` в запросе, и внутри него обычно
            // сидит `srtauth` с ключом доступа к приёмнику. Всё, что было в
            // запросе у baseUrl, отбрасывается: раз поле заполнено, оно и есть
            // источник истины, иначе получились бы два streamid подряд.
            ServerType.SRTLA -> baseUrl.substringBefore('?').trimEnd('/') + "?streamid=" + key
            // У WHIP ключ в адрес не выносится.
            ServerType.WHIP -> baseUrl
        }
    }
}

@Serializable
data class OverlayConfig(
    val id: String,
    val url: String,
    val enabled: Boolean = true,
    /** Overlay anchor on the video frame, as fractions 0..1 (0.5,0.5 = center). */
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    /** Overlay size as fractions of the video frame width/height (0..1). */
    val width: Float = 0.18f,
    val height: Float = 0.18f,
    /** Play alert audio through the device speaker (streamer hears it). */
    val audioOnDevice: Boolean = false,
    /** Mix alert audio into the stream (viewers hear it). */
    val audioInStream: Boolean = false,
)

/** A generic "browser source" overlay — any URL, rendered live via periodic
 *  WebView snapshots (not the donation-detect pipeline; separate feature,
 *  separate widget list). No audio: Android has no way to capture a
 *  WebView's own audio output into the stream without a MediaProjection
 *  screen-capture consent, which was deliberately ruled out. */
@Serializable
data class BrowserWidgetConfig(
    val id: String,
    val url: String,
    val enabled: Boolean = true,
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    val width: Float = 0.18f,
    val height: Float = 0.18f,
    /** How often to re-snapshot the WebView, in ms. Snapshotting is
     *  expensive (main-thread View.draw) — keep this modest. */
    val refreshMs: Long = 200L,
)

@Serializable
data class AppSettings(
    /** Настройки звука общие для всех профилей. Раньше лежали в каждом
     *  профиле копией: человек правил их в одном месте, а при переключении
     *  профиля получал другие — и понять почему было неоткуда. */
    val audio: AudioSettings = AudioSettings(),
    val streamProfiles: List<StreamProfile> = emptyList(),
    val serverProfiles: List<ServerProfile> = emptyList(),
    val selectedStreamProfileId: String? = null,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val cameraDefaults: CameraDefaults = CameraDefaults(),
    val advanced: AdvancedSettings = AdvancedSettings(),
    val quickButtons: QuickButtonConfig = QuickButtonConfig(),
    val hud: HudConfig = HudConfig(),
    val overlays: List<OverlayConfig> = emptyList(),
    val browserWidgets: List<BrowserWidgetConfig> = emptyList(),
    val customPresets: List<StreamPreset> = emptyList(),
    val moblink: MoblinkSettings = MoblinkSettings(),
    val chat: ChatSettings = ChatSettings(),
    val scenes: List<Scene> = emptyList(),
    val selectedSceneId: String? = null,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayUrl: String? = null,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayEnabled: Boolean = false,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayPosX: Float = 0.5f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayPosY: Float = 0.5f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayWidth: Float = 0.3f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayHeight: Float = 0.3f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayAudioOnDevice: Boolean = false,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayAudioInStream: Boolean = false,
) {
    fun selectedStreamProfile(): StreamProfile? =
        streamProfiles.firstOrNull { it.id == selectedStreamProfileId }

    fun selectedScene(): Scene? = scenes.firstOrNull { it.id == selectedSceneId }

    /**
     * Оверлеи, которые сейчас в кадре.
     *
     * Пока сцен нет — всё включённое, как было до их появления. Заводить сцену
     * не должно означать «теперь настраивай заново»: у кого сцен нет, для того
     * ничего не меняется.
     */
    fun activeOverlays(): List<OverlayConfig> {
        val scene = selectedScene() ?: return overlays
        return overlays.filter { it.id in scene.overlayIds }
    }

    fun activeBrowserWidgets(): List<BrowserWidgetConfig> {
        val scene = selectedScene() ?: return browserWidgets
        return browserWidgets.filter { it.id in scene.browserWidgetIds }
    }

    fun enabledServers(): List<ServerProfile> =
        serverProfiles.filter { it.enabled }

    @Suppress("DEPRECATION")
    fun migrate(): AppSettings {
        val migratedServers = serverProfiles.map { server ->
            if (server.baseUrl.isBlank() && !server.url.isNullOrBlank()) {
                // Старое поле дочищаем: иначе оно переписывается на диск при
                // каждом сохранении и живёт устаревшим дублем baseUrl — правку
                // адреса оно не увидит, а следующий, кто в него заглянет,
                // получит адрес, по которому уже никто не вещает. Читателей у
                // него нет ни в одном модуле (проверено), поэтому чистка
                // безопасна, и миграция становится идемпотентной.
                server.copy(baseUrl = server.url, url = null)
            } else {
                server
            }
        }
        val migratedOverlays = if (overlays.isEmpty() && !overlayUrl.isNullOrBlank()) {
            listOf(
                OverlayConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    url = overlayUrl,
                    enabled = overlayEnabled,
                    posX = overlayPosX,
                    posY = overlayPosY,
                    width = overlayWidth,
                    height = overlayHeight,
                    audioOnDevice = overlayAudioOnDevice,
                    audioInStream = overlayAudioInStream,
                ),
            )
        } else {
            overlays
        }
        // Звук переезжает из профиля в общие настройки. Берём его у выбранного
        // профиля — именно он и был виден пользователю на экране «Аудио».
        // Условие «общие ещё по умолчанию» делает миграцию однократной: после
        // первого сохранения она больше не срабатывает и не затирает правки.
        @Suppress("DEPRECATION")
        val migratedAudio = if (audio == AudioSettings()) {
            (streamProfiles.firstOrNull { it.id == selectedStreamProfileId } ?: streamProfiles.firstOrNull())
                ?.audio ?: audio
        } else {
            audio
        }
        // Веса каналов переведены из 0…100 в ранг 1…10 (14.09). Значение больше
        // десяти заведомо старое — делим и округляем вверх, чтобы 1…9 не схлопнулись
        // в ноль и канал не выключился молча. Значения 10 и меньше уже годны как ранг
        // и остаются как есть, поэтому миграция идемпотентна.
        val migratedProfiles = streamProfiles.map { profile ->
            profile.copy(
                srtConnectionPriorities = profile.srtConnectionPriorities.map { p ->
                    if (p.weight > 10) p.copy(weight = ((p.weight + 9) / 10).coerceIn(1, 10)) else p
                },
            )
        }
        // Пароль «1234» был значением по умолчанию до 14.09, то есть общеизвестным
        // для всех, у кого стоит приложение. Меняем на случайный. Настроенную пару
        // это рассорило бы, но Moblink со вторым телефоном ни разу не работал —
        // ломать нечего, а оставлять известный пароль у службы в локальной сети
        // нельзя. Миграция идемпотентна: после замены значение уже не «1234».
        val migratedMoblink = if (moblink.password == "1234") {
            moblink.copy(password = generateMoblinkPassword())
        } else {
            moblink
        }
        return copy(
            moblink = migratedMoblink,
            serverProfiles = migratedServers,
            overlays = migratedOverlays,
            audio = migratedAudio,
            streamProfiles = migratedProfiles,
        )
    }
}

/**
 * Пароль для Moblink, пригодный к вводу руками.
 *
 * **Почему не длинный hex.** Этот пароль человек читает с одного телефона и набирает
 * на другом, обычно на улице. Тридцать два знака никто не введёт, а значит заменит
 * на «1234» — и мы вернёмся туда, откуда ушли. Восемь знаков из однозначного
 * алфавита дают около сорока бит: для службы, доступной только в своей локальной
 * сети и только пока идёт эфир, этого достаточно.
 *
 * Из алфавита выброшены пары, которые путают при наборе: 0 и o, 1 и l с i.
 */
fun generateMoblinkPassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    val random = java.security.SecureRandom()
    return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

fun defaultConnectionPriorities(): List<ConnectionPriority> = listOf(
    ConnectionPriority("WIFI", enabled = true, weight = 8),
    ConnectionPriority("CELLULAR", enabled = true, weight = 2),
    ConnectionPriority("ETHERNET", enabled = false, weight = 0),
)

fun defaultQuickButtonSlots(): List<QuickButtonSlot> = listOf(
    QuickButtonSlot(ButtonAction.TORCH, QuickButtonWidth.SMALL, 0, QuickButtonColumn.RIGHT),
    QuickButtonSlot(ButtonAction.FLIP, QuickButtonWidth.SMALL, 0, QuickButtonColumn.LEFT),
    QuickButtonSlot(ButtonAction.INFO, QuickButtonWidth.SMALL, 1, QuickButtonColumn.RIGHT),
    QuickButtonSlot(ButtonAction.LOCK, QuickButtonWidth.SMALL, 1, QuickButtonColumn.LEFT),
    QuickButtonSlot(ButtonAction.ADAPTIVE_BITRATE, QuickButtonWidth.SMALL, 2, QuickButtonColumn.RIGHT),
    QuickButtonSlot(ButtonAction.SETTINGS, QuickButtonWidth.SMALL, 2, QuickButtonColumn.LEFT),
)

/**
 * Восстанавливает инвариант «хотя бы один стрим-профиль», НЕ теряя остального.
 *
 * Раньше загрузка делала `takeIf { streamProfiles.isNotEmpty() } ?: defaultAppSettings()`,
 * то есть пустой список профилей выбрасывал ВЕСЬ файл настроек: серверы,
 * оверлеи, раскладку быстрых кнопок, умолчания камеры. Инвариант при этом
 * держала одна строка в UI — стоило его нарушить импортом, миграцией или
 * ошибкой, и пользователь оставался с чистым приложением, не тронув ничего,
 * кроме профилей.
 */
/**
 * Приводит задержку SRT к диапазону, который переживёт рукопожатие.
 *
 * Значение уходит в поле расширения SRT как UInt16 (`SrtSender.writeUInt16`),
 * поэтому всё, что больше 65535, там молча обрезалось бы в мусор — на слух
 * «поставил 100000, а получил 34464». Снизу тоже нужен пол: слишком короткий
 * буфер приёмника не даст восстановить ни одной потери, ради чего SRT и
 * берут.
 *
 * Границы 100..10000 — рабочий диапазон приёмников экосистемы BELABOX, где
 * типичное значение 2000.
 */
fun clampSrtLatency(ms: Int): Int = ms.coerceIn(100, 10_000)

fun AppSettings.withStreamProfilesEnsured(): AppSettings {
    if (streamProfiles.isNotEmpty()) return this
    val defaults = defaultAppSettings()
    return copy(
        streamProfiles = defaults.streamProfiles,
        selectedStreamProfileId = defaults.selectedStreamProfileId,
    )
}

fun defaultAppSettings(): AppSettings {
    val profile = StreamProfile(
        id = Ids.newId(),
        name = "Default",
    )
    return AppSettings(
        streamProfiles = listOf(profile),
        selectedStreamProfileId = profile.id,
    )
}
