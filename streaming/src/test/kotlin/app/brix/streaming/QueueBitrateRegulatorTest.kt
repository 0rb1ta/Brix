package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Время подаётся вручную: восстановление у регулятора рассчитано на
 * тридцатисекундные окна, и без управляемых часов такой тест шёл бы минутами.
 */
class QueueBitrateRegulatorTest {

    private class ManualClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private fun regulator(clock: ManualClock, target: Long = 6_000_000, min: Long = 1_000_000) =
        QueueBitrateRegulator(targetBitrate = target, minimumBitrate = min, clock = clock)

    @Test
    fun `пустая очередь ничего не меняет, пока не выждано окно восстановления`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(3_000_000)
        repeat(20) {
            assertNull(r.update(itemsInCache = 0, cacheSize = 200))
            clock.advance(1_000)
        }
        assertEquals(3_000_000L, r.current())
    }

    @Test
    fun `после тридцати секунд тишины битрейт растёт шагом Larix`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(3_000_000)
        var raised: Long? = null
        repeat(40) {
            raised = r.update(0, 200) ?: raised
            clock.advance(1_000)
        }
        assertEquals(3_500_000L, raised)
    }

    @Test
    fun `рост упирается в целевой битрейт`() {
        val clock = ManualClock()
        val r = regulator(clock, target = 3_200_000)
        r.reset(3_000_000)
        repeat(300) {
            r.update(0, 200)
            clock.advance(1_000)
        }
        assertEquals(3_200_000L, r.current())
    }

    @Test
    fun `почти полная очередь режет вдвое сразу`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(4_000_000)
        assertEquals(2_000_000L, r.update(itemsInCache = 140, cacheSize = 200))
    }

    @Test
    fun `одиночный всплеск очереди битрейт не трогает`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(4_000_000)
        // 25 % — выше мягкого порога, но один такт: это ключевой кадр, не затор.
        assertNull(r.update(itemsInCache = 50, cacheSize = 200))
        assertEquals(4_000_000L, r.current())
    }

    @Test
    fun `наполнение, которое держится, снижает битрейт на долю недоставленного`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(4_000_000)
        assertNull(r.update(50, 200))
        clock.advance(2_000)
        // fill = 0.25 -> остаётся 75 %
        assertEquals(3_000_000L, r.update(50, 200))
    }

    @Test
    fun `битрейт не проваливается ниже минимума профиля`() {
        val clock = ManualClock()
        val r = regulator(clock, min = 1_500_000)
        r.reset(6_000_000)
        repeat(40) {
            r.update(itemsInCache = 190, cacheSize = 200)
            clock.advance(3_000)
        }
        assertEquals(1_500_000L, r.current())
    }

    @Test
    fun `затор в середине окна тишины откладывает восстановление`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(3_000_000)
        repeat(25) {
            r.update(0, 200)
            clock.advance(1_000)
        }
        // Очередь дёрнулась — окно тишины начинается заново.
        r.update(60, 200)
        clock.advance(1_000)
        repeat(10) {
            assertNull("рост не должен наступить сразу после затора", r.update(0, 200))
            clock.advance(1_000)
        }
        assertTrue(r.current() <= 3_000_000)
    }

    @Test
    fun `нулевой размер кэша не роняет регулятор`() {
        val clock = ManualClock()
        val r = regulator(clock)
        r.reset(3_000_000)
        assertNull(r.update(itemsInCache = 0, cacheSize = 0))
        assertEquals(3_000_000L, r.current())
    }
}
