package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRestartGuardTest {

    private fun guard(
        minRestartGapMs: Long = 1500L,
        rapidStartWindowMs: Long = 60_000L,
        rapidStartLimit: Int = 10,
        rapidStartBackoffMs: Long = 5000L,
        clock: () -> Long,
    ) = StartRestartGuard(
        minRestartGapMs = minRestartGapMs,
        rapidStartWindowMs = rapidStartWindowMs,
        rapidStartLimit = rapidStartLimit,
        rapidStartBackoffMs = rapidStartBackoffMs,
        clock = clock,
    )

    private fun mutableClock() = object {
        var now = 1_000_000L
        val get: () -> Long = { now }
    }

    @Test
    fun `first start after stop allowed once gap elapsed`() {
        val clock = mutableClock()
        val g = guard(clock = clock.get)

        // Start with no prior stop is allowed.
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
        g.onStop()
        clock.now += 1500
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
    }

    @Test
    fun `start immediately after stop rejected as too soon`() {
        val clock = mutableClock()
        val g = guard(clock = clock.get)

        g.onStop()
        assertEquals(StartRestartGuard.Decision.TOO_SOON_AFTER_STOP, g.tryStart())
    }

    @Test
    fun `rapid start spam triggers backoff then blocks while cooling down`() {
        val clock = mutableClock()
        val g = guard(rapidStartLimit = 2, rapidStartWindowMs = 4000, rapidStartBackoffMs = 5000, clock = clock.get)

        g.onStop()
        clock.now += 2000 // clear the min-gap

        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
        clock.now += 10
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
        clock.now += 10
        // Third start within the window exceeds the limit -> backoff.
        assertEquals(StartRestartGuard.Decision.RATE_LIMITED, g.tryStart())
        assertTrue(g.remainingCooldownMs() > 0)

        // Still cooling down.
        clock.now += 4000
        assertEquals(StartRestartGuard.Decision.IN_COOLDOWN, g.tryStart())

        // Cooldown elapsed -> allowed again.
        clock.now += 2000
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
        assertEquals(0, g.remainingCooldownMs())
    }

    @Test
    fun `old starts age out of the rapid-start window`() {
        val clock = mutableClock()
        val g = guard(rapidStartLimit = 1, rapidStartWindowMs = 60_000, clock = clock.get)

        g.onStop()
        clock.now += 2000
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())

        // A later start more than a window after the recorded one does not count
        // against the limit (window is 60s, so 61s later the old entry is gone).
        clock.now += 61_000
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
    }

    @Test
    fun `cooldown blocks even after min-gap has elapsed`() {
        val clock = mutableClock()
        val g = guard(rapidStartLimit = 1, rapidStartWindowMs = 4000, rapidStartBackoffMs = 10_000, clock = clock.get)

        g.onStop()
        clock.now += 2000
        g.tryStart() // allowed
        clock.now += 10
        g.tryStart() // exceeds limit -> backoff (cooldown ~10s from now)

        // Min-gap long passed, but cooldown still active (3s < 10s backoff).
        clock.now += 3000
        assertEquals(StartRestartGuard.Decision.IN_COOLDOWN, g.tryStart())

        // After the cooldown elapses it is allowed again.
        clock.now += 8000
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
    }

    @Test
    fun `shared clock domain stop then start after gap`() {
        // Regression for the P0: SrtlaStreamer passes SystemClock.elapsedRealtime
        // into tryStart() and onStop() must use the same domain. A guard whose
        // injected clock is a single shared value must record onStop() against the
        // same ticks it compares in tryStart(). Using the same monotonic clock for
        // both keeps `at - lastStopTime` in a consistent domain.
        var ticks = 10_000L
        val clock: () -> Long = { ticks }
        val g = StartRestartGuard(clock = clock)

        g.onStop()
        ticks += 1500
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())

        ticks += 200
        g.onStop()
        ticks += 1499
        assertEquals(StartRestartGuard.Decision.TOO_SOON_AFTER_STOP, g.tryStart())

        ticks += 1
        assertEquals(StartRestartGuard.Decision.ALLOWED, g.tryStart())
    }
}