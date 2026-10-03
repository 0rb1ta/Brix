package app.brix.streaming

/**
 * Какие сети заводить каналами эфира, а какие держать в запасе.
 *
 * Вынесено из колбэков SrtlaStreamer по той же причине, что и
 * [SrtlaReconnectController]: решение зависело от Android-класса `Network` и
 * проверялось только на улице, а баг с `srt://` (аудит 23.09) сидел ровно здесь —
 * вторую сеть отбрасывали насовсем, и уход с Wi-Fi убивал эфир. [N] — ключ сети:
 * в приложении `android.net.Network`, в тестах что угодно.
 *
 * Правила:
 * - вне активной сессии и с весом 0 (канал выключен в профиле) — не заводим;
 * - SRTLA заводит все сети: серверная сторона склеивает поток обратно;
 * - обычный SRT держит ОДИН канал: второй сокет к тому же серверу ломает сессию
 *   (полевой прогон 14.09). Лишние сети — в запас, и когда рабочая пропала,
 *   первая из запаса становится каналом. Хранить запас обязательно:
 *   BondingNetworkManager второй раз про ту же сеть не сообщит.
 */
internal class ChannelRoster<N : Any> {

    enum class Decision { SKIP, STANDBY, ADD }

    private val standby = LinkedHashMap<N, String>()

    @Synchronized
    fun onAvailable(
        network: N,
        type: String,
        weight: Int,
        sessionActive: Boolean,
        plainSrt: Boolean,
        channelCount: Int,
    ): Decision {
        if (!sessionActive || weight <= 0) return Decision.SKIP
        if (plainSrt && channelCount > 0) {
            standby[network] = type
            return Decision.STANDBY
        }
        standby.remove(network)
        return Decision.ADD
    }

    /**
     * Сеть пропала. Возвращает сеть из запаса, которую надо завести вместо
     * неё, — только для обычного SRT, оставшегося совсем без канала.
     */
    @Synchronized
    fun onLost(network: N, plainSrt: Boolean, channelsLeft: Int, sessionActive: Boolean): Pair<N, String>? {
        standby.remove(network)
        if (!plainSrt || channelsLeft > 0 || !sessionActive) return null
        val next = standby.entries.firstOrNull() ?: return null
        standby.remove(next.key)
        return next.key to next.value
    }

    @get:Synchronized
    val standbyCount: Int get() = standby.size

    @Synchronized
    fun clear() = standby.clear()
}
