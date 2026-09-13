package app.brix.core

import app.brix.core.diagnostics.PeriodicTasks
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PeriodicTasksTest {

    @Before
    fun setUp() {
        PeriodicTasks.clearForTest()
    }

    @Test
    fun `состав пуст, пока никто не заявился`() {
        assertEquals("", PeriodicTasks.snapshot())
        assertEquals(0, PeriodicTasks.count())
    }

    @Test
    fun `период печатается в секундах`() {
        PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("vk-chat", 4000)
        assertEquals("stats:1.0s vk-chat:4.0s", PeriodicTasks.snapshot())
    }

    @Test
    fun `дробный период не округляется до нуля`() {
        // 200 мс — период браузерного виджета по умолчанию. Печать целыми
        // секундами превратила бы его в "0s" и спрятала бы самую дорогую
        // задачу из всех (замер 12.09: 9-10% одного ядра).
        PeriodicTasks.register("widget-ab12", 200)
        assertEquals("widget-ab12:0.2s", PeriodicTasks.snapshot())
    }

    @Test
    fun `сортировка по имени, а не по порядку регистрации`() {
        // Строки разных сессий должны сравниваться глазами без перестановок.
        PeriodicTasks.register("uptime", 1000)
        PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("thermal", 5000)
        assertEquals("stats:1.0s thermal:5.0s uptime:1.0s", PeriodicTasks.snapshot())
    }

    @Test
    fun `закрытая заявка уходит из состава`() {
        val reg = PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("thermal", 5000)
        reg.close()
        assertEquals("thermal:5.0s", PeriodicTasks.snapshot())
        assertEquals(1, PeriodicTasks.count())
    }

    @Test
    fun `повторная регистрация того же имени не плодит строк`() {
        // Задача может перезапуститься (эфир остановили и начали заново).
        // Дубли в отчёте сделали бы его нечитаемым, а счётчик — врущим.
        PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("stats", 1000)
        assertEquals(1, PeriodicTasks.count())
        assertEquals("stats:1.0s", PeriodicTasks.snapshot())
    }
}
