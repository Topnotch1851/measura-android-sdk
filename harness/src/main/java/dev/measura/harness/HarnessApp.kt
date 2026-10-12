package dev.measura.harness

import android.app.Application
import dev.measura.sdk.Measura
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.debug.MeasuraDebug

/**
 * The harness deliberately does NOT call Measura.init here.
 *
 * A real integrator initialises in Application.onCreate, and that is what the
 * documentation tells them to do. This is a laboratory: initialisation is a
 * button, so a demonstration can show the SDK inert, then starting, then
 * resolving, rather than having all of that happen before the first frame.
 *
 * The debug listener IS attached here, because it has to be in place before
 * init to capture the /resolve-key exchange, which is the single most useful
 * record the SDK produces.
 */
class HarnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MeasuraDebug.attach { record -> DebugLog.add(record) }

        // Real-integrator mode, off by default.
        //
        // MeasuraFlushWorker returns immediately unless Measura.isInitialised(),
        // and it says why: WorkManager can start the process on its own, and
        // without init there is no API key and no signing secret, so nothing
        // could be sent even in principle.
        //
        // That means a harness which only initialises from a button can NEVER
        // exercise the background drain, because the worker wakes a process in
        // which init never ran. Initialising here is what a real integrator
        // does, and it is the only configuration in which the worker can be
        // tested at all.
        //
        // Build with -PmeasuraAutoInit=true for the flush-worker test; leave it
        // off for the demo, where showing the SDK inert and then starting is
        // the more useful thing to watch.
        if (BuildConfig.MEASURA_AUTO_INIT &&
            BuildConfig.MEASURA_ENDPOINT.isNotBlank() &&
            BuildConfig.MEASURA_API_KEY.isNotBlank()
        ) {
            Measura.init(
                this,
                BuildConfig.MEASURA_API_KEY,
                MeasuraConfig(
                    ingestEndpoint = BuildConfig.MEASURA_ENDPOINT,
                    debugInstallReferrer = BuildConfig.MEASURA_SEED_REFERRER.ifBlank { null },
                    // Isolates the background drain: with this on, the only
                    // code path left that can empty the queue is
                    // MeasuraFlushWorker.
                    debugSuppressInProcessFlush = BuildConfig.MEASURA_SUPPRESS_FLUSH,
                    logLevel = MeasuraConfig.LogLevel.VERBOSE,
                    batchSize = 1,
                    flushIntervalMs = 5_000L,
                    wifiOnlyMode = false,
                    suppressOnLowBattery = false,
                ),
            )
        }
    }
}

/**
 * In-memory ring of diagnostic records.
 *
 * Bounded on purpose. A purchase-heavy demo run can emit hundreds of records,
 * and an unbounded list in a process that also holds the SDK's queue is a
 * memory leak in the tool built to observe leaks.
 */
object DebugLog {

    private const val MAX_RECORDS = 500

    private val lock = Any()
    private val records = ArrayDeque<MeasuraDebug.Record>()

    /** Set by the activity so the view can refresh. Cleared in onDestroy. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun add(record: MeasuraDebug.Record) {
        synchronized(lock) {
            records.addLast(record)
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
        onChange?.invoke()
    }

    fun snapshot(): List<MeasuraDebug.Record> = synchronized(lock) { records.toList() }

    fun latestOf(stage: MeasuraDebug.Stage): MeasuraDebug.Record? =
        synchronized(lock) { records.lastOrNull { it.stage == stage } }

    fun clear() {
        synchronized(lock) { records.clear() }
        onChange?.invoke()
    }
}
