package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пароль Moblink перестал быть общеизвестным (14.09).
 *
 * До этого значением по умолчанию было «1234» — одинаковым у всех, у кого стоит
 * приложение, при том что служба поднимается в локальной сети.
 */
class MoblinkPasswordTest {

    @Test
    fun `пароль пригоден к вводу руками`() {
        val p = generateMoblinkPassword()
        assertEquals("восемь знаков", 8, p.length)
        // Путающиеся при наборе знаки выброшены намеренно: иначе человек ошибётся
        // на улице и заменит пароль на что-нибудь простое.
        assertTrue("нет путающихся знаков", p.none { it in "01loi" })
        assertTrue("только строчные и цифры", p.all { it.isLowerCase() || it.isDigit() })
    }

    @Test
    fun `два пароля подряд не совпадают`() {
        assertNotEquals(generateMoblinkPassword(), generateMoblinkPassword())
    }

    @Test
    fun `общеизвестный пароль заменяется при миграции`() {
        val migrated = AppSettings(moblink = MoblinkSettings(password = "1234")).migrate()
        assertNotEquals("1234", migrated.moblink.password)
        assertEquals(8, migrated.moblink.password.length)
    }

    @Test
    fun `свой пароль не трогаем`() {
        val mine = AppSettings(moblink = MoblinkSettings(password = "мой пароль")).migrate()
        assertEquals("мой пароль", mine.moblink.password)
    }

    @Test
    fun `миграция идемпотентна`() {
        val once = AppSettings(moblink = MoblinkSettings(password = "1234")).migrate()
        assertEquals(once.moblink.password, once.migrate().moblink.password)
    }
}
