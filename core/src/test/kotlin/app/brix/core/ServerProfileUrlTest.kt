package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProfileUrlTest {

    private fun rtmp(baseUrl: String, key: String = "") =
        ServerProfile(id = "s", name = "n", type = ServerType.RTMP, baseUrl = baseUrl, streamId = key)

    @Test
    fun `ключ приклеивается к адресу RTMP`() {
        assertEquals(
            "rtmp://eun10.contribute.live-video.net/app/live_123",
            rtmp("rtmp://eun10.contribute.live-video.net/app", "live_123").connectUrl(),
        )
    }

    @Test
    fun `лишние слэши не задваиваются`() {
        // Адрес с площадки часто копируют вместе с хвостовым слэшем, а ключ —
        // с ведущим. Склейка не должна давать "app//live_123".
        assertEquals(
            "rtmp://host/app/live_123",
            rtmp("rtmp://host/app/", "/live_123").connectUrl(),
        )
    }

    @Test
    fun `пробелы вокруг ключа срезаются`() {
        // Ключ почти всегда попадает в поле вставкой из буфера, вместе с
        // переводом строки или пробелом.
        assertEquals("rtmp://host/app/live_123", rtmp("rtmp://host/app", "  live_123\n").connectUrl())
    }

    @Test
    fun `без ключа адрес не меняется`() {
        // Профили, заведённые до появления поля, держат ключ прямо в адресе.
        val old = rtmp("rtmp://host/app/live_OLDKEY")
        assertEquals("rtmp://host/app/live_OLDKEY", old.connectUrl())
    }

    @Test
    fun `у SRTLA ключ живёт в адресе и не трогается`() {
        // Там это параметр streamid в запросе, его разбирает транспорт.
        val srtla = ServerProfile(
            id = "s", name = "n", type = ServerType.SRTLA,
            baseUrl = "srtla://host:5000?streamid=live/stream/x",
            streamId = "не должно приклеиться",
        )
        assertEquals("srtla://host:5000?streamid=live/stream/x", srtla.connectUrl())
    }

    @Test
    fun `у WHIP ключ тоже не приклеивается`() {
        val whip = ServerProfile(
            id = "s", name = "n", type = ServerType.WHIP,
            baseUrl = "https://host/whip", streamId = "k",
        )
        assertEquals("https://host/whip", whip.connectUrl())
    }
}
