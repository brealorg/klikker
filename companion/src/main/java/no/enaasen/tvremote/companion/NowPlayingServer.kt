package no.enaasen.tvremote.companion

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A minimal read-only HTTP server: `GET /nowplaying` answers with [body] as JSON.
 * Anyone on the local network can read it, which is the point (the phone does).
 */
internal class NowPlayingServer(private val body: () -> String) {
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
            val request = socket.getInputStream().bufferedReader().readLine().orEmpty()
            val found = request.startsWith("GET /nowplaying")
            val payload = (if (found) body() else "{}").toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 ${if (found) "200 OK" else "404 Not Found"}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
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
        private const val TAG = "NowPlayingServer"
        private const val READ_TIMEOUT_MS = 2_000
    }
}
