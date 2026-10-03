package app.brix.streaming.kick

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class OAuthCallback(val code: String?, val state: String?, val error: String?)

object LoopbackReceiver {

    fun parseRequestLine(line: String): OAuthCallback? {
        val parts = line.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        val url = "http://127.0.0.1${parts[1]}".toHttpUrlOrNull() ?: return null
        if (url.encodedPath != "/callback") return null
        val code = url.queryParameter("code")
        val error = url.queryParameter("error")
        // Запрос к /callback без code и без error — не ответ сервера входа, а
        // чей-то посторонний заход на порт (сканер, предзагрузчик). Раньше он
        // завершал приём, сокет закрывался, и настоящий редирект браузера
        // получал отказ соединения (аудит 23.09). Отвечаем 404 и ждём дальше.
        if (code == null && error == null) return null
        return OAuthCallback(code, url.queryParameter("state"), error)
    }

    suspend fun awaitCallback(port: Int, timeoutMs: Int, successHtml: String): OAuthCallback? =
        runInterruptible(Dispatchers.IO) {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = timeoutMs
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        return@runInterruptible null
                    }
                    socket.use { s ->
                        s.soTimeout = 5_000
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val result = parseRequestLine(line)
                        val body = if (result != null) successHtml else "Not found"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val status = if (result != null) "200 OK" else "404 Not Found"
                        s.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(bytes)
                            flush()
                        }
                        if (result != null) return@runInterruptible result
                    }
                }
                null
            }
        }
}
