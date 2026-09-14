package app.brix.core.diagnostics

import app.brix.core.AppSettings
import app.brix.core.ChatSettings
import app.brix.core.MoblinkSettings
import app.brix.core.OverlayConfig
import app.brix.core.Scene
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выгрузка диагностики не должна опознавать владельца.
 *
 * Это не гипотетическая осторожность: до 14.09 здесь пряталось единственное
 * поле `passphrase`, которое заведомо всегда было пустым (задать его из
 * приложения было нельзя). Открытым уезжало всё остальное — ключ трансляции,
 * адрес личного сервера, имена каналов и токен виджета DonationAlerts.
 * Кнопка «Поделиться диагностикой» отдавала ровно то, чего отдавать нельзя.
 */
class DiagnosticsRedactionTest {

    private fun settings() = AppSettings(
        serverProfiles = listOf(
            ServerProfile(
                id = "rtmp",
                name = "twitch",
                type = ServerType.RTMP,
                baseUrl = "rtmp://ingest.example.com/app/live_111_SECRETKEY",
            ),
            ServerProfile(
                id = "srtla",
                name = "bbox",
                type = ServerType.SRTLA,
                baseUrl = "srtla://example.com:5000",
                streamId = "publish/live?srtauth=SECRETAUTH",
                latencyMs = 4321,
                preferIpv4 = true,
            ),
        ),
        moblink = MoblinkSettings(password = "SECRETRELAY"),
        chat = ChatSettings(
            twitchChannel = "SECRETCHANNEL",
            vkClientSecret = "SECRETVK",
        ),
        overlays = listOf(OverlayConfig(id = "o", url = "https://donationalerts.com/widget/SECRETTOKEN")),
        scenes = listOf(Scene(id = "s", name = "SECRETSCENE", imageUri = "/storage/emulated/0/SECRETPATH.png")),
        // Старое поле для миграции. Именно оно проехало мимо первой версии
        // вырезания: список `overlays` чистился, а этот дубль — нет.
        overlayUrl = "https://www.donationalerts.com/widget/alerts?token=SECRETWIDGET",
    )

    @Test
    fun `ничто опознающее владельца не попадает в выгрузку`() {
        val json = Diagnostics.redactedJson(settings())
        assertFalse("ключ RTMP из пути baseUrl", json.contains("SECRETKEY"))
        assertFalse("srtauth из streamId", json.contains("SECRETAUTH"))
        assertFalse("адрес личного сервера", json.contains("example.com"))
        assertFalse("имя профиля", json.contains("twitch"))
        assertFalse("пароль Moblink", json.contains("SECRETRELAY"))
        assertFalse("секрет VK", json.contains("SECRETVK"))
        assertFalse("имя канала", json.contains("SECRETCHANNEL"))
        assertFalse("токен виджета доната", json.contains("SECRETTOKEN"))
        assertFalse("токен в старом поле overlayUrl", json.contains("SECRETWIDGET"))
        assertFalse("имя сцены", json.contains("SECRETSCENE"))
        assertFalse("путь к файлу заставки", json.contains("SECRETPATH"))
    }

    @Test
    fun `настройки, влияющие на поведение, остаются`() {
        val json = Diagnostics.redactedJson(settings())
        // Вырезание не должно превратиться в «на всякий случай уберём всё»:
        // отчёт без настроек бесполезен. Тип сервера и его числа — это то,
        // с чего начинается разбор любой жалобы на транспорт.
        assertTrue("тип сервера", json.contains("SRTLA"))
        assertTrue("тип сервера", json.contains("RTMP"))
        // Нестандартные значения намеренно: kotlinx не пишет поля, равные
        // умолчанию, и проверка на дефолт прошла бы вхолостую.
        assertTrue("задержка", json.contains("4321"))
        assertTrue("принудительный IPv4", json.contains("preferIpv4"))
    }
}
