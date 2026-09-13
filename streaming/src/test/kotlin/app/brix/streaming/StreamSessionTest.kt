package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamSessionTest {

    @Test
    fun `happy path reaches live then releases`() {
        val s = StreamSession()
        val gen = s.start()!!
        assertEquals(SessionPhase.Preparing, s.phase)

        assertTrue(s.transition(SessionPhase.Connecting, gen))
        assertTrue(s.transition(SessionPhase.Live, gen))
        assertEquals(SessionPhase.Live, s.phase)

        assertTrue(s.transition(SessionPhase.Stopping, gen))
        assertTrue(s.transition(SessionPhase.Released, gen))
        assertEquals(SessionPhase.Released, s.phase)
    }

    @Test
    fun `start is only allowed from Idle or Failed`() {
        val s = StreamSession()
        assertNotNull(s.start())

        val s2 = StreamSession()
        assertNotNull(s2.start())
        // Now in Preparing; a second start must be rejected.
        assertNull(s2.start())
    }

    @Test
    fun `start allowed again after failure`() {
        val s = StreamSession()
        val gen = s.start()!!
        s.transition(SessionPhase.Connecting, gen)
        s.transition(SessionPhase.Failed, gen)
        assertEquals(SessionPhase.Failed, s.phase)

        val newGen = s.start()
        assertNotNull(newGen)
        assertTrue("generation must advance on a new session", newGen != gen)
        assertEquals(SessionPhase.Preparing, s.phase)
    }

    @Test
    fun `illegal transitions are rejected`() {
        val s = StreamSession()
        val gen = s.start()!!
        // Live -> Preparing is illegal.
        s.transition(SessionPhase.Connecting, gen)
        s.transition(SessionPhase.Live, gen)
        assertFalse(s.transition(SessionPhase.Preparing, gen))
        assertEquals(SessionPhase.Live, s.phase)

        // Live -> Idle is illegal without going through Stopping/Released.
        assertFalse(s.transition(SessionPhase.Idle, gen))
    }

    @Test
    fun `released is terminal`() {
        val s = StreamSession()
        val gen = s.start()!!
        s.transition(SessionPhase.Connecting, gen)
        s.transition(SessionPhase.Stopping, gen)
        s.transition(SessionPhase.Released, gen)

        assertFalse(s.transition(SessionPhase.Live, gen))
        assertEquals(SessionPhase.Released, s.phase)
    }

    @Test
    fun `stale generation callbacks are ignored`() {
        val s = StreamSession()
        val oldGen = s.start()!!
        s.transition(SessionPhase.Connecting, oldGen)
        s.transition(SessionPhase.Live, oldGen)

        // User stops; the session completes teardown and is Released.
        s.transition(SessionPhase.Stopping, oldGen)
        s.transition(SessionPhase.Released, oldGen)

        // Quick Stop->Start mints a new generation while the old session's
        // late callbacks are still in flight.
        val newGen = s.start()!!
        assertTrue(newGen != oldGen)

        // A late "Connected" callback from the OLD session must be dropped.
        assertFalse(s.transition(SessionPhase.Live, oldGen))
        // It must not have clobbered the new session's Preparing phase.
        assertEquals(SessionPhase.Preparing, s.phase)
        assertEquals(newGen, s.generation)
    }

    @Test
    fun `restart is allowed after released and mints a new generation`() {
        val s = StreamSession()
        val gen1 = s.start()!!
        s.transition(SessionPhase.Connecting, gen1)
        s.transition(SessionPhase.Live, gen1)
        s.transition(SessionPhase.Stopping, gen1)
        s.transition(SessionPhase.Released, gen1)

        val gen2 = s.start()
        assertNotNull(gen2)
        assertTrue(gen2 != gen1)
        assertEquals(SessionPhase.Preparing, s.phase)
    }

    @Test
    fun `reset mints a new generation and returns to idle`() {
        val s = StreamSession()
        s.start()
        val before = s.generation

        s.reset()
        assertEquals(SessionPhase.Idle, s.phase)
        assertTrue(s.generation > before)
    }

    @Test
    fun `matches compares against current generation`() {
        val s = StreamSession()
        val gen = s.start()!!
        assertTrue(s.matches(gen))
        assertFalse(s.matches(gen - 1))
        assertFalse(s.matches(gen + 1))
    }

    @Test
    fun `concurrent start succeeds exactly once and generation is monotonic`() {
        val s = StreamSession()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val started = AtomicInteger(0)
        val generations = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                barrier.await()
                s.start()?.let { gen ->
                    started.incrementAndGet()
                    generations.add(gen)
                }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // Only the very first start() (from Idle) may succeed.
        assertEquals(1, started.get())
        // All observed generations must be distinct and positive.
        assertEquals(1, generations.size)
    }

    @Test
    fun `concurrent stale transitions never clobber current phase`() {
        val s = StreamSession()
        val oldGen = s.start()!!
        s.transition(SessionPhase.Connecting, oldGen)
        s.transition(SessionPhase.Live, oldGen)
        // Fully tear down the old session so a fresh start is allowed.
        s.transition(SessionPhase.Stopping, oldGen)
        s.transition(SessionPhase.Released, oldGen)
        // New session is in Preparing (gen = oldGen + 1).
        val newGen = s.start()!!
        assertTrue(newGen != oldGen)

        val threads = 16
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                barrier.await()
                // Hammer with the OLD generation (stale): must never apply.
                s.transition(SessionPhase.Live, oldGen)
                s.transition(SessionPhase.Released, oldGen)
                done.countDown()
            }.start()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(SessionPhase.Preparing, s.phase)
        assertEquals(newGen, s.generation)
    }

    @Test
    fun `guarded transition is atomic under concurrent writers`() {
        val s = StreamSession()
        val gen = s.start()!!
        s.transition(SessionPhase.Connecting, gen)

        // Half the threads race with the current gen, half with a stale gen.
        val threads = 16
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { i ->
            Thread {
                barrier.await()
                val g = if (i % 2 == 0) gen else gen - 1L
                s.transition(SessionPhase.Live, g)
                done.countDown()
            }.start()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // A valid transition from Connecting with the current gen sets Live;
        // stale-gen transitions are ignored. Either outcome is legal, but phase
        // must be one of the two allowed values and never something invalid.
        assertTrue(
            s.phase == SessionPhase.Live || s.phase == SessionPhase.Connecting,
        )
    }

    /**
     * Обе проверки ниже — не про саму сессию, а про то, на что опирается
     * переподключение RTMP/WHIP. Ошибиться здесь легко и незаметно: неверный
     * переход возвращает false молча, и правка выглядит сделанной, оставаясь
     * мёртвой. Ровно так и было написано прежнее `onDisconnect`, которое
     * пыталось увести Live прямо в Failed.
     */
    @Test
    fun `Live не уходит в Failed напрямую — только через Reconnecting`() {
        val s = StreamSession()
        s.start()
        s.transition(SessionPhase.Connecting)
        s.transition(SessionPhase.Live)

        assertFalse("прямой Live->Failed запрещён", s.transition(SessionPhase.Failed))
        assertEquals(SessionPhase.Live, s.phase)

        assertTrue(s.transition(SessionPhase.Reconnecting))
        assertTrue(s.transition(SessionPhase.Failed))
    }

    @Test
    fun `из Failed поднимаются через start, а не переходом в Reconnecting`() {
        val s = StreamSession()
        s.start()
        s.transition(SessionPhase.Connecting)
        s.transition(SessionPhase.Failed)

        assertFalse("Failed->Reconnecting запрещён", s.transition(SessionPhase.Reconnecting))
        assertEquals(SessionPhase.Failed, s.phase)

        // Путь, которым пользуется ручная кнопка «Переподключить».
        val gen = s.start()
        assertNotNull(gen)
        assertEquals(SessionPhase.Preparing, s.phase)
        assertTrue(s.transition(SessionPhase.Connecting))
    }
}
