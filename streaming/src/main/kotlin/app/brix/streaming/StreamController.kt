package app.brix.streaming

import android.content.Context
import android.os.SystemClock
import app.brix.core.ServerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the single [LiveStreamer] instance so streaming survives Activity
 * destruction (e.g. locking the screen) while a foreground [StreamService]
 * keeps the process alive.
 */
object StreamController {

    @Volatile
    private var streamer: LiveStreamer? = null

    @Volatile
    private var kind: ServerType? = null

    private val restartGuard = StartRestartGuard(clock = { SystemClock.elapsedRealtime() })

    /** Emits the current streamer (or null) and re-emits on every swap, so
     *  long-lived collectors (StreamService) never stick to a dead instance. */
    val currentFlow: StateFlow<LiveStreamer?> get() = _currentFlow
    private val _currentFlow = MutableStateFlow<LiveStreamer?>(null)

    @Synchronized
    fun getOrCreate(context: Context, type: ServerType): LiveStreamer {
        val cur = streamer
        if (cur != null && kind == type && !cur.isReleased) return cur
        cur?.release()
        val created = when (type) {
            ServerType.SRTLA -> SrtlaStreamer(context.applicationContext, restartGuard)
            ServerType.RTMP -> Streamer(context.applicationContext)
            ServerType.WHIP -> WhipStreamer(context.applicationContext)
        }
        // Перечислить входы разово, при создании ЛЮБОГО стримера: чем именно
        // телефон готов делиться по микрофонам, из документации не узнать,
        // только с устройства. Раньше этот вызов стоял в SrtlaStreamer и не
        // срабатывал вовсе, когда активен профиль RTMP.
        MicDevices.logInputs(context.applicationContext)
        streamer = created
        kind = type
        _currentFlow.value = created
        return created
    }

    @Synchronized
    fun current(): LiveStreamer? = streamer

    @Synchronized
    fun releaseAll() {
        android.util.Log.w("Brix", "releaseAll: dropping cached streamer")
        streamer?.release()
        streamer = null
        kind = null
        _currentFlow.value = null
    }
}
