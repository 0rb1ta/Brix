package app.brix.streaming

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.brix.core.MicSource

/**
 * Какие микрофоны сейчас подключены и как выбрать нужный.
 *
 * **Почему храним тип, а не устройство.** У `AudioDeviceInfo` есть `id`, но он
 * живёт до переподключения: вынули гарнитуру, вставили обратно — id другой, и
 * сохранённая настройка указывала бы в пустоту. Тип (встроенный, проводная,
 * Bluetooth, USB) переживает и это, и перезагрузку телефона.
 *
 * **Список считается каждый раз заново.** Гарнитуру подключают и отключают
 * прямо посреди эфира, поэтому кэшировать нечего: между двумя нажатиями кнопки
 * набор устройств вполне может смениться.
 */
object MicDevices {

    /** Типы, которые мы показываем как один пункт. Всё прочее — [MicSource.AUTO]. */
    private fun sourceOf(device: AudioDeviceInfo): MicSource? = when (device.type) {
        // Встроенные капсюли приходят РАЗНЫМИ устройствами одного типа и
        // различаются адресом: на S21 это «bottom» и «back» (замер 03.09).
        // На телефонах, которые их не различают, адрес пустой — тогда остаётся
        // один пункт «Встроенный».
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> builtinOf(device.address)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicSource.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicSource.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> MicSource.USB
        else -> null
    }

    /**
     * Куда смотрит капсюль, по строке адреса от HAL.
     *
     * **Это самая непереносимая часть решения, и её границы надо знать.**
     * Слова здесь — то, что сообщают известные нам прошивки: на S21 это
     * «bottom» и «back» (замер 03.09). Другой производитель волен написать
     * что угодно или не написать ничего. Если адрес не опознан, капсюль
     * становится просто «встроенным»: пункт в списке один, выбрать между
     * двумя нельзя — но и вранья нет, а звук работает.
     *
     * Неопознанные адреса пишутся в лог (см. [logInputs]), чтобы список слов
     * пополнялся по фактам с чужих устройств, а не по догадкам.
     */
    private fun builtinOf(address: String): MicSource = when {
        address.isBlank() -> MicSource.BUILTIN
        listOf("bottom", "primary", "main").any { address.contains(it, true) } -> MicSource.BUILTIN_BOTTOM
        listOf("back", "rear", "camera").any { address.contains(it, true) } -> MicSource.BUILTIN_BACK
        listOf("top", "front", "secondary").any { address.contains(it, true) } -> MicSource.BUILTIN_TOP
        else -> MicSource.BUILTIN
    }

    private fun inputs(context: Context): List<AudioDeviceInfo> = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }.getOrDefault(emptyList())

    /**
     * Что телефон сообщает о своих входах — в лог, одной строкой на устройство.
     *
     * Нужно затем, что аудиополитика различает больше микрофонов, чем обычно
     * видно прикладному API: на S21 в `dumpsys media.audio_policy` есть три
     * встроенных порта (`@:bottom`, `@:back`, `@:both`), и вопрос, отдаёт ли их
     * `getDevices()` раздельно, решается только замером на устройстве.
     */
    fun logInputs(context: Context) {
        inputs(context).forEach { d ->
            android.util.Log.i(
                "BrixMic",
                "вход: type=${d.type} address='${d.address}' name='${d.productName}' " +
                    "channels=${d.channelCounts.joinToString(",")} id=${d.id}" +
                    // Метка ради переносимости: если встроенный капсюль не
                    // опознан по адресу, значит на этом телефоне выбрать между
                    // капсюлями нельзя, и слово надо добавить в builtinOf.
                    if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                        builtinOf(d.address) == MicSource.BUILTIN &&
                        d.address.isNotBlank()
                    ) {
                        " [адрес не опознан]"
                    } else {
                        ""
                    },
            )
        }
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.microphones.forEach { m ->
                android.util.Log.i(
                    "BrixMic",
                    "капсюль: id=${m.id} address='${m.address}' location=${m.location} " +
                        "directionality=${m.directionality} pos=${m.position}",
                )
            }
        }.onFailure { android.util.Log.w("BrixMic", "getMicrophones недоступен: ${it.message}") }
    }

    /**
     * Что можно выбрать прямо сейчас: всегда [MicSource.AUTO] плюс типы
     * подключённых устройств. Показывать в списке недоступное — тот же обман,
     * что тумблер без кода за ним.
     */
    fun available(context: Context): List<MicSource> {
        val found = inputs(context).mapNotNull { sourceOf(it) }.distinct()
        return listOf(MicSource.AUTO) + found
    }

    /**
     * Устройство под выбранный тип, или null — если тип AUTO либо устройство
     * отключили. В обоих случаях вызывающий должен отдать выбор системе, а не
     * остаться без звука.
     */
    fun deviceFor(context: Context, source: MicSource, preferredName: String = ""): AudioDeviceInfo? {
        if (source == MicSource.AUTO) return null
        val ofType = devicesFor(context, source)
        // Названное устройство — если оно сейчас на месте. Иначе любое этого
        // типа: гарнитуру могли не взять с собой, и молчащий эфир хуже, чем
        // звук с соседнего микрофона.
        return ofType.firstOrNull { it.productName?.toString() == preferredName && preferredName.isNotEmpty() }
            ?: ofType.firstOrNull()
    }

    /**
     * Все подключённые устройства одного типа, в порядке, который отдала система.
     *
     * Нужно там, где типа мало: двух Bluetooth-гарнитур тип не различает, и
     * выбор между ними — это выбор между именами.
     */
    fun devicesFor(context: Context, source: MicSource): List<AudioDeviceInfo> =
        inputs(context).filter { sourceOf(it) == source }

    /** Имена устройств этого типа — то, что видит человек в списке. */
    fun deviceNamesFor(context: Context, source: MicSource): List<String> =
        devicesFor(context, source).map { it.productName?.toString().orEmpty() }.filter { it.isNotBlank() }

    /** Наш режим в константу MediaRecorder.AudioSource. */
    fun audioSourceOf(processing: app.brix.core.AudioProcessing): Int = when (processing) {
        app.brix.core.AudioProcessing.CAMCORDER -> android.media.MediaRecorder.AudioSource.CAMCORDER
        app.brix.core.AudioProcessing.MIC -> android.media.MediaRecorder.AudioSource.MIC
        app.brix.core.AudioProcessing.UNPROCESSED -> android.media.MediaRecorder.AudioSource.UNPROCESSED
        app.brix.core.AudioProcessing.VOICE_COMMUNICATION ->
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }
}
