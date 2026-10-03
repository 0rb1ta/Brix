package app.brix.streaming

/**
 * Решения о переподключении SRTLA: когда переходить между фазами сессии, когда
 * брать ступень бэкоффа, когда сдаваться.
 *
 * **Почему отдельным классом.** Эта логика жила внутри SrtlaStreamer вперемешку
 * с камерой, энкодером и колбэками Android, и проверить её можно было только
 * эфиром. Аудит 23.09 нашёл в ней три серьёзных бага разом: ручное
 * переподключение вешало сессию в Reconnecting, отказы забирали лишние ступени
 * (из пяти попыток оставалось три), поздний успех упирался в запрещённый
 * переход. Здесь нет ни Android, ни корутин — только [StreamSession],
 * [ReconnectPolicy] и два интерфейса, поэтому каждый такой сценарий проверяется
 * обычным тестом (SrtlaReconnectControllerTest).
 *
 * Стример по-прежнему отвечает за потоки и за то, чьё это событие
 * (`session.matches(activeGen)`): сюда события приходят уже отфильтрованными.
 */
internal class SrtlaReconnectController(
    private val session: StreamSession,
    private val host: Host,
    private val scheduler: Scheduler,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    /** Что контроллер просит у стримера. */
    interface Host {
        /** Переход фазы с обновлением состояния эфира (`updateSession`). */
        fun moveTo(phase: SessionPhase, gen: Long): Boolean

        /** Поднять транспорт заново; false — некуда (адрес ещё неизвестен). */
        fun reconnectTransport()

        /** Ручное переподключение из Failed: новая сессия и переход в Connecting. */
        fun restartFromFailed(): Boolean

        /** Номер попытки для HUD и уведомления. */
        fun onAttempt(attempt: Int)

        /** Попытки кончились: ошибка в состояние, конвейер остановить. */
        fun onExhausted(gen: Long)

        /** Отказ вне переподключения: [retryable] решает, держать ли конвейер. */
        fun onFatal(reason: String)
    }

    fun interface Scheduler {
        fun after(delayMs: Long, block: () -> Unit): Handle
    }

    interface Handle {
        val isActive: Boolean
        fun cancel()
    }

    private var pending: Handle? = null

    /** Текущая попытка — переподключение, а не первый старт. От этого зависит,
     *  как трактовать отказ: повторить или объявить Failed. */
    @Volatile
    var isReconnectAttempt = false
        private set

    val attempts: Int get() = policy.attempts

    /** Новый эфир: счётчик с нуля, запланированное отменить. */
    fun onStarted() {
        cancelPending()
        policy.reset()
        isReconnectAttempt = false
        host.onAttempt(0)
    }

    /** Остановка или освобождение. */
    fun onStopped() {
        cancelPending()
        isReconnectAttempt = false
    }

    /**
     * Кнопка «Переподключить» (экран, уведомление).
     *
     * С Live и Reconnecting идём в Connecting — как scheduleReconnect и как
     * RTMP/WHIP. Раньше фаза оставалась Reconnecting, а переход Reconnecting ->
     * Live таблица запрещает: эфир шёл под затемнением, часы стояли, и
     * следующий настоящий обрыв уже не запускал автопереподключение.
     */
    fun manualReconnect() {
        cancelPending()
        policy.reset()
        host.onAttempt(0)
        isReconnectAttempt = true
        val gen = session.generation
        when (session.phase) {
            SessionPhase.Live -> {
                if (!host.moveTo(SessionPhase.Reconnecting, gen)) return
                if (!host.moveTo(SessionPhase.Connecting, gen)) return
            }
            SessionPhase.Reconnecting -> if (!host.moveTo(SessionPhase.Connecting, gen)) return
            SessionPhase.Preparing,
            SessionPhase.Connecting,
            -> Unit
            SessionPhase.Failed -> if (!host.restartFromFailed()) return
            else -> return
        }
        host.reconnectTransport()
    }

    /**
     * Транспорт поднялся. Страховка на поздний успех: если сессия считала себя
     * в Reconnecting (ответ на рукопожатие пришёл после объявленного обрыва),
     * идём через Connecting — иначе эфир живой, а сессия навсегда в
     * «переподключении».
     */
    fun onSuccess() {
        policy.reset()
        host.onAttempt(0)
        isReconnectAttempt = false
        cancelPending()
        val gen = session.generation
        if (session.phase == SessionPhase.Reconnecting) host.moveTo(SessionPhase.Connecting, gen)
        host.moveTo(SessionPhase.Live, gen)
    }

    /**
     * Отказ транспорта. Во время переподключения ступень берётся, только если
     * фаза действительно сменилась или попытка ещё не запланирована: таймаут
     * рукопожатия, таймаут соединения и обрыв приходят по одному отказу каждый,
     * и раньше каждый забирал свою ступень.
     */
    fun onFailure(reason: String) {
        if (session.phase == SessionPhase.Failed) return
        if (isReconnectAttempt) {
            val moved = host.moveTo(SessionPhase.Reconnecting, session.generation)
            if (moved || (session.phase == SessionPhase.Reconnecting && pending?.isActive != true)) {
                schedule()
            }
            return
        }
        host.onFatal(reason)
    }

    /** Обрыв живого или подключающегося эфира. */
    fun onDisconnect() {
        if (session.phase == SessionPhase.Live || session.phase == SessionPhase.Connecting) {
            if (host.moveTo(SessionPhase.Reconnecting, session.generation)) schedule()
        }
    }

    private fun schedule() {
        cancelPending()
        val gen = session.generation
        val delayMs = policy.nextDelayMs()
        host.onAttempt(policy.attempts)
        if (delayMs == null) {
            host.onExhausted(gen)
            return
        }
        pending = scheduler.after(delayMs) {
            if (host.moveTo(SessionPhase.Connecting, gen)) {
                isReconnectAttempt = true
                if (session.matches(gen)) host.reconnectTransport()
            }
        }
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
    }
}
