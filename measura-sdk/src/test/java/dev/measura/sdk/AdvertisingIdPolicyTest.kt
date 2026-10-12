package dev.measura.sdk

import dev.measura.sdk.core.MeasuraConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisingIdPolicyTest {

    @Test
    fun `advertising id is collected by default`() {
        assertTrue(
            "collectAdvertisingId defaulted to false; every native postback " +
                "format would fail with 'requires a gaid' and the ad_id " +
                "attribution tier would be unreachable",
            MeasuraConfig().collectAdvertisingId,
        )
    }

    @Test
    fun `an integrator can refuse to collect it`() {
        assertEquals(false, MeasuraConfig(collectAdvertisingId = false).collectAdvertisingId)
    }

    @Test
    fun `turning it off changes nothing else about the configuration`() {
        val on = MeasuraConfig()
        val off = MeasuraConfig(collectAdvertisingId = false)
        assertEquals(on.batchSize, off.batchSize)
        assertEquals(on.wifiOnlyMode, off.wifiOnlyMode)
        assertEquals(on.maxOfflineQueueSize, off.maxOfflineQueueSize)
        assertEquals(on.maxRetryAttempts, off.maxRetryAttempts)
    }
}
