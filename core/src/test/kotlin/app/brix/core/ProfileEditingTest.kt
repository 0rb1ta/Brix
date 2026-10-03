package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditingTest {

    @Test
    fun `video fields must all be positive integers`() {
        assertTrue(videoFieldsValid("1280", "720", "30", "2500"))
        assertTrue(videoFieldsValid(" 1920 ", "1080", "60", "6000"))
        assertFalse("стёртая ширина", videoFieldsValid("", "720", "30", "2500"))
        assertFalse(videoFieldsValid("1280", "0", "30", "2500"))
        assertFalse(videoFieldsValid("1280", "720", "30k", "2500"))
        assertFalse(videoFieldsValid("1280", "720", "30", "-1"))
    }

    /** Аудит 23.09: правка Wi-Fi на старом профиле с пустым списком давала
     *  два Wi-Fi, и стример читал первый — старое умолчание. */
    @Test
    fun `editing a channel on an empty legacy list does not duplicate it`() {
        val result = emptyList<ConnectionPriority>().withPriority("WIFI", enabled = true, weight = 3)

        assertEquals(1, result.count { it.name.equals("WIFI", ignoreCase = true) })
        assertEquals(3, result.first { it.name == "WIFI" }.weight)
        assertTrue(result.any { it.name == "CELLULAR" })
        assertTrue(result.any { it.name == "ETHERNET" })
    }

    @Test
    fun `editing replaces the entry case-insensitively`() {
        val list = listOf(ConnectionPriority("wifi", weight = 8), ConnectionPriority("CELLULAR", weight = 2))
        val result = list.withPriority("WIFI", enabled = false, weight = 5)

        assertEquals(1, result.count { it.name.equals("WIFI", ignoreCase = true) })
        val wifi = result.first { it.name.equals("WIFI", ignoreCase = true) }
        assertEquals(5, wifi.weight)
        assertFalse(wifi.enabled)
    }
}
