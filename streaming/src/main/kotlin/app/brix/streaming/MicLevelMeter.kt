package app.brix.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Уровень МИКРОФОНА по каналам — для индикатора на экране стримера.
 *
 * Считается на кадре PCM микрофона с уже применённым усилением и мьютом, но ДО
 * подмешивания донатов (владелец, 17.09: на индикаторе нужен голос, а не сумма
 * всего, что уходит в эфир, — иначе алерт выглядит как крик в микрофон).
 *
 * **Почему пик, а не среднее.** Стримеру нужны два ответа: «звук вообще идёт»
 * и «не перегружен ли вход». Оба видны по пику; среднеквадратичное значение
 * ровнее, но клиппинг на нём не заметен.
 *
 * Значение в долях 0..1 по шкале децибел (-60 дБ и тише — ноль), потому что
 * линейная шкала для голоса почти всё время висит в нижней десятой части.
 * Спад добавлен искусственно: без него полоска мигает на каждой паузе между
 * словами и читается как сбой.
 */
class MicLevelMeter(private val decayPerFrame: Float = 0.25f) {

    private val _left = MutableStateFlow(0f)

    /** Левый канал (в моно — единственный). */
    val left: StateFlow<Float> = _left

    private val _right = MutableStateFlow(0f)
    val right: StateFlow<Float> = _right

    private val _clipping = MutableStateFlow(false)
    val clipping: StateFlow<Boolean> = _clipping

    @Volatile
    var stereo: Boolean = true

    fun submit(pcm16: ByteArray, offset: Int = 0, size: Int = pcm16.size - offset) {
        var peakL = 0
        var peakR = 0
        // Прореживание: кадр — тысячи отсчётов, пик от этого почти не меняется,
        // а считается это в потоке записи звука, где лишние такты не нужны.
        // Шаг кратен кадру стерео (4 байта), иначе каналы перепутаются местами.
        val step = if (stereo) 16 else 8
        val end = offset + size
        var i = offset
        while (i + 1 < end) {
            val l = sampleAt(pcm16, i)
            peakL = max(peakL, abs(l))
            if (stereo && i + 3 < end) {
                peakR = max(peakR, abs(sampleAt(pcm16, i + 2)))
            }
            i += step
        }
        _left.value = max(scale(peakL), _left.value - decayPerFrame)
        _right.value = if (stereo) {
            max(scale(peakR), _right.value - decayPerFrame)
        } else {
            _left.value
        }
        _clipping.value = max(peakL, peakR) >= 32000
    }

    private fun sampleAt(pcm16: ByteArray, index: Int): Int =
        (((pcm16[index].toInt() and 0xFF) or (pcm16[index + 1].toInt() shl 8)).toShort()).toInt()

    /** Децибелы, а не линейная шкала: на линейной голос почти всё время висит
     *  в нижней десятой части полоски. −60 дБ и тише — ноль. */
    private fun scale(peak: Int): Float {
        val normalized = peak / 32768f
        val db = if (normalized <= 0.0001f) -60f else 20f * log10(normalized)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    fun reset() {
        _left.value = 0f
        _right.value = 0f
        _clipping.value = false
    }
}
