package app.brix.streaming

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
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
 *
 * Оба ответа нужны, чтобы приложение не врало пользователю: не предлагало
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
        put("schema", 1)
        put("device", deviceInfo())
        put("encoders", encoders())
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
