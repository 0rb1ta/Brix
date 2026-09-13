package app.brix.bonding

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Открытая адресация с удалением сдвигом — место, где легко посадить тонкий
 * баг: элемент становится ненаходимым, потому что в его цепочке пробирования
 * образовалась дыра. Поэтому основная проверка здесь — случайные операции
 * против эталонного HashSet, а не отдельные случаи.
 */
class LongHashSetTest {

    @Test
    fun `ведёт себя как HashSet на случайных операциях`() {
        val rnd = Random(20260831)
        val ours = LongHashSet(16)
        val ref = HashSet<Long>()
        repeat(200_000) {
            // Узкий диапазон: коллизии и повторные вставки нужны намеренно.
            val v = rnd.nextLong(0, 3000)
            when (rnd.nextInt(3)) {
                0 -> assertEquals("add($v)", ref.add(v), ours.add(v))
                1 -> assertEquals("remove($v)", ref.remove(v), ours.remove(v))
                else -> assertEquals("size", ref.size, ours.size)
            }
        }
        assertEquals(ref.size, ours.size)
        // Каждый оставшийся элемент обязан находиться.
        for (v in ref) assertTrue("потерян $v", ours.remove(v))
        assertEquals(0, ours.size)
    }

    @Test
    fun `removeIf удаляет ровно подходящие`() {
        val ours = LongHashSet(16)
        val ref = HashSet<Long>()
        for (v in 0L until 5000L) {
            ours.add(v)
            ref.add(v)
        }
        assertTrue(ours.removeIf { it % 3L == 0L })
        ref.removeAll { it % 3L == 0L }
        assertEquals(ref.size, ours.size)
        for (v in ref) assertTrue("потерян $v", ours.remove(v))
        assertEquals(0, ours.size)
    }

    @Test
    fun `removeIf без совпадений ничего не трогает`() {
        val ours = LongHashSet(16)
        for (v in 0L until 100L) ours.add(v)
        assertFalse(ours.removeIf { it > 1000L })
        assertEquals(100, ours.size)
    }

    @Test
    fun `растёт без потерь`() {
        val ours = LongHashSet(16)
        for (v in 0L until 100_000L) ours.add(v)
        assertEquals(100_000, ours.size)
        for (v in 0L until 100_000L) assertTrue("потерян $v", ours.remove(v))
        assertEquals(0, ours.size)
    }

    @Test
    fun `повторное добавление не растит размер`() {
        val ours = LongHashSet(16)
        assertTrue(ours.add(42))
        assertFalse(ours.add(42))
        assertEquals(1, ours.size)
    }

    @Test
    fun `clear опустошает`() {
        val ours = LongHashSet(16)
        for (v in 0L until 500L) ours.add(v)
        ours.clear()
        assertEquals(0, ours.size)
        assertFalse(ours.remove(1))
        assertTrue(ours.add(1))
    }
}
