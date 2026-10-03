package app.brix.streaming

import app.brix.streaming.ChannelRoster.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Выбор каналов при появлении и пропаже сетей — без телефона. */
class ChannelRosterTest {

    @Test
    fun `plain SRT moves to the standby network when its only channel is lost`() {
        // Аудит 23.09: эфир на Wi-Fi, сота отброшена «канал уже есть» и забыта;
        // ушёл с Wi-Fi — пять попыток на пустом списке и Failed.
        val r = ChannelRoster<String>()
        assertEquals(Decision.ADD, r.onAvailable("wifi#1", "wifi", 8, sessionActive = true, plainSrt = true, channelCount = 0))
        assertEquals(Decision.STANDBY, r.onAvailable("cell#1", "cellular", 2, sessionActive = true, plainSrt = true, channelCount = 1))

        val next = r.onLost("wifi#1", plainSrt = true, channelsLeft = 0, sessionActive = true)

        assertEquals("cell#1" to "cellular", next)
        assertEquals(0, r.standbyCount)
    }

    @Test
    fun `plain SRT keeps exactly one channel`() {
        val r = ChannelRoster<String>()
        r.onAvailable("wifi#1", "wifi", 8, true, plainSrt = true, channelCount = 0)
        assertEquals(Decision.STANDBY, r.onAvailable("cell#1", "cellular", 2, true, plainSrt = true, channelCount = 1))
        assertEquals(Decision.STANDBY, r.onAvailable("eth#1", "ethernet", 5, true, plainSrt = true, channelCount = 1))
    }

    @Test
    fun `losing a standby network does not promote anything`() {
        val r = ChannelRoster<String>()
        r.onAvailable("wifi#1", "wifi", 8, true, true, 0)
        r.onAvailable("cell#1", "cellular", 2, true, true, 1)

        assertNull(r.onLost("cell#1", plainSrt = true, channelsLeft = 1, sessionActive = true))
        assertNull("запасная сеть ушла — заводить нечего", r.onLost("wifi#1", plainSrt = true, channelsLeft = 0, sessionActive = true))
    }

    @Test
    fun `SRTLA bonds every network and keeps no standby`() {
        val r = ChannelRoster<String>()
        assertEquals(Decision.ADD, r.onAvailable("wifi#1", "wifi", 8, true, plainSrt = false, channelCount = 0))
        assertEquals(Decision.ADD, r.onAvailable("cell#1", "cellular", 2, true, plainSrt = false, channelCount = 1))
        assertNull(r.onLost("wifi#1", plainSrt = false, channelsLeft = 1, sessionActive = true))
        assertEquals(0, r.standbyCount)
    }

    @Test
    fun `disabled channel and inactive session are skipped`() {
        val r = ChannelRoster<String>()
        assertEquals(Decision.SKIP, r.onAvailable("cell#1", "cellular", 0, true, false, 0))
        assertEquals(Decision.SKIP, r.onAvailable("wifi#1", "wifi", 8, sessionActive = false, plainSrt = false, channelCount = 0))
    }

    @Test
    fun `no promotion after the session ended`() {
        val r = ChannelRoster<String>()
        r.onAvailable("wifi#1", "wifi", 8, true, true, 0)
        r.onAvailable("cell#1", "cellular", 2, true, true, 1)
        assertNull(r.onLost("wifi#1", plainSrt = true, channelsLeft = 0, sessionActive = false))
    }

    @Test
    fun `clear forgets the standby between sessions`() {
        val r = ChannelRoster<String>()
        r.onAvailable("wifi#1", "wifi", 8, true, true, 0)
        r.onAvailable("cell#1", "cellular", 2, true, true, 1)
        r.clear()
        assertNull(r.onLost("wifi#1", plainSrt = true, channelsLeft = 0, sessionActive = true))
    }
}
