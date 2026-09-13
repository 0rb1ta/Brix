package app.brix.bonding

/**
 * Множество порядковых номеров пакетов без упаковки в объекты.
 *
 * Зачем: `packetsInFlight` был `HashSet<Long>`, а он упаковывает каждый номер —
 * при добавлении, при поиске и на каждом элементе при полном обходе. Замер
 * 31.08 показал ~350 мкс процессора на пакет при норме в единицы-десятки, и
 * обработка пакетов грела втрое сильнее энкода. Здесь открытая адресация с
 * линейным пробированием: ни одной аллокации на операцию.
 *
 * Номера SRT лежат в диапазоне 0..2^32-1, поэтому -1 свободен и служит меткой
 * пустой ячейки.
 *
 * Не потокобезопасен: вызывающий держит свой монитор, как и раньше.
 */
internal class LongHashSet(initialCapacity: Int = 2048) {

    private var keys = LongArray(tableSizeFor(initialCapacity)) { EMPTY }
    private var mask = keys.size - 1

    var size: Int = 0
        private set

    fun add(value: Long): Boolean {
        require(value != EMPTY) { "номер $value зарезервирован как метка пустоты" }
        var i = index(value)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) {
                keys[i] = value
                size++
                if (size * 10 >= keys.size * 7) grow()
                return true
            }
            if (k == value) return false
            i = (i + 1) and mask
        }
    }

    fun remove(value: Long): Boolean {
        if (value == EMPTY) return false
        var i = index(value)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return false
            if (k == value) {
                removeAt(i)
                size--
                return true
            }
            i = (i + 1) and mask
        }
    }

    /** Удалить всё, что подходит под условие. Возвращает true, если что-то ушло. */
    inline fun removeIf(predicate: (Long) -> Boolean): Boolean {
        var removed = false
        var i = 0
        while (i < capacity()) {
            val k = keyAt(i)
            if (k != EMPTY && predicate(k)) {
                // Сдвиг при удалении может перенести элемент в уже пройденную
                // ячейку, поэтому позицию не увеличиваем и проверяем заново.
                removeSlot(i)
                removed = true
            } else {
                i++
            }
        }
        return removed
    }

    fun clear() {
        keys.fill(EMPTY)
        size = 0
    }

    /** Для removeIf: работа со слотами напрямую, без создания итератора. */
    @PublishedApi
    internal fun capacity(): Int = keys.size

    @PublishedApi
    internal fun keyAt(i: Int): Long = keys[i]

    @PublishedApi
    internal fun removeSlot(i: Int) {
        removeAt(i)
        size--
    }

    /**
     * Удаление при открытой адресации: пустую ячейку нельзя оставить посреди
     * цепочки пробирования, иначе следующие за ней элементы станут ненаходимы.
     * Классический сдвиг по Кнуту (алгоритм R, 6.4).
     */
    private fun removeAt(pos: Int) {
        var i = pos
        keys[i] = EMPTY
        var j = i
        while (true) {
            j = (j + 1) and mask
            val k = keys[j]
            if (k == EMPTY) return
            val home = index(k)
            // Стоит ли k «за» дырой в своей цепочке — тогда его надо подвинуть.
            val shouldMove = if (j > i) home <= i || home > j else home <= i && home > j
            if (shouldMove) {
                keys[i] = k
                keys[j] = EMPTY
                i = j
            }
        }
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2) { EMPTY }
        mask = keys.size - 1
        size = 0
        for (k in old) if (k != EMPTY) add(k)
    }

    private fun index(value: Long): Int {
        // Перемешивание: номера идут подряд, и без него линейное пробирование
        // вырождается в длинные цепочки.
        var h = value * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return (h.toInt()) and mask
    }

    companion object {
        const val EMPTY = -1L

        private fun tableSizeFor(n: Int): Int {
            var c = 16
            while (c < n) c = c shl 1
            return c
        }
    }
}
