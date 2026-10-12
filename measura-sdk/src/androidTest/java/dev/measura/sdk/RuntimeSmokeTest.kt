package dev.measura.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.storage.PersistentEventStore
import dev.measura.sdk.utils.BatteryMonitor
import dev.measura.sdk.utils.DeviceFingerprint
import dev.measura.sdk.utils.NetworkMonitor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android runtime.
 *
 * The unit tests cover pure logic on the JVM, which cannot exercise the three
 * things most likely to break under R8: coroutine state machines, SQLite
 * persistence, and the framework calls behind DeviceFingerprint,
 * NetworkMonitor and BatteryMonitor. Those only fail when executed, and they
 * fail silently, so nothing else in the pipeline would notice.
 *
 * This deliberately does NOT hit the network. It proves the device-side half:
 * that events survive being written and read back, and that the framework
 * accessors return usable values rather than throwing after obfuscation.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deviceFingerprintReturnsRealValues() {
        // Exercises WindowManager, PackageManager and Build. If R8 stripped or
        // broke any of it, this throws rather than returning "unknown".
        val fp = DeviceFingerprint(context).getComponents()

        assertNotNull(fp.userAgent)
        assertTrue("user agent should identify the SDK", fp.userAgent.contains("MeasuraSDK"))
        assertTrue("device model should not be blank", fp.deviceModel.isNotBlank())
        assertTrue("os version should not be blank", fp.osVersion.isNotBlank())
        assertTrue(
            "screen resolution should look like WxH, got ${fp.screenResolution}",
            fp.screenResolution.matches(Regex("\\d+x\\d+")) || fp.screenResolution == "unknown",
        )
    }

    @Test
    fun fingerprintIsStableAcrossCalls() {
        val a = DeviceFingerprint(context).getComponents()
        val b = DeviceFingerprint(context).getComponents()
        // A fingerprint that changes between calls would split one device into
        // many and destroy probabilistic attribution.
        assertEquals(a.userAgent, b.userAgent)
        assertEquals(a.deviceModel, b.deviceModel)
    }

    @Test
    fun batteryMonitorReturnsSaneValue() {
        // Emulators return Integer.MIN_VALUE from BATTERY_PROPERTY_CAPACITY.
        // Treating that as "low" once suppressed every event for the process
        // lifetime, so the guard matters and is worth asserting on a device.
        val pct = BatteryMonitor(context).getBatteryPercent()
        assertTrue("battery percent out of range: $pct", pct in 0..100)
    }

    @Test
    fun networkMonitorDoesNotThrow() {
        val nm = NetworkMonitor(context)
        // The value depends on the emulator's state; what matters is that the
        // ConnectivityManager calls survive obfuscation.
        nm.isConnected()
        nm.isWifiConnected()
    }

    @Test
    fun eventsSurviveWriteAndReadBack() = runBlocking {
        // The critical path: coroutines plus SQLite. If either broke under R8,
        // offline events would be silently lost rather than queued.
        val store = PersistentEventStore(context, 1000)
        store.clearAll()

        val event = mapOf<String, Any>(
            "event_type" to "session_start",
            "timestamp" to System.currentTimeMillis(),
            "event_id" to "instrumentation_${System.currentTimeMillis()}",
            "sdk_version" to "1.0.0",
        )
        store.saveAll(listOf(event))

        val loaded = store.loadAll()
        assertEquals("event did not survive the round trip", 1, loaded.size)
        assertEquals("session_start", loaded[0]["event_type"])

        store.clearAll()
        assertEquals("clearAll did not empty the store", 0, store.loadAll().size)
    }

    @Test
    fun offlineQueueRespectsItsCap() = runBlocking {
        // FIFO eviction protects a device that has been offline for a long
        // time from unbounded local storage growth.
        val cap = 5
        val store = PersistentEventStore(context, cap)
        store.clearAll()

        val many = (1..cap * 3).map {
            mapOf<String, Any>(
                "event_type" to "custom",
                "timestamp" to System.currentTimeMillis(),
                "event_id" to "cap_test_$it",
                "sdk_version" to "1.0.0",
            )
        }
        store.saveAll(many)

        assertTrue(
            "store held ${store.loadAll().size} events, above the $cap cap",
            store.loadAll().size <= cap,
        )
        store.clearAll()
    }

    @Test
    fun publicApiIsCallableAfterMinification() {
        // Names kept by proguard-rules.pro. If R8 renamed any of these, this
        // test would not compile against the minified artifact.
        val config = MeasuraConfig(
            batchSize = 10,
            wifiOnlyMode = false,
            logLevel = MeasuraConfig.LogLevel.DEBUG,
        )
        assertEquals(10, config.batchSize)
        assertEquals(false, config.wifiOnlyMode)
        assertEquals("purchase", dev.measura.sdk.core.EventType.PURCHASE.value)
    }
}
