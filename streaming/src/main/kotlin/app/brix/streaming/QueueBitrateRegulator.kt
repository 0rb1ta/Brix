package app.brix.streaming

import android.os.SystemClock

/**
 * Адаптивный битрейт для транспортов поверх TCP (RTMP, WHIP).
 *
 * **Почему отдельный класс, а не наш [AdaptiveBitrateSrtBelabox].** Тот — порт
 * belacoder, и пять из семи его переменных состояния описывают RTT: `rttAverage`,
 * `rttMin`, `rttJitter`, `rttAverageDelta`, плюс пороги `rttThMax`/`rttThMin`.
 * У RTMP поверх TCP round-trip недоступен в принципе. Если подать нули, ветка
 * роста становится безусловной (`rttMin` уползает в ноль, порог вырождается в
 * 1 мс, `rttAverageDelta` тождественно ноль) — то есть регулятор поднимал бы
 * битрейт при любом состоянии сети. Это не переиспользование, а отключение
 * половины контура, да ещё и ценой правок в общем классе, которым живёт
 * рабочий SRTLA-путь.
 *
 * **На чём работает здесь.** TCP не теряет пакеты — при нехватке полосы растёт
 * очередь отправки, а с ней задержка, неограниченно. Значит сигнал затора —
 * заполненность этой очереди: `getItemsInCache()/getCacheSize()` у
 * RootEncoder. Доля заполнения это и есть недоставленная часть.
 *
 * **Откуда числа.** Восстановление — по документированному поведению Larix для
 * RTMP: шаг 500 кбит/с не чаще раза в 30 секунд и только если очередь всё это
 * время оставалась пустой. Снижение — пропорционально заполнению очереди
 * (аналог «снизить на долю недоставленного» там же), плюс резкий срез при
 * почти полной очереди, чтобы задержка не успела вырасти.
 *
 * **Пол — [minimumBitrate] из профиля, а не доля от целевого.** У Larix пол 25 %,
 * но в этом проекте сознательно решено не схлопывать качество за пользователя:
 * нижнюю границу задаёт он сам.
 *
 * **Пороги — первого приближения.** Они выведены из поведения очереди, а не
 * измерены в поле; калибровка отдельной задачей.
 */
internal class QueueBitrateRegulator(
    private var targetBitrate: Long,
    private var minimumBitrate: Long,
    private var currentBitrate: Long = targetBitrate,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Очередь почти полна: режем вдвое, не дожидаясь подтверждений. */
    private val hardFill = 0.60

    /** Очередь заметно наполнена — снижаем пропорционально. */
    private val softFill = 0.20

    /** Очередь считается пустой (единичный кадр в полёте — норма). */
    private val clearFill = 0.05

    // «Никогда»: иначе на нулевой отметке часов первый же резкий срез
    // блокировался бы окном ограничения частоты, то есть ровно в момент, когда
    // затор случился на старте эфира.
    private var lastDecreaseAt = NEVER
    private var lastIncreaseAt = NEVER

    /** Момент, с которого очередь непрерывно пуста. 0 — сейчас не пуста. */
    private var clearSince = 0L

    /** Одиночный всплеск очереди — это один кадр-ключ, а не затор. Требуем,
     *  чтобы наполнение держалось два такта подряд, как это делает
     *  `consecutiveBadMid` в SRT-регуляторе по полевой находке 31.08. */
    private var consecutiveSoft = 0

    @Synchronized
    fun configure(target: Long, min: Long) {
        targetBitrate = target
        minimumBitrate = min
        currentBitrate = currentBitrate.coerceIn(min, target)
    }

    @Synchronized
    fun reset(initial: Long = targetBitrate) {
        currentBitrate = initial.coerceIn(minimumBitrate, targetBitrate)
        lastDecreaseAt = NEVER
        lastIncreaseAt = NEVER
        clearSince = 0L
        consecutiveSoft = 0
    }

    @Synchronized
    fun current(): Long = currentBitrate

    /**
     * Один такт регулятора (зовётся раз в секунду).
     *
     * @return новый битрейт, если он изменился, иначе null.
     */
    @Synchronized
    fun update(itemsInCache: Int, cacheSize: Int): Long? {
        if (cacheSize <= 0) return null
        val now = clock()
        val fill = itemsInCache.toDouble() / cacheSize.toDouble()
        var bitrate = currentBitrate

        if (fill <= clearFill) {
            if (clearSince == 0L) clearSince = now
        } else {
            clearSince = 0L
        }
        consecutiveSoft = if (fill >= softFill) consecutiveSoft + 1 else 0

        when {
            fill >= hardFill && now - lastDecreaseAt >= HARD_DECREASE_INTERVAL_MS -> {
                bitrate = maxOf(bitrate / 2, minimumBitrate)
                lastDecreaseAt = now
            }
            consecutiveSoft >= 2 && now - lastDecreaseAt >= SOFT_DECREASE_INTERVAL_MS -> {
                // Снижаем на долю, которую сеть не забрала.
                bitrate = maxOf((bitrate * (1.0 - fill)).toLong(), minimumBitrate)
                lastDecreaseAt = now
            }
            clearSince != 0L &&
                now - clearSince >= RECOVERY_QUIET_MS &&
                now - lastIncreaseAt >= RECOVERY_INTERVAL_MS -> {
                bitrate = minOf(bitrate + RECOVERY_STEP_BPS, targetBitrate)
                lastIncreaseAt = now
            }
        }

        if (bitrate == currentBitrate) return null
        currentBitrate = bitrate
        return bitrate
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2

        const val HARD_DECREASE_INTERVAL_MS = 3_000L
        const val SOFT_DECREASE_INTERVAL_MS = 2_000L

        /** Larix для RTMP восстанавливает раз в 30 с шагами по 500 кбит/с. */
        const val RECOVERY_INTERVAL_MS = 30_000L
        const val RECOVERY_QUIET_MS = 30_000L
        const val RECOVERY_STEP_BPS = 500_000L
    }
}
