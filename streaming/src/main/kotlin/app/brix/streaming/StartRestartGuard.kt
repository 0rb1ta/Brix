package app.brix.streaming

/**
 * Client-side guard against rapid Stop->Start, protecting the receiver's
 * per-source SRT auth-failure throttle.
 *
 * srtla_rec throttles a source after a handful of SRT sessions that are torn
 * down before establishing (each quick stop looks like an auth failure to its
 * rate limiter), so a Stop->Start burst trips the throttle and bricks every
 * following connect for ~60s. This guard enforces a minimum gap after stop and
 * backs off once the user spams Start, surfacing a clear reason instead of
 * silently ignoring the attempt.
 *
 * The clock is injectable so behaviour can be unit-tested deterministically.
 */
class StartRestartGuard(
    val minRestartGapMs: Long = 1500L,
    val rapidStartWindowMs: Long = 60_000L,
    val rapidStartLimit: Int = 10,
    val rapidStartBackoffMs: Long = 5000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Decision {
        /** Start is allowed. */
        ALLOWED,

        /** Too soon after a stop; wait for the minimum restart gap. */
        TOO_SOON_AFTER_STOP,

        /** A previous burst is still cooling down. */
        IN_COOLDOWN,

        /** Rapid-start spam detected; backing off. */
        RATE_LIMITED,
    }

    private var lastStopTime = 0L
    private var blockedUntil = 0L
    private val startTimes = ArrayDeque<Long>()

    /** Record a stop (sets the minimum-gap clock). Synchronized so the write
     *  to [lastStopTime] is published to [tryStart], which reads it under the
     *  same monitor — without this a torn/unsynchronized write can leave
     *  tryStart() seeing a stale value and wrongly allowing a too-early
     *  restart. */
    @Synchronized
    fun onStop() {
        lastStopTime = now()
    }

    /**
     * Decide whether a start at [at] is allowed, and if so record it.
     *
     * @param at the timestamp (ms) of the attempted start; defaults to now.
     */
    @Synchronized
    fun tryStart(at: Long = now()): Decision {
        val decision = checkGate(at)
        if (decision == Decision.ALLOWED) recordStart(at)
        return decision
    }

    /** Gate-only check (no recording, but arms the backoff cooldown on rate
     *  limit so a rejected burst enters cooldown). Use to reject before
     *  expensive work, then call [recordStart] only once the start actually
     *  proceeds. */
    @Synchronized
    fun checkGate(at: Long = now()): Decision {
        if (at - lastStopTime < minRestartGapMs) return Decision.TOO_SOON_AFTER_STOP
        if (at < blockedUntil) return Decision.IN_COOLDOWN
        val within = startTimes.count { at - it <= rapidStartWindowMs }
        if (within >= rapidStartLimit) {
            blockedUntil = at + rapidStartBackoffMs
            return Decision.RATE_LIMITED
        }
        return Decision.ALLOWED
    }

    /** Record a successful start at [at] (rate-limit arming is applied here). */
    @Synchronized
    fun recordStart(at: Long = now()) {
        startTimes.addLast(at)
        while (startTimes.isNotEmpty() && at - startTimes.first() > rapidStartWindowMs) {
            startTimes.removeFirst()
        }
        if (startTimes.size > rapidStartLimit) {
            startTimes.removeLast()
            blockedUntil = at + rapidStartBackoffMs
        }
    }

    /** Remaining ms of cooldown, or 0 when not cooling down. */
    @Synchronized
    fun remainingCooldownMs(at: Long = now()): Long = (blockedUntil - at).coerceAtLeast(0)

    private fun now(): Long = clock()
}
