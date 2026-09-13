package app.brix.streaming.overlay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads media (images/GIF, audio) referenced by an overlay URL and caches
 * them on disk so repeated alerts reuse the same file instead of hitting the
 * network. Cache size is bounded by an LRU pass over the cache directory.
 */
class MediaCache(
    context: Context,
    private val maxCacheBytes: Long = 100L * 1024 * 1024,
    private val client: OkHttpClient = defaultClient(),
) {
    private val cacheDir = File(context.cacheDir, "overlay_media").apply { mkdirs() }

    // Per-URL locks, not one global lock: the download runs inside the lock (to
    // collapse duplicate concurrent fetches of the same file), so a single
    // global monitor made every overlay's media queue behind whichever request
    // was slowest — one stalled alert image held up every other alert's audio.
    private val keyLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    suspend fun getOrDownload(url: String, type: MediaType): File? =
        withContext(Dispatchers.IO) {
            val cached = File(cacheDir, cacheKey(url, type))
            if (cached.exists() && cached.length() > 0) {
                touch(cached)
                return@withContext cached
            }
            synchronized(keyLocks.getOrPut(cached.name) { Any() }) {
                if (cached.exists() && cached.length() > 0) {
                    touch(cached)
                    return@withContext cached
                }
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null
                        val tmp = File(cacheDir, "${cached.name}.part")
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        if (tmp.renameTo(cached)) {
                            cached.apply { evictIfNeeded() }
                        } else {
                            tmp.delete()
                            cached
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun getCachedSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0

    private fun cacheKey(url: String, type: MediaType): String =
        "${type.name.lowercase()}_${url.hashCode().toUInt().toString(16)}_${extension(type)}"

    private fun extension(type: MediaType): String = when (type) {
        MediaType.IMAGE -> ".img"
        MediaType.AUDIO -> ".aud"
    }

    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxCacheBytes) return
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
