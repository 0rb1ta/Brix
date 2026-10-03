package app.brix.streaming

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import app.brix.core.Codec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoMode(val width: Int, val height: Int, val fps: Int)

/**
 * Что умеет ЭТО железо — один опрос на запуск, дальше из кэша.
 *
 * Настройки показывают только то, что здесь есть: выбор, которого телефон не
 * даёт, раньше заканчивался ошибкой на «Старте». Решение владельца 17.09 —
 * не подменять настройки молча при старте (откат 7616765), а не предлагать
 * недоступное.
 */
data class DeviceCapabilities(
    val hardwareCodecs: Set<Codec>,
    private val encoderSupports: Map<Codec, (Int, Int, Int) -> Boolean>,
    val cameraMaxFps: Int,
    private val cameraSizes: List<Pair<Int, Int>>,
    val opticalStabilization: Boolean,
    val electronicStabilization: Boolean,
    val torch: Boolean,
) {
    fun supports(codec: Codec, mode: VideoMode): Boolean {
        val encoderOk = encoderSupports[codec]?.invoke(mode.width, mode.height, mode.fps) ?: false
        val cameraOk = mode.fps <= cameraMaxFps && cameraSizes.any { (w, h) ->
            w >= mode.width && h >= mode.height && w * mode.height == h * mode.width
        }
        return encoderOk && cameraOk
    }

    companion object {
        @Volatile
        private var cached: DeviceCapabilities? = null

        val ALL = DeviceCapabilities(
            hardwareCodecs = Codec.entries.toSet(),
            encoderSupports = Codec.entries.associateWith { { _, _, _ -> true } },
            cameraMaxFps = Int.MAX_VALUE,
            cameraSizes = listOf(Int.MAX_VALUE / 2 to Int.MAX_VALUE / 2),
            opticalStabilization = true,
            electronicStabilization = true,
            torch = true,
        )

        fun cachedOrNull(): DeviceCapabilities? = cached

        suspend fun load(context: Context): DeviceCapabilities =
            cached ?: withContext(Dispatchers.IO) { probe(context.applicationContext).also { cached = it } }

        private fun probe(context: Context): DeviceCapabilities {
            val hw = mutableMapOf<Codec, MutableList<MediaCodecInfo.VideoCapabilities>>()
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                    if (!info.isEncoder || !isHardware(info)) return@forEach
                    Codec.entries.forEach { codec ->
                        val mime = EncoderCapabilities.mimeFor(codec)
                        if (info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                            runCatching { info.getCapabilitiesForType(mime).videoCapabilities }
                                .getOrNull()?.let { hw.getOrPut(codec) { mutableListOf() } += it }
                        }
                    }
                }
            }
            val encoderSupports = hw.mapValues { (_, caps) ->
                { w: Int, h: Int, fps: Int ->
                    caps.any { c ->
                        runCatching {
                            c.areSizeAndRateSupported(w, h, fps.toDouble()) ||
                                c.areSizeAndRateSupported(h, w, fps.toDouble())
                        }.getOrDefault(false)
                    }
                }
            }

            var maxFps = 30
            var sizes = emptyList<Pair<Int, Int>>()
            var ois = false
            var eis = false
            var torch = false
            runCatching {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val back = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.firstOrNull()
                if (back != null) {
                    val ch = manager.getCameraCharacteristics(back)
                    maxFps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.maxOfOrNull { it.upper } ?: 30
                    sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(SurfaceTexture::class.java)
                        ?.map { it.width to it.height }
                        .orEmpty()
                    ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                    eis = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
                    torch = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }
            return DeviceCapabilities(
                hardwareCodecs = hw.keys,
                encoderSupports = encoderSupports,
                cameraMaxFps = maxFps,
                cameraSizes = sizes,
                opticalStabilization = ois,
                electronicStabilization = eis,
                torch = torch,
            ).also {
                Log.i(
                    "BrixStream",
                    "железо: кодеки=${it.hardwareCodecs} камера до ${maxFps}fps режимов=${sizes.size} " +
                        "OIS=$ois EIS=$eis вспышка=$torch",
                )
            }
        }

        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google", true) && !info.name.startsWith("c2.android", true)
            }
    }
}
