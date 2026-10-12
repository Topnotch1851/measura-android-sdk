package dev.measura.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.measura.sdk.core.MeasuraClient
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.storage.PersistentEventStore
import dev.measura.sdk.utils.NetworkMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The delivery pipeline, live against staging (MEASURA_E2E_API_KEY and
 * MEASURA_E2E_ENDPOINT, like LiveIngestE2ETest). Each test pins one of the
 * faults fixed on 2026-09-24 and fails on the code before the fix:
 *
 *  1. A backlog over 50 events was sent as one request, refused by /ingest's
 *     MAX_BATCH_SIZE, and never drained. It now drains in batches.
 *  2. The persisted drain ignored Wi-Fi only and sent the backlog over mobile
 *     data. It now holds it.
 *  3. /resolve-key was tried once; a failure left the SDK unable to sign
 *     anything until the app restarted. It now retries until it answers.
 *  4. A push token handed over while tracking was off was remembered as sent,
 *     so it never was. It is now remembered only once accepted.
 *
 * Each test builds its own MeasuraClient rather than going through the
 * Measura singleton, so the order the runner picks cannot make one test's
 * init another's precondition.
 *
 * Events carry an event_id prefix the host checks against the staging
 * database afterwards (events_raw), so "sent" means stored, not just posted.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryPipelineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val apiKey: String get() = BuildConfig.E2E_API_KEY
    private val endpoint: String get() = BuildConfig.E2E_INGEST_ENDPOINT

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
            BufferedReader(InputStreamReader(it)).readText()
        }
    }

    private fun waitFor(timeoutMs: Long, what: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (what()) return true
            Thread.sleep(250)
        }
        return what()
    }

    private fun client(wifiOnly: Boolean, key: String = apiKey): MeasuraClient {
        val c = MeasuraClient(
            context,
            key,
            MeasuraConfig(
                ingestEndpoint = endpoint,
                wifiOnlyMode = wifiOnly,
                suppressOnLowBattery = false,
                collectAdvertisingId = false,
                logLevel = MeasuraConfig.LogLevel.DEBUG,
            ),
        )
        c.start()
        return c
    }

    private fun seed(store: PersistentEventStore, prefix: String, n: Int) {
        val stamp = System.currentTimeMillis()
        store.saveAll(
            (1..n).map {
                mapOf<String, Any>(
                    "event_type" to "custom",
                    "timestamp" to stamp,
                    "event_id" to "${prefix}_$it",
                    "sdk_version" to MeasuraClient.SDK_VERSION,
                    "event_properties" to mapOf("custom_event_name" to "pipeline_test", "n" to it),
                )
            },
        )
    }

    @After
    fun restoreNetwork() {
        shell("svc wifi enable")
        shell("svc data enable")
    }

    @Test
    fun backlogOfOneHundredTwentyDrainsInBatchesTheServerAccepts() {
        assumeTrue("needs MEASURA_E2E_API_KEY", apiKey.isNotBlank())
        assumeTrue("needs MEASURA_E2E_ENDPOINT", endpoint.isNotBlank())

        val c = client(wifiOnly = false)
        assertTrue("/resolve-key did not answer in 60s", waitFor(60_000) { c.canSend() })

        val store = PersistentEventStore(context, 10_000)
        store.clearAll()
        val prefix = "pipe120_${System.currentTimeMillis()}"
        seed(store, prefix, 120)

        val sendsBefore = c.persistedDrainSends.get()
        val drained = c.flushBlocking()

        assertTrue("flushBlocking reports the queue drained", drained)
        assertTrue("the offline queue is empty", store.loadOldest(1).first.isEmpty())
        assertEquals("three sends: 50 + 50 + 20", 3, c.persistedDrainSends.get() - sendsBefore)
        android.util.Log.i("MeasuraPipelineTest", "BACKLOG_PREFIX=$prefix")
    }

    /**
     * Runs as a tenant with the server's default config (Wi-Fi only on,
     * MEASURA_E2E_WIFI_API_KEY): server settings override local ones, so the
     * main E2E tenant, which has Wi-Fi only off server side, cannot prove this.
     */
    @Test
    fun wifiOnlyHoldsTheBacklogOnMobileData() {
        val wifiKey = BuildConfig.E2E_WIFI_API_KEY
        assumeTrue("needs MEASURA_E2E_WIFI_API_KEY", wifiKey.isNotBlank())
        assumeTrue("needs MEASURA_E2E_ENDPOINT", endpoint.isNotBlank())

        val c = client(wifiOnly = true, key = wifiKey)
        assertTrue("/resolve-key did not answer in 60s", waitFor(60_000) { c.canSend() })

        shell("svc wifi disable")
        shell("svc data enable")
        val net = NetworkMonitor(context)
        assumeTrue(
            "the device has no mobile data connection to test against",
            waitFor(20_000) { net.isConnected() && !net.isWifiConnected() },
        )

        val store = PersistentEventStore(context, 10_000)
        store.clearAll()
        val prefix = "pipeWifi_${System.currentTimeMillis()}"
        seed(store, prefix, 5)

        val sendsBefore = c.persistedDrainSends.get()
        val drained = c.flushBlocking()

        assertFalse("nothing drains on mobile data with Wi-Fi only on", drained)
        assertEquals("no send was attempted", 0, c.persistedDrainSends.get() - sendsBefore)
        // Counted by prefix: the client's own session_start from start() is
        // held on mobile data too, correctly.
        val held = store.loadOldest(100).first.count { (it["event_id"] as? String)?.startsWith(prefix) == true }
        assertEquals("all five seeded events are still queued", 5, held)

        // And once Wi-Fi is back, the same backlog goes - on devices that have
        // Wi-Fi at all (the API 21 emulator image has only mobile data).
        shell("svc wifi enable")
        if (waitFor(30_000) { net.isWifiConnected() }) {
            assertTrue("drains once on Wi-Fi", c.flushBlocking())
            assertTrue(store.loadOldest(1).first.isEmpty())
        } else {
            android.util.Log.i("MeasuraPipelineTest", "no Wi-Fi on this device; hold proven, release not exercised")
            store.clearAll()
        }
        android.util.Log.i("MeasuraPipelineTest", "WIFI_PREFIX=$prefix")
    }

    @Test
    fun resolveRetriesUntilTheNetworkAnswers() {
        assumeTrue("needs MEASURA_E2E_API_KEY", apiKey.isNotBlank())
        assumeTrue("needs MEASURA_E2E_ENDPOINT", endpoint.isNotBlank())

        shell("svc wifi disable")
        shell("svc data disable")
        val net = NetworkMonitor(context)
        assumeTrue("could not take the emulator offline", waitFor(20_000) { !net.isConnected() })

        val c = client(wifiOnly = false)
        Thread.sleep(4_000)
        assertFalse("no secret while offline", c.canSend())

        shell("svc wifi enable")
        shell("svc data enable")
        assertTrue(
            "the SDK obtained its signing secret after the network returned, without a restart",
            waitFor(90_000) { c.canSend() },
        )
    }

    @Test
    fun aPushTokenRefusedWhileTrackingIsOffIsSentOnceItIsOn() {
        assumeTrue("needs MEASURA_E2E_API_KEY", apiKey.isNotBlank())
        assumeTrue("needs MEASURA_E2E_ENDPOINT", endpoint.isNotBlank())

        val prefs = context.getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE)
        val c = client(wifiOnly = false)
        val token = "pipeline-test-token-${System.currentTimeMillis()}"
        try {
            c.setTrackingEnabled(false)
            c.setPushToken(token)
            assertNotEquals("not remembered as sent while tracking is off", token, prefs.getString("push_token", null))

            c.setTrackingEnabled(true)
            c.setPushToken(token)
            assertEquals("remembered once accepted", token, prefs.getString("push_token", null))
        } finally {
            c.setTrackingEnabled(true)
        }
    }
}
