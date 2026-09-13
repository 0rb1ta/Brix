package app.brix.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.util.Log
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StreamService : Service() {

    companion object {
        private const val CHANNEL_ID = "streaming"
        private const val NOTIF_ID = 1

        const val ACTION_STOP = "app.brix.streaming.action.STOP"
        const val ACTION_START = "app.brix.streaming.action.START"
        const val EXTRA_URL = "url"
        const val ACTION_RECONNECT = "app.brix.streaming.action.RECONNECT"
        const val ACTION_MUTE = "app.brix.streaming.action.MUTE"

        /**
         * Разрешить службе захват экрана.
         *
         * На Android 14+ поднимать службу с типом mediaProjection можно ТОЛЬКО
         * после того, как пользователь дал согласие на захват. Поэтому тип
         * добавляется отдельным шагом: сначала согласие, потом эта команда,
         * и лишь затем создаётся виртуальный дисплей.
         */
        const val ACTION_ALLOW_SCREEN = "app.brix.streaming.action.ALLOW_SCREEN"

        /** Захват экрана закончился — снять тип со службы. */
        const val ACTION_REVOKE_SCREEN = "app.brix.streaming.action.REVOKE_SCREEN"

        /**
         * Остановить захват экрана, не трогая эфир.
         *
         * Живёт в уведомлении, потому что сцену с экраном включают ради того,
         * чтобы приложение свернуть, — а свёрнутое приложение своей кнопки не
         * покажет. Системный значок захвата на эту роль не годится: он в
         * строке состояния, которую полноэкранный эфир прячет.
         */
        const val ACTION_STOP_SCREEN = "app.brix.streaming.action.STOP_SCREEN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * A PARTIAL_WAKE_LOCK is what actually keeps an IRL session alive with the
     * phone in a pocket: the Activity's FLAG_KEEP_SCREEN_ON only holds while
     * the UI is visible, and a foreground service alone does NOT stop the CPU
     * from sleeping once the user hits the power button mid-stream.
     *
     * Acquired in onCreate, not from ACTION_START: this service is only ever
     * started for a streaming session, and the real Start path
     * (StreamScreen -> StreamService.start()) sends an action-less intent, so
     * an ACTION_START-only acquire never ran at all and every screen-off
     * stalled the stream.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brix:streaming").apply {
            setReferenceCounted(false)
            // No 4h safety cap: an IRL session can exceed it and would silently
            // fall into Doze mid-stream (M2). The lock is released in onDestroy.
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Разрешил ли пользователь захват экрана в этом запуске.
     *
     * Флаг нужен, потому что тип службы задаётся ЦЕЛИКОМ при каждом
     * `startForeground`, а не добавляется. Любая следующая команда — «мьют»,
     * «переподключить», просто повторный старт — переобъявила бы типы, и без
     * этого флага mediaProjection из набора бы выпал, а вместе с ним оборвался
     * бы и захват экрана посреди эфира.
     */
    @Volatile
    private var screenAllowed = false

    /**
     * Служба уже поднята на переднем плане.
     *
     * Повторный `startForeground` из фона система отвергает: «Foreground
     * service started from background can not have location/camera/microphone
     * access». Поймано 05.09 — команда, пришедшая по кнопке уведомления, шла
     * из фона, объявление типов отвергалось трижды подряд, и через минуту
     * служба была остановлена как простаивающая, вместе с эфиром. Поэтому
     * повторно объявляем типы только там, где это действительно нужно, а
     * текст уведомления обновляем через notify().
     */
    @Volatile
    private var inForeground = false

    /**
     * Типы службы, объявляемые ЯВНО.
     *
     * Двухаргументный `startForeground` берёт объединение всех типов из
     * манифеста, а там с 04.09 объявлен и mediaProjection. На Android 14+ этот
     * тип без выданного согласия запрещён, то есть двухаргументная форма
     * ставила под SecurityException КАЖДЫЙ старт эфира, а не только захват
     * экрана. Поэтому набор собирается здесь и всегда передаётся явно.
     */
    private fun foregroundTypes(): Int =
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            if (screenAllowed) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    /** Поднять службу на переднем плане с текущим набором типов. Отказ не
     *  должен ронять процесс: эфир важнее любой из причин отказа. */
    private fun startForegroundSafely(text: String) {
        runCatching { startForeground(NOTIF_ID, buildNotification(text), foregroundTypes()) }
            .onSuccess { inForeground = true }
            .onFailure { Log.e("StreamService", "служба не поднята: ${it.message}") }
    }
    @Volatile
    private var startThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely(getString(R.string.notif_streaming_title))
        acquireWakeLock()

        scope.launch {
            // Re-resolve the current streamer on every emission: the controller
            // may swap it (transport switch / release) while this service is
            // alive, and collecting a dead instance's state would freeze the
            // notification on stale text.
            // flatMapLatest: a nested collect never returns, so a plain
            // outer collect would never observe streamer swaps
            // and the notification/thermal would freeze on a dead instance.
            StreamController.currentFlow.flatMapLatest { streamer ->
                streamer?.state ?: kotlinx.coroutines.flow.flowOf(StreamState())
            }.collect { state ->
                    val text = when {
                        state.phase == SessionPhase.Reconnecting -> getString(R.string.notif_reconnecting)
                        state.status == StreamStatus.Connecting -> getString(R.string.notif_connecting)
                        state.status == StreamStatus.Connected -> getString(R.string.notif_connected)
                        state.status == StreamStatus.Rejected -> getString(R.string.notif_failed, state.message ?: "")
                        state.status == StreamStatus.Failed -> getString(R.string.notif_failed, state.error?.message ?: state.message ?: "")
                        state.status == StreamStatus.Idle -> getString(R.string.notif_interrupted)
                        else -> getString(R.string.notif_streaming_title)
                    }
                    notify(buildNotification(text))
                // NEVER releaseAll() here: killing the streamer on an
                // error destroys the camera mid-preview ("frozen frame"
                // field bug) and poisons the next Start (released cached
                // instance). Recovery belongs to the reconnect logic /
                // explicit user Stop.
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = StreamController.current()
        // Every start path must reach startForeground within the ANR window —
        // но только пока служба ещё не на переднем плане. Когда она уже там,
        // повторное объявление типов ничего не добавляет, зато из фона его
        // отвергают и служба рискует быть остановленной (см. inForeground).
        if (inForeground) {
            notify(buildNotification(getString(R.string.notif_connecting)))
        } else {
            startForegroundSafely(getString(R.string.notif_connecting))
        }
        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                // Single-flight: overlapping STARTs used to overwrite
                // startThread, leaving an orphan thread still calling
                // st.start(). start() is @Synchronized, but the
                // loser would still run to completion afterwards.
                if (startThread?.isAlive == true) {
                    Log.w("StreamService", "ACTION_START ignored — start already in progress")
                    return START_NOT_STICKY
                }
                current?.let { st ->
                    // Camera ops must stay off main. Store the reference so
                    // onDestroy() can wait for it instead of racing.
                    val t = Thread {
                        try {
                            // release() may have raced us between current() and
                            // this thread running.
                            if (st.isReleased) return@Thread
                            if (!st.isStreaming) {
                                st.start(intent.getStringExtra(EXTRA_URL) ?: return@Thread)
                                StreamService.start(this)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("StreamService", "ACTION_START failed", e)
                        }
                    }
                    startThread = t
                    t.start()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                releaseWakeLock()
                // Soft stop ONLY. releaseAll() here was the root cause of
                // "connects once then never again": it killed the prepared
                // encoder and zeroed activeGen, and the cached instance kept
                // dropping every callback of the next session. Full resource
                // teardown belongs to a dedicated Phase 3 mechanism.
                current?.stop()
                // Захват живёт ровно столько, сколько сеанс: иначе значок
                // «идёт запись экрана» остался бы висеть после конца эфира, а
                // система продолжала бы считать захват активным.
                ScreenCapture.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                current?.reconnect()
                if (current == null) {
                    notify(buildNotification(getString(R.string.notif_interrupted)))
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_ALLOW_SCREEN -> {
                acquireWakeLock()
                // Запрошенный тип обязан быть подмножеством объявленного в
                // манифесте, иначе система бросает IllegalArgumentException и
                // роняет ВЕСЬ процесс — вместе с идущим эфиром. Именно это и
                // случилось 04.09: отладочный манифест заменяет объявление
                // службы целиком через tools:node="replace", и mediaProjection
                // туда не доехал. Манифест починен, но падать из-за него
                // приложение больше не должно: захват экрана — это фича, а
                // эфир — работа.
                screenAllowed = true
                // Тип объявляем ЯВНО и здесь всегда: команда приходит сразу
                // после согласия, то есть приложение на переднем плане.
                inForeground = false
                startForegroundSafely(getString(R.string.notif_streaming_title))
                // Только теперь интерфейсу можно брать токен: до объявления
                // типа система его не отдаёт (см. ScreenCapture.awaitAllowed).
                ScreenCapture.notifyAllowed()
                // Перерисовать: кнопка остановки захвата появляется только
                // после того, как интерфейс отдал токен в ScreenCapture.
                notify(buildNotification(getString(R.string.notif_streaming_title)))
                return START_NOT_STICKY
            }
            ACTION_STOP_SCREEN -> {
                // Тип службы снимет ACTION_REVOKE_SCREEN — его пришлёт
                // интерфейс из onStop, когда система разошлёт коллбэки.
                // Здесь только сама остановка, чтобы порядок был один и тот же
                // и для нашей кнопки, и для остановки системными средствами.
                if (!ScreenCapture.stop()) {
                    Log.i("StreamService", "останавливать нечего: захват не идёт")
                }
                notify(buildNotification(getString(R.string.notif_streaming_title)))
                return START_NOT_STICKY
            }
            ACTION_REVOKE_SCREEN -> {
                // Захват кончился — сам ли пользователь остановил его из
                // шторки, или сцена ушла с экрана. Тип надо снять: держать
                // mediaProjection без живого захвата система не обязана
                // терпеть, а значок «идёт запись экрана» остался бы висеть.
                screenAllowed = false
                // Типы НЕ переобъявляем: команда приходит из фона (захват
                // останавливают, когда приложение свёрнуто), а оттуда система
                // объявление отвергает и служба может быть остановлена
                // целиком. Лишний тип без живого захвата безвреден — он
                // проверяется в момент объявления, а не постоянно; уйдёт он
                // при следующем честном подъёме службы.
                notify(buildNotification(getString(R.string.notif_streaming_title)))
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                if (current != null) {
                    current.setMuted(!current.state.value.micMuted)
                } else {
                    notify(buildNotification(getString(R.string.notif_interrupted)))
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        // Don't let the system restart us after the process is killed: we have
        // no session metadata to resume a stream, so a restarted service would
        // show a stale "live" notification with no actual stream.
        if (current == null) {
            notify(buildNotification(getString(R.string.notif_interrupted)))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents ends the streaming session: the
        // foreground service outlives the UI, so without this the stream would
        // keep running with no way to control or observe it. Release the
        // streamer and tear the service down. releaseAll() nulls the cached
        // instance, so the next getOrCreate() builds a fresh, prepared
        // streamer instead of handing back a scope-cancelled one (the crash
        // the old comment warned about).
        StreamController.releaseAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Захват не переживает службу: он часть сеанса, а не приложения.
        ScreenCapture.stop()
        // Wait for the start thread so onDestroy doesn't race with st.start().
        // Bounded tightly: this runs on the main thread, and a long join here
        // blocks the UI on the system's own teardown path. start() is
        // @Synchronized and generation-guarded, so a straggler cannot corrupt
        // the next session — the join is a courtesy, not a correctness need.
        startThread?.let { t ->
            try { t.join(500) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        startThread = null
        // System-initiated destroy (task removal, OOM trim) never goes through
        // ACTION_STOP — without this the PARTIAL_WAKE_LOCK is held up to its
        // 4h safety timeout.
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val reconnectPending = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val muted = StreamController.current()?.state?.value?.micMuted == true
        val mutePending = PendingIntent.getService(
            this, 2,
            Intent(this, StreamService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Tapping the notification body returns to the app (P0: a foreground
        // notification with no contentIntent is dead — the user cannot get
        // back to the stream without re-launching from the launcher).
        // Resolve the launcher activity via PackageManager so we don't need a
        // compile-time dependency on the :app module (MainActivity lives there).
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 3,
            launchIntent ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.notif_streaming_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
        // **В уведомлении помещается ровно три кнопки.** Четвёртую Android
        // молча отбрасывает — она есть в объекте (`actions=4` в dumpsys), но
        // на экране её нет. Поймано 05.09: кнопка «Вернуть камеру» была
        // добавлена четвёртой, владелец её не видел, а захват при этом честно
        // шёл. Поэтому третье место делится: пока захват идёт, оно отдано
        // возврату к камере, а «Переподключить» уступает — переподключение
        // и так делается само, а из захвата выйти больше нечем.
        if (ScreenCapture.isActive) {
            val stopScreenPending = PendingIntent.getService(
                this, 4,
                Intent(this, StreamService::class.java).setAction(ACTION_STOP_SCREEN),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.notif_action_stop_screen), stopScreenPending)
        } else {
            builder.addAction(0, getString(R.string.notif_action_reconnect), reconnectPending)
        }
        builder.addAction(
            0,
            getString(if (muted) R.string.notif_action_unmute else R.string.notif_action_mute),
            mutePending,
        )
        return builder.build()
    }

    private fun notify(notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }
}
