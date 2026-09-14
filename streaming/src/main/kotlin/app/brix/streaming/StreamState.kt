package app.brix.streaming

import app.brix.core.ConnectionStat
import app.brix.core.MicSource
import app.brix.core.StreamError
import app.brix.moblink.MoblinkRelayInfo

enum class StreamStatus {
    Idle,
    Connecting,
    Connected,
    Failed,
    Disconnected,
    Rejected,
}

enum class VideoEffect {
    NONE,
    GRAYSCALE,
    SEPIA,
}

data class StreamState(
    val status: StreamStatus = StreamStatus.Idle,
    val message: String? = null,
    val bitrateKbps: Long = 0,
    val throughput: String = "",
    val connections: List<ConnectionStat> = emptyList(),
    val moblinkRelays: List<MoblinkRelayInfo> = emptyList(),
    val adaptiveBitrateEnabled: Boolean = false,
    val adaptiveBitrateKbps: Long = 0,
    val micMuted: Boolean = false,
    /** Текущий предпочитаемый микрофон — для подписи и индикации кнопки. */
    val micSource: MicSource = MicSource.AUTO,
    val torchOn: Boolean = false,
    val blackScreenOn: Boolean = false,
    val videoEffect: VideoEffect = VideoEffect.NONE,
    val overlayVisible: Boolean = false,
    /** Whether at least one donation overlay's widget is currently connected
     *  to its alert server (per its own WS console logging) — null when no
     *  donation overlay is configured/no signal has arrived yet. */
    val donationWidgetConnected: Boolean? = null,
    val phase: SessionPhase? = null,
    val error: StreamError? = null,
    val connectedAtElapsedMs: Long? = null,
    val reconnectAttempt: Int = 0,
)

/**
 * Состояние после остановки эфира — одно на все три транспорта.
 *
 * **Зачем отдельная функция.** Сброс был написан трижды, по разу в каждом стримере,
 * и разъехался: RTMP и WHIP чистили список каналов, SRTLA нет — и после Stop HUD
 * показывал застывшие битрейты по Wi-Fi и соте, как будто эфир идёт (14.09). Ни один
 * из трёх не обнулял `adaptiveBitrateKbps`, а HUD показывает именно его, когда включён
 * адаптивный битрейт: сбрасывали не то, что показывают.
 *
 * Пока сброс написан в трёх местах, он будет расходиться снова. Здесь он один.
 */
fun StreamState.stopped(): StreamState = copy(
    status = StreamStatus.Idle,
    message = null,
    bitrateKbps = 0,
    adaptiveBitrateKbps = 0,
    connections = emptyList(),
    error = null,
    connectedAtElapsedMs = null,
)
