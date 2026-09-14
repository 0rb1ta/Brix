package app.brix.streaming

import app.brix.core.ConnectionStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * После остановки эфира в состоянии не остаётся цифр, которые показывает HUD.
 *
 * 14.09: сброс был написан трижды и разъехался — SRTLA не чистил список каналов, и
 * HUD показывал застывшие битрейты по Wi-Fi и соте, будто эфир идёт. А
 * `adaptiveBitrateKbps` не обнулял никто, хотя при включённом ABR показывают именно
 * его. Тест держит общий сброс, чтобы это не разошлось снова.
 */
class StoppedStateTest {

    private fun live() = StreamState(
        status = StreamStatus.Connected,
        message = "идёт",
        bitrateKbps = 2500,
        adaptiveBitrateKbps = 2300,
        connections = listOf(ConnectionStat(type = "WIFI", score = 10, rtt = 40, enabled = true)),
        connectedAtElapsedMs = 1000,
    )

    @Test
    fun `цифры, которые показывает HUD, обнуляются`() {
        val s = live().stopped()
        assertEquals(0L, s.bitrateKbps)
        // HUD показывает adaptiveBitrateKbps, когда ABR включён — его и проверяем.
        assertEquals(0L, s.adaptiveBitrateKbps)
        assertTrue("список каналов пуст", s.connections.isEmpty())
        assertNull(s.connectedAtElapsedMs)
        assertEquals(StreamStatus.Idle, s.status)
    }

    @Test
    fun `настройки эфира сбросом не трогаются`() {
        // Сброс относится к показаниям, а не к тому, что человек настроил: мьют
        // микрофона и фонарь после Stop должны остаться как были.
        val s = live().copy(micMuted = true, torchOn = true).stopped()
        assertTrue(s.micMuted)
        assertTrue(s.torchOn)
    }
}
