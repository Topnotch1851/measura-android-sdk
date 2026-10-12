package dev.measura.sdk

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * The three Measura endpoints the SDK calls, served from 127.0.0.1 inside the
 * test process, so a test can script what the server says (a 503, a 422, a
 * refused gzip body, a kill switch) and read exactly what the device sent.
 *
 * Plain HTTP/1.1 over a ServerSocket rather than a mock-server dependency: the
 * SDK speaks HttpURLConnection with a Content-Length body, which is all this
 * has to understand.
 */
class FakeMeasuraServer : AutoCloseable {

    class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String,
        val gzip: Boolean,
        val atMs: Long,
        val status: Int,
    ) {
        /** The events of an /ingest POST. */
        fun events(): List<JSONObject> {
            val arr = JSONObject(body).optJSONArray("events") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Request>()

    /** Statuses /ingest answers with, in order; 200 once empty. */
    val ingestStatuses = ConcurrentLinkedQueue<Int>()

    /** Answer 400 to any gzip-encoded /ingest body, as a proxy that cannot inflate would. */
    @Volatile var rejectGzip = false

    /** The `config` object /resolve-key returns. Keys left out fall back to the local config. */
    @Volatile var remoteConfig = JSONObject()

    /** The `deferred` object /deferred-link returns, or null for "no link". */
    @Volatile var deferred: JSONObject? = null

    val ingestUrl = "http://127.0.0.1:${socket.localPort}/functions/v1/ingest"

    init {
        thread(isDaemon = true, name = "fake-measura") {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { s.use { handle(it) } }
            }
        }
    }

    fun ingestPosts(): List<Request> = requests.filter { it.path.endsWith("/ingest") }
    fun count(path: String): Int = requests.count { it.path.endsWith(path) }

    /** Posts that carried an event named [name] (event_properties.custom_event_name). */
    fun postsWith(name: String): List<Request> = ingestPosts().filter { p -> p.events().any { it.named(name) } }

    /** Accepted events named [name]. */
    fun accepted(name: String): List<JSONObject> =
        ingestPosts().filter { it.status in 200..299 }.flatMap { it.events() }.filter { it.named(name) }

    private fun JSONObject.named(name: String) =
        optJSONObject("event_properties")?.optString("custom_event_name") == name

    private fun handle(s: Socket) {
        val input = s.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, target) = lines[0].split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        val raw = readBytes(input, headers["content-length"]?.toIntOrNull() ?: 0)
        val gzip = headers["content-encoding"] == "gzip"
        val body = try {
            (if (gzip) GZIPInputStream(raw.inputStream()).readBytes() else raw).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
        val path = target.substringBefore('?')

        val (code, reply) = when {
            path.endsWith("/resolve-key") -> 200 to JSONObject()
                .put("customer_id", CUSTOMER_ID)
                .put("app_id", APP_ID)
                .put("sdk_secret", SECRET)
                .put("config", remoteConfig)
                .toString()
            path.endsWith("/deferred-link") -> 200 to JSONObject()
                .put("version", 1)
                .put("deferred", deferred ?: JSONObject.NULL)
                .toString()
            path.endsWith("/ingest") -> {
                val status = if (rejectGzip && gzip) 400 else ingestStatuses.poll() ?: 200
                status to if (status in 200..299) {
                    val arr = JSONObject(body).optJSONArray("events") ?: JSONArray()
                    val results = JSONArray()
                    for (i in 0 until arr.length()) {
                        results.put(JSONObject().put("status", "accepted").put("event_id", arr.getJSONObject(i).optString("event_id")))
                    }
                    JSONObject().put("results", results).toString()
                } else {
                    """{"error":"scripted $status"}"""
                }
            }
            else -> 404 to "{}"
        }
        requests.add(
            Request(method, path, target.substringAfter('?', ""), headers, body, gzip, SystemClock.elapsedRealtime(), code),
        )

        val bytes = reply.toByteArray(Charsets.UTF_8)
        s.getOutputStream().apply {
            write(
                ("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val end = "\r\n\r\n"
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b.toChar() == end[matched] -> matched + 1
                b.toChar() == '\r' -> 1
                else -> 0
            }
        }
        return out.toString("UTF-8")
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf
    }

    override fun close() = socket.close()

    companion object {
        const val CUSTOMER_ID = "11111111-1111-1111-1111-111111111111"
        const val APP_ID = "22222222-2222-2222-2222-222222222222"
        const val SECRET = "fake-sdk-secret"
    }
}
