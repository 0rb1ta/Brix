package app.brix.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Лестница переподключения: ждёт очередную задержку из [ReconnectPolicy] и
 * зовёт [onAttempt]. Когда ступени кончились — [onExhausted].
 *
 * Вынесено из `SrtlaStreamer.scheduleReconnect` не ради красоты. RTMP и WHIP до
 * 03.09 не переподключались вообще: `onDisconnect` сразу переводил сессию в
 * `Failed`, а их `reconnect()` требовал фазу `Live`/`Connecting` и потому из
 * `Failed` не выходил — то есть и кнопка «Переподключить» в уведомлении не
 * делала ничего. Единственным лечением был Stop→Start руками. Копировать сюда
 * тот же код четвёртый раз означало бы завести четвёртое место, где его чинят
 * по одному.
 *
 * Отдельный класс ещё и потому, что так лестница наконец проверяется тестами:
 * `scope` задаётся снаружи, и в тестах это `TestScope` с виртуальным временем —
 * ни сети, ни телефона, ни `Thread.sleep` на тридцать секунд.
 *
 * Потокобезопасность: [schedule], [reset] и [cancel] приходят и с колбэков
 * транспорта, и из UI, поэтому работа с [job] идёт под монитором. Сам
 * [ReconnectPolicy] уже синхронизирован внутри.
 */
internal class StreamReconnector(
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    /** Номер начавшейся попытки — для `StreamState.reconnectAttempt` в HUD. */
    private val onScheduled: (attempt: Int) -> Unit = {},
    /** Ступени кончились: дальше решает вызывающий (обычно — перевод в Failed). */
    private val onExhausted: () -> Unit = {},
    private val onAttempt: suspend () -> Unit,
) {
    private val tag = "BrixStream"

    private var job: Job? = null

    val attempts: Int get() = policy.attempts

    /**
     * Запланировать следующую попытку. Предыдущая запланированная отменяется:
     * при обрыве колбэки транспорта прилетают пачкой, и без этого одна авария
     * съедала бы сразу несколько ступеней лестницы.
     */
    fun schedule() {
        // Колбэки зовём ВНЕ монитора: onExhausted уходит в стример менять фазу
        // сессии, и держать при этом свой лок означало бы ровно ту схему, из-за
        // которой 02.09 выносили колбэк из-под лока AdaptiveBitrate.
        val delayMs = takeNextStep()
        if (delayMs == null) {
            Log.w(tag, "reconnect: ступени кончились после ${policy.attempts} попыток")
            onExhausted()
            return
        }
        val attempt = policy.attempts
        Log.w(tag, "reconnect: попытка $attempt через ${delayMs}ms")
        onScheduled(attempt)
        launchAttempt(delayMs)
    }

    @Synchronized
    private fun takeNextStep(): Long? {
        job?.cancel()
        job = null
        return policy.nextDelayMs()
    }

    @Synchronized
    private fun launchAttempt(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            onAttempt()
        }
    }

    /** Успешно подключились — лестница начинается заново. */
    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        policy.reset()
    }

    /** Остановка или release: запланированная попытка не должна воскресить эфир. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }
}
