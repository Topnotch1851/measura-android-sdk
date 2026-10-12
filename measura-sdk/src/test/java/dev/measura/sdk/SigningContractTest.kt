package dev.measura.sdk

import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.network.IngestApiClient
import dev.measura.sdk.utils.Logger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK/server signing contract.
 *
 * The server rebuilds the canonical string independently in
 * backend/lib/crypto.ts. If the two ever disagree by a single byte, every
 * event is rejected with a signature error and no exception is thrown on the
 * device: installs simply stop arriving. That is the failure mode that already
 * cost this product its entire ingestion path once, via gzip.
 *
 * GOLDEN_SIGNATURE below is the exact value the server-side test
 * (backend/lib/crypto.test.ts) asserts against, for the same payload and
 * secret. The two suites are deliberately pinned to one constant: if a change
 * on either side breaks the contract, one of them goes red.
 */
class SigningContractTest {

    private val client = IngestApiClient(MeasuraConfig(), Logger(MeasuraConfig.LogLevel.NONE))

    private val secret = "test_secret_do_not_use"

    /** Same fields and values as PAYLOAD in backend/lib/crypto.test.ts. */
    private fun payload(): JSONObject = JSONObject().apply {
        put("event_type", "install")
        put("timestamp", 1700000000000L)
        put("customer_id", "39e5b348-9311-4471-b428-fbf37dac453a")
        put("app_id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        put("event_id", "install_1700000000000_abc12345")
        put("sdk_version", "1.0.0")
    }

    private val expectedCanonical =
        """{"app_id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",""" +
            """"customer_id":"39e5b348-9311-4471-b428-fbf37dac453a",""" +
            """"event_id":"install_1700000000000_abc12345",""" +
            """"event_type":"install","sdk_version":"1.0.0",""" +
            """"timestamp":1700000000000}"""

    private val goldenSignature =
        "ff43eaac0aac7a040461efe49c382ce77e08fa6013dfd2617017af154072b2dc"

    @Test
    fun `canonical string byte-matches the server`() {
        assertEquals(
            "The SDK no longer produces the canonical form the server rebuilds. " +
                "Every event would be rejected as an invalid signature.",
            expectedCanonical,
            client.canonicalize(payload()),
        )
    }

    @Test
    fun `signature matches the server golden vector`() {
        assertEquals(
            goldenSignature,
            client.hmacSha256(client.canonicalize(payload()), secret),
        )
    }

    @Test
    fun `key insertion order does not change the signature`() {
        // The SDK builds its JSON in a different order than the server receives
        // it. Sorting is the only reason the two agree.
        val reversed = JSONObject().apply {
            put("sdk_version", "1.0.0")
            put("event_id", "install_1700000000000_abc12345")
            put("app_id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            put("customer_id", "39e5b348-9311-4471-b428-fbf37dac453a")
            put("timestamp", 1700000000000L)
            put("event_type", "install")
        }
        assertEquals(goldenSignature, client.hmacSha256(client.canonicalize(reversed), secret))
    }

    @Test
    fun `signature field is excluded from the signed bytes`() {
        // The wire payload carries its own signature. Including it would make
        // verification impossible.
        val withSig = payload().apply { put("signature", goldenSignature) }
        assertEquals(goldenSignature, client.hmacSha256(client.canonicalize(withSig), secret))
    }

    @Test
    fun `changing any field changes the signature`() {
        val tampered = payload().apply { put("event_type", "purchase") }
        assertNotEquals(goldenSignature, client.hmacSha256(client.canonicalize(tampered), secret))
    }

    @Test
    fun `canonical form has no whitespace`() {
        val c = client.canonicalize(payload())
        assertTrue("canonical form must not contain spaces", !c.contains(" "))
        assertTrue("canonical form must not contain newlines", !c.contains("\n"))
    }

    @Test
    fun `numbers serialise without quotes so the server parses them as numbers`() {
        // A timestamp serialised as a string would still verify against itself
        // but fail the server's numeric validation.
        val c = client.canonicalize(payload())
        assertTrue(
            "timestamp must be a bare number, got: $c",
            c.contains("\"timestamp\":1700000000000"),
        )
    }

    // ------------------------------------------------------------------
    // Regression cover for the fault that shipped past this file.
    //
    // Every test above uses a FLAT payload of plain alphanumeric values, so
    // all seven passed while every real event was rejected with AUTH_ERROR.
    // A real event always carries device_fingerprint_components, whose
    // user_agent reads "MeasuraSDK/1.0.0 (...) App/0.1.0".
    //
    // org.json escapes a forward slash as \/ inside a nested object rendered
    // with toString(). That is legal JSON, and JSON.parse turns it back into
    // /, but the server rebuilds the canonical form with JSON.stringify AFTER
    // parsing, and JSON.stringify does not escape /. Two different byte
    // strings, one signature check, silent failure.
    // ------------------------------------------------------------------

    @Test
    fun `forward slashes are never escaped`() {
        val c = client.jsQuote("MeasuraSDK/1.0.0 (Android 11) App/0.1.0")
        assertEquals(
            "A forward slash must be emitted verbatim. JSON.stringify does not " +
                "escape it, so escaping it here breaks every signature.",
            "\"MeasuraSDK/1.0.0 (Android 11) App/0.1.0\"",
            c,
        )
    }

    @Test
    fun `nested objects serialise the way JSON stringify does`() {
        val nested = JSONObject().apply {
            put("user_agent", "MeasuraSDK/1.0.0 (Android 11) App/0.1.0")
            put("screen_resolution", "1080x2072")
        }
        val p = payload().apply { put("device_fingerprint_components", nested) }

        val c = client.canonicalize(p)

        assertTrue(
            "nested value must not contain an escaped slash, got: $c",
            !c.contains("\\/"),
        )
        // NOT an assertion about insertion order. org.json picks its own key
        // order and it differs by implementation: Android backs JSONObject with
        // a LinkedHashMap, the JVM artifact used by these tests with a HashMap.
        // Pinning one order would fail on the other platform while proving
        // nothing.
        //
        // The invariant that actually matters is that the canonical nested
        // fragment matches THE WIRE. The server parses the bytes we send and
        // re-stringifies them, and JSON.parse preserves the order it received,
        // so whatever order org.json chose is correct as long as both sides of
        // this client agree on it.
        val wireNested = nested.toString().replace("\\/", "/")
        assertTrue(
            "canonical nested fragment must match the wire bytes, got: $c",
            c.contains(
                "\"device_fingerprint_components\":$wireNested",
            ),
        )
    }

    @Test
    fun `jsQuote escapes exactly what JSON stringify escapes`() {
        assertEquals("\"a\\\"b\"", client.jsQuote("a\"b"))
        assertEquals("\"a\\\\b\"", client.jsQuote("a\\b"))
        assertEquals("\"a\\nb\"", client.jsQuote("a\nb"))
        assertEquals("\"a\\tb\"", client.jsQuote("a\tb"))
        assertEquals("\"a\\rb\"", client.jsQuote("a\rb"))
        // Non-ASCII is emitted raw, exactly as JSON.stringify does by default.
        assertEquals("\"naïve\"", client.jsQuote("naïve"))
    }

    @Test
    fun `arrays serialise without escaping slashes`() {
        val arr = org.json.JSONArray().apply {
            put("a/b")
            put(2)
        }
        val p = payload().apply { put("paths", arr) }
        val c = client.canonicalize(p)
        assertTrue("array must serialise verbatim, got: $c", c.contains("\"paths\":[\"a/b\",2]"))
    }
}
