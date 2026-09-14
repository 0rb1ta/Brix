package app.brix.streaming

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Паспорт устройства: что именно умеет ЭТО железо.
 *
 * **Зачем.** За один день разработки мы дважды упёрлись в вопрос, на который
 * нельзя ответить из документации, только с телефона:
 * 1. Держит ли аппаратный энкодер постоянный битрейт (CBR)? На S21 — ни HEVC,
 *    ни H.264, и узнали мы это подменой кодека и чтением системного лога.
 * 2. Как называются капсюли встроенного микрофона? На S21 «bottom» и «back»,
 *    но это язык прошивки Samsung, и что пишут остальные — неизвестно.
 * 3. Отдаёт ли матрица наш размер кадра НАТИВНО? Камера с ISP съедает 53%
 *    энергобюджета эфира — больше, чем всё остальное вместе, — и если сенсор
 *    не умеет 1280x720, то каждый кадр ещё и масштабируется, а при несовпадении
 *    сторон (сенсор 4:3, эфир 16:9) вдобавок обрезается. Обрезка бесплатна,
 *    когда её делает ISP по готовому режиму, и стоит ватт, когда её делаем мы
 *    в GL. Плюс она сужает угол обзора, что для IRL заметно само по себе.
 *    У S21 объективы — РАЗНЫЕ сенсоры (широкий, сверхширокий), списки режимов
 *    у них тоже разные, поэтому опрашиваем каждый, а не только дефолтный.
 *
 * Эти ответы нужны, чтобы приложение не врало пользователю: не предлагало
 * выбор, которого железо не даёт, и не схлопывало два микрофона в один пункт.
 * Собрать их можно только с реальных устройств.
 *
 * **Что здесь НЕ собирается.** Ни ключей вещания, ни адресов серверов, ни
 * координат, ни имени пользователя, ни чего-либо, что связывает отчёт с
 * человеком. Только характеристики железа, которые одинаковы у всех владельцев
 * такой же модели. Отчёт формируется по явной команде и показывается целиком
 * перед отправкой — отправлять вслепую то, чего не видел, нельзя.
 */
object DeviceReport {

    fun build(context: Context): JSONObject = JSONObject().apply {
        // 2 — добавлен блок cameras.
        put("schema", 2)
        put("device", deviceInfo())
        put("encoders", encoders())
        put("cameras", cameras(context))
        put("audioInputs", audioInputs(context))
    }

    /** Человекочитаемый вид — его же показываем перед отправкой. */
    fun render(context: Context): String = build(context).toString(2)

    private fun deviceInfo() = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("soc", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
        put("androidSdk", Build.VERSION.SDK_INT)
    }

