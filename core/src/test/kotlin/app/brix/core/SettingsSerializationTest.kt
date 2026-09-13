package app.brix.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stream profile round-trips through json`() {
        val profile = StreamProfile(
            id = "p1",
            name = "Test",
            video = VideoSettings(width = 1920, height = 1080, fps = 60, bitrateKbps = 6000, codec = Codec.HEVC),
            audio = AudioSettings(sampleRate = 44_100, bitrateKbps = 192, stereo = false),
            adaptiveBitrate = AdaptiveBitrateSettings(enabled = true, targetBitrateKbps = 9000),
        )

        val encoded = json.encodeToString<StreamProfile>(profile)
        val decoded = json.decodeFromString<StreamProfile>(encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `server profile round-trips with defaults`() {
        val profile = ServerProfile(id = "s1", name = "Relay", type = ServerType.SRTLA, url = "srt://host:9000")

        val decoded = json.decodeFromString<ServerProfile>(json.encodeToString<ServerProfile>(profile))

        assertEquals(profile, decoded)
        assertTrue(decoded.enabled)
    }

    @Test
    fun `selectedStreamProfile returns matching profile`() {
        val settings = AppSettings(
            streamProfiles = listOf(
                StreamProfile(id = "a", name = "A"),
                StreamProfile(id = "b", name = "B"),
            ),
            selectedStreamProfileId = "b",
        )

        assertNotNull(settings.selectedStreamProfile())
        assertEquals("B", settings.selectedStreamProfile()?.name)
    }

    @Test
    fun `selectedStreamProfile returns null when id unknown`() {
        val settings = AppSettings(selectedStreamProfileId = "missing")
        assertNull(settings.selectedStreamProfile())
    }

    @Test
    fun `enabledServers filters disabled`() {
        val settings = AppSettings(
            serverProfiles = listOf(
                ServerProfile(id = "1", name = "on", type = ServerType.SRTLA, url = "u", enabled = true),
                ServerProfile(id = "2", name = "off", type = ServerType.RTMP, url = "u", enabled = false),
            ),
        )

        assertEquals(1, settings.enabledServers().size)
        assertEquals("on", settings.enabledServers()[0].name)
    }

    @Test
    fun `stream preset round-trips through json`() {
        val preset = StreamPreset(
            name = "Custom 4K",
            width = 3840,
            height = 2160,
            fps = 30,
            videoBitrateKbps = 20_000,
            audioBitrateKbps = 192,
        )

        val decoded = json.decodeFromString<StreamPreset>(json.encodeToString<StreamPreset>(preset))

        assertEquals(preset, decoded)
    }

    @Test
    fun `appSettings customPresets round-trips through json`() {
        val settings = AppSettings(
            customPresets = listOf(
                StreamPreset(name = "Custom", width = 2560, height = 1440, fps = 60, videoBitrateKbps = 12_000),
            ),
        )

        val decoded = json.decodeFromString<AppSettings>(json.encodeToString<AppSettings>(settings))

        assertEquals(settings.customPresets, decoded.customPresets)
    }

    @Test
    fun `scene round-trips through json`() {
        val scene = Scene(
            id = "s1",
            name = "Main",
            camera = CameraSide.FRONT,
            overlayIds = listOf("o1", "o2"),
            browserWidgetIds = listOf("w1"),
        )

        val decoded = json.decodeFromString<Scene>(json.encodeToString<Scene>(scene))

        assertEquals(scene, decoded)
    }

    @Test
    fun `appSettings scenes round-trips through json`() {
        val settings = AppSettings(
            scenes = listOf(Scene(id = "s1", name = "Main")),
            selectedSceneId = "s1",
        )

        val decoded = json.decodeFromString<AppSettings>(json.encodeToString<AppSettings>(settings))

        assertEquals(settings.scenes, decoded.scenes)
        assertEquals(settings.selectedSceneId, decoded.selectedSceneId)
    }

    @Test
    fun `normalizeLanguageTag maps legacy enum values`() {
        assertEquals("", normalizeLanguageTag(""))
        assertEquals("", normalizeLanguageTag("AUTO"))
        assertEquals("ru", normalizeLanguageTag("RU"))
        assertEquals("en", normalizeLanguageTag("EN"))
    }

    @Test
    fun `normalizeLanguageTag lowercases a plain locale tag`() {
        assertEquals("de", normalizeLanguageTag("DE"))
        assertEquals("de", normalizeLanguageTag("de"))
    }

    @Test
    fun `defaultAppSettings creates one selected profile`() {
        val settings = defaultAppSettings()
        assertEquals(1, settings.streamProfiles.size)
        assertNotNull(settings.selectedStreamProfile())
        assertEquals(settings.selectedStreamProfileId, settings.selectedStreamProfile()?.id)
    }

    // Пустой список стрим-профилей раньше выбрасывал весь файл настроек. Цена
    // ошибки — вся конфигурация пользователя, поэтому проверяем именно то, что
    // остальное переживает починку.

    @Test
    fun `пустой список профилей чинится, не унося остальные настройки`() {
        val broken = AppSettings(
            streamProfiles = emptyList(),
            serverProfiles = listOf(
                ServerProfile(id = "s", name = "мой сервер", type = ServerType.SRTLA, baseUrl = "srtla://h:5000"),
            ),
        )

        val fixed = broken.withStreamProfilesEnsured()

        assertEquals(1, fixed.streamProfiles.size)
        assertEquals(fixed.streamProfiles.first().id, fixed.selectedStreamProfileId)
        assertEquals(broken.serverProfiles, fixed.serverProfiles)
    }

    @Test
    fun `непустой список профилей не трогается`() {
        val settings = defaultAppSettings()
        assertSame(settings, settings.withStreamProfilesEnsured())
    }

    @Test
    fun `задержка SRT удерживается в диапазоне, переживающем рукопожатие`() {
        // Значение уходит в UInt16, поэтому 100000 иначе обрезалось бы в мусор.
        assertEquals(10_000, clampSrtLatency(100_000))
        assertEquals(100, clampSrtLatency(0))
        assertEquals(100, clampSrtLatency(-5))
        assertEquals(2000, clampSrtLatency(2000))
        assertEquals(500, clampSrtLatency(500))
    }

    @Test
    fun `звук переезжает из выбранного профиля в общие настройки`() {
        // На устройствах уже лежат файлы, где звук записан в каждом профиле.
        // Терять его при обновлении нельзя: человек настраивал битрейт и частоту
        // руками, а после переезда они должны остаться теми же.
        @Suppress("DEPRECATION")
        val old = AppSettings(
            selectedStreamProfileId = "p2",
            streamProfiles = listOf(
                StreamProfile(id = "p1", name = "первый", audio = AudioSettings(bitrateKbps = 64)),
                StreamProfile(id = "p2", name = "второй", audio = AudioSettings(bitrateKbps = 256, stereo = false)),
            ),
        )

        val migrated = old.migrate()

        assertEquals(256, migrated.audio.bitrateKbps)
        assertEquals(false, migrated.audio.stereo)
    }

    @Test
    fun `повторная миграция не затирает уже настроенный общий звук`() {
        // Миграция срабатывает один раз: после первого сохранения общие
        // настройки перестают быть значением по умолчанию, и профиль их больше
        // не перебивает. Иначе правка звука откатывалась бы при каждой загрузке.
        @Suppress("DEPRECATION")
        val already = AppSettings(
            audio = AudioSettings(bitrateKbps = 192),
            selectedStreamProfileId = "p1",
            streamProfiles = listOf(
                StreamProfile(id = "p1", name = "первый", audio = AudioSettings(bitrateKbps = 64)),
            ),
        )

        assertEquals(192, already.migrate().audio.bitrateKbps)
    }

    // --- Сцены ---
    //
    // Модель Scene лежала в коде и не читалась никем. Теперь она решает, что в
    // кадре, поэтому важнее всего проверить обратную совместимость: у кого сцен
    // нет, для того ничего не должно измениться.

    private fun withLayers() = AppSettings(
        overlays = listOf(
            OverlayConfig(id = "o1", url = "https://a"),
            OverlayConfig(id = "o2", url = "https://b"),
        ),
        browserWidgets = listOf(BrowserWidgetConfig(id = "w1", url = "https://c")),
    )

    @Test
    fun `без сцен показывается всё, как было до их появления`() {
        val s = withLayers()
        assertEquals(2, s.activeOverlays().size)
        assertEquals(1, s.activeBrowserWidgets().size)
    }

    @Test
    fun `сцена оставляет в кадре только свои слои`() {
        val base = withLayers()
        val s = base.copy(
            scenes = listOf(Scene(id = "s1", name = "камера", overlayIds = listOf("o2"))),
            selectedSceneId = "s1",
        )

        assertEquals(listOf("o2"), s.activeOverlays().map { it.id })
        assertEquals("виджет не выбран в сцене — в кадре его нет", 0, s.activeBrowserWidgets().size)
    }

    @Test
    fun `сцена без слоёв очищает кадр, а не показывает всё`() {
        // Пустой список слоёв — это осознанный выбор «чистая картинка», а не
        // «настройка не задана». Иначе сцену нельзя было бы использовать для
        // того, ради чего её чаще всего заводят.
        val s = withLayers().copy(
            scenes = listOf(Scene(id = "s1", name = "чисто")),
            selectedSceneId = "s1",
        )
        assertEquals(0, s.activeOverlays().size)
    }

    @Test
    fun `выбранная сцена находится по идентификатору`() {
        val s = AppSettings(
            scenes = listOf(Scene(id = "a", name = "A"), Scene(id = "b", name = "B")),
            selectedSceneId = "b",
        )
        assertEquals("B", s.selectedScene()?.name)
    }
}
