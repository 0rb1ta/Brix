package app.brix.streaming

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.brix.bonding.SrtlaClient
import app.brix.core.diagnostics.PeriodicTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Раз в секунду снимает состояние бондинга и транспорта: обновляет то, что
 * видит HUD, и пишет одну машиночитаемую строку в лог.
 *
 * Выделено из [SrtlaStreamer] — обязанность чисто читающая, ни на что в тракте
 * не влияет, и держать её в одном классе с камерой, оверлеями и
 * переподключением было незачем.
 */
internal class StatsReporter(
    private val appContext: Context,
    private val srtlaClient: SrtlaClient,
    private val stream: SrtlaStream,
    private val scope: CoroutineScope,
    private val phase: () -> SessionPhase,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
    /** Тик часов эфира. Отдельно от [updateState] нарочно: см. LiveStreamer.uptimeTick. */
    private val publishTick: (Long) -> Unit,
) {
    private var job: Job? = null

    private val recorder = SessionRecorder(appContext)

    /** Пишем ли сессию в файл. Читается на старте эфира, а не на каждом тике:
     *  переключить запись посреди эфира значило бы получить файл с дырой. */
    @Volatile
    var debugLog: Boolean = false

    fun start() {
        if (job?.isActive == true) return
        if (debugLog) recorder.start()
        job = scope.launch {
            var prevBytes = mapOf<String, Long>()
            PeriodicTasks.register("stats", 1000).use {
                while (true) {
                    updateState { it.copy(connections = srtlaClient.connectionStats()) }
                    publishTick(SystemClock.elapsedRealtime())
                    prevBytes = logStatLine(prevBytes)
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        recorder.stop()
        job?.cancel()
        job = null
    }

    /** Уровень теплового троттлинга устройства, в одной строке с цифрами
     *  транспорта: на телефоне жара и битрейт — это один и тот же размен, а
     *  читая их из двух разных мест, потом невозможно было их сопоставить.
     *  NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN. */
    private fun thermalStatus(): String = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            else -> "emergency+"
        }
    }.getOrDefault("?")

    /**
     * Одна машиночитаемая строка состояния в секунду, тег `BrixStat`.
     *
     * Журнал событий рассказывает, что случилось; для сессии с бондингом нужно
     * другое — как выглядели линки В ТОТ МОМЕНТ. Поля выбраны по реальным
     * разборам: `in=` (сколько секунд назад по линку хоть что-то пришло) — это
     * то, чем выдаёт себя мёртвый входящий канал, пока линк ещё зарегистрирован
     * и ещё отчитывается об исходящих байтах. Ровно та картина, что стоит за
     * «Wi-Fi один работает, добавляешь соту — картинка сыпется», и никакой HUD
     * в приложении её сейчас не покажет. `srtrtt=0` означает, что ABR не идёт.
     *
     * `tasks=` — состав периодических задач, работавших в этот момент. Без него
     * по логу нельзя восстановить, что вообще было включено, и выводы о нагреве
     * приходится строить на догадках: ровно это и случилось при разборе сессии
     * 07.09, где не удалось установить даже, была ли включена электронная
     * стабилизация.
     *
     * @return счётчики байт для дельты на следующем тике.
     */
    private fun logStatLine(prevBytes: Map<String, Long>): Map<String, Long> {
        val links = srtlaClient.debugLinks()
        if (links.isEmpty()) return prevBytes
        val srt = stream.srtDebug()
        val next = HashMap<String, Long>(links.size)
        val linkText = links.joinToString(" | ") { l ->
            val prev = prevBytes[l.type] ?: l.bytesSent
            next[l.type] = l.bytesSent
            val kbps = ((l.bytesSent - prev).coerceAtLeast(0) * 8 / 1000)
            "${l.type}:${l.state} rtt=${l.rttMs} w=${l.windowSize} inf=${l.inFlight} " +
                "in=${l.msSinceInbound / 1000.0}s out=${kbps}kbps score=${l.score}"
        }
        Log.i(
            "BrixStat",
            "phase=${phase()} srtconn=${srt.connected} srtrtt=${srt.rttMs.toInt()} " +
                "srtinf=${srt.inFlight} srtdrop=${srt.droppedPackets} " +
                "rate=${"%.1f".format(java.util.Locale.US, srt.sendRateMbps)}Mbps " +
                "abr=${if (srt.abrEnabled) srt.abrKbps.toString() else "off"} " +
                "therm=${thermalStatus()} | tasks=[${PeriodicTasks.snapshot()}] | $linkText",
        )
        if (recorder.isActive) {
            // Точки с запятой внутри поля линков: они уезжают в одну колонку
            // CSV, а вертикальная черта из строки лога сама по себе разделитель
            // не хуже — лишь бы это была не запятая.
            recorder.record(
                phase = phase().toString(),
                transport = listOf(
                    srt.connected.toString(),
                    srt.rttMs.toInt().toString(),
                    srt.inFlight.toString(),
                    srt.droppedPackets.toString(),
                    "%.1f".format(java.util.Locale.US, srt.sendRateMbps),
                    if (srt.abrEnabled) srt.abrKbps.toString() else "",
                    "\"${linkText.replace(",", ";").replace("\"", "'")}\"",
                ).joinToString(","),
            )
        }
        return next
    }
}
