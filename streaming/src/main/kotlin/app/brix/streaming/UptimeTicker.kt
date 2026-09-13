package app.brix.streaming

import android.os.SystemClock
import app.brix.core.diagnostics.PeriodicTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Раз в секунду двигает [LiveStreamer.uptimeTick].
 *
 * Счётчик времени эфира в UI считается как разница этой отметки и
 * `connectedAtElapsedMs` — собственного цикла у него нет специально, чтобы не
 * будить процессор вторым таймером с тем же периодом. В SRTLA-пути отметку
 * двигает [StatsReporter] попутно с телеметрией линков; у RTMP и WHIP линков
 * нет, телеметрии тоже, и до 03.09 отметку не двигал никто — поэтому таймер
 * эфира стоял на 0:00.
 */
internal class UptimeTicker(
    private val scope: CoroutineScope,
    private val publishTick: (Long) -> Unit,
    /** Попутная работа того же такта. Отдельный цикл под адаптивный битрейт
     *  здесь не заводится сознательно: два таймера с одним периодом будили бы
     *  процессор дважды в секунду ради одной работы (то же соображение, что в
     *  комментарии к `statsTickElapsedMs`). */
    private val onTick: () -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            PeriodicTasks.register("uptime", 1000).use {
                while (true) {
                    publishTick(SystemClock.elapsedRealtime())
                    onTick()
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
