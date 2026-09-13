package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDeepLinkTest {

    private fun sampleSettings(): AppSettings {
        val streamProfile = StreamProfile(
            id = "p1",
            name = "Test",
            video = VideoSettings(fps = 60, bitrateKbps = 6000),
        )
        val serverProfile = ServerProfile(
            id = "s1",
            name = "Relay",
            type = ServerType.SRTLA,
            baseUrl = "srtla://host:9000?streamid=x",
        )
        return AppSettings(
            streamProfiles = listOf(streamProfile),
            serverProfiles = listOf(serverProfile),
        )
    }

    @Test
    fun `encode then decode round-trips profiles`() {
        val settings = sampleSettings()

        // includeSecrets: этот тест про саму конвертацию туда-обратно, а
        // вычистка ключей проверяется отдельно ниже. Без флага профили серверов
        // возвращаются урезанными, и сравнение с исходными было бы неверным.
        val link = SettingsDeepLink.encode(settings, includeSecrets = true)
        assertTrue(link.startsWith("brix://settings?config="))

        val decoded = SettingsDeepLink.decode(link)
        assertEquals(settings.streamProfiles, decoded?.streamProfiles)
        assertEquals(settings.serverProfiles, decoded?.serverProfiles)
    }

    @Test
    fun `decode rejects non-brix scheme`() {
        assertNull(SettingsDeepLink.decode("https://example.com?config=%7B%7D"))
    }

    @Test
    fun `decode rejects malformed payload`() {
        assertNull(SettingsDeepLink.decode("brix://settings?config=not-json"))
    }

    @Test
    fun `decode returns null when config param missing`() {
        assertNull(SettingsDeepLink.decode("brix://settings?other=1"))
    }

    @Test
    fun `moblin import maps stream url and video settings`() {
        val moblinJson = """
            {"streams":[{"name":"Main","url":"srt://relay.example:4000","video":{"bitrate":6000000,"fps":60},"audio":{"bitrate":160000}}]}
        """.trimIndent()
        val encoded = java.net.URLEncoder.encode(moblinJson, "UTF-8")
        val link = "moblin://settings?$encoded"

        val decoded = SettingsDeepLink.decodeMoblin(link)

        assertEquals(1, decoded?.serverProfiles?.size)
        val server = decoded!!.serverProfiles[0]
        assertEquals("Main", server.name)
        assertEquals(ServerType.SRTLA, server.type)
        assertEquals("srt://relay.example:4000", server.baseUrl)
        val stream = decoded.streamProfiles[0]
        assertEquals(60, stream.video.fps)
        assertEquals(6000, stream.video.bitrateKbps)
        assertEquals(160, stream.audio.bitrateKbps)
    }

    @Test
    fun `moblin import rejects unrelated scheme`() {
        assertNull(SettingsDeepLink.decodeMoblin("https://example.com?%7B%7D"))
    }

    @Test
    fun `moblin import accepts a raw payload under the brix scheme with no host`() {
        val moblinJson = """{"streams":[{"name":"Main","url":"srt://relay.example:4000"}]}"""
        val encoded = java.net.URLEncoder.encode(moblinJson, "UTF-8")
        val link = "brix://?$encoded"

        val decoded = SettingsDeepLink.decodeMoblin(link)

        assertEquals(1, decoded?.serverProfiles?.size)
        assertEquals("Main", decoded!!.serverProfiles[0].name)
    }

    @Test
    fun `decode then decodeMoblin fallback handles a host-less brix link with moblin payload`() {
        val moblinJson = """{"streams":[{"name":"Main","url":"srt://relay.example:4000"}]}"""
        val encoded = java.net.URLEncoder.encode(moblinJson, "UTF-8")
        val link = "brix://?$encoded"

        val decoded = SettingsDeepLink.decode(link) ?: SettingsDeepLink.decodeMoblin(link)

        assertEquals(1, decoded?.serverProfiles?.size)
    }

    // --- Экспорт ключей вещания ---
    //
    // Ссылка уходит в шер-шит, историю браузера и переписку. Кто её получил,
    // тот может вещать на чужой канал, поэтому по умолчанию ключей в ней быть
    // не должно ни в одном из трёх мест, где они прячутся.

    private fun secretSettings() = AppSettings(
        serverProfiles = listOf(
            ServerProfile(
                id = "rtmp",
                name = "rtmp",
                type = ServerType.RTMP,
                baseUrl = "rtmp://example.com:1935/live/SECRETKEY",
            ),
            ServerProfile(
                id = "srtla",
                name = "bbox",
                type = ServerType.SRTLA,
                baseUrl = "srtla://example.com:5000?streamid=live/stream/x",
                passphrase = "hunter2",
                streamId = "live/stream/x?srtauth=SECRETAUTH",
            ),
        ),
    )

    @Test
    fun `по умолчанию ссылка не несёт ключей вещания`() {
        val link = SettingsDeepLink.encode(secretSettings())
        assertFalse("ключ RTMP в пути baseUrl", link.contains("SECRETKEY"))
        assertFalse("srtauth в streamId", link.contains("SECRETAUTH"))
        assertFalse("пароль SRT", link.contains("hunter2"))
    }

    @Test
    fun `без ключей остаётся адрес, по которому настройку можно дописать`() {
        val decoded = SettingsDeepLink.decode(SettingsDeepLink.encode(secretSettings()))!!
        val rtmp = decoded.serverProfiles.first { it.id == "rtmp" }
        assertEquals("rtmp://example.com:1935/live", rtmp.baseUrl)
        val srtla = decoded.serverProfiles.first { it.id == "srtla" }
        assertEquals("srtla://example.com:5000", srtla.baseUrl)
        assertEquals("", srtla.passphrase)
        assertEquals("", srtla.streamId)
        assertEquals("bbox", srtla.name)
        assertEquals(ServerType.SRTLA, srtla.type)
    }

    @Test
    fun `по явной просьбе ключи переносятся целиком`() {
        val link = SettingsDeepLink.encode(secretSettings(), includeSecrets = true)
        val decoded = SettingsDeepLink.decode(link)!!
        val srtla = decoded.serverProfiles.first { it.id == "srtla" }
        assertEquals("hunter2", srtla.passphrase)
        assertEquals("live/stream/x?srtauth=SECRETAUTH", srtla.streamId)
        assertEquals(
            "rtmp://example.com:1935/live/SECRETKEY",
            decoded.serverProfiles.first { it.id == "rtmp" }.baseUrl,
        )
    }

    @Test
    fun `адрес без пути не портится вычисткой`() {
        val settings = AppSettings(
            serverProfiles = listOf(
                ServerProfile(id = "a", name = "a", type = ServerType.RTMP, baseUrl = "rtmp://example.com:1935"),
            ),
        )
        val decoded = SettingsDeepLink.decode(SettingsDeepLink.encode(settings))!!
        assertEquals("rtmp://example.com:1935", decoded.serverProfiles.first().baseUrl)
    }
}