    /**
     * Видеоэнкодеры и то, что о них важно знать заранее: аппаратный ли,
     * и держит ли CBR. Второе — прямой ответ на находку 03.09, когда битрейт
     * перелетал над целью, а причина была видна только в системном логе.
     */
    private fun encoders(): JSONArray {
        val out = JSONArray()
        val wanted = listOf("video/avc", "video/hevc", "video/av01")
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (!info.isEncoder) return@forEach
                info.supportedTypes.filter { it.lowercase() in wanted }.forEach { mime ->
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                    val enc = caps?.encoderCapabilities
                    out.put(
                        JSONObject().apply {
                            put("name", info.name)
                            put("mime", mime)
                            put(
                                "hardware",
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    info.isHardwareAccelerated
                                } else {
                                    !info.name.startsWith("OMX.google", true)
                                },
                            )
                            put("cbr", enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                            put("vbr", enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                            put("cq", enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ))
                        },
                    )
                }
            }
        }
        return out
    }

    /** Кадры, которые приложение реально просит у камеры. */
    private val PROFILE_SIZES = listOf(Size(1280, 720), Size(1920, 1080))

    /**
     * Режимы съёмки по каждому объективу.
     *
     * Логическая камера прячет за собой физические сенсоры, и списки режимов у
     * них разные: на S21 «широкий» и «сверхширокий» — это две разные матрицы.
     * Спрашивать только `cameraIdList` мало, иначе переключение объектива молча
     * меняет тепловой профиль, а в отчёте этого не видно.
     */
    private fun cameras(context: Context): JSONArray {
        val out = JSONArray()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return out
        val ids = linkedSetOf<String>()
        runCatching {
            manager.cameraIdList.forEach { id ->
                ids += id
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }
                        .getOrNull()?.let { ids += it }
                }
            }
        }
        ids.forEach { id -> runCatching { out.put(camera(manager, id)) } }
        return out
    }

    private fun camera(manager: CameraManager, id: String): JSONObject {
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // SurfaceTexture — ровно тот класс, через который RootEncoder заводит и
        // превью, и вход энкодера, так что список режимов нужен именно для него.
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return JSONObject().apply {
            put("id", id)
            put("facing", facingName(chars.get(CameraCharacteristics.LENS_FACING)))
            put(
                "focalMm",
                (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f).toDouble(),
            )
            if (active != null) {
                put("sensorActive", "${active.width()}x${active.height()}")
                put("sensorAspect", aspect(active.width(), active.height()))
            }
            put("outputSizes", JSONArray(sizes.map { describeSize(it, map) }))
            put(
                "profileFit",
                JSONObject().apply {
                    PROFILE_SIZES.forEach { want ->
                        put("${want.width}x${want.height}", fit(want, sizes, active))
                    }
                },
            )
        }
    }

    /** «1920x1080 16:9 60fps» — потолок кадров считаем из минимальной длительности кадра. */
    private fun describeSize(
        size: Size,
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ): String {
        val ns = runCatching {
            map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
        }.getOrDefault(0L)
        val fps = if (ns > 0) (1_000_000_000.0 / ns).toInt() else 0
        val head = "${size.width}x${size.height} ${aspect(size.width, size.height)}"
        return if (fps > 0) "$head ${fps}fps" else head
    }

    /**
     * Вердикт по одному нашему размеру кадра: даёт ли его матрица даром.
     *
     * Отчёт читают глазами, поэтому не флаги, а фраза: «нативно», «масштабирование
     * с 1920x1080», «+ обрезка с 4:3 сенсора». Обрезка попадает сюда всегда, даже
     * при нативном размере: её делает ISP, но угол обзора она съедает в любом случае.
     */
    private fun fit(want: Size, sizes: List<Size>, active: android.graphics.Rect?): String {
        val wantAspect = aspect(want.width, want.height)
        val sensorAspect = active?.let { aspect(it.width(), it.height()) }
        val crop = if (sensorAspect != null && sensorAspect != wantAspect) {
            " + обрезка с $sensorAspect сенсора"
        } else {
            ""
        }
        if (sizes.any { it.width == want.width && it.height == want.height }) return "нативно$crop"
        val src = sizes
            .filter { aspect(it.width, it.height) == wantAspect && it.width >= want.width }
            .minByOrNull { it.width.toLong() * it.height }
        return if (src != null) {
            "масштабирование с ${src.width}x${src.height}$crop"
        } else {
            "нет ни одного режима $wantAspect — масштабирование и обрезка силами GPU"
        }
    }

    private fun aspect(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "?"
        var a = width
        var b = height
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        return "${width / a}:${height / a}"
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    /**
     * Входы звука с адресами. Адрес — это то самое слово («bottom», «back»),
     * по которому мы отличаем капсюли, и словарь этих слов у нас сейчас
     * составлен ровно по одному телефону.
     */
    private fun audioInputs(context: Context): JSONArray {
        val out = JSONArray()
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
                out.put(
                    JSONObject().apply {
                        put("type", d.type)
                        put("builtin", d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                        // Адрес встроенного микрофона описывает железо, а не
                        // владельца. У Bluetooth-гарнитуры адрес — это MAC, то
                        // есть уже про конкретное устройство человека, поэтому
                        // он не выносится.
                        put("address", if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) d.address else "")
                        put("channels", JSONArray(d.channelCounts.toList()))
                    },
                )
            }
        }
        return out
    }
}
