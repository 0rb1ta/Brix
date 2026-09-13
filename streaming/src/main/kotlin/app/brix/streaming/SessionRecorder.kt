package app.brix.streaming

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись сессии эфира в CSV-файл на телефоне: мощность, температура, тепловой
 * статус и цифры транспорта, по строке в секунду.
 *
 * Зачем отдельно от `BrixStat` в logcat: все тепловые замеры проекта сделаны
 * с телефоном на кабеле, от батареи, по 5-15 минут. Реальный отказ выглядит
 * иначе — двухчасовой выход с пауэрбанком, без adb. Логи logcat в этом
 * сценарии недоступны вовсе, поэтому увидеть настоящую кривую нагрева было
 * неоткуда.
 *
 * Ток заряда на этом телефоне (~5.5 Вт) сопоставим с мощностью всего эфира
 * (5.31 Вт), поэтому `plugged` и знак `curr_ua` тут не украшение, а главная
 * непроверенная переменная: питание от повербанка добавляет тепло поверх
 * съёмки, и ни один замер этого не учитывал.
 *
 * **Урок сессии 07.09.** Первая же полевая запись оказалась сделана на кабеле,
 * но пометила себя автономной, и колонка ватт была нулевой от начала до конца.
 * Отсюда два правила, зашитые ниже: внешнее питание определяется по
 * `EXTRA_PLUGGED` (полный телефон на кабеле рапортует `BATTERY_STATUS_FULL`,
 * а не `CHARGING`), а ватты на внешнем питании не пишутся вовсе — пустая
 * клетка честнее нуля, потому что ноль читается как измерение.
 */
internal class SessionRecorder(private val appContext: Context) {

    private var writer: BufferedWriter? = null
    private var startedAtMs = 0L
    private var currentFile: File? = null

    val isActive: Boolean get() = writer != null

    fun start() {
        if (writer != null) return
        val dir = File(appContext.getExternalFilesDir(null), "debug")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "не удалось создать каталог для записи сессии: $dir")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "session-$stamp.csv")
        runCatching {
            val w = BufferedWriter(FileWriter(file, true))
            w.write(HEADER)
            w.newLine()
            w.flush()
            writer = w
            currentFile = file
            startedAtMs = System.currentTimeMillis()
            Log.i(TAG, "запись сессии начата: ${file.absolutePath}")
        }.onFailure {
            Log.w(TAG, "запись сессии не открылась: ${it.message}")
        }
    }

    fun stop() {
        val w = writer ?: return
        writer = null
        runCatching { w.flush(); w.close() }
        Log.i(TAG, "запись сессии закончена: ${currentFile?.absolutePath}")
        currentFile = null
    }

    /**
     * @param phase фаза сессии, как её видит [StatsReporter].
     * @param transport уже собранные цифры транспорта, в порядке колонок
     *   [HEADER] после `charging`.
     */
    fun record(phase: String, transport: String) {
        val w = writer ?: return
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else Float.NaN
        val voltMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        // Внешнее питание определяется по EXTRA_PLUGGED, а НЕ по
        // EXTRA_STATUS == BATTERY_STATUS_CHARGING. Полный телефон на кабеле
        // рапортует BATTERY_STATUS_FULL, и старая проверка записывала его как
        // «от батареи»: в сессии 07.09 все 664 строки помечены charging=0,
        // при этом заряд ни разу не ушёл со 100%, а ток местами
        // ПОЛОЖИТЕЛЬНЫЙ (+364), то есть тёк в батарею. Весь лог выглядел
        // автономным замером, не будучи им, и выводы по мощности из него
        // делались неверные. EXTRA_PLUGGED покрывает и AC, и USB, и
        // беспроводную, и док.
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        // Сырой ток пишется как есть, вместе с производными ваттами: часть
        // прошивок Samsung отдаёт CURRENT_NOW в миллиамперах вместо
        // микроампер, и по расхождению этих двух колонок это видно сразу,
        // а не после того, как выводы уже сделаны.
        val currUa = runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE)
        // На внешнем питании ватты не считаем вовсе. BatteryManager отдаёт
        // ЧИСТЫЙ ток батареи, а он на кабеле близок к нулю — нагрузку несёт
        // зарядник, — так что число получается не маленькое, а
        // бессмысленное: в сессии 07.09 колонка watts равна 0.00 во всех 664
        // строках при живом эфире на 2.5 Мбит/с. Пустая клетка честнее нуля:
        // ноль читается как измерение, пустота — как «мерить было нельзя».
        //
        // Единицы тока определяются по величине, а не берутся на веру.
        // `BATTERY_PROPERTY_CURRENT_NOW` документирован как микроамперы, но
        // часть прошивок Samsung отдаёт миллиамперы — и S21 из их числа:
        // в трёх замерах 13.09 на живом эфире модуль тока был 700..3200, что
        // в микроамперах означало бы 3 мА и 0.01 Вт на весь телефон с
        // работающей камерой. Проверка по разряду батареи (7-13% за десять
        // минут) дала 6-10 Вт, то есть значения действительно в миллиамперах.
        //
        // Порог в 20 000 разделяет случаи с запасом в два порядка: настоящие
        // микроамперы при эфире это 1-3 МИЛЛИОНА единиц, миллиамперы — тысячи.
        // Попасть в зазор нечем.
        val currAbs = Math.abs(currUa.toDouble())
        val currentMicroAmps = if (currAbs < 20_000) currAbs * 1000.0 else currAbs
        val watts = if (!plugged && currUa != Int.MIN_VALUE && voltMv > 0) {
            currentMicroAmps * voltMv / 1_000_000_000.0
        } else {
            Double.NaN
        }

        val therm = runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                else -> "emergency+"
            }
        }.getOrDefault("?")

        val elapsedS = (System.currentTimeMillis() - startedAtMs) / 1000
        val wall = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val row = listOf(
            elapsedS.toString(),
            wall,
            phase,
            therm,
            pct.toString(),
            fmt(tempC),
            if (currUa == Int.MIN_VALUE) "" else currUa.toString(),
            voltMv.toString(),
            fmt(watts),
            if (plugged) "1" else "0",
            transport,
        ).joinToString(",")

        // Сброс на диск после каждой строки, без буферизации: телефон в этом
        // сценарии убивает система по перегреву — то есть ровно в тот момент,
        // ради которого запись и ведётся. Буфер съел бы именно хвост.
        runCatching {
            w.write(row)
            w.newLine()
            w.flush()
        }.onFailure {
            Log.w(TAG, "строка записи потеряна: ${it.message}")
            stop()
        }
    }

    private fun fmt(v: Float): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%.1f", v)

    private fun fmt(v: Double): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%.2f", v)

    private companion object {
        const val TAG = "BrixSession"
        // Колонка называется `plugged`, а не `charging`: она отвечает на
        // вопрос «годится ли эта строка для выводов о мощности», а не
        // «идёт ли зарядка». Разница не словесная — именно она испортила
        // разбор сессии 07.09.
        const val HEADER = "t_s,wall,phase,therm,batt_pct,batt_temp_c,curr_ua,volt_mv,watts," +
            "plugged,srt_conn,srt_rtt_ms,srt_inflight,srt_dropped,rate_mbps,abr_kbps,links"
    }
}
