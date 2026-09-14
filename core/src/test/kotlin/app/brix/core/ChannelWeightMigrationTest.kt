package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Перевод весов каналов из 0…100 в ранг 1…10 (14.09).
 *
 * Трогает сохранённые настройки живых пользователей, поэтому проверяется здесь,
 * а не «на глаз»: ошибка тут молча меняет распределение трафика по каналам или
 * вовсе выключает канал.
 */
class ChannelWeightMigrationTest {

    private fun withWeights(vararg weights: Int): AppSettings {
        val priorities = weights.mapIndexed { i, w ->
            ConnectionPriority(name = "LINK$i", enabled = w > 0, weight = w)
        }
        return AppSettings(
            streamProfiles = listOf(
                StreamProfile(id = "p", name = "p", srtConnectionPriorities = priorities),
            ),
        )
    }

    private fun weightsOf(s: AppSettings) =
        s.streamProfiles.first().srtConnectionPriorities.map { it.weight }

    @Test
    fun `старые значения делятся на десять`() {
        val out = weightsOf(withWeights(80, 20, 100).migrate())
        assertEquals(listOf(8, 2, 10), out)
    }

    @Test
    fun `мелкие старые значения не схлопываются в ноль`() {
        // 1…9 при делении нацело дали бы 0, то есть канал выключился бы молча.
        val out = weightsOf(withWeights(11, 15).migrate())
        assertEquals(listOf(2, 2), out)
    }

    @Test
    fun `ноль остаётся нулём — это выключенный канал`() {
        assertEquals(listOf(0), weightsOf(withWeights(0).migrate()))
    }

    @Test
    fun `значения в новом диапазоне не трогаются`() {
        assertEquals(listOf(1, 5, 10), weightsOf(withWeights(1, 5, 10).migrate()))
    }

    @Test
    fun `миграция идемпотентна`() {
        val once = withWeights(80, 20).migrate()
        assertEquals(weightsOf(once), weightsOf(once.migrate()))
    }
}
