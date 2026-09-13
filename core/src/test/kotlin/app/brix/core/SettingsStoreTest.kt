package app.brix.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `SettingsStore` держит ВСЮ конфигурацию пользователя, и цена его ошибки —
 * профили, серверы, оверлеи и раскладка кнопок разом. При этом тестов у него не
 * было ни одного: и резервная копия, и восстановление после порчи держались на
 * комментариях в коде.
 *
 * Проверяется здесь не сериализация (она закрыта в [SettingsSerializationTest]),
 * а поведение на диске — то, ради чего эта запись вообще устроена сложнее, чем
 * `writeText`.
 */
class SettingsStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = File.createTempFile("brix-settings", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        file = File(dir, "settings.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = SettingsStore(file)

    private fun settingsNamed(name: String) = AppSettings(
        serverProfiles = listOf(
            ServerProfile(id = "s1", name = name, type = ServerType.SRTLA, baseUrl = "srtla://h:5000"),
        ),
    )

    @Test
    fun `отсутствующий файл — это не ошибка, а пустые настройки`() {
        val result = store().load()
        assertTrue(result.isSuccess)
        assertEquals(AppSettings(), result.getOrNull())
    }

    @Test
    fun `сохранённое читается обратно`() {
        val original = settingsNamed("мой сервер")
        store().save(original)

        val loaded = store().load()
        assertTrue(loaded.isSuccess)
        assertEquals(original.serverProfiles, loaded.getOrNull()?.serverProfiles)
    }

    @Test
    fun `запись не оставляет за собой временный файл`() {
        store().save(settingsNamed("a"))
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun `резервная копия хранит ПРЕДЫДУЩЕЕ состояние, а не новое`() {
        val store = store()
        store.save(settingsNamed("первый"))
        store.save(settingsNamed("второй"))

        // Копия делается ДО подмены файла, иначе она бесполезна: обе версии
        // оказались бы одинаковыми, и откатываться было бы не к чему.
        val bak = File(dir, "settings.json.bak")
        assertTrue(bak.exists())
        assertTrue("в копии должно лежать предыдущее", bak.readText().contains("первый"))
        assertTrue(file.readText().contains("второй"))
    }

    @Test
    fun `испорченный файл восстанавливается из резервной копии`() {
        val store = store()
        store.save(settingsNamed("живой"))
        store.save(settingsNamed("новее"))
        // Обрыв записи, севшая флешка, что угодно.
        file.writeText("{ это не json")

        val loaded = store.load()

        assertTrue("порченый основной файл не должен ронять загрузку", loaded.isSuccess)
        assertEquals("живой", loaded.getOrNull()?.serverProfiles?.first()?.name)
    }

    @Test
    fun `когда испорчены оба файла, загрузка честно возвращает ошибку`() {
        val store = store()
        store.save(settingsNamed("a"))
        store.save(settingsNamed("b"))
        file.writeText("{ мусор")
        File(dir, "settings.json.bak").writeText("тоже мусор")

        val loaded = store.load()

        // Молча подставить дефолты здесь нельзя: вызывающий обязан узнать, что
        // конфигурация потеряна, и сказать об этом человеку.
        assertTrue(loaded.isFailure)
    }

    @Test
    fun `испорченный файл без резервной копии — тоже ошибка, а не тишина`() {
        file.writeText("{ мусор")
        assertTrue(store().load().isFailure)
    }

    @Test
    fun `при загрузке применяется миграция старого поля url`() {
        // Схема сменила url на baseUrl; старые файлы на устройствах остались.
        file.writeText(
            """
            {
              "serverProfiles": [
                { "id": "s1", "name": "старый", "type": "SRTLA", "url": "srtla://old:5000" }
              ]
            }
            """.trimIndent(),
        )

        val server = store().load().getOrNull()?.serverProfiles?.first()

        assertEquals("srtla://old:5000", server?.baseUrl)
        assertNull("после миграции старое поле должно опустеть", server?.url)
    }
}
