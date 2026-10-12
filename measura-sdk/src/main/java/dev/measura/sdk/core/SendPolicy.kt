package dev.measura.sdk.core

import org.json.JSONObject

/**
 * The rules every send obeys, in one place, with no Android dependency so the
 * JVM suite can pin them (SendPolicyTest).
 *
 * The limits are the server's, not tunables: /ingest (backend/functions/
 * ingest/index.ts) refuses a batch of more than MAX_BATCH_SIZE = 50 events or
 * MAX_PAYLOAD_BYTES = 256 000 bytes, and it refuses it whole. Every request
 * this SDK makes has to fit inside both, whatever the local or remote config
 * says the batch size is.
 */
internal object SendPolicy {

    /** backend/functions/ingest/index.ts MAX_BATCH_SIZE. */
    const val MAX_INGEST_BATCH = 50

    /**
     * Below the server's 256 000 byte cap, leaving room for what signing adds
     * to each event (the signature and send-time fields) after this estimate.
     */
    const val MAX_BATCH_BYTES = 200_000

    const val RESOLVE_BACKOFF_START_MS = 2_000L
    const val RESOLVE_BACKOFF_MAX_MS = 300_000L

    /** A configured batch size the server will accept: 1 to 50. */
    fun batchSize(configured: Int): Int = configured.coerceIn(1, MAX_INGEST_BATCH)

    /** Rough wire size of events, as JSON. */
    fun approxBytes(events: List<Map<String, Any>>): Int =
        events.sumOf { JSONObject(it).toString().length + 1 } + 12

    /**
     * Splits events, in order, into batches that are each within both server
     * caps. An event too large to share a batch goes alone: the server decides
     * on it, and it never holds up the events behind it.
     */
    fun <T : Map<String, Any>> batches(events: List<T>, configured: Int): List<List<T>> {
        val max = batchSize(configured)
        val out = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var bytes = 12
        for (event in events) {
            val size = JSONObject(event).toString().length + 1
            if (current.isNotEmpty() && (current.size >= max || bytes + size > MAX_BATCH_BYTES)) {
                out.add(current)
                current = mutableListOf()
                bytes = 12
            }
            current.add(event)
            bytes += size
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** Next wait between /resolve-key attempts: doubling, capped at 5 minutes. */
    fun nextResolveBackoff(current: Long): Long =
        (current * 2).coerceAtMost(RESOLVE_BACKOFF_MAX_MS)

    /**
     * Whether the network allows sending now. Wi-Fi only holds events on
     * mobile data; with no connection at all nothing is sent.
     */
    fun networkAllowsSend(wifiOnly: Boolean, connected: Boolean, onWifi: Boolean): Boolean =
        if (wifiOnly) connected && onWifi else connected
}
