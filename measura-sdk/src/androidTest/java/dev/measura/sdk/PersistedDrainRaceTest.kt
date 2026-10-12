package dev.measura.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.storage.PersistentEventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistedDrainRaceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val apiKey: String get() = BuildConfig.E2E_API_KEY
    private val endpoint: String get() = BuildConfig.E2E_INGEST_ENDPOINT

    @Test
    fun twoConcurrentDrainsSendOneBatch() = runBlocking {
        assumeTrue("needs MEASURA_E2E_API_KEY", apiKey.isNotBlank())
        assumeTrue("needs MEASURA_E2E_ENDPOINT", endpoint.isNotBlank())

        Measura.init(
            context,
            apiKey,
            MeasuraConfig(ingestEndpoint = endpoint, logLevel = MeasuraConfig.LogLevel.NONE),
        )

        val deadline = System.currentTimeMillis() + 30_000
        while (!Measura.canSend() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assumeTrue("/resolve-key did not answer in 30s", Measura.canSend())

        val client = Measura.clientOrNull()!!

        val store = PersistentEventStore(context, 1000)
        store.clearAll()
        val stamp = System.currentTimeMillis()
        store.saveAll(
            (1..3).map {
                mapOf<String, Any>(
                    "event_type" to "custom",
                    "timestamp" to stamp,
                    "event_id" to "drainRace_${stamp}_$it",
                    "sdk_version" to "1.0.0",
                )
            },
        )

        val before = client.persistedDrainSends.get()

        withContext(Dispatchers.IO) {
            listOf(
                async { client.flushBlocking() },
                async { client.flushBlocking() },
            ).awaitAll()
        }

        val sends = client.persistedDrainSends.get() - before
        assertEquals(
            "the persisted batch was uploaded $sends times. Two cold-start " +
                "callers raced and each sent the same rows, paying twice for " +
                "one drain on a metered connection.",
            1,
            sends,
        )
        assertTrue(
            "the store still holds events after both drains completed",
            store.loadAll().isEmpty(),
        )
    }
}
