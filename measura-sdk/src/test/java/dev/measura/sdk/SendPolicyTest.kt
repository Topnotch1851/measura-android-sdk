package dev.measura.sdk

import dev.measura.sdk.core.SendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules every send obeys, pinned to the server's own limits
 * (backend/functions/ingest/index.ts: MAX_BATCH_SIZE 50, MAX_PAYLOAD_BYTES
 * 256 000). A batch outside them is refused whole, which is how an offline
 * queue of more than 50 events used to stop draining for good.
 */
class SendPolicyTest {

    private fun events(n: Int, bytes: Int = 400) = (1..n).map { mapOf<String, Any>("event_id" to "e$it", "pad" to "x".repeat(bytes)) }

    @Test
    fun `a queue of 120 splits into batches no larger than the server accepts`() {
        val batches = SendPolicy.batches(events(120), configured = 50)
        assertEquals(listOf(50, 50, 20), batches.map { it.size })
        assertEquals(120, batches.flatten().size)
    }

    @Test
    fun `order is preserved across batches`() {
        val flat = SendPolicy.batches(events(73), configured = 50).flatten().map { it["event_id"] }
        assertEquals((1..73).map { "e$it" }, flat)
    }

    @Test
    fun `a configured batch size above the server cap is clamped to 50`() {
        assertEquals(50, SendPolicy.batchSize(500))
        assertEquals(listOf(50, 50), SendPolicy.batches(events(100), configured = 500).map { it.size })
    }

    @Test
    fun `a zero or negative batch size still sends one at a time`() {
        assertEquals(1, SendPolicy.batchSize(0))
        assertEquals(1, SendPolicy.batchSize(-3))
    }

    @Test
    fun `large events start a new batch before the payload cap`() {
        // 30 events of ~10 KB is ~300 KB: over the 256 KB cap in one request.
        val batches = SendPolicy.batches(events(30, bytes = 10_000), configured = 50)
        assertTrue(batches.size > 1)
        batches.forEach { assertTrue(SendPolicy.approxBytes(it) <= SendPolicy.MAX_BATCH_BYTES) }
        assertEquals(30, batches.flatten().size)
    }

    @Test
    fun `a single oversized event still goes out alone rather than blocking the queue`() {
        val batches = SendPolicy.batches(events(3, bytes = 300_000), configured = 50)
        assertEquals(listOf(1, 1, 1), batches.map { it.size })
    }

    @Test
    fun `an empty queue makes no batches`() {
        assertTrue(SendPolicy.batches(emptyList<Map<String, Any>>(), configured = 50).isEmpty())
    }

    @Test
    fun `resolve backoff doubles and caps at five minutes`() {
        var b = SendPolicy.RESOLVE_BACKOFF_START_MS
        val seen = mutableListOf(b)
        repeat(12) { b = SendPolicy.nextResolveBackoff(b); seen.add(b) }
        assertEquals(2_000L, seen[0])
        assertEquals(4_000L, seen[1])
        assertEquals(8_000L, seen[2])
        assertEquals(SendPolicy.RESOLVE_BACKOFF_MAX_MS, seen.last())
        assertTrue(seen.all { it <= SendPolicy.RESOLVE_BACKOFF_MAX_MS })
    }

    @Test
    fun `wifi only holds events on mobile data`() {
        assertFalse(SendPolicy.networkAllowsSend(wifiOnly = true, connected = true, onWifi = false))
        assertTrue(SendPolicy.networkAllowsSend(wifiOnly = true, connected = true, onWifi = true))
    }

    @Test
    fun `without wifi only any connection sends, and no connection never does`() {
        assertTrue(SendPolicy.networkAllowsSend(wifiOnly = false, connected = true, onWifi = false))
        assertFalse(SendPolicy.networkAllowsSend(wifiOnly = false, connected = false, onWifi = false))
        assertFalse(SendPolicy.networkAllowsSend(wifiOnly = true, connected = false, onWifi = false))
    }
}
