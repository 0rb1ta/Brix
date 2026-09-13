package app.brix.streaming

/**
 * Lifecycle phases of a streaming session.
 *
 * Idle -> Preparing -> Connecting -> Live -> Reconnecting -> ... -> Stopping -> Released
 *
 * [Failed] is reachable from any active phase and can be left via [start] (a
 * brand-new session, which mints a fresh generation) or [release] (terminal).
 */
enum class SessionPhase {
    Idle,
    Preparing,
    Connecting,
    Live,
    Reconnecting,
    Stopping,
    Failed,
    Released,
}

/**
 * Single source of truth for the stream session lifecycle.
 *
 * Every async callback that mutates the session (camera, encoder, transport,
 * reconnect worker) carries the [generation] it belongs to. If the UI issues a
 * quick Stop->Start, [start] mints a new generation; stale callbacks from the
 * previous session are then rejected by [transition]/[matches], fixing the
 * "Stop->Start does not reconnect" race without touching the media pipeline.
 *
 * Thread-safety: the session is mutated from UI, service, transport callbacks
 * and coroutines on different threads, so every read/write of [phase] and
 * [generation] is guarded by [lock]. In particular [transition] checks the
 * generation and mutates the phase atomically, so a stale callback can never
 * pass the [matches] check and then be reordered to clobber the new phase.
 */
class StreamSession {

    // Guards phase/generation so concurrent callbacks cannot race each other.
    private val lock = Any()

    // @Volatile for cheap lock-free reads by observers; the check-and-set in
    // start()/transition()/reset() is serialized under [lock] for atomicity.
    @Volatile
    private var _generation = 0L
    val generation: Long get() = _generation

    @Volatile
    var phase: SessionPhase = SessionPhase.Idle
        private set

    private val allowedTransitions: Map<SessionPhase, Set<SessionPhase>> = mapOf(
        SessionPhase.Idle to setOf(SessionPhase.Preparing),
        SessionPhase.Preparing to setOf(
            SessionPhase.Connecting,
            SessionPhase.Stopping,
            SessionPhase.Failed,
            SessionPhase.Released,
        ),
        SessionPhase.Connecting to setOf(
            SessionPhase.Live,
            SessionPhase.Reconnecting,
            SessionPhase.Stopping,
            SessionPhase.Failed,
            SessionPhase.Released,
        ),
        SessionPhase.Live to setOf(
            SessionPhase.Reconnecting,
            SessionPhase.Stopping,
            SessionPhase.Released,
        ),
        SessionPhase.Reconnecting to setOf(
            SessionPhase.Connecting,
            SessionPhase.Stopping,
            SessionPhase.Failed,
            SessionPhase.Released,
        ),
        SessionPhase.Stopping to setOf(
            SessionPhase.Released,
            SessionPhase.Failed,
        ),
        SessionPhase.Failed to setOf(
            SessionPhase.Idle,
            SessionPhase.Released,
        ),
        SessionPhase.Released to emptySet(),
    )

    /** True if [eventGeneration] belongs to the current session. */
    fun matches(eventGeneration: Long): Boolean = synchronized(lock) { eventGeneration == _generation }

    /** True while the session is in a connectable/active phase. */
    fun isActive(): Boolean = synchronized(lock) {
        phase == SessionPhase.Preparing ||
            phase == SessionPhase.Connecting ||
            phase == SessionPhase.Live ||
            phase == SessionPhase.Reconnecting
    }

    /**
     * Begin a new session. Only allowed from [Idle], or after a [Failed] or
     * [Released] phase. Mints a new generation, invalidating any in-flight
     * callbacks from the previous session.
     *
     * @return the new generation, or null if the current phase forbids a start.
     */
    fun start(): Long? = synchronized(lock) {
        if (phase != SessionPhase.Idle && phase != SessionPhase.Failed && phase != SessionPhase.Released) {
            return null
        }
        _generation += 1
        phase = SessionPhase.Preparing
        _generation
    }

    /**
     * Attempt a transition in the current session.
     *
     * @return true if the transition was valid and applied, false otherwise.
     */
    // Генерацию читаем ВНУТРИ лока, а не в аргументе вызова: между чтением
    // _generation и входом в двухаргументный transition другой поток успевает
    // сделать start()/reset() и увеличить её. Тогда «переход в текущей сессии»
    // приносил старую генерацию, matches её отклонял, и переход молча терялся.
    // Комментарий класса обещает, что каждое чтение generation под локом —
    // здесь это обещание и нарушалось.
    fun transition(to: SessionPhase): Boolean = synchronized(lock) {
        transition(to, _generation)
    }

    /**
     * Attempt a transition guarded by [eventGeneration]. Stale callbacks (from a
     * previous session) and illegal transitions are rejected without side effects.
     *
     * The generation check and the phase mutation happen atomically under [lock]:
     * a stale callback cannot pass the [matches] check and then be reordered to
     * clobber the new session's phase.
     */
    fun transition(to: SessionPhase, eventGeneration: Long): Boolean = synchronized(lock) {
        if (!matches(eventGeneration)) return false
        if (to !in allowedTransitions.getValue(phase)) return false
        phase = to
        true
    }

    /** Force back to [Idle] and mint a new generation. Used for a fresh session. */
    fun reset() {
        synchronized(lock) {
            _generation += 1
            phase = SessionPhase.Idle
        }
    }
}