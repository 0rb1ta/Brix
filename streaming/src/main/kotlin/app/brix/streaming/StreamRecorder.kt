package app.brix.streaming

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pedro.library.base.StreamBase
import com.pedro.library.base.recording.RecordController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальная запись эфира в MP4 параллельно вещанию.
 *
 * Тумблер «Запись эфира» жил в настройках с самого начала, сохранялся на диск и
 * **не делал ничего** — за ним не стояло ни строчки кода. Это худший вид
 * дефекта из найденных сравнением с Moblin: интерфейс обещал страховку от
 * обрыва, а её не было, и узнать об этом можно было только не найдя файла.
 *
 * Запись идёт тем же энкодером, что и эфир: отдельного прохода кодирования нет,
 * дописывается только мультиплексирование в файл. Поэтому цена по процессору
 * мала, а вот место на диске расходуется всерьёз — при 4 Мбит/с это около
 * 30 МБ в минуту.
 *
 * Пишем в каталог самого приложения (`Android/data/<пакет>/files/Movies`):
 * разрешений он не требует ни на одной версии Android и переживает Scoped
 * Storage без единой оговорки. Плата за это — файлы удаляются вместе с
 * приложением, поэтому путь пишется в лог при каждом старте.
 */
internal class StreamRecorder(
    private val appContext: Context,
    private val stream: StreamBase,
) {
    private val tag = "BrixStream"

    /** Настройка пользователя. Смена во время эфира вступит в силу со следующего. */
    @Volatile
    var enabled = false

    @Volatile
    private var currentFile: File? = null

    private val listener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.i(tag, "запись: $status ${currentFile?.name ?: ""}")
        }

        override fun onError(e: Exception?) {
            // Не роняем эфир из-за записи: вещание важнее файла.
            Log.e(tag, "запись сорвалась, эфир продолжается", e)
            currentFile = null
        }
    }

    /** Начать запись, если она включена. Эфир при отказе не прерывается. */
    fun start() {
        if (!enabled || stream.isRecording) return
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (dir == null) {
            Log.e(tag, "запись: каталог недоступен, пишем только в эфир")
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(tag, "запись: не удалось создать ${dir.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "brix-$stamp.mp4")
        runCatching {
            stream.startRecord(file.absolutePath, RecordController.RecordTracks.ALL, listener)
        }.onSuccess {
            currentFile = file
            Log.i(tag, "запись начата: ${file.absolutePath}")
        }.onFailure {
            currentFile = null
            Log.e(tag, "запись не начата, эфир продолжается: ${it.message}")
        }
    }

    fun stop() {
        if (!stream.isRecording) return
        runCatching { stream.stopRecord() }
            .onSuccess { Log.i(tag, "запись закончена: ${currentFile?.absolutePath}") }
            .onFailure { Log.e(tag, "не удалось закрыть файл записи", it) }
        currentFile = null
    }
}
