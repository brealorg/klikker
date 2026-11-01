package no.enaasen.tvremote.companion

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

internal class HttpResponse(val contentType: String, val body: ByteArray)

/**
 * A minimal HTTP server: each `GET` path is answered by [route], or with 404
 * when it returns null. Anyone on the local network can read it, which is the
 * point (the phone does). Commands, such as seeking, are `POST`s handled by
 * [command] (true if done). They must carry the [COMMAND_HEADER], which a web
 * page in a browser on the network can't add without the server's consent, so
 * a page can't steer playback.
 */
internal class NowPlayingServer(
    private val route: (path: String) -> HttpResponse?,
    private val command: (path: String) -> Boolean = { false },
) {
    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val socket = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            Log.w(TAG, "Could not listen on port $PORT", e)
            return
        }
        server = socket
        thread(name = "now-playing-server", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    thread(isDaemon = true) { answer(client) }
                } catch (_: IOException) {
                    // The socket was closed by stop().
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun answer(client: Socket) = client.use { socket ->
        runCatching {
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            val path = request.substringAfter(' ').substringBefore(' ')
            val response = when {
                request.startsWith("GET ") -> route(path)
                request.startsWith("POST ") && headers.any { it.startsWith("$COMMAND_HEADER:", ignoreCase = true) } ->
                    if (command(path)) HttpResponse("text/plain", ByteArray(0)) else null
                else -> null
            }
            val payload = response?.body ?: ByteArray(0)
            val head = "HTTP/1.1 ${if (response != null) "200 OK" else "404 Not Found"}\r\n" +
                "Content-Type: ${response?.contentType ?: "text/plain"}\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(payload)
                flush()
            }
        }
    }

    companion object {
        const val PORT = 6477
        const val COMMAND_HEADER = "X-TvRemote"
        private const val TAG = "NowPlayingServer"
        private const val READ_TIMEOUT_MS = 2_000
    }
}
