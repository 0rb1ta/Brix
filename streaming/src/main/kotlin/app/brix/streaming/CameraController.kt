package app.brix.streaming

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.StreamBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Камера и локальное превью: поверхность видоискателя, смена камеры и линзы,
 * зум, фонарь, тап по фокусу, применение пользовательских умолчаний.
 *
 * Выделено из [SrtlaStreamer]. Кроме уборки это закрывает старую ловушку:
 * превью поднимается из ТРЁХ мест (внешний [startPreview], восстановление
 * после пересборки энкодера и перезапуск на старте эфира), и на каждом надо
 * было не забыть позвать [applyPostPreviewCameraSetup]. Раньше это держалось на
 * комментарии — один путь уже терял настройки съёмки. Теперь два внутренних
 * пути сведены в [resumePreviewIfDetached], и забыть негде.
 *
 * Работает со `StreamBase`, а не с конкретным транспортом: [SrtlaStream],
 * `RtmpStream` и `WhipStream` наследуют его все три, и всё, что нужно камере
 * (GL-интерфейс, превью, источник видео), объявлено именно там. Ради этого тип
 * и расширен — до 03.09 контроллер знал только про SRTLA, поэтому каждая правка
 * камеры чинила один путь из трёх, а RTMP и WHIP годами оставались с
 * замерзающим тапом по фокусу и без умолчаний камеры вовсе.
 */
