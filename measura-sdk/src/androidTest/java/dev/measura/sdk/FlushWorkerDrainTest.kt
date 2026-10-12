package dev.measura.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import dev.measura.sdk.storage.PersistentEventStore
import dev.measura.sdk.work.MeasuraFlushWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlushWorkerDrainTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<MeasuraFlushWorker>(context).build().startWork().get()

    @Before
    fun emptyTheStore() {
        PersistentEventStore(context, 1000).clearAll()
    }

    @Test
    fun anUninitialisedSdkDrainsNothingAndSaysSoRatherThanLosingEvents() {
        val store = PersistentEventStore(context, 1000)
        store.saveAll(
            listOf(
                mapOf<String, Any>(
                    "event_type" to "install",
                    "timestamp" to System.currentTimeMillis(),
                    "event_id" to "workerTest_${System.currentTimeMillis()}",
                    "sdk_version" to "1.0.0",
                ),
            ),
        )
        assertEquals("precondition: one event queued", 1, store.loadAll().size)

        val result = runWorker()

        assertTrue(
            "worker returned $result; an uninitialised SDK must not fail the work",
            result is ListenableWorker.Result.Success,
        )
        assertEquals(
            "the worker DISCARDED a queued event it could not send. " +
                "An install captured on a bad connection would be lost silently.",
            1,
            store.loadAll().size,
        )
    }

    @Test
    fun theWorkerIsReachableUnderItsPersistedName() {
        val worker = TestListenableWorkerBuilder<MeasuraFlushWorker>(context).build()
        assertEquals(
            "dev.measura.sdk.work.MeasuraFlushWorker",
            worker.javaClass.name,
        )
    }
}
