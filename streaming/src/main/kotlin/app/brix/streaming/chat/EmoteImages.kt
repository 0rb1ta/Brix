package app.brix.streaming.chat

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Картинки эмодзи для панели чата.
 *
 * Glide уже в зависимостях `:streaming` и сам держит кэш в памяти и на диске —
 * одна и та же картинка в сотне сообщений качается один раз. `asBitmap()` у
 * анимированных файлов отдаёт первый кадр — это и нужно (см. [EmoteParts]).
 */
object EmoteImages {
    suspend fun load(context: Context, url: String, heightPx: Int, aspect: Float): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val h = heightPx.coerceIn(16, 256)
                val w = (h * aspect).toInt().coerceIn(16, 768)
                Glide.with(context.applicationContext)
                    .asBitmap()
                    .load(url)
                    .submit(w, h)
                    .get()
            }.getOrNull()
        }
}
