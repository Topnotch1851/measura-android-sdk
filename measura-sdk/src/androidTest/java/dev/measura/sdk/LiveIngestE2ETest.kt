package dev.measura.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.network.ApiKeyResolver
import dev.measura.sdk.network.IngestApiClient
import dev.measura.sdk.utils.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The join.
 *
 * Every other test verifies one side. RuntimeSmokeTest proves the device-side
 * components survive R8. SigningContractTest proves the canonical form matches
 * backend/lib/crypto.ts. The server was proven separately with curl.
 *
 * None of that proves the SDK, running minified on a device, produces a request
 * the live endpoint accepts. That gap is exactly the shape of the gzip failure:
 * both halves were correct, the join was broken, and no event ever arrived
 * while nothing threw and nothing logged an error.
 *
 * This test closes it by running the real path over the real network:
 *
 *   resolve-key -> sign -> gzip -> POST /ingest -> assert accepted
 *
 * It is SKIPPED, not failed, when MEASURA_E2E_API_KEY is absent, so a
 * contributor without the secret still gets a green build. CI supplies it, so
 * CI always runs it.
 *
 * The event_id it sends is unique and prefixed `androidE2E_`, which the CI
 * cleanup step uses to find and delete the row afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LiveIngestE2ETest {

    private val apiKey: String get() = BuildConfig.E2E_API_KEY

    /**
     * No fallback, deliberately.
     *
     * This used to default to the production ingest host when
     * MEASURA_E2E_ENDPOINT was unset, which meant a test whose whole job is to
     * post events would quietly post them into the production database the
     * moment a configuration variable went missing. The endpoint must be
     * stated, or the test does not run at all.
     */
    private val endpoint: String get() = BuildConfig.E2E_INGEST_ENDPOINT

    private fun requireCredentials() {
        assumeTrue(
            "MEASURA_E2E_API_KEY not set; skipping the live end-to-end test. " +
                "CI sets it, so this only skips on a local build.",
            apiKey.isNotBlank(),
        )
        assumeTrue(
            "MEASURA_E2E_ENDPOINT not set; skipping the live end-to-end test. " +
                "This test never assumes a host: an unset endpoint is a " +
                "configuration error, not a cue to fall back to production.",
            endpoint.isNotBlank(),
        )
    }

    @Test
    fun resolveKeyReturnsCredentialsAndServerConfig() = runBlocking {
        requireCredentials()

        val resolved = ApiKeyResolver.resolve(apiKey, endpoint)

        assertTrue("customer_id missing", resolved.customerId.isNotBlank())
        assertTrue("app_id missing", resolved.appId.isNotBlank())
        assertTrue("sdk_secret missing, events could not be signed", resolved.sdkSecret.isNotBlank())

        // Remote config must arrive, or the SDK is silently pinned to whatever
        // was compiled in and the kill switch does not exist in the field.
        assertNotNull("server returned no config block", resolved.remote)
        assertNotNull("config carried no ingest endpoint", resolved.remote?.ingestEndpoint)
    }

    @Test
    fun signedGzippedEventIsAcceptedByTheLiveServer() = runBlocking {
        requireCredentials()

        val resolved = ApiKeyResolver.resolve(apiKey, endpoint)
        val client = IngestApiClient(
            MeasuraConfig(ingestEndpoint = endpoint, logLevel = MeasuraConfig.LogLevel.DEBUG),
            Logger(MeasuraConfig.LogLevel.DEBUG),
        )

        val eventId = "androidE2E_${System.currentTimeMillis()}"
        val event = mapOf<String, Any>(
            "event_type" to "session_start",
            "timestamp" to System.currentTimeMillis(),
            "customer_id" to resolved.customerId,
            "app_id" to resolved.appId,
            "event_id" to eventId,
            "sdk_version" to "1.0.0",
        )

        // send() signs, canonicalises, gzips and posts. A signature mismatch,
        // a canonicalisation drift or a decompression failure all surface here.
        val results = client.send(listOf(event), resolved.sdkSecret)

        assertEquals("server did not return exactly one result", 1, results.size)
        assertEquals(
            "server rejected the event the SDK produced: $results",
            "accepted",
            results[0]["status"],
        )
        assertEquals(eventId, results[0]["event_id"])
    }

    @Test
    fun serverRejectsAnEventSignedWithTheWrongSecret() = runBlocking {
        requireCredentials()

        val resolved = ApiKeyResolver.resolve(apiKey, endpoint)
        val client = IngestApiClient(
            MeasuraConfig(ingestEndpoint = endpoint, logLevel = MeasuraConfig.LogLevel.NONE),
            Logger(MeasuraConfig.LogLevel.NONE),
        )

        val event = mapOf<String, Any>(
            "event_type" to "session_start",
            "timestamp" to System.currentTimeMillis(),
            "customer_id" to resolved.customerId,
            "app_id" to resolved.appId,
            "event_id" to "androidE2E_forged_${System.currentTimeMillis()}",
            "sdk_version" to "1.0.0",
        )

        // A server that accepts this would accept forged revenue from anyone.
        // Signature verification failing open is worse than ingestion being down.
        val results = client.send(listOf(event), "not_the_real_secret")
        assertTrue(
            "server ACCEPTED an event signed with the wrong secret: $results",
            results.isEmpty() || results[0]["status"] != "accepted",
        )
    }
}
