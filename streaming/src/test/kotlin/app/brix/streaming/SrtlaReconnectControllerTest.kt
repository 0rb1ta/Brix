package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сценарии переподключения SRTLA без телефона.
 *
 * Каждый тест — случай из аудита 23.09 или из поля: до выноса логики в
 * [SrtlaReconnectController] они проверялись только эфиром, и три из них
 * оказались сломаны одновременно.
 */
class SrtlaReconnectControllerTest {

    /** Планировщик, который ничего не запускает сам: тест решает, когда «прошла задержка». */
    private class ManualScheduler : SrtlaReconnectController.Scheduler {
        val delays = mutableListOf<Long>()
        private val queue = mutableListOf<Pair<Task, () -> Unit>>()

        class Task : SrtlaReconnectController.Handle {
            var cancelled = false
            var fired = false
            override val isActive get() = !cancelled && !fired
            override fun cancel() { cancelled = true }
        }

        override fun after(delayMs: Long, block: () -> Unit): SrtlaReconnectController.Handle {
            delays += delayMs
            val t = Task()
            queue += t to block
            return t
        }

        val pending: Int get() = queue.count { it.first.isActive }

        /** Сработать следующей живой задаче. */
        fun fire() {
            val (task, block) = queue.first { it.first.isActive }
            task.fired = true
            block()
        }
    }

    private class FakeHost(private val session: StreamSession) : SrtlaReconnectController.Host {
        var transportReconnects = 0
        var exhausted = false
        var fatal: String? = null
        var lastAttempt = -1

        override fun moveTo(phase: SessionPhase, gen: Long) = session.transition(phase, gen)
        override fun reconnectTransport() { transportReconnects++ }
        override fun restartFromFailed(): Boolean {
            val gen = session.start() ?: return false
            return session.transition(SessionPhase.Connecting, gen)
        }
        override fun onAttempt(attempt: Int) { lastAttempt = attempt }
        override fun onExhausted(gen: Long) {
            exhausted = true
            session.transition(SessionPhase.Failed, gen)
        }
        override fun onFatal(reason: String) {
            fatal = reason
            session.transition(SessionPhase.Failed, session.generation)
        }
    }

    private class Rig {
        val session = StreamSession()
        val host = FakeHost(session)
        val scheduler = ManualScheduler()
        val controller = SrtlaReconnectController(session, host, scheduler)

        fun goLive() {
            val gen = session.start()!!
            controller.onStarted()
            assertTrue(session.transition(SessionPhase.Connecting, gen))
            controller.onSuccess()
            assertEquals(SessionPhase.Live, session.phase)
        }
    }

    @Test
    fun `manual reconnect from Live and success returns to Live`() {
        val r = Rig()
        r.goLive()

        r.controller.manualReconnect()
        assertEquals("ручное переподключение идёт в Connecting", SessionPhase.Connecting, r.session.phase)
        assertEquals(1, r.host.transportReconnects)

        r.controller.onSuccess()
        assertEquals("раньше сессия оставалась в Reconnecting навсегда", SessionPhase.Live, r.session.phase)
    }

    @Test
    fun `after a manual reconnect a real drop still triggers auto reconnect`() {
        val r = Rig()
        r.goLive()
        r.controller.manualReconnect()
        r.controller.onSuccess()

        r.controller.onDisconnect()
        assertEquals(SessionPhase.Reconnecting, r.session.phase)
        assertEquals("обрыв обязан запланировать попытку", 1, r.scheduler.pending)
    }

    @Test
    fun `several failure signals of one attempt take a single backoff step`() {
        val r = Rig()
        r.goLive()
        r.controller.onDisconnect() // ступень 1
        r.scheduler.fire() // попытка 1: Connecting, transport.reconnect
        assertEquals(SessionPhase.Connecting, r.session.phase)

        // Одна неудачная попытка шлёт три сигнала: обрыв отправителя, таймаут
        // рукопожатия, таймаут соединения.
        r.controller.onDisconnect()
        r.controller.onFailure("SRT handshake timeout")
        r.controller.onFailure("SRTLA connection timeout")

        assertEquals("на одну попытку — одна ступень", listOf(1_000L, 2_000L), r.scheduler.delays)
        assertEquals(1, r.scheduler.pending)
    }

    @Test
    fun `all five backoff steps are really attempted before giving up`() {
        val r = Rig()
        r.goLive()
        r.controller.onDisconnect()
        repeat(5) {
            r.scheduler.fire()
            r.controller.onFailure("SRT handshake timeout")
            r.controller.onFailure("SRTLA connection timeout")
        }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L), r.scheduler.delays)
        assertEquals(5, r.host.transportReconnects)
        assertTrue(r.host.exhausted)
        assertEquals(SessionPhase.Failed, r.session.phase)
    }

    @Test
    fun `late success while Reconnecting goes through Connecting to Live`() {
        val r = Rig()
        r.goLive()
        r.controller.onDisconnect()
        assertEquals(SessionPhase.Reconnecting, r.session.phase)

        // Ответ на старое рукопожатие оживил отправителя раньше запланированной попытки.
        r.controller.onSuccess()

        assertEquals(SessionPhase.Live, r.session.phase)
        assertEquals("запланированная попытка отменена", 0, r.scheduler.pending)
    }

    @Test
    fun `success resets the backoff ladder`() {
        val r = Rig()
        r.goLive()
        r.controller.onDisconnect()
        r.scheduler.fire()
        r.controller.onFailure("timeout")
        r.scheduler.fire()
        r.controller.onSuccess()

        r.controller.onDisconnect()
        assertEquals("после успеха лестница начинается заново", 1_000L, r.scheduler.delays.last())
    }

    @Test
    fun `failure of the first start is fatal, not a retry`() {
        val r = Rig()
        val gen = r.session.start()!!
        r.controller.onStarted()
        r.session.transition(SessionPhase.Connecting, gen)

        r.controller.onFailure("Auth rejected")

        assertEquals("Auth rejected", r.host.fatal)
        assertEquals(0, r.scheduler.pending)
    }

    @Test
    fun `manual reconnect from Failed starts a new session`() {
        val r = Rig()
        r.goLive()
        r.controller.onDisconnect()
        repeat(5) {
            r.scheduler.fire()
            r.controller.onFailure("timeout")
        }
        assertEquals(SessionPhase.Failed, r.session.phase)
        val before = r.host.transportReconnects

        r.controller.manualReconnect()

        assertEquals(SessionPhase.Connecting, r.session.phase)
        assertEquals(before + 1, r.host.transportReconnects)
        assertEquals("счётчик попыток сброшен", 0, r.host.lastAttempt)
    }

    @Test
    fun `stop cancels the scheduled attempt`() {
        val r = Rig()
        r.goLive()
        r.controller.onDisconnect()
        r.controller.onStopped()
        assertEquals(0, r.scheduler.pending)
        assertFalse(r.controller.isReconnectAttempt)
    }
}
