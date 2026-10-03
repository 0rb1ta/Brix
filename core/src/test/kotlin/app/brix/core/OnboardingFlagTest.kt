package app.brix.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Признак «мастер первого запуска пройден» (16.09).
 *
 * Ошибка в одну сторону — мастер выскакивает у человека с настроенным эфиром,
 * в другую — новичок его не видит. Проверяем через настоящий JSON: вся схема
 * держится на том, что kotlinx не пишет значения по умолчанию.
 */
class OnboardingFlagTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(s: AppSettings): AppSettings =
        json.decodeFromString<AppSettings>(json.encodeToString(s)).migrate()

    @Test
    fun `файл до появления мастера считается пройденным`() {
        val old = json.decodeFromString<AppSettings>("""{"serverProfiles":[]}""")
        assertEquals(true, old.migrate().onboardingDone)
    }

    @Test
    fun `свежая установка мастер ещё не прошла и это переживает запись`() {
        assertEquals(false, roundTrip(defaultAppSettings()).onboardingDone)
    }

    @Test
    fun `пройденный мастер остаётся пройденным`() {
        val done = defaultAppSettings().copy(onboardingDone = true)
        assertEquals(true, roundTrip(done).onboardingDone)
    }
}
