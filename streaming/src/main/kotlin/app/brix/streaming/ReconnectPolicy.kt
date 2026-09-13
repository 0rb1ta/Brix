package app.brix.streaming

/**
 * Reconnect backoff policy: 1 -> 2 -> 5 -> 10 -> 30 seconds,
 * a bounded number of attempts, after which the session transitions to Failed.
 *
 * The delay list is finite: once [exhausted], no further attempt is scheduled.
 * Call [reset] (e.g. on a successful connection) to start over.
 */
class ReconnectPolicy(
    private val delaysMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L),
) {
    // Счётчик трогают три потока: start()/reconnect() под монитором
    // SrtlaStreamer, onConnectionSuccess() из транспортного колбэка вообще без
    // монитора, и nextDelayMs() из IO-корутины scheduleReconnect. Общего лока у
    // них нет, а `attempt++` — это чтение-изменение-запись. Отсюда пропущенная
    // или задвоенная ступень бэкоффа и расхождение с StreamState.reconnectAttempt.
    // Класс крошечный и не на горячем пути, поэтому просто монитор на всё.
    private var attempt = 0

    @get:Synchronized
    val attempts: Int get() = attempt

    @get:Synchronized
    val exhausted: Boolean get() = attempt >= delaysMs.size

    /** @return the next backoff delay in ms, or null when the policy is exhausted. */
    @Synchronized
    fun nextDelayMs(): Long? {
        if (attempt >= delaysMs.size) return null
        return delaysMs[attempt++]
    }

    /** Start over after a successful (re)connect. */
    @Synchronized
    fun reset() {
        attempt = 0
    }

    /** True if there is at least one more retry available. */
    @Synchronized
    fun hasNext(): Boolean = attempt < delaysMs.size
}