internal class CameraController(
    private val appContext: Context,
    private val stream: StreamBase,
    private val scope: CoroutineScope,
    /** Частота кадров текущего профиля — уходит в подсказку системе о реальном
     *  темпе превью, см. [applyPreviewFrameRateHint]. */
    private val profileFps: () -> Int,
    private val isReleased: () -> Boolean,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
) {
    private val tag = "BrixStream"

    private var defaults = CameraDefaults()

    // Умолчание по камере ставим только при самом первом открытии: повторное
    // применение на каждом startPreview() (например когда превью возвращается
    // после переподключения) дралось бы с ручной сменой камеры, сделанной
    // пользователем посреди сессии.
    @Volatile
    private var defaultCameraApplied = false

    // Операции с HAL камеры (смена камеры, зум, тап по фокусу) обязаны идти не
    // с UI-потока (блокирующий поход в HAL даёт рывки и ANR), но при этом
    // строго по одной: раскидывание их по пулу Dispatchers.IO позволяло двум
    // вызовам столкнуться на сессии захвата и подвесить камеру («No request /
    // wait timeout»).
    private val cameraDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Сериализация выше убирает одновременные вызовы, но очередь из них
    // (быстрый щипок зума, несколько тапов по линзам) всё равно отработала бы
    // каждый до конца, а это реальные закрытия-открытия и походы в HAL. Отмена
    // предыдущей ещё не начатой задачи оставляет из пачки только последнюю.
    private var cameraJob: Job? = null

    private var lastPreviewSurface: Surface? = null
    private var lastPreviewWidth = 0
    private var lastPreviewHeight = 0

    fun configure(newDefaults: CameraDefaults) {
        // Экземпляр стримера переживает Stop->Start (см. StreamController.getOrCreate),
        // поэтому одноразовая защёлка defaultCameraApplied иначе молча проглотила
        // бы настоящую смену настройки между сессиями — тот самый класс багов
        // «настройка не применяется без перезапуска приложения», только в другом
        // поле. Сбрасываем её лишь когда УМОЛЧАНИЕ действительно изменилось, а не
        // на каждый вызов, иначе ручная смена камеры посреди сессии будет
        // отменяться.
        if (newDefaults.defaultCamera != defaults.defaultCamera) {
            defaultCameraApplied = false
        }
        defaults = newDefaults
        // Если камера и превью уже живут (обычный случай: настройки меняют
        // посреди сессии, а не перед самым первым подключением превью) —
        // применяем сразу. Старый код применял это только из одноразового блока
        // в startPreview(), поэтому изменение, сделанное во время превью, молча
        // не действовало до полного перезапуска приложения; ровно ради этого
        // метод и существует.
        camera2()?.let { src ->
            applyDefaults(src)
            applyDefaultLensOnce(src)
        }
    }

    /** Стабилизация и зеркало превью — дешёвые правки запроса захвата, того же
     *  веса, что и вызов enableAutoFocus рядом, можно прямо на потоке вызова. */
    private fun applyDefaults(src: Camera2Source) {
        if (defaults.stabilization) src.enableVideoStabilization() else src.disableVideoStabilization()
        // Оптическая — отдельный механизм, и до 04.09 мы её не трогали вовсе:
        // библиотека даёт её раздельно, а мы дёргали только электронную.
        // Не все камеры её имеют, поэтому отказ игнорируем молча.
        runCatching {
            if (defaults.opticalStabilization) {
                src.enableOpticalVideoStabilization()
            } else {
                src.disableOpticalVideoStabilization()
            }
        }
        applyMirror(src)
    }

    /** Перейти на заданную умолчанием камеру, один раз. Это полное закрытие и
     *  открытие HAL — только на [cameraDispatcher], никогда не на месте (вся
     *  сага с тапом по фокусу в этом файле выросла ровно из того, что поход в
     *  HAL с неправильного потока замораживает превью). */
    private fun applyDefaultLensOnce(src: Camera2Source) {
        if (defaultCameraApplied) return
        defaultCameraApplied = true
        val wantFront = defaults.defaultCamera == CameraSide.FRONT
        val isFront = src.getCameraFacing() == CameraHelper.Facing.FRONT
        if (wantFront == isFront) return
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            try {
                src.switchCamera()
                applyMirror(src)
            } catch (e: Exception) {
                Log.e(tag, "applyDefaultLensOnce failed", e)
            }
        }
    }

    /** Зеркалит только превью на устройстве (не исходящий эфир), когда активна
     *  фронтальная камера и включено соответствующее умолчание — так ведёт себя
     *  большинство камерных приложений с «селфи»-превью. */
    private fun applyMirror(src: Camera2Source) {
        val isFront = src.getCameraFacing() == CameraHelper.Facing.FRONT
        runCatching {
            val gl = stream.getGlInterface()
            gl.setIsPreviewHorizontalFlip(defaults.mirrorFront && isFront)
            // Эфир отдельно от превью: стримеру привычно видеть себя зеркально,
            // зрителю — как в жизни. При зеркале в эфире весь текст в кадре
            // читается наоборот, поэтому по умолчанию выключено.
            gl.setIsStreamHorizontalFlip(defaults.mirrorFrontInStream && isFront)
        }
    }

    /** Размеры, с которыми превью реально стартовало: по ним видно, что окно
     *  с тех пор повернулось и viewport больше не соответствует поверхности. */
    private var lastStartedWidth = 0
    private var lastStartedHeight = 0

    fun startPreview(surfaceView: SurfaceView) {
        try {
            // Размер буфера, а не вьюхи. UI зовёт holder.setFixedSize(), то есть
            // поверхность меньше вьюхи, а SurfaceFlinger растягивает. Эти числа
            // уходят в glViewport (ScreenRender.drawPreview), поэтому брать
            // размер вьюхи означало задавать окно вывода больше самой
            // поверхности: до 01.09 сюда шло 2400×1080 при буфере 1920×1080.
            val frame = surfaceView.holder.surfaceFrame
            val pw = if (frame.width() > 0) frame.width() else surfaceView.width
            val ph = if (frame.height() > 0) frame.height() else surfaceView.height
            if (surfaceView.holder.surface.isValid) {
                lastPreviewSurface = surfaceView.holder.surface
                lastPreviewWidth = pw
                lastPreviewHeight = ph
            }
            // Превью, запущенное с ДРУГИМ размером поверхности, надо
            // перезапустить. Размер уходит в glViewport один раз, при старте:
            // если окно с тех пор повернулось (портретная заставка на запуске —
            // 15.09), картинка остаётся узкой вертикальной полосой посреди
            // альбомного экрана. Стримить в этот момент нельзя — на живом эфире
            // не трогаем ничего.
            val sizeChanged = pw != lastStartedWidth || ph != lastStartedHeight
            if (stream.isOnPreview && sizeChanged && !stream.isStreaming &&
                surfaceView.holder.surface.isValid
            ) {
                runCatching { stream.stopPreview() }
            }
            if (!stream.isOnPreview && surfaceView.holder.surface.isValid) {
                dropDeadScreenSource()
                stream.startPreview(surfaceView.holder.surface, pw, ph)
                lastStartedWidth = pw
                lastStartedHeight = ph
                applyPostPreviewCameraSetup()
            }
        } catch (e: Exception) {
            // Раньше здесь было молчаливое проглатывание: сбой камеры или
            // энкодера оставлял чёрный экран вообще без диагностики.
            // Пишем хотя бы в лог; типизированная StreamError — отдельная работа.
            Log.e(tag, "startPreview failed", e)
        }
    }

    /**
     * Сменить остановленный захват экрана на камеру, пока превью не начали.
     *
     * `startPreview` в библиотеке поднимает источник, если тот не работает
     * (проверено по байт-коду `StreamBase` 2.8.0), а `stopPreview` глушит его,
     * только когда нет ни эфира, ни записи. Значит после «свернул — развернул»
     * без эфира источник экрана оказывается остановленным, и превью попробует
     * поднять его снова. Для захвата экрана это запрещено: токен одноразовый,
     * второй `createVirtualDisplay` даёт SecurityException — и система заодно
     * гасит захват (поймано на устройстве 05.09).
     *
     * Поэтому мёртвый источник экрана меняется на камеру заранее. Согласие на
     * новый захват спрашивает интерфейс, когда сцену выбирают снова.
     */
    private fun dropDeadScreenSource() {
        val source = stream.videoSource
        if (source !is com.pedro.encoder.input.sources.video.ScreenSource) return
        if (runCatching { source.isRunning() }.getOrDefault(true)) return
        Log.i(tag, "захват экрана остановлен — возвращаю камеру, токен одноразовый")
        runCatching { stream.changeVideoSource(Camera2Source(appContext)) }
            .onFailure { Log.e(tag, "камера не вернулась после захвата: ${it.message}") }
    }

    fun stopPreview() {
        try {
            if (stream.isOnPreview) stream.stopPreview()
        } catch (e: Exception) {
            Log.e(tag, "stopPreview failed", e)
        }
    }

    /**
     * Вернуть превью, если поверхность есть, а показ отвалился.
     *
     * Два внутренних пути ведут сюда: пересборка энкодера в `prepare()` (превью
     * гасили ради неё) и старт эфира — RootEncoder на `stopStream()` гасит и
     * захват с камеры, поэтому после Stop→Start картинка замерзает на последнем
     * кадре, и пользователь решает, что камера умерла.
     */
    fun resumePreviewIfDetached() {
        val surface = lastPreviewSurface ?: return
        if (!surface.isValid || stream.isOnPreview) return
        runCatching {
            stream.startPreview(surface, lastPreviewWidth, lastPreviewHeight)
            applyPostPreviewCameraSetup()
        }.onFailure { Log.e(tag, "preview restore failed: ${it.message}") }
    }

    /** Непрерывный автофокус (CONTROL_AF_MODE_CONTINUOUS_VIDEO с цепочкой
     *  запасных вариантов) плюс стабилизация, зеркало и камера по умолчанию —
     *  сразу после ЛЮБОГО пути, который реально поднял превью в RootEncoder, а
     *  не только после внешнего [startPreview]. */
    private fun applyPostPreviewCameraSetup() {
        camera2()?.enableAutoFocus()
        camera2()?.let { src ->
            applyDefaults(src)
            applyDefaultLensOnce(src)
        }
        applyPreviewFrameRateHint()
    }

    /** Сказать системе истинную частоту превью.
     *
     *  Поле 01.09, `SurfaceFlinger --timestats` по слою превью, 752 кадра за
     *  25 с: `averageFPS=30.4`, `droppedFrames=0`, `jankyFrames=0`, но
     *  present2present лёг как 16ms=136 / 33ms=482 / 50ms=118 / 66ms=5. То
     *  есть кадры не теряются, а выводятся неравномерно: треть держится один
     *  такт вместо двух или три вместо двух. Мгновенная частота в этих местах
     *  20 и 15 к/с, и на профиле 30 fps это видно глазом. На 60 fps незаметно,
     *  потому что кадр есть на каждый такт экрана.
     *
     *  Причина: GL-поток RootEncoder меняет буфер по приходу кадра с камеры,
     *  а та свободно бежит на 30.0 Гц относительно 60 Гц экрана, часы
     *  расходятся. При этом слой голосует за 60 (`SetFrameRate vote:
     *  frameRate = 60.00` в том же дампе), то есть SurfaceFlinger планирует
     *  вывод под неверную частоту. Подсказка с FIXED_SOURCE даёт ему право
     *  держать стабильную раскладку под реальные 30. */
    private fun applyPreviewFrameRateHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = lastPreviewSurface ?: return
        if (!surface.isValid) return
        val fps = profileFps().toFloat()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }.onFailure { Log.w(tag, "setFrameRate($fps) не принят: ${it.message}") }
    }

    /**
     * Показать заставку вместо камеры: неподвижную картинку в кадр.
     *
     * Ради чего: «сейчас вернусь», не обрывая эфир. Обрыв стоит дорого —
     * приёмник считает каждый оборванный сеанс неудачной авторизацией и банит
     * адрес примерно на минуту (см. StartRestartGuard), так что уйти в заставку
     * гораздо дешевле, чем остановить и поднять заново.
     *
     * Камера при этом отпускается: держать её открытой ради картинки — это
     * 0.29 ядра и заметная доля ватт по замерам 02.09.
     *
     * @return false, если картинку не удалось прочитать; вызывающий обязан
     *  остаться на камере, а не показать зрителям чёрный кадр.
     */
    fun showStillImage(uri: android.net.Uri): Boolean = runCatching {
        val bitmap = decodeScaled(uri) ?: return false
        stream.changeVideoSource(com.pedro.encoder.input.sources.video.BitmapSource(bitmap))
        // Прежнюю освобождаем только ПОСЛЕ того, как источник принял новую:
        // на неудаче в кадре остаётся старая картинка, и переработанный битмап
        // дал бы чёрный экран вместо заставки.
        swapStillBitmap(bitmap)
        true
    }.getOrElse {
        Log.e(tag, "заставка не показана: ${it.message}")
        false
    }

    /**
     * Заставка сейчас в кадре. Держим ссылку, чтобы освободить память:
     * `BitmapSource` её не освобождает, а картинка из галереи — это снимок
     * основной камеры, то есть десятки мегабайт на каждое переключение сцены.
     * Без этого несколько переключений туда-обратно кончались нехваткой памяти.
     */
    private var stillBitmap: android.graphics.Bitmap? = null

    private fun swapStillBitmap(next: android.graphics.Bitmap?) {
        val previous = stillBitmap
        stillBitmap = next
        if (previous !== next) previous?.recycle()
    }

    /**
     * Прочитать картинку сразу уменьшенной до размера кадра.
     *
     * Снимок на 12 Мп — это 48 МБ в памяти при четырёх байтах на точку, а в
     * эфир уходит в лучшем случае 1080p, то есть в шесть раз меньше. Читать
     * полный размер, чтобы тут же его ужать, значит платить памятью ни за что.
     * [MAX_SIDE] взят с запасом над 1080p: `inSampleSize` уменьшает только
     * степенями двойки, точную подгонку всё равно делает библиотека.
     */
    private fun decodeScaled(uri: android.net.Uri): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(tag, "заставка не прочитана: не картинка либо файл недоступен")
            return null
        }
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return appContext.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_SIDE || height / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    /**
     * Показать экран телефона вместо камеры.
     *
     * [projection] выдаётся системой только после согласия пользователя, и
     * сохранить его между запусками нельзя — Android этого не разрешает. Поэтому
     * при каждом запуске приложения согласие спрашивается заново.
     *
     * @return false, если источник не создался; вызывающий обязан вернуть
     *  камеру, а не оставить зрителей без картинки.
     */
    fun showScreen(projection: android.media.projection.MediaProjection): Boolean = runCatching {
        stream.changeVideoSource(
            com.pedro.encoder.input.sources.video.ScreenSource(appContext, projection, null, null),
        )
        swapStillBitmap(null)
        true
    }.getOrElse {
        Log.e(tag, "экран не захвачен: ${it.message}")
        false
    }

    /** Вернуть камеру после заставки или захвата экрана. */
    fun showCamera() {
        if (camera2() != null) return
        runCatching { stream.changeVideoSource(Camera2Source(appContext)) }
            .onSuccess {
                swapStillBitmap(null)
                applyPostPreviewCameraSetup()
            }
            .onFailure { Log.e(tag, "камера не вернулась: ${it.message}") }
    }

    /**
     * Перейти на заданную сторону, а не «переключить».
     *
     * Сцене нужно именно это: она говорит «фронтальная», а не «другая». Через
     * switchCamera() пришлось бы сначала выяснять текущую сторону у вызывающего,
     * и при быстром переключении сцен две команды подряд отменяли бы друг друга.
     */
    fun setCameraSide(side: CameraSide) {
        val src = camera2() ?: return
        val isFront = src.getCameraFacing() == CameraHelper.Facing.FRONT
        if ((side == CameraSide.FRONT) == isFront) return
        switchCamera()
    }

    fun switchCamera() {
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            Log.d(tag, "switchCamera: calling Camera2Source.switchCamera()")
            try {
                val src = camera2()
                src?.switchCamera()
                src?.let { applyMirror(it) }
                Log.d(tag, "switchCamera: returned")
            } catch (e: Exception) {
                Log.e(tag, "switchCamera failed", e)
                updateState { it.copy(status = StreamStatus.Failed, message = e.message) }
            }
        }
    }

    /** Операции с камерой и GL блокируют на сотни миллисекунд и не должны идти
     *  с главного потока; плюс защита от вызова после release — колбэк из UI,
     *  пришедший позже, уронил бы RootEncoder. */
    fun setTorch(enabled: Boolean) {
        if (isReleased()) return
        scope.launch {
            if (setTorchEnabled(enabled)) {
                updateState { it.copy(torchOn = enabled) }
            }
        }
    }

    /** Фонарь через камеру, а если она его не даёт — напрямую через
     *  [CameraManager]. Запасной путь нужен не для красоты: на части устройств
     *  `Camera2Source` отдаёт отказ, пока сессия захвата перестраивается, и без
     *  него кнопка фонаря просто молчала бы.
     *
     *  @return false, когда фонаря нет или камера отклонила запрос. */
    private fun setTorchEnabled(enabled: Boolean): Boolean {
        camera2()?.let { source ->
            try {
                if (enabled) source.enableLantern() else source.disableLantern()
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return false
            cm.setTorchMode(camId, enabled)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Зажечь фонарь на старте эфира, если так настроено. Решение о том, что
     *  такое «настроено», принадлежит камере, поэтому спрашивают об этом здесь,
     *  а не в стримере. */
    fun applyTorchOnStart() {
        if (defaults.torchOnStart) setTorch(true)
    }

    /**
     * Выбирает конкретную заднюю линзу (широкую, сверхширокую, теле), сначала
     * при необходимости уходя с фронтальной камеры — выбор задней линзы в
     * пилюле И ЕСТЬ запрос быть на этой линзе, ровно как в Moblin.
     *
     * Проверка стороны камеры раньше шла синхронно у вызывающего и выходила с
     * `false`, поэтому путь «перевернуть, потом выбрать линзу» из UI линзу так
     * и не применял: switchCamera() асинхронна, в тот момент камера была ещё
     * фронтальной, и setLens возвращался раньше, чем она успевала повернуться.
     * Теперь проверка живёт внутри задачи камеры, после переворота, на том же
     * сериализованном диспетчере, где идут все походы в HAL.
     */
    fun setLens(cameraId: String, zoom: Float): Boolean {
        if (isReleased()) return false
        val src = camera2() ?: return false
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            Log.d(tag, "setLens: calling openCameraId($cameraId)")
            try {
                if (src.getCameraFacing() != CameraHelper.Facing.BACK) {
                    Log.d(tag, "setLens: on front camera — flipping to back first")
                    src.switchCamera()
                    applyMirror(src)
                }
                src.openCameraId(cameraId)
                Log.d(tag, "setLens: openCameraId returned")
                if (zoom > 1f) {
                    Log.d(tag, "setLens: calling setZoom($zoom)")
                    src.setZoom(zoom)
                    Log.d(tag, "setLens: setZoom returned")
                }
            } catch (e: Exception) {
                Log.e(tag, "setLens failed (cameraId=$cameraId, zoom=$zoom)", e)
            }
        }
        return true
    }

    fun currentLensId(): String? {
        val src = camera2() ?: return null
        if (src.getCameraFacing() != CameraHelper.Facing.BACK) return null
        return runCatching { src.getCurrentCameraId() }.getOrNull()
    }

    fun zoomByScale(scale: Float) {
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            runCatching {
                val src = camera2() ?: return@runCatching
                val range = src.getZoomRange() ?: return@runCatching
                val target = (src.getZoom() * scale).coerceIn(range.lower, range.upper)
                Log.d(tag, "zoomByScale: calling setZoom($target)")
                src.setZoom(target)
                Log.d(tag, "zoomByScale: setZoom returned")
            }.onFailure { e -> Log.e(tag, "zoomByScale failed (scale=$scale)", e) }
        }
    }

    fun getZoomRange(): ClosedFloatingPointRange<Float>? =
        camera2()?.getZoomRange()?.let { it.lower..it.upper }

    /**
     * Метод библиотеки `Camera2ApiManager.tapToFocus()` (который тут звался
     * раньше) делает тап по фокусу через `session.stopRepeating()` и два
     * одиночных `session.capture()`, а повторяющееся превью восстанавливает
     * ТОЛЬКО из асинхронного колбэка onCaptureCompleted, привязанного к запросу
     * с меткой «focus». На этом устройстве тот колбэк (и, судя по всему, сами
     * capture — подтверждено живьём через `dumpsys media.camera`:
     * android.sensor.timestamp немедленно перестаёт расти, сессия камеры
     * остаётся «open») стабильно не наступает, поэтому повторяющийся запрос
     * никогда не взводится заново и превью намертво замерзает на каждом тапе,
     * причём без единого исключения. Переписано по образцу, который советует
     * сама документация Android: обновлять область и триггер автофокуса НА УЖЕ
     * ИДУЩЕМ повторяющемся запросе через setRepeatingRequest — без
     * stopRepeating и без одиночного capture, — так что превью не встаёт даже
     * на мгновение, независимо от того, отчитается ли HAL о срабатывании.
     */
    fun tapFocus(view: View, x: Float, y: Float) {
        val clampedX = x.coerceIn(0f, view.width.toFloat())
        val clampedY = y.coerceIn(0f, view.height.toFloat())
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            val src = camera2() ?: return@launch
            try {
                val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics(src.getCurrentCameraId())
                val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@launch
                val focusX = (clampedX / view.width.toFloat()) * sensorArraySize.width()
                val focusY = (clampedY / view.height.toFloat()) * sensorArraySize.height()
                val focusRect = MeteringRectangle(
                    (focusX - 100).toInt().coerceIn(0, sensorArraySize.width()),
                    (focusY - 100).toInt().coerceIn(0, sensorArraySize.height()),
                    100 * 2,
                    100 * 2,
                    MeteringRectangle.METERING_WEIGHT_MAX,
                )
                Log.d(tag, "tapFocus: setting AF region+trigger START on repeating request ($clampedX, $clampedY)")
                src.setCustomRequest { builder ->
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                // Дать HAL пару кадров с активным триггером, прежде чем его
                // отпустить — та же выдержка, что в примерах camera2 от Android
                // для ровно этого сценария.
                delay(120L)
                src.setCustomRequest { builder ->
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                }
                // AF_MODE_AUTO держит фокус на выбранной области, а не следит за
                // сценой дальше — как «тап по фокусу» в настоящем камерном
                // приложении (ненадолго заперли), после чего возвращаем
                // непрерывный автофокус, чтобы камера дальше наводилась сама,
                // без ещё одного тапа.
                delay(3000L)
                src.enableAutoFocus()
                Log.d(tag, "tapFocus: trigger released, AF continues in AUTO mode on region")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Штатно: следующий тап, зум или смена линзы отменяют эту задачу
                // (см. cameraJob — из пачки нажатий доживает только последнее).
                // Раньше это писалось в лог как «tapFocus failed» с трассой, и в
                // полевом разборе выглядело поломкой; по правилам структурной
                // конкурентности отмену надо пробросить, а не глотать как сбой.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "tapFocus failed", e)
            }
        }
    }

    private fun camera2(): Camera2Source? = stream.videoSource as? Camera2Source

    private companion object {
        /** Длинная сторона заставки. Запас над 1080p: точную подгонку под кадр
         *  делает сама библиотека, наше дело — не держать в памяти снимок
         *  целиком. */
        const val MAX_SIDE = 1920
    }
}
