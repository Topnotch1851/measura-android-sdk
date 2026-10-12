package dev.measura.sdk.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.measura.sdk.Measura
import java.util.concurrent.TimeUnit

/**
 * Drains the offline queue from outside the app's own lifetime.
 *
 * Without this, flushing happened only on coroutines owned by the app process.
 * That is fine while someone is using the app and useless the moment they are
 * not: a user who installs, opens once on a bad connection and never returns
 * kept their events in SQLite forever. For an attribution product those are
 * precisely the events that matter, because the install is the billable one.
 *
 * WorkManager survives process death and reboots, and defers to the system on
 * Doze and battery, so the queue drains when the device is genuinely able to
 * rather than when the app happens to be foregrounded.
 *
 * The worker deliberately does nothing when the SDK has not been initialised.
 * WorkManager can start the process on its own, and if the host app has not
 * called Measura.init in Application.onCreate there is no API key and no signing
 * secret, so no event could be sent even in principle. Returning success rather
 * than retrying avoids a backoff loop that could never succeed.
 */
internal class MeasuraFlushWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        // Logged unconditionally, including the no-op case. A worker that wakes
        // and returns success because the host app never called init is
        // indistinguishable from one that ran and had nothing to do, and that
        // silence is what made this whole path unverifiable.
        android.util.Log.d(
            "MeasuraSDK",
            "MeasuraFlushWorker.doWork ENTER on thread ${Thread.currentThread().name}, " +
                "initialised=${Measura.isInitialised()}",
        )

        if (!Measura.isInitialised()) {
            android.util.Log.e(
                "MeasuraSDK",
                "MeasuraFlushWorker: SDK not initialised, draining nothing. " +
                    "The host app must call Measura.init in Application.onCreate " +
                    "or the offline queue never empties in the background.",
            )
            return Result.success()
        }

        // Wait for the signing secret before giving up.
        //
        // init() returns immediately and resolves the secret over the network
        // afterwards. When WorkManager wakes a DEAD process it starts the
        // worker within a second or two, while /resolve-key is still in flight,
        // so the worker used to find no secret, drain nothing, and return
        // success. WorkManager then waited a full 15 minutes, the periodic
        // floor, before trying again.
        //
        // That failed hardest in the exact case this worker exists for: a user
        // installs, opens once on a bad connection and never returns. The
        // install event is the billable one, and it could sit in SQLite through
        // repeated firings, each losing the same race and reporting success.
        //
        // Measured on an emulator on 2026-08-22: the worker entered at
        // 23:23:53 and the secret arrived at 23:23:59. Six seconds.
        val deadline = System.currentTimeMillis() + SECRET_WAIT_MS
        while (!Measura.canSend() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(SECRET_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.retry()
            }
        }

        if (!Measura.canSend()) {
            // retry, NOT success. Success here would mean waiting out the
            // 15 minute periodic floor; retry uses WorkManager's backoff, which
            // starts far sooner. The events are safe in SQLite either way.
            android.util.Log.e(
                "MeasuraSDK",
                "MeasuraFlushWorker: no signing secret after ${SECRET_WAIT_MS}ms " +
                    "(/resolve-key did not answer). Retrying with backoff.",
            )
            return Result.retry()
        }

        return try {
            // Blocking on purpose. doWork runs on a WorkManager background
            // thread, and returning before the flush finished would let the
            // system reclaim the process mid-send, which is the exact failure
            // this worker exists to prevent.
            // Retry, not success, while events are still waiting: a batch the
            // network refused, or Wi-Fi only on a metered connection. Success
            // here used to be reported unconditionally, so WorkManager treated
            // an undrained queue as done until the next 15 minute period.
            if (!Measura.flushBlocking()) {
                android.util.Log.d("MeasuraSDK", "MeasuraFlushWorker: offline queue not empty, retrying")
                return Result.retry()
            }
            android.util.Log.d(
                "MeasuraSDK",
                "MeasuraFlushWorker.doWork EXIT ok on thread ${Thread.currentThread().name}",
            )
            Result.success()
        } catch (t: Throwable) {
            // Retry with WorkManager's backoff. Events stay in SQLite either
            // way; the queue is the durable part, this is only the trigger.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "measura-flush"

        /**
         * How long to wait for /resolve-key before giving up and retrying.
         *
         * Comfortably longer than the 10s connect and 10s read timeouts in
         * ApiKeyResolver, so a single slow round trip is covered, and far
         * inside WorkManager's 10 minute doWork budget.
         */
        private const val SECRET_WAIT_MS = 30_000L
        private const val SECRET_POLL_MS = 250L

        /**
         * Schedules the periodic drain. Safe to call on every init: KEEP means an
         * already-scheduled worker is left alone rather than restarted, so the
         * interval does not reset every time the app launches.
         *
         * Fifteen minutes is WorkManager's floor for periodic work, not a chosen
         * value. Normal flushing still happens in-process on the existing timer;
         * this is the safety net for when the process is gone.
         */
        fun schedule(context: Context, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()

            val request = PeriodicWorkRequestBuilder<MeasuraFlushWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
