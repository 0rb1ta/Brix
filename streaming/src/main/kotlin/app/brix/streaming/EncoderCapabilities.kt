package app.brix.streaming

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.brix.core.Codec

/**
 * Держит ли аппаратура постоянный битрейт (CBR) для выбранного кодека.
 *
 * **Зачем это видеть пользователю.** RootEncoder всегда просит CBR, сам
 * проверяет поддержку и при отказе молча берёт режим по умолчанию (VBR),
 * сообщая об этом единственной строкой в системный лог:
 * `bitrate mode CBR not supported using default mode`. Снаружи это выглядит
 * так: стример ставит 4000 кбит/с, а в канал уходит 5.5 — и понять почему
 * неоткуда. Замер 03.09 на S21 (Exynos 2100): CBR не держит НИ ОДИН из двух
 * аппаратных энкодеров, ни HEVC, ни H.264.
 *
 * **Выбрать режим нельзя, и это не наше упущение:** у `VideoEncoder` в
 * RootEncoder нет сеттера режима, он захардкожен. Поэтому здесь только запрос,
 * без переключателя — обещать выбор, которого нет, было бы хуже молчания.
 *
 * Поддержка зависит от КОДЕКА: на другом устройстве HEVC может не уметь, а
 * H.264 уметь, поэтому спрашивать надо при каждой смене кодека, а не однократно.
 */
object EncoderCapabilities {

    fun mimeFor(codec: Codec): String =
        if (codec == Codec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /**
     * Лучший профиль кодирования, который реально держит железо этого телефона.
     *
     * **Зачем.** RootEncoder принимает профиль седьмым параметром `prepareVideo`,
     * по умолчанию там `-1`, а внутри стоит `if (profile > 0)` — то есть мы его
     * не выставляли вовсе и энкодер брал свой, обычно самый простой. Разница
     * не косметическая: Baseline кодирует энтропию через CAVLC, Main и High —
     * через CABAC и трансформации 8×8, и это около 10–15% битрейта при том же
     * качестве. Мы бьём в фиксированный битрейт, поэтому выигрыш достаётся
     * картинкой. Мобильный Twitch по той же причине ставит High или Main.
     *
     * **Почему со спросом у железа, а не константой.** Профиль, которого
     * энкодер не умеет, роняет `prepare` целиком — а мы уже знаем на своём же
     * примере, что заявленное в документации и поддержанное на Exynos это
     * разные множества (история с CBR прямо выше). Поэтому спрашиваем
     * `MediaCodecInfo` и берём лучшее из того, что он подтвердил.
     *
     * `level` намеренно не трогаем: он проверяется библиотекой отдельным
     * `if (level > 0)`, и без него энкодер подбирает уровень сам под
     * разрешение и битрейт. Угадывать его руками — лишний способ не собраться.
     *
     * @return профиль для `MediaFormat`, либо -1, если спросить не удалось —
     *   тогда всё остаётся как было, на усмотрение энкодера.
     */
    fun bestProfile(codec: Codec): Int = runCatching {
        val mime = mimeFor(codec)
        val supported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .flatMap { it.getCapabilitiesForType(mime).profileLevels.map { pl -> pl.profile } }
            .toSet()
        // Порядок предпочтения — от лучшего сжатия к худшему.
        val wanted = if (codec == Codec.HEVC) {
            listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        } else {
            listOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            )
        }
        val chosen = wanted.firstOrNull { it in supported } ?: -1
        // В лог — и выбранное, и всё, что железо подтвердило: если на чужом
        // телефоне картинка окажется хуже ожидаемой, первый вопрос будет
        // «а какой профиль вообще применился», и ответ должен быть в логе,
        // а не выясняться перепиской.
        android.util.Log.i(
            "BrixStream",
            "профиль $codec: выбран $chosen, железо поддерживает ${supported.sorted()}",
        )
        chosen
    }.getOrDefault(-1)

    /** @return true, если хотя бы один энкодер для этого кодека держит CBR. */
    fun isCbrSupported(codec: Codec): Boolean = runCatching {
        val mime = mimeFor(codec)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.getCapabilitiesForType(mime)
                    .encoderCapabilities
                    ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        }
    }.getOrDefault(false)
}
