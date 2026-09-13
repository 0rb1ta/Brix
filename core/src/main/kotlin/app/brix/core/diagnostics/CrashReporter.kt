package app.brix.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a process-wide uncaught-exception handler that persists a crash
 * report to [crashDir] before delegating to the previously installed handler
 * (so the system still shows the normal crash dialog / tombstone).
 *
 * Reports are kept locally and exposed through the Diagnostics screen; the app
 * has no backend, so crash reporting is file-based and user-exported.
 */
object CrashReporter {

    private const val MAX_REPORTS = 10
    private val TS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashDir(context: Context): File =
        File(context.filesDir, "crash").also { it.mkdirs() }

    fun listReports(context: Context): List<File> =
        (crashDir(context).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".txt") }
            .sortedByDescending { it.name }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(crashDir(context), "crash_${TS.format(Date())}.txt")
        file.writeText(reportText(context, thread, throwable))
        prune(context)
    }

    internal fun reportText(context: Context, thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("=== Brix crash report ===")
        sb.appendLine("time: ${TS.format(Date())}")
        sb.appendLine("app: ${appVersion(context)}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("thread: ${thread.name} (${thread.id})")
        sb.appendLine()
        sb.appendLine("exception: ${throwable::class.qualifiedName}: ${throwable.message}")
        throwable.stackTraceToString().lineSequence().forEach { sb.appendLine(it) }
        var cause = throwable.cause
        while (cause != null) {
            sb.appendLine()
            sb.appendLine("caused by: ${cause::class.qualifiedName}: ${cause.message}")
            cause.stackTraceToString().lineSequence().forEach { sb.appendLine(it) }
            cause = cause.cause
        }
        return sb.toString()
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun pruneDir(dir: File) {
        val files = dir.listFiles()?.filter { it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: return
        files.drop(MAX_REPORTS).forEach { it.delete() }
    }

    // Exposed for callers that install with a known context.
    fun prune(context: Context) = pruneDir(crashDir(context))
}
