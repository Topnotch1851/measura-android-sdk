package dev.measura.sdk.network

import dev.measura.sdk.core.MeasuraDeferredLink
import dev.measura.sdk.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exchanges a click id for its deferred deep link payload.
 *
 * The consumer end of the deferred deep link contract (backend/functions/deep-link
 * and backend/functions/deferred-link). The resolver puts `measura_click_id` in the Play referrer and nothing else;
 * this turns that token into the payload by asking the server, authenticated
 * with the app's API key.
 *
 * EVERY failure mode returns null. A deferred link is an enhancement to the
 * first launch, never a precondition for it: an install must not be degraded
 * because this endpoint was slow, unreachable, or answered something
 * unexpected. The caller treats null as "this install had no deferred link",
 * which is also the correct answer for the great majority of installs.
 */
internal object DeferredLinkFetcher {

    /** The contract version this SDK speaks. See migration 122. */
    private const val CONTRACT_VERSION = 1

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * A response larger than this is refused unread rather than buffered.
     * The contract caps a payload at 2 KB plus a small envelope, so anything
     * approaching this is a misconfigured host or a hostile one, and neither
     * should be allowed to allocate freely on a low-end device.
     */
    private const val MAX_RESPONSE_BYTES = 16 * 1024

    suspend fun fetch(
        apiKey: String,
        baseEndpoint: String,
        clickId: String,
        logger: Logger,
    ): MeasuraDeferredLink? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // Derived from the ingest endpoint the same way ApiKeyResolver
            // derives /resolve-key, so a self-hosted or staging deployment that
            // already works for ingest works for this with no extra config.
            val endpoint = baseEndpoint.replace("/ingest", "/deferred-link")
            val url = URL("$endpoint?click_id=${java.net.URLEncoder.encode(clickId, "UTF-8")}")

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-Measura-Api-Key", apiKey)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            val code = connection.responseCode
            if (code != 200) {
                logger.debug("Deferred link lookup returned HTTP $code")
                return@withContext null
            }

            val body = connection.inputStream.use { stream ->
                val bytes = ByteArray(MAX_RESPONSE_BYTES)
                var read = 0
                while (read < MAX_RESPONSE_BYTES) {
                    val n = stream.read(bytes, read, MAX_RESPONSE_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            }

            parse(body, clickId, logger)
        } catch (e: Exception) {
            logger.debug("Deferred link lookup failed: ${e.message}")
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * Parses the response body.
     *
     * Internal rather than private so the unit tests can drive it directly:
     * the HTTP half needs a live server, but the parsing half is where the
     * malformed-input handling lives and it must be provably safe.
     */
    internal fun parse(body: String, clickId: String, logger: Logger): MeasuraDeferredLink? {
        return try {
            val json = JSONObject(body)

            // An unknown contract version means the server is ahead of this
            // SDK. Refusing is deliberate: guessing at a payload shape we do
            // not know is how an app ends up routing somewhere unintended.
            val version = json.optInt("version", -1)
            if (version != CONTRACT_VERSION) {
                logger.debug("Deferred link contract v$version is not supported by this SDK")
                return null
            }

            // The normal case for most installs. Explicitly not an error.
            if (json.isNull("deferred")) return null
            val d = json.optJSONObject("deferred") ?: return null

            val path = if (d.isNull("deep_link_path")) null else d.optString("deep_link_path", "")
                .takeIf { it.isNotEmpty() }

            val paramsJson = d.optJSONObject("params")
            val params = LinkedHashMap<String, String>()
            if (paramsJson != null) {
                for (key in paramsJson.keys()) {
                    if (paramsJson.isNull(key)) continue
                    // opt() then toString(), rather than getString(), so a
                    // number or boolean is coerced instead of dropped. The
                    // contract allows all three types; the SDK surfaces them
                    // uniformly as strings.
                    params[key] = paramsJson.opt(key)?.toString() ?: continue
                }
            }

            // Nothing to act on. Reporting null keeps "there is a deferred
            // link" meaningful for the integrator rather than waking their
            // listener with an empty object.
            if (path == null && params.isEmpty()) return null

            MeasuraDeferredLink(
                path = path,
                params = params,
                clickId = if (d.isNull("click_id")) clickId else d.optString("click_id", clickId),
                slug = if (d.isNull("slug")) null else d.optString("slug", "").takeIf { it.isNotEmpty() },
                campaignId = if (d.isNull("campaign_id")) null else d.optString("campaign_id", "").takeIf { it.isNotEmpty() },
                channel = if (d.isNull("channel")) null else d.optString("channel", "").takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            logger.debug("Deferred link payload was not usable: ${e.message}")
            null
        }
    }
}
