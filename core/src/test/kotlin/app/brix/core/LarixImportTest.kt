package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Импорт ссылок Larix Grove (ими же делится IRL Pro), 16.09.
 * Формат: https://softvelum.com/larix/grove/
 */
class LarixImportTest {

    @Test
    fun `два соединения и кодер разбираются`() {
        val link = "larix://set/v1?" +
            "conn[][url]=srtla%3A%2F%2Fbelabox.example%3A5000&conn[][name]=Bond&" +
            "conn[][srtstreamid]=live%2Fme%3Fsrtauth%3Dsecret&conn[][srtlatency]=2500&" +
            "conn[][url]=rtmp%3A%2F%2Flive.example%2Fapp%2Fkey&conn[][active]=off&" +
            "enc[vid][res]=1920x1080&enc[vid][fps]=60&enc[vid][bitrate]=6000&enc[vid][format]=hevc&" +
            "enc[aud][bitrate]=160"
        val config = SettingsDeepLink.decodeAny(link)!!

        assertEquals(2, config.serverProfiles.size)
        val srt = config.serverProfiles[0]
        assertEquals(ServerType.SRTLA, srt.type)
        assertEquals("Bond", srt.name)
        assertEquals("srtla://belabox.example:5000", srt.baseUrl)
        assertEquals("live/me?srtauth=secret", srt.streamId)
        assertEquals(2500, srt.latencyMs)
        assertEquals(true, srt.enabled)

        val rtmp = config.serverProfiles[1]
        assertEquals(ServerType.RTMP, rtmp.type)
        assertEquals("live.example", rtmp.name)
        assertEquals(false, rtmp.enabled)

        val video = config.streamProfiles.single().video
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(60, video.fps)
        assertEquals(6000, video.bitrateKbps)
        assertEquals(Codec.HEVC, video.codec)
        assertEquals(160, config.streamProfiles.single().audio.bitrateKbps)
    }

    @Test
    fun `скобки в ключах могут быть закодированы`() {
        val link = "larix://set/v1?conn%5B%5D%5Burl%5D=srt%3A%2F%2Fh%3A4000"
        assertEquals("srt://h:4000", SettingsDeepLink.decodeAny(link)!!.serverProfiles.single().baseUrl)
    }

    @Test
    fun `без кодера профиль не создаётся`() {
        val link = "larix://set/v1?conn[][url]=srt%3A%2F%2Fh%3A4000"
        assertEquals(0, SettingsDeepLink.decodeAny(link)!!.streamProfiles.size)
    }

    @Test
    fun `только непонятные схемы — не импорт`() {
        assertNull(SettingsDeepLink.decodeAny("larix://set/v1?conn[][url]=rist%3A%2F%2Fh%3A1"))
        assertNull(SettingsDeepLink.decodeAny("larix://set/v1"))
    }
}
