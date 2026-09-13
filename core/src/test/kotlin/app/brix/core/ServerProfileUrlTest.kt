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

    private fun srtla(baseUrl: String, id: String = "") =
        ServerProfile(id = "s", name = "n", type = ServerType.SRTLA, baseUrl = baseUrl, streamId = id)

    @Test
    fun `у SRTLA идентификатор уходит в параметр streamid`() {
        assertEquals(
            "srtla://host:5000?streamid=live/stream/brix?srtauth=KEY",
            srtla("srtla://host:5000", "live/stream/brix?srtauth=KEY").connectUrl(),
        )
    }

    @Test
    fun `прежний streamid в адресе отбрасывается, а не задваивается`() {
        // Иначе при заполнении поля получилось бы два streamid подряд, и
        // приёмник отверг бы подключение.
        assertEquals(
            "srtla://host:5000?streamid=новый",
            srtla("srtla://host:5000?streamid=старый", "новый").connectUrl(),
        )
    }

    @Test
    fun `у SRTLA без заполненного поля адрес не меняется`() {
        // Профили, заведённые до появления поля, держат streamid прямо в адресе.
        val old = srtla("srtla://host:5000?streamid=live/stream/x?srtauth=KEY")
        assertEquals("srtla://host:5000?streamid=live/stream/x?srtauth=KEY", old.connectUrl())
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
