package com.takeoutfm.tv.data.listen

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets a phone hand a long value (the ListenBrainz token) to the TV: a tiny web page served by
 * the app on the local network at a one-time URL (shown as a QR code), with one field. Each
 * submitted value goes to [onValue], whose answer is shown on the phone; the page stops when
 * [onValue] says it's done, on [stop], or after [TimeoutMs].
 */
class TokenHandoff(
    private val title: String,
    private val hint: String,
    private val helpUrl: String,
    /** Checks a submitted value; returns the message for the phone and whether we're done. */
    private val onValue: suspend (String) -> Pair<String, Boolean>,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null
    private val nonce = ByteArray(9).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** Starts serving; returns the URL to show, or null without a local network address. */
    fun start(scope: CoroutineScope): String? {
        val address = localAddress() ?: return null
        // A memorable port when free (it's shown on screen), else any.
        val socket = (
            PreferredPorts.firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() }
                ?: ServerSocket(0)
            ).apply { soTimeout = 1_000 }
        server = socket
        job = scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(TimeoutMs) {
                var done = false
                while (isActive && !done) {
                    val client = try {
                        socket.accept()
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    done = client.use { handle(it) }
                }
            }
            runCatching { socket.close() }
        }
        return "http://${address.hostAddress}:${socket.localPort}/$PathPrefix$nonce"
    }

    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
    }

    /** Serves one request; true once [onValue] accepted a value. */
    private fun handle(client: Socket): Boolean {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return false
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        if (path.substringBefore('?') != "/$PathPrefix$nonce") {
            respond(client, 404, page("Not found", "This link has expired. Start again on the TV."))
            return false
        }
        if (method == "POST") {
            val body = CharArray(contentLength.coerceIn(0, 8_192))
            var read = 0
            while (read < body.size) {
                val n = reader.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            val value = String(body, 0, read).split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.firstOrNull() == "value" }
                ?.getOrNull(1)
                ?.let { URLDecoder.decode(it, "UTF-8") }
                .orEmpty()
            val (message, done) = try {
                runBlocking { onValue(value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't check that right now. Try again." to false
            }
            respond(client, 200, if (done) page(title, message) else form(message))
            return done
        }
        respond(client, 200, form(null))
        return false
    }

    private fun respond(client: Socket, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().run {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun form(error: String?) = page(
        title,
        """
        <p>$hint <a href="$helpUrl">$helpUrl</a></p>
        ${if (error != null) "<p class=e>${escape(error)}</p>" else ""}
        <form method=post>
          <input name=value autocomplete=off autocapitalize=off spellcheck=false
                 placeholder="Paste here" autofocus>
          <button>Send to TV</button>
        </form>
        """.trimIndent(),
    )

    private fun page(heading: String, body: String) = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>${escape(heading)}</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#1b1c1f;color:#e5e1e6;
            margin:0;padding:24px}
          h1{font-size:1.4em}a{color:#a8c7fa;word-break:break-all}
          input{width:100%;box-sizing:border-box;font-size:1.1em;padding:12px;margin:12px 0;
            border-radius:8px;border:1px solid #555;background:#2a2b2f;color:inherit}
          button{width:100%;font-size:1.1em;padding:12px;border:0;border-radius:8px;
            background:#e5e1e6;color:#1b1c1f}
          .e{color:#ffb4ab}
        </style></head><body><h1>${escape(heading)}</h1>$body</body></html>
    """.trimIndent()

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val TimeoutMs = 10 * 60_000L
        private const val PathPrefix = "h/"
        private val PreferredPorts = listOf(8723, 8724, 8725)

        /** The device's IPv4 address on the local network (Wi-Fi or Ethernet). */
        fun localAddress(): Inet4Address? =
            runCatching { NetworkInterface.getNetworkInterfaces().toList() }
                .getOrDefault(emptyList())
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
    }
}
