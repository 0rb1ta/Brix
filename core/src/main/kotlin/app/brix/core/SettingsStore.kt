package app.brix.core

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}

class SettingsStore(
    private val file: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    /**
     * Load settings from disk. Returns [Result.failure] if the file exists but
     * contains corrupt JSON — callers MUST check [Result.isFailure] instead of
     * silently falling back to defaults.
     */
    @Synchronized
    fun load(): Result<AppSettings> {
        // Primary missing or corrupt → try the backup before giving up.
        val bak = File(file.parent, "${file.name}.bak")
        val primary = if (file.exists()) {
            runCatching { json.decodeFromString<AppSettings>(file.readText()).migrate() }
        } else null
        if (primary != null && primary.isSuccess) return primary
        if (bak.exists()) {
            val fromBak = runCatching { json.decodeFromString<AppSettings>(bak.readText()).migrate() }
            if (fromBak.isSuccess) {
                android.util.Log.w("SettingsStore", "settings.json unreadable, restored from .bak")
                return fromBak
            }
        }
        // Файла нет вовсе — это первая установка. Пароль Moblink генерируем здесь,
        // а не оставляем значением по умолчанию: служба поднимается в локальной
        // сети, и одинаковый пароль у всех владельцев приложения — не пароль.
        return primary ?: Result.success(
            AppSettings(moblink = MoblinkSettings(password = generateMoblinkPassword())),
        )
    }

    /**
     * Atomic save: write tmp → fsync → backup old → rename tmp → settings.
     * If the process dies mid-write only the tmp file is left behind; on next
     * load the old settings.json (or its .bak) is still intact.
     */
    @Synchronized
    fun save(settings: AppSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parent, "${file.name}.tmp")
        val bak = File(file.parent, "${file.name}.bak")

        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(json.encodeToString(AppSettings.serializer(), settings).toByteArray())
                fos.fd.sync()
            }
            // Copy (not rename!) the current file to .bak: if the tmp→target
            // rename below failed after a rename-based backup, settings.json
            // would be GONE and the user wiped to defaults despite a healthy
            // backup existing.
            if (file.exists()) file.copyTo(bak, overwrite = true)
            if (!tmp.renameTo(file)) {
                // Rename can legitimately fail (cross-device, target locked):
                // copy is the fallback instead of a non-atomic writeText.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("SettingsStore", "save failed; previous settings kept", e)
        }
    }

    companion object {
        fun create(context: Context): SettingsStore =
            SettingsStore(File(context.filesDir, "settings.json"))
    }
}
