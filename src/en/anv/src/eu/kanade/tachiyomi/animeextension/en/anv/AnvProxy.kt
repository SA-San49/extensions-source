package eu.kanade.tachiyomi.animeextension.en.anv

import android.util.Base64
import eu.kanade.tachiyomi.lib.m3u8server.AutoDetector
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny local HTTP proxy.
 *
 * anv only serves playlists, segments and subtitles to requests that carry the
 * `shiro_watch` session cookie, and it disguises MPEG-TS segments behind PNG/JPEG
 * headers. A video player cannot replay those headers on its own, so every URL
 * handed to the player points back here and this server replays the headers,
 * rewrites nested playlists and strips the fake image headers.
 */
class AnvProxy(private val client: OkHttpClient) {

    private var server: AnvProxyServer? = null
    private var sessionCounter = 0L
    private val sessions = ConcurrentHashMap<String, Map<String, String>>()

    @Synchronized
    private fun server(): AnvProxyServer? {
        server?.let { return it }
        return try {
            AnvProxyServer(client, sessions).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = it
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Registers a header set and returns the id used in the proxied URLs. */
    fun newSession(headers: Map<String, String>): String {
        val id = (++sessionCounter).toString()
        sessions[id] = headers
        return id
    }

    fun proxyUrl(url: String, sessionId: String): String {
        val port = server()?.listeningPort ?: return url
        return "http://127.0.0.1:$port/p?d=${encode(url)}&s=$sessionId"
    }

    fun stop() {
        server?.stop()
        server = null
        sessions.clear()
    }

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private class AnvProxyServer(
        private val client: OkHttpClient,
        private val sessions: ConcurrentHashMap<String, Map<String, String>>,
    ) : NanoHTTPD("127.0.0.1", 0) {

        private val uriAttribute = Regex("URI=\"([^\"]+)\"")

        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "method not allowed")
            }

            val sessionId = session.parameters["s"]?.firstOrNull()
            val rawTarget = session.parameters["d"]?.firstOrNull()
            val target = rawTarget?.let { decode(it) }
            if (target.isNullOrBlank() || sessionId == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing url")
            }

            return try {
                handle(target, sessionId, session)
            } catch (e: Exception) {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "proxy error: ${e.message}",
                )
            }
        }

        private fun handle(target: String, sessionId: String, session: IHTTPSession): Response {
            val requestHeaders = sessions[sessionId].orEmpty()
            val builder = Request.Builder().url(target)
            requestHeaders.forEach { (key, value) -> builder.header(key, value) }
            session.headers["range"]?.let { builder.header("Range", it) }

            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                return newFixedLengthResponse((Response.Status.lookup(502) ?: Response.Status.INTERNAL_ERROR), MIME_PLAINTEXT, "upstream error: ${e.message}")
            }

            response.use { res ->
                if (!res.isSuccessful) {
                    return newFixedLengthResponse(
                        statusOf(res.code),
                        MIME_PLAINTEXT,
                        "upstream HTTP ${res.code}",
                    )
                }

                val body = res.body.bytes()
                val finalUrl = res.request.url
                val contentType = res.header("Content-Type")
                val text = if (looksTextual(body, contentType, finalUrl.toString())) {
                    String(body, Charsets.UTF_8)
                } else {
                    null
                }

                return when {
                    text != null && text.trimStart().startsWith("#EXTM3U") ->
                        respond(
                            Response.Status.OK,
                            MIME_M3U8,
                            rewritePlaylist(text, finalUrl, sessionId).toByteArray(),
                            res,
                        )

                    text != null && text.trimStart().startsWith("WEBVTT") ->
                        respond(Response.Status.OK, MIME_VTT, body, res)

                    else -> {
                        val mine = when {
                            finalUrl.toString().contains(".vtt") -> MIME_VTT
                            finalUrl.toString().contains(".mp4") -> MIME_MP4
                            else -> MIME_TS
                        }
                        respond(statusOf(res.code), mine, stripDisguise(body), res)
                    }
                }
            }
        }

        private fun respond(status: Response.IStatus, mime: String, body: ByteArray, upstream: okhttp3.Response): Response {
            val response = newFixedLengthResponse(status, mime, ByteArrayInputStream(body), body.size.toLong())
            upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        private fun rewritePlaylist(text: String, base: HttpUrl, sessionId: String): String {
            val output = StringBuilder(text.length + 4096)
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() -> output.append('\n')
                    line.startsWith("#") -> output.append(rewriteTag(line, base, sessionId)).append('\n')
                    else -> {
                        val absolute = base.resolve(line)?.toString() ?: line
                        output.append(proxyUrl(absolute, sessionId)).append('\n')
                    }
                }
            }
            return output.toString()
        }

        private fun rewriteTag(line: String, base: HttpUrl, sessionId: String): String {
            if (!line.contains("URI=\"")) return line
            return uriAttribute.replace(line) { match ->
                val uri = match.groupValues[1]
                val absolute = base.resolve(uri)?.toString() ?: uri
                "URI=\"${proxyUrl(absolute, sessionId)}\""
            }
        }

        private fun proxyUrl(url: String, sessionId: String): String =
            "http://127.0.0.1:$listeningPort/p?d=${encode(url)}&s=$sessionId"

        private fun encode(value: String): String = Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        private fun decode(value: String): String? = try {
            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }

        private fun looksTextual(body: ByteArray, contentType: String?, url: String): Boolean {
            if (contentType?.contains("mpegurl", true) == true) return true
            if (url.contains(".m3u8", true) || url.contains(".vtt", true)) return true
            val prefix = String(body, 0, minOf(body.size, 16), Charsets.UTF_8)
            return prefix.startsWith("#EXTM3U") || prefix.startsWith("WEBVTT")
        }

        /** anv serves TS segments with `.jpg` / `.image` extensions and fake image headers. */
        private fun stripDisguise(body: ByteArray): ByteArray {
            if (body.size < 512) return body
            val head = body.copyOfRange(0, minOf(body.size, 4096))
            val skip = AutoDetector.detectSkipBytes(head)
            return if (skip in 1 until body.size) body.copyOfRange(skip, body.size) else body
        }

        private fun statusOf(code: Int): Response.IStatus =
            Response.Status.lookup(code) ?: Response.Status.OK

        companion object {
            private const val MIME_M3U8 = "application/vnd.apple.mpegurl"
            private const val MIME_TS = "video/mp2t"
            private const val MIME_MP4 = "video/mp4"
            private const val MIME_VTT = "text/vtt"
        }
    }
}
