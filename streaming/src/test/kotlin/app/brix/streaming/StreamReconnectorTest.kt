package app.brix.streaming

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лестница переподключения на виртуальном времени: `TestScope` подменяет
 * `delay`, поэтому все пять ступеней (до 30 секунд) проверяются мгновенно и
 * без сети. Ради этой проверяемости [StreamReconnector] и вынесен из стримеров.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamReconnectorTest {

    @Test
    fun `попытки идут по ступеням лестницы, каждая после своей задержки`() = runTest {
        val scope = TestScope(testScheduler)
        val attemptsAt = mutableListOf<Long>()
        val reconnector = StreamReconnector(scope = scope) {
            attemptsAt += testScheduler.currentTime
        }

        val expected = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        var elapsed = 0L
        expected.forEach { step ->
            reconnector.schedule()
            advanceUntilIdle()
            elapsed += step
            assertEquals("ступень $step", elapsed, attemptsAt.last())
        }
        assertEquals(5, attemptsAt.size)
        assertEquals(5, reconnector.attempts)
    }

    @Test
    fun `исчерпание лестницы сообщается один раз и попытки не запускает`() = runTest {
        val scope = TestScope(testScheduler)
        var attempts = 0
        var exhausted = 0
        val reconnector = StreamReconnector(
            scope = scope,
            onExhausted = { exhausted += 1 },
        ) { attempts += 1 }

        repeat(5) {
            reconnector.schedule()
            advanceUntilIdle()
        }
        assertEquals(5, attempts)
        assertEquals(0, exhausted)

        reconnector.schedule()
        advanceUntilIdle()
        // Шестая попытка не должна ни выполниться, ни отложиться.
        assertEquals(5, attempts)
        assertEquals(1, exhausted)
    }

    @Test
    fun `cancel во время ожидания не даёт попытке выполниться`() = runTest {
        val scope = TestScope(testScheduler)
        var attempts = 0
        val reconnector = StreamReconnector(scope = scope) { attempts += 1 }

        reconnector.schedule()
        advanceTimeBy(500)
        reconnector.cancel()
        advanceUntilIdle()

        assertEquals("отменённая попытка не должна воскресить эфир", 0, attempts)
    }

    @Test
    fun `повторный schedule отменяет прошлую попытку, а не копит их`() = runTest {
        val scope = TestScope(testScheduler)
        var attempts = 0
        val reconnector = StreamReconnector(scope = scope) { attempts += 1 }

        // При обрыве колбэки транспорта приходят пачкой: onDisconnect и
        // onConnectionFailed подряд. Без отмены предыдущей задачи одна авария
        // выполнила бы сразу несколько попыток.
        reconnector.schedule()
        reconnector.schedule()
        reconnector.schedule()
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals("но ступени при этом израсходованы", 3, reconnector.attempts)
    }

    @Test
    fun `reset возвращает лестницу в начало`() = runTest {
        val scope = TestScope(testScheduler)
        val attemptsAt = mutableListOf<Long>()
        val reconnector = StreamReconnector(scope = scope) {
            attemptsAt += testScheduler.currentTime
        }

        repeat(3) {
            reconnector.schedule()
            advanceUntilIdle()
        }
        assertEquals(3, reconnector.attempts)

        reconnector.reset()
        assertEquals(0, reconnector.attempts)

        val before = testScheduler.currentTime
        reconnector.schedule()
        advanceUntilIdle()
        assertEquals("после сброса снова первая ступень", 1_000L, attemptsAt.last() - before)
    }

    @Test
    fun `номер попытки сообщается до задержки, чтобы HUD показал её сразу`() = runTest {
        val scope = TestScope(testScheduler)
        val reported = mutableListOf<Int>()
        val reconnector = StreamReconnector(
            scope = scope,
            onScheduled = { reported += it },
        ) { }

        reconnector.schedule()
        // Ещё ничего не ждали — номер уже должен быть известен.
        assertEquals(listOf(1), reported)
        advanceUntilIdle()
        reconnector.schedule()
        assertTrue(reported == listOf(1, 2))
    }
}
