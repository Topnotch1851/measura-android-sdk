package dev.measura.sdk.network

import android.os.SystemClock
import dev.measura.sdk.utils.Logger
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.core.MeasuraClient
import dev.measura.sdk.debug.MeasuraDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HTTP client for sending batched events to the Measura /ingest endpoint.
 * Uses HttpURLConnection (no external dependencies) for minimal SDK size.
 */
internal class IngestApiClient(
    private val config: MeasuraConfig,
    private val logger: Logger,
) {
    /**
     * Where events are posted.
     *
     * A var, not a val, because the server can move it. The compiled value is
     * only a bootstrap: /resolve-key returns the authoritative endpoint at
     * startup. Without this, the hostname baked in at build time would be
     * permanent for every app already shipped, and moving hosts would strand
     * them all until each developer rebuilt.
     */
    @Volatile
    internal var endpoint: String = config.ingestEndpoint

    /**
     * Whether to gzip outgoing payloads.
     *
     * Compression is on by default because it materially reduces mobile data
     * usage, which matters for our users. It is disabled automatically and
     * permanently for the process if the server rejects a compressed body,
     * because an ingest endpoint that cannot decompress will reject EVERY
     * event, and silently losing all telemetry is far worse than sending a
     * few more kilobytes.
     *
     * See the gzip handling in backend/functions/ingest/index.ts. Older
     * deployments without it reject compressed bodies with HTTP 400.
     */
    @Volatile
    private var useGzip: Boolean = true

    /**
     * Send a batch of events, each signed with the customer's sdk_secret
     * (returned by /resolve-key). Returns list of per-event results.
     * Throws on network failure (caller handles retry).
     */
    suspend fun send(events: List<Map<String, Any>>, sdkSecret: String): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val signedEvents = events.map { event -> signEvent(JSONObject(event), sdkSecret) }
            val payload = JSONObject().apply {
                put("events", JSONArray(signedEvents))
            }
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)

            var response = post(bodyBytes, useGzip)

            // A compressed body rejected as malformed means the server does not
            // decompress. Fall back once, remember it, and carry on rather than
            // dropping the batch.
            if (response.code == 400 && useGzip) {
                logger.error(
                    "Ingest rejected a compressed payload (HTTP 400). Retrying uncompressed."
                )
                response = post(bodyBytes, false)
                // Only a 400 that the uncompressed body fixes was about
                // compression. Any 400 used to turn gzip off for the session,
                // so one validation error cost every later batch its
                // compression on a metered connection.
                if (response.code in 200..299) {
                    logger.error("Uncompressed payload accepted - disabling gzip for this session.")
                    useGzip = false
                }
            }

            if (response.code !in 200..299) {
                throw IngestException("Server returned ${response.code}: ${response.body}", response.code)
            }

            // Parse per-event results for SDK dedup tracking
            val responseJson = JSONObject(response.body)
            val results = responseJson.optJSONArray("results") ?: JSONArray()
            val parsed = (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                mapOf(
                    "status" to item.optString("status"),
                    "event_id" to item.optString("event_id"),
                )
            }

            // A 2xx means the BATCH was accepted for processing. It says nothing
            // about the individual events: /ingest answers 200 with a per-event
            // results array in which any entry may carry an error, and a batch
            // where every event was rejected still returns 200.
            //
            // This used to log "Ingest success: 200, N events" on the strength
            // of the status line alone. On 2026-08-22 that hid a total failure:
            // the SDK escaped forward slashes when canonicalising nested
            // objects, the server did not, every signature mismatched, and the
            // client cheerfully reported success while nothing was stored. The
            // fault was invisible from the device for as long as this line was
            // the only thing reporting.
            //
            // Rejections are NOT retried and NOT thrown. An invalid signature
            // or a failed validation is deterministic, so a retry would repeat
            // it; throwing would also strand the whole batch including the
            // events the server did accept. The correct response is to be loud.
            val rejected = parsed.filter { it["status"] != "accepted" }
            if (rejected.isEmpty()) {
                logger.debug("Ingest accepted ${parsed.size}/${events.size} events")
            } else {
                val why = (0 until minOf(results.length(), 3)).joinToString("; ") { i ->
                    val it2 = results.getJSONObject(i)
                    it2.optString("code") + "/" + it2.optString("error")
                }
                logger.error(
                    "Ingest REJECTED ${rejected.size}/${parsed.size} events " +
                        "despite HTTP ${response.code}. $why",
                )
                if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                    MeasuraDebug.Stage.ERROR,
                    "server rejected ${rejected.size} of ${parsed.size} events",
                    mapOf(
                        "http_status" to response.code.toString(),
                        "rejected" to rejected.size.toString(),
                        "accepted" to (parsed.size - rejected.size).toString(),
                        "reasons" to why,
                    ),
                )
            }

            parsed
        }

    private class HttpResult(val code: Int, val body: String)

    /**
     * One POST attempt. Reads the error stream on a non-2xx, because
     * HttpURLConnection.inputStream throws for error statuses and the response
     * body carries the reason we need for diagnostics.
     */
    private fun post(bodyBytes: ByteArray, gzip: Boolean): HttpResult {
        val body = if (gzip) gzipCompress(bodyBytes) else bodyBytes

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (gzip) setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty(
                "X-Measura-SDK-Version",
                "android/${MeasuraClient.SDK_VERSION}",
            )
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }

        // Instrumented here rather than in send(), so the gzip fallback retry is
        // observed as its own request instead of disappearing into the first.
        if (MeasuraDebug.isAttached) MeasuraDebug.emit(
            MeasuraDebug.Stage.REQUEST,
            "POST $endpoint",
            mapOf(
                "method" to "POST",
                "endpoint" to endpoint,
                "content_encoding" to if (gzip) "gzip" else "identity",
                "body_bytes_raw" to bodyBytes.size.toString(),
                "body_bytes_sent" to body.size.toString(),
                "sdk_version" to "android/${MeasuraClient.SDK_VERSION}",
                // Which path actually delivered this batch. The in-process
                // flush runs on a coroutine dispatcher thread; the background
                // drain runs on a WorkManager thread. Both can fire during the
                // same process start, and without this the logs cannot say
                // which one carried the events.
                "thread" to Thread.currentThread().name,
            ),
        )

        return try {
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

            if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                if (code in 200..299) MeasuraDebug.Stage.RESPONSE else MeasuraDebug.Stage.ERROR,
                "HTTP $code from $endpoint",
                mapOf(
                    "status" to code.toString(),
                    "endpoint" to endpoint,
                    "body" to text.take(2000),
                ),
            )

            HttpResult(code, text)
        } catch (e: Exception) {
            if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                MeasuraDebug.Stage.ERROR,
                "transport failure: ${e.javaClass.simpleName}",
                mapOf("endpoint" to endpoint, "message" to (e.message ?: "")),
            )
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun signEvent(json: JSONObject, sdkSecret: String): JSONObject {
        // sent_at = signing time, covered by the signature. The server's ±10 min
        // replay window checks this instead of the (possibly hours-old, offline-
        // queued) event timestamp, so late flushes still land but captured
        // requests cannot be replayed indefinitely (audit M-1).
        json.put("sent_at", System.currentTimeMillis())

        // Convert the capture-time monotonic mark into an age, and drop the raw
        // mark: it is meaningless off-device. age_ms lets the server compute the
        // true event time as (server receipt - age), independent of the device
        // clock entirely.
        //
        // elapsedRealtime resets on reboot, so a queued event that survived a
        // restart yields a negative or nonsensical age. In that case the field is
        // omitted and the server falls back to the wall-clock timestamp, which is
        // the honest answer: the correction is genuinely unavailable.
        if (json.has("_elapsed_at_event")) {
            val capturedAt = json.optLong("_elapsed_at_event", -1L)
            json.remove("_elapsed_at_event")
            if (capturedAt >= 0L) {
                val age = SystemClock.elapsedRealtime() - capturedAt
                if (age >= 0L) json.put("age_ms", age)
            }
        }

        val canonical = canonicalize(json)
        val signature = hmacSha256(canonical, sdkSecret)
        json.put("signature", signature)

        // The canonical form and the digest, never the key that produced them.
        // This pair is the whole point of a glass-box view: it is exactly what
        // backend/lib/crypto.ts recomputes, so a mismatch is visible on the
        // device instead of only as a 401 from the server.
        if (MeasuraDebug.isAttached) {
            if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                MeasuraDebug.Stage.CANONICALIZE,
                "canonical payload for ${json.optString("event_type", "?")}",
                mapOf(
                    "event_id" to json.optString("event_id", ""),
                    "canonical" to canonical,
                    "canonical_bytes" to canonical.toByteArray(Charsets.UTF_8).size.toString(),
                ),
            )
            if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                MeasuraDebug.Stage.SIGN,
                "HMAC-SHA256 over the canonical payload",
                mapOf(
                    "event_id" to json.optString("event_id", ""),
                    "signature_alg" to "HmacSHA256",
                    "signature" to signature,
                    "sent_at" to json.optString("sent_at", ""),
                    "age_ms" to json.optString("age_ms", "(absent)"),
                ),
            )
        }
        return json
    }

    /**
     * Canonical payload MUST byte-match the server's buildCanonicalPayload
     * (backend/lib/crypto.ts): JSON.stringify of the object with top-level keys
     * sorted alphabetically, 'signature' excluded, no whitespace. Nested values
     * are serialized from the same JSONObject instances that go on the wire, so
     * their key order matches what the server re-serializes after JSON.parse.
     */
    internal fun canonicalize(json: JSONObject): String {
        val keys = json.keys().asSequence().filter { it != "signature" }.toMutableList()
        keys.sort()
        val sb = StringBuilder("{")
        keys.forEachIndexed { i, key ->
            if (i > 0) sb.append(',')
            sb.append(jsQuote(key)).append(':').append(serializeValue(json.get(key)))
        }
        return sb.append('}').toString()
    }

    internal fun serializeValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> serializeObject(value)
        is JSONArray -> serializeArray(value)
        is String -> jsQuote(value)
        is Boolean -> value.toString()
        is Number -> JSONObject.numberToString(value)
        else -> jsQuote(value.toString())
    }

    /**
     * Nested objects, serialised by hand rather than with JSONObject.toString().
     *
     * WHY THIS IS NOT toString(). org.json escapes a forward slash as `\/`,
     * which is legal JSON and which JSON.parse turns back into `/`. The server
     * rebuilds the canonical form with JSON.stringify AFTER parsing
     * (backend/lib/crypto.ts), and JSON.stringify does not escape `/`. So the
     * two sides computed different bytes for the same value and every
     * signature failed.
     *
     * It only bit nested values, because the top level went through
     * JSONObject.quote rather than toString. Every real event carries
     * device_fingerprint_components, whose user_agent is "MeasuraSDK/1.0.0
     * (...) App/0.1.0", so in practice every event the SDK ever sent was
     * rejected with AUTH_ERROR while the client logged "Ingest success: 200".
     *
     * Found on 2026-08-22 by running the SDK against staging from the harness
     * and reading the response body in the glass box. SigningContractTest
     * passed throughout: its fixture is flat and contains no slash, so it could
     * not have caught this.
     *
     * Insertion order is preserved and NOT sorted. Only the top level is
     * sorted; the server sorts only top-level keys too, and JSON.parse followed
     * by JSON.stringify preserves the order nested keys arrived in.
     */
    private fun serializeObject(o: JSONObject): String {
        val sb = StringBuilder("{")
        var first = true
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (!first) sb.append(',')
            first = false
            sb.append(jsQuote(k)).append(':').append(serializeValue(o.get(k)))
        }
        return sb.append('}').toString()
    }

    private fun serializeArray(a: JSONArray): String {
        val sb = StringBuilder("[")
        for (i in 0 until a.length()) {
            if (i > 0) sb.append(',')
            sb.append(serializeValue(a.get(i)))
        }
        return sb.append(']').toString()
    }

    /**
     * Quotes a string exactly as JSON.stringify does, and no more.
     *
     * Deliberately not JSONObject.quote: that is free to escape characters
     * JSON.stringify leaves alone, and any such difference is a silent
     * signature mismatch rather than a visible error.
     */
    internal fun jsQuote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c.code) {
                0x22 -> sb.append("\\\"")
                0x5C -> sb.append("\\\\")
                0x08 -> sb.append("\\b")
                0x0C -> sb.append("\\f")
                0x0A -> sb.append("\\n")
                0x0D -> sb.append("\\r")
                0x09 -> sb.append("\\t")
                else ->
                    if (c.code < 0x20) {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", c.code))
                    } else {
                        // Everything else is emitted verbatim, including the
                        // forward slash. JSON.stringify does not escape it,
                        // and org.json does, which is the whole bug.
                        sb.append(c)
                    }
            }
        }
        return sb.append('"').toString()
    }

    internal fun hmacSha256(data: String, secret: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        hmac.init(keySpec)
        val bytes = hmac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun gzipCompress(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    /** [status] is the HTTP status, so a caller can tell a retryable failure from one that is not. */
    class IngestException(message: String, val status: Int = 0) : Exception(message)
}

/**
 * Resolves customer_id and app_id from an API key by calling the key-resolve endpoint.
 */
/**
 * Server-controlled SDK settings returned by /resolve-key.
 *
 * Every field is nullable and nullable means "the server expressed no opinion,
 * keep the compiled default". That matters in both directions: an older SDK
 * must keep working when the server adds fields it has never heard of, and a
 * newer SDK must keep working when the server omits fields it expects.
 */
internal class RemoteConfig(
    val ingestEndpoint: String?,
    val batchSize: Int?,
    val flushIntervalMs: Long?,
    val maxRetryAttempts: Int?,
    val wifiOnlyMode: Boolean?,
    val suppressOnLowBattery: Boolean?,
    val lowBatteryThreshold: Int?,
    val samplingRate: Double?,
    val trackingEnabled: Boolean?,
)

internal object ApiKeyResolver {
    class ResolvedConfig(
        val customerId: String,
        val appId: String,
        val sdkSecret: String,
        val remote: RemoteConfig?,
    )

    suspend fun resolve(apiKey: String, baseEndpoint: String): ResolvedConfig =
        withContext(Dispatchers.IO) {
            val resolveUrl = baseEndpoint.replace("/ingest", "/resolve-key")
            val url = URL(resolveUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-Measura-Api-Key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val json = JSONObject(body)
                    ResolvedConfig(
                        customerId = json.getString("customer_id"),
                        appId = json.getString("app_id"),
                        sdkSecret = json.getString("sdk_secret"),
                        remote = parseRemoteConfig(json.optJSONObject("config")),
                    )
                } else {
                    throw IllegalStateException("Key resolve failed: HTTP $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Reads the optional `config` block.
     *
     * has() is checked per field rather than using opt* defaults, because a
     * server default and an absent field must stay distinguishable: absent
     * means keep the compiled value, whereas opt-with-default would silently
     * overwrite it with a guess. An older server that returns no config block
     * at all yields null here and the SDK behaves exactly as before.
     */
    /**
     * Reads the optional `config` block.
     *
     * JSONObject.isNull() is true for both an absent key and an explicit null,
     * which is exactly the distinction we need: either way the server expressed
     * no opinion and the compiled default stands. Casting through Number rather
     * than calling getInt/getLong/getDouble keeps this to one accessor instead
     * of one per type, and tolerates a server sending 30000 where 30000.0 was
     * expected.
     */
    private fun parseRemoteConfig(o: JSONObject?): RemoteConfig? {
        if (o == null) return null
        fun v(k: String): Any? = if (o.isNull(k)) null else o.opt(k)
        return RemoteConfig(
            ingestEndpoint       = v("ingest_endpoint") as? String,
            batchSize            = (v("batch_size") as? Number)?.toInt(),
            flushIntervalMs      = (v("flush_interval_ms") as? Number)?.toLong(),
            maxRetryAttempts     = (v("max_retry_attempts") as? Number)?.toInt(),
            wifiOnlyMode         = v("wifi_only_mode") as? Boolean,
            suppressOnLowBattery = v("suppress_on_low_battery") as? Boolean,
            lowBatteryThreshold  = (v("low_battery_threshold") as? Number)?.toInt(),
            samplingRate         = (v("sampling_rate") as? Number)?.toDouble(),
            trackingEnabled      = v("tracking_enabled") as? Boolean,
        )
    }
}
