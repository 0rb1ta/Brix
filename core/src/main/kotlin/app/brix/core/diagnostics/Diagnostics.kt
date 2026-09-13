package app.brix.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.brix.core.AppSettings
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
        sb.appendLine("--- settings (passphrases redacted) ---")
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

    private fun redactedJson(settings: AppSettings): String {
        val redacted = settings.copy(
            serverProfiles = settings.serverProfiles.map { it.copy(passphrase = "***") },
        )
        return runCatching { Json.encodeToString(redacted) }.getOrDefault("<serialization failed>")
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
