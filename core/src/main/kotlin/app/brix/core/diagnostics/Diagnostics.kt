package app.brix.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.brix.core.AppSettings
import app.brix.core.ServerProfile
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds an end-user-exportable diagnostics bundle: device info, redacted
 * settings, recent crash reports and a snapshot of the app's own logcat.
 *
 * No backend exists, so diagnostics are written to app-external storage and
 * shared via a FileProvider-backed ACTION_SEND (the user picks email/cloud).
 */
object Diagnostics {

    private const val AUTHORITY = "app.brix.fileprovider"
    private val TS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun collect(context: Context, settings: AppSettings): String {
        val sb = StringBuilder()
        sb.appendLine("=== Brix diagnostics ===")
        sb.appendLine("time: ${TS.format(Date())}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("--- settings (addresses, keys and channel names removed) ---")
        sb.appendLine(redactedJson(settings))
        sb.appendLine()
        sb.appendLine("--- crash reports (${CrashReporter.listReports(context).size}) ---")
        CrashReporter.listReports(context).forEach { report ->
            sb.appendLine("* ${report.name}")
        }
        sb.appendLine()
        sb.appendLine("--- logcat (app process) ---")
        sb.appendLine(recentLogcat())
        return sb.toString()
    }

    fun export(context: Context, settings: AppSettings): Uri {
        val dir = File(context.getExternalFilesDir(null), "diagnostics").also { it.mkdirs() }
        val file = File(dir, "diagnostics_${TS.format(Date())}.txt")
        file.writeText(collect(context, settings))
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /**
     * Кладёт готовый текст в файл и отдаёт `Uri` для `ACTION_SEND`.
     *
     * Нужен там, где отчёт собирает не этот модуль: паспорт устройства живёт в
     * `streaming` (ему нужен доступ к камерам и кодекам), а `core` про него не
     * знает и знать не должен. Общее у них — только «записать и поделиться»,
     * и повторять здесь `FileProvider` с его authority в третий раз незачем.
     */
    fun exportText(context: Context, prefix: String, text: String): Uri {
        val dir = File(context.getExternalFilesDir(null), "diagnostics").also { it.mkdirs() }
        val file = File(dir, "${prefix}_${TS.format(Date())}.txt")
        file.writeText(text)
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /** Файлы записи сессий (`SessionRecorder`), новые первыми. */
    fun listSessionLogs(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "debug")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /** Самая свежая запись сессии, готовая к отправке. `null`, если записей нет. */
    fun exportLatestSessionLog(context: Context): Uri? {
        val file = listSessionLogs(context).firstOrNull() ?: return null
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /**
     * Настройки для выгрузки: только то, что влияет на ПОВЕДЕНИЕ.
     *
     * **Почему не «вырезать секреты», а «оставить нужное».** Первая версия
     * прятала одно поле `passphrase` — единственное, которое заведомо всегда
     * было пустым, потому что задать его из приложения было нельзя. Всё
     * остальное уезжало открытым: ключ трансляции (у RTMP он в пути `baseUrl`,
     * у SRTLA в `streamId`), адрес личного сервера, имена каналов на Twitch,
     * Kick и VK — то есть имя стримера, — и `url` донат-оверлея, а это токен
     * виджета DonationAlerts.
     *
     * Список «что убрать» нужно поддерживать, и он молча отстаёт от модели:
     * каждое новое поле по умолчанию оказывается в выгрузке. Список «что
     * оставить» ошибается в другую сторону — новое поле по умолчанию НЕ
     * уезжает, и цена ошибки всего лишь «в отчёте чего-то не хватает».
     *
     * Отчёт читают чужие люди. Для разбора жалобы на транспорт нужен тип
     * сервера и его числовые настройки, а не адрес: если адрес понадобится,
     * его можно спросить.
     */
    internal fun redactedJson(settings: AppSettings): String {
        // ВАЖНО: собираем НОВЫЙ объект, а не settings.copy(). copy() — это
        // блок-лист: перечисляешь плохие поля, а всё остальное едет как есть.
        // Он уже подвёл 14.09 — мимо вырезания проехал `overlayUrl`, старое
        // поле для миграции, а в нём токен виджета DonationAlerts. Здесь
        // каждое поле названо явно, поэтому новое поле модели по умолчанию
        // в отчёт НЕ попадает, и цена ошибки — «чего-то не хватает».
        val safe = AppSettings(
            // Видео, звук и ABR живут в StreamProfile, а не здесь — они едут
            // в составе streamProfiles ниже. Полe AppSettings.audio помечено
            // @Deprecated и оставлено только для миграции, поэтому не берём.
            cameraDefaults = settings.cameraDefaults,
            appearance = settings.appearance,
            advanced = settings.advanced,
            hud = settings.hud,
            quickButtons = settings.quickButtons,
            selectedStreamProfileId = settings.selectedStreamProfileId,
            selectedSceneId = settings.selectedSceneId,
            streamProfiles = settings.streamProfiles,
            customPresets = settings.customPresets,
            // Оставляем то, от чего зависит транспорт: тип, задержка,
            // принудительный IPv4, включён ли профиль. Адрес и ключ — нет.
            serverProfiles = settings.serverProfiles.map {
                ServerProfile(
                    id = it.id,
                    name = "",
                    type = it.type,
                    baseUrl = "",
                    streamId = "",
                    latencyMs = it.latencyMs,
                    preferIpv4 = it.preferIpv4,
                    enabled = it.enabled,
                )
            },
            moblink = settings.moblink.copy(password = ""),
            chat = settings.chat.copy(
                twitchChannel = "",
                kickChannel = "",
                vkChannelUrl = "",
                vkClientId = "",
                vkClientSecret = "",
            ),
            // У оверлея в url — токен виджета DonationAlerts, у браузерного
            // виджета — произвольная страница владельца. Геометрия и звук
            // остаются: именно они объясняют нагрузку на кадр.
            overlays = settings.overlays.map { it.copy(url = "") },
            browserWidgets = settings.browserWidgets.map { it.copy(url = "") },
            // Имя сцены человек пишет сам, а imageUri — путь к файлу на его
            // телефоне, обычно с именем пользователя внутри.
            scenes = settings.scenes.map { it.copy(name = "", imageUri = "") },
            // Поля overlay* с пометкой @Deprecated не перечислены намеренно:
            // они остаются со значениями по умолчанию и наружу не едут.
        )
        return runCatching { Json.encodeToString(safe) }.getOrDefault("<serialization failed>")
    }

    /** Our own log tags. `--pid` alone is NOT enough: the framework logs its own
     *  noise (HWUI, InsetsController, GraphicsEnvironment, InputTransport, …)
     *  *from inside our process*, and it drowned the app's signal — a real
     *  export was 482 KB of which almost none was ours. Whitelist our tags,
     *  keep errors from everything else, silence the rest. */
    private val APP_TAGS = listOf(
        "Srtla", "BrixStat", "BrixStream", "BrixLifecycle", "BrixPreview", "BrixTouch",
        "Overlay", "NativeOverlay", "DonationAudio", "Moblink", "StreamService",
        "SettingsStore", "SettingsViewModel", "Brix",
    )

    private fun recentLogcat(): String = runCatching {
        val command = buildList {
            add("logcat")
            add("-d")
            // Lines are dense now that the noise is gone, so the same budget
            // covers far more wall-clock time than it did unfiltered.
            add("-t"); add("6000")
            add("-v"); add("threadtime")
            add("-b"); add("main,crash")
            add("--pid"); add(android.os.Process.myPid().toString())
            APP_TAGS.forEach { add("$it:V") }
            // Keep hard failures from anywhere, then silence the rest.
            add("AndroidRuntime:E")
            add("*:E")
            add("*:S")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also {
            // Bounded: a hung logcat must not wedge the export.
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroy()
        }
    }.getOrDefault("<logcat unavailable>")
}
