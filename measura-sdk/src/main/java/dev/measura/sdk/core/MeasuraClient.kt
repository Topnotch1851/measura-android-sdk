package dev.measura.sdk.core

import dev.measura.sdk.BuildConfig
import dev.measura.sdk.debug.MeasuraDebug
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.measura.sdk.network.IngestApiClient
import dev.measura.sdk.network.ApiKeyResolver
import dev.measura.sdk.network.DeferredLinkFetcher
import dev.measura.sdk.storage.EventQueue
import dev.measura.sdk.storage.PersistentEventStore
import dev.measura.sdk.utils.DeviceFingerprint
import dev.measura.sdk.work.MeasuraFlushWorker
import dev.measura.sdk.utils.InstallReferrerReader
import dev.measura.sdk.utils.AdvertisingId
import dev.measura.sdk.utils.Logger
import dev.measura.sdk.utils.NetworkMonitor
import dev.measura.sdk.utils.BatteryMonitor
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal SDK client. Not public API - use the Measura object instead.
 */
internal class MeasuraClient(
    private val context: Context,
    private val apiKey: String,
    private val config: MeasuraConfig,
) {
    private val logger = Logger(config.logLevel)
    // The handler is the host app's crash guard. Without it, any Throwable a
    // background task did not catch - including an Error such as the
    // NoSuchMethodError the network check threw on Android 5.x - reached the
    // thread's uncaught-exception handler and killed the integrator's app.
    // Analytics must never be the reason an app crashes.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            logger.error("Measura background task failed: ${t.javaClass.simpleName}: ${t.message}")
        },
    )

    private val apiClient = IngestApiClient(config, logger)
    private val persistentStore = PersistentEventStore(context, config.maxOfflineQueueSize)
    private val eventQueue = EventQueue(config.batchSize)

    private val networkMonitor = NetworkMonitor(context)
    private val batteryMonitor = BatteryMonitor(context)
    private val deviceFingerprint = DeviceFingerprint(context)
    private val prefs = context.getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE)

    /**
     * Whether tracking is currently allowed.
     *
     * Restored from disk so that an opt-out survives an app restart. Holding
     * this only in memory meant a user who called disableTracking() was
     * silently opted back in on the next launch, which is a consent failure
     * rather than a minor bug.
     */
    private val trackingEnabled = AtomicBoolean(
        prefs.getBoolean(PREF_TRACKING_ENABLED, true),
    )
    private var customerId: String? = config.customerId
    private var appId: String? = config.appId
    private var userId: String? = null

    // HMAC signing key, returned by /resolve-key. Events are held in the
    // persistent store until this is available - they cannot be signed without it.
    @Volatile private var sdkSecret: String? = null

    private var flushJob: Job? = null

    /**
     * Deferred deep link delivery.
     *
     * The listener and the payload arrive from opposite directions and in
     * either order: the integrator registers a listener at some point during
     * startup, and the lookup completes whenever the network allows. Whichever
     * lands second triggers delivery, which is why the payload is held rather
     * than fired at the moment it is fetched.
     *
     * What survives a process death is the CLICK ID, not the payload: an app
     * killed between the lookup and the first navigation re-fetches on the next
     * launch. The Play referrer that produced the id is long gone by then -
     * Google only guarantees it briefly after install - which is the whole
     * reason the id is kept at all.
     */
    @Volatile private var deferredListener: MeasuraDeferredLinkListener? = null
    @Volatile private var pendingDeferred: MeasuraDeferredLink? = null
    @Volatile private var advertisingId: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredDelivered = AtomicBoolean(false)

    fun start() {
        if (config.collectAdvertisingId) {
            scope.launch {
                val info = AdvertisingId.fetch(context, logger)
                advertisingId = info?.id
                logger.debug(
                    "Advertising ID " + if (info != null) "resolved" else "unavailable",
                )
            }
        }

        // Resolve customer/app IDs from the API key, retrying until it answers.
        scope.launch { resolveUntilDone() }

        // Schedule periodic flush
        if (!BuildConfig.MEASURA_DIAGNOSTICS || !config.debugSuppressInProcessFlush) startFlushScheduler()

        // And a drain that outlives the process. startFlushScheduler covers the
        // app while it is running; this covers the case the in-process timer
        // structurally cannot: the user opens the app once on a bad connection
        // and never returns, leaving the install event stranded in SQLite.
        // Failures here must never break init, so a host device that has somehow
        // disabled WorkManager degrades to the old in-process behaviour rather
        // than crashing the integrator's app.
        try {
            MeasuraFlushWorker.schedule(context, config.wifiOnlyMode)
        } catch (e: Throwable) {
            logger.error("Could not schedule background flush: ${e.message}")
        }

        // First launch: read the Play Install Referrer and emit the install
        // event with the referrer attached - the deterministic attribution
        // signal for link-based channels (audit S-2 / BUILD_PLAN C3).
        if (!prefs.getBoolean(PREF_INSTALL_TRACKED, false)) {
            scope.launch {
                val real = withTimeoutOrNull(INSTALL_REFERRER_TIMEOUT_MS) {
                    InstallReferrerReader(context, logger).read()
                }
                // A seeded referrer NEVER overrides a genuine one, so it cannot
                // mask a real Play install. It only fills the gap a sideloaded
                // build always leaves.
                // Folds to null in a published build, so the entire simulated
                // branch below is removed by R8 rather than shipped.
                val seeded = if (BuildConfig.MEASURA_DIAGNOSTICS) config.debugInstallReferrer else null
                val simulated = real == null && seeded != null
                val referrer = real ?: seeded

                if (simulated) {
                    logger.error(
                        "Using debugInstallReferrer. This is a SIMULATED tier-1 " +
                            "signal and must never be set in a release build.",
                    )
                    if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                        MeasuraDebug.Stage.EVENT_QUEUED,
                        "install referrer SIMULATED via debugInstallReferrer",
                        mapOf(
                            "referrer" to (referrer ?: ""),
                            "click_id" to (InstallReferrerReader.extractClickId(referrer) ?: "none"),
                        ),
                    )
                }

                trackInstall(referrer, simulated)
                prefs.edit().putBoolean(PREF_INSTALL_TRACKED, true).apply()

                // Deferred deep link. Runs AFTER the install event is queued,
                // deliberately: the install is the product's core metric and
                // must not wait on, or be lost to, an optional lookup.
                resolveDeferredLink(InstallReferrerReader.extractClickId(referrer))
            }
        }

        // A payload fetched on a previous launch that the app never consumed -
        // because it was killed before navigating, or registered its listener
        // after the window closed. Restored here so first-launch routing is not
        // lost to a process death.
        retryPendingDeferred()

        // Track open/session_start for this launch
        trackEvent(EventType.SESSION_START.value, emptyMap())
    }

    /**
     * Calls /resolve-key until it answers, backing off from 2 s to 5 minutes.
     *
     * It used to be tried exactly once, in start(). One failure - a cold start
     * on a dead connection, a timeout - left the signing secret null for the
     * rest of the process: every event went to SQLite, nothing was sent, and
     * the background worker retried forever waiting for a secret that no code
     * would ever fetch. Waiting out an offline spell costs nothing; the loop
     * only calls the network when there is one.
     */
    private suspend fun resolveUntilDone() {
        var backoff = SendPolicy.RESOLVE_BACKOFF_START_MS
        while (currentCoroutineContext().isActive && sdkSecret == null) {
            if (!networkMonitor.isConnected()) {
                delay(backoff)
                backoff = SendPolicy.nextResolveBackoff(backoff)
                continue
            }
            try {
                resolveOnce()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to resolve API key: ${e.message}")
                if (MeasuraDebug.isAttached) MeasuraDebug.emit(
                    MeasuraDebug.Stage.ERROR,
                    "/resolve-key failed: ${e.javaClass.simpleName}",
                    mapOf(
                        "endpoint_attempted" to config.ingestEndpoint,
                        "message" to (e.message ?: ""),
                    ),
                )
                logger.debug("Retrying /resolve-key in ${backoff}ms")
                delay(backoff)
                backoff = SendPolicy.nextResolveBackoff(backoff)
            }
        }
    }

    private suspend fun resolveOnce() {
        val resolved = ApiKeyResolver.resolve(apiKey, config.ingestEndpoint)
        customerId = customerId ?: resolved.customerId
        appId = appId ?: resolved.appId
        sdkSecret = resolved.sdkSecret
        applyRemoteConfig(resolved.remote)
        logger.debug("SDK resolved: customerId=$customerId, appId=$appId")

        // The compiled endpoint is only a bootstrap; the server can move
        // the SDK elsewhere. Reporting both is what makes an accidental
        // cross-environment redirect visible on the device rather than
        // only as rows appearing in the wrong database. sdk_secret is
        // deliberately absent: it is the signing key.
        // Guarded, not merely null-checked inside emit. The map below is
        // an argument, so it is built BEFORE the call: without this the
        // allocation and every string literal ship and run regardless.
        // R8 folds isAttached to false in a published build and removes
        // this whole block.
        if (MeasuraDebug.isAttached) MeasuraDebug.emit(
            MeasuraDebug.Stage.RESOLVE,
            "/resolve-key answered",
            mapOf(
                "customer_id" to (customerId ?: ""),
                "app_id" to (appId ?: ""),
                "endpoint_compiled" to config.ingestEndpoint,
                "endpoint_effective" to apiClient.endpoint,
                "endpoint_moved_by_server" to
                    (apiClient.endpoint != config.ingestEndpoint).toString(),
                "remote_config_present" to (resolved.remote != null).toString(),
                "tracking_enabled" to (resolved.remote?.trackingEnabled?.toString() ?: "(inherited)"),
                "sampling_rate" to (resolved.remote?.samplingRate?.toString() ?: "(inherited)"),
                "batch_size" to (resolved.remote?.batchSize?.toString() ?: "(inherited)"),
                "wifi_only_mode" to (resolved.remote?.wifiOnlyMode?.toString() ?: "(inherited)"),
            ),
        )

        // Flush any events persisted from a previous session. Gated so a
        // test can prove the background worker, and only the worker, drained it.
        if (!BuildConfig.MEASURA_DIAGNOSTICS || !config.debugSuppressInProcessFlush) flushPersistedEvents()
    }

    /**
     * Emits the one-time install event. Bypasses the low-battery deferral -
     * this event fires exactly once and drives the product's core metric.
     * The referrer (and the click ID parsed from it) ride as top-level
     * payload fields the attribution engine matches on (hierarchy tiers 1–2).
     */
    private suspend fun trackInstall(referrer: String?, referrerSimulated: Boolean = false) {
        if (!trackingEnabled.get()) {
            logger.debug("Tracking disabled - dropping install event")
            return
        }

        val extras = buildMap<String, Any> {
            if (referrer != null) {
                put("referrer", referrer)
                InstallReferrerReader.extractClickId(referrer)?.let { put("click_id", it) }
            }
        }

        // Carried in event_properties, NOT as a top-level field.
        //
        // /ingest maps a fixed set of top-level keys onto columns and persists
        // only sdk_version and event_properties into events_raw.payload. A
        // top-level key it does not recognise is dropped without complaint, so
        // the first version of this marker vanished between the device and the
        // database while every log line said it had been set.
        //
        // It has to survive, because it is the only thing distinguishing an
        // install attributed from a seeded referrer from one attributed by a
        // genuine Play install, and those look identical in the attributions
        // table: both come out as model=install_referrer, confidence 96.
        val props = if (referrerSimulated) {
            mapOf<String, Any>("referrer_simulated" to true)
        } else {
            emptyMap()
        }

        val event = buildEvent(EventType.INSTALL.value, props, extras)
        eventQueue.enqueue(event)
        logger.debug("Queued install event (referrer present: ${referrer != null})")
        doFlush()
    }

    /**
     * Returns whether the event was accepted - queued, or persisted for later -
     * rather than dropped by consent, the kill switch or sampling. Callers that
     * remember having sent something (setPushToken, setIntegrityToken) must only
     * remember it when this is true.
     */
    fun trackEvent(eventType: String, properties: Map<String, Any>): Boolean {
        // The server's event_type enum has 9 members. A caller passing anything
        // outside it (e.g. Measura.trackEvent("signup", ...) from the public
        // String overload) used to be rejected by /ingest with VALIDATION_ERROR
        // inside an HTTP 200, which is invisible unless something reads the
        // per-event results. Remapped to `custom` instead, the same way
        // identify() and setPushToken() already carry their real name in
        // custom_event_name, so an unrecognised type is recorded rather than
        // silently dropped.
        val resolvedType: String
        val resolvedProperties: Map<String, Any>
        if (eventType in KNOWN_EVENT_TYPES) {
            resolvedType = eventType
            resolvedProperties = properties
        } else {
            resolvedType = EventType.CUSTOM.value
            resolvedProperties = properties + mapOf("custom_event_name" to eventType)
        }

        if (!trackingEnabled.get()) {
            logger.debug("Tracking disabled by the user, dropping event: $resolvedType")
            return false
        }

        // Server kill switch. Checked separately from the user opt-out above so
        // neither can silently re-enable the other.
        if (!remoteTrackingAllowed()) {
            logger.debug("Tracking disabled by server config, dropping event: $resolvedType")
            return false
        }

        if (!passesSampling()) {
            logger.debug("Event sampled out: $resolvedType")
            return false
        }

        // Low battery: persist for a later flush instead of dropping - the old
        // early-return said "deferring" but queued nothing, silently losing
        // events (incl. purchases) on low-end devices (audit M-13).
        if (effectiveSuppressOnLowBattery && batteryMonitor.isBelowThreshold(effectiveLowBatteryThreshold)) {
            logger.debug("Low battery - persisting event for later flush: $resolvedType")
            scope.launch {
                persistentStore.saveAll(listOf(buildEvent(resolvedType, resolvedProperties)))
            }
            return true
        }

        scope.launch {
            val event = buildEvent(resolvedType, resolvedProperties)
            eventQueue.enqueue(event)

            logger.debug("Queued event: $resolvedType (queue size: ${eventQueue.size()})")

            // Immediate flush if batch size reached
            if (eventQueue.size() >= SendPolicy.batchSize(effectiveBatchSize)) {
                flush()
            }
        }
        return true
    }

    /**
     * Sent as `custom`, not as `identify`.
     *
     * `event_type` is a Postgres enum on the server and /ingest rejects
     * anything outside click, install, open, session_start, session_end,
     * purchase, custom, re_engagement, uninstall. This method emitted
     * "identify", which is not in that list, so every call it ever made was
     * rejected with VALIDATION_ERROR. Nothing surfaced it, because the client
     * treated a 200 with per-event errors as success.
     *
     * The name is preserved in the properties rather than lost, so the event is
     * still identifiable downstream without widening the server enum.
     *
     * Found on 2026-08-22 by pressing the button in the harness and reading the
     * response body.
     */
    fun identify(userId: String, traits: Map<String, Any>) {
        this.userId = userId
        trackEvent(
            EventType.CUSTOM.value,
            traits + mapOf("user_id" to userId, "custom_event_name" to "identify"),
        )
    }

    /**
     * Sends the FCM token as a `custom` event so the server can ping it later
     * and read an uninstall from FCM's NotRegistered response.
     *
     * Rides the ordinary event pipeline rather than a bespoke endpoint: it is
     * signed, queued, retried and drained exactly like everything else, so a
     * token registered on a bad connection is not lost. Deduplicated on the
     * device so a token that has not changed is not re-sent on every launch.
     */
    fun setPushToken(token: String) {
        if (token.isBlank() || token == prefs.getString(PREF_PUSH_TOKEN, null)) return
        // Remembered only once accepted. Saving it first meant a token handed
        // over while tracking was off, the kill switch was on or the event was
        // sampled out was never sent, and never would be: the next call saw
        // the same token already "sent" and returned.
        val accepted = trackEvent(
            EventType.CUSTOM.value,
            mapOf("custom_event_name" to "push_token", "push_token" to token),
        )
        if (!accepted) return
        prefs.edit().putString(PREF_PUSH_TOKEN, token).apply()
        logger.debug("Push token registered for uninstall detection")
    }

    /**
     * Forwards a Google Play Integrity token for server-side verification.
     *
     * Opaque on purpose. The SDK never decodes the token and never computes a
     * verdict: anything this process decides about its own integrity is
     * controlled by whoever is running the emulator farm. Only Google's
     * decodeIntegrityToken, called server side with a service account this
     * device never sees, produces an answer worth recording.
     *
     * Deliberately a token you pass in rather than a Play Integrity dependency
     * here. com.google.android.play:integrity is well over 100 KB and the AAR
     * budget is 102,400 bytes gzipped, so depending on it would break the size
     * figure the site publishes for a signal an integrator can already supply.
     * Request a token with StandardIntegrityManager and hand it to this method.
     *
     * Costs nothing if never called: no token, no verdict rows, and no other
     * behaviour changes. Play Integrity is an additional fraud signal, never a
     * precondition for ordinary event collection.
     *
     * Rides the ordinary event pipeline for the same reason setPushToken does:
     * it is signed, queued, retried and drained like everything else, so a
     * token produced on a bad connection is not lost. The last submitted value
     * is remembered so an accidental repeat submission is not sent twice.
     */
    fun setIntegrityToken(token: String) {
        if (token.isBlank() || token == prefs.getString(PREF_INTEGRITY_TOKEN, null)) return
        // Remembered only once accepted; see setPushToken.
        val accepted = trackEvent(
            EventType.CUSTOM.value,
            mapOf("custom_event_name" to "integrity_token", "integrity_token" to token),
        )
        if (!accepted) return
        prefs.edit().putString(PREF_INTEGRITY_TOKEN, token).apply()
        logger.debug("Integrity token forwarded for server-side verification")
    }

    internal fun isTrackingEnabled(): Boolean = trackingEnabled.get()

    fun setTrackingEnabled(enabled: Boolean) {
        trackingEnabled.set(enabled)
        // apply(), not commit(): these are consent-toggle APIs an integrator
        // plausibly calls straight from a UI callback (a "reject analytics"
        // button), i.e. the main thread, and commit() blocks that thread
        // until the write reaches disk. This does not trade away durability -
        // apply()'s pending write is still flushed by the platform's own
        // QueuedWork mechanism before the process is allowed to be killed, the
        // same guarantee commit() was chosen for here, just non-blocking.
        prefs.edit().putBoolean(PREF_TRACKING_ENABLED, enabled).apply()
        // Opting out also discards what was recorded but not yet sent, in
        // memory and on disk. Withdrawn consent covers events still waiting
        // for Wi-Fi or a batch, not only the ones tracked after the tap.
        if (!enabled) {
            eventQueue.drainAll()
            scope.launch { persistentStore.clearAll() }
        }
        logger.debug("Tracking ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Blocking flush, for MeasuraFlushWorker only. runBlocking is correct here
     * precisely because it is wrong everywhere else: the caller is a WorkManager
     * background thread whose whole contract is to stay alive until the work is
     * done.
     */
    /**
     * Whether an event could actually be sent right now.
     *
     * Distinct from "initialised". init() returns immediately and resolves the
     * signing secret over the network afterwards, so there is a window, several
     * seconds wide on a cold start, in which the SDK is fully initialised and
     * still cannot sign anything. MeasuraFlushWorker has to tell those apart:
     * the first is worth waiting for, the second is not.
     */
    internal fun canSend(): Boolean = !sdkSecret.isNullOrBlank()

    /**
     * Returns true when the offline queue is empty afterwards, so the worker
     * can ask WorkManager to retry instead of reporting success over events
     * that are still waiting.
     */
    internal fun flushBlocking(): Boolean = runBlocking {
        val drained = flushPersistedEvents()
        doFlush()
        drained
    }

    fun flush() {
        scope.launch { doFlush() }
    }

    private suspend fun doFlush() {
        // Cannot sign events until /resolve-key has returned the sdk_secret
        val secret = sdkSecret
        if (secret == null) {
            logger.debug("SDK secret not resolved yet - persisting events")
            val pending = eventQueue.drainAll()
            if (pending.isNotEmpty()) persistentStore.saveAll(pending)
            return
        }

        // Check network conditions
        if (effectiveWifiOnly && !networkMonitor.isWifiConnected()) {
            logger.debug("Wi-Fi not available - persisting events for later")
            val pending = eventQueue.drainAll()
            if (pending.isNotEmpty()) persistentStore.saveAll(pending)
            return
        }

        if (!networkMonitor.isConnected()) {
            logger.debug("No network - persisting events")
            val pending = eventQueue.drainAll()
            if (pending.isNotEmpty()) persistentStore.saveAll(pending)
            return
        }

        // Drain in-memory queue. Events built before /resolve-key completed
        // carry empty customer/app IDs - fill them at send time so early
        // events (session_start fires before resolution) stay attributable.
        val events = stampResolvedIds(eventQueue.drainAll())
        if (events.isEmpty()) return

        // Send in batches the server accepts (at most 50 events, inside the
        // payload cap), each with its own retry ladder. A batch that exhausts
        // its retries is persisted with every batch behind it, so a failure
        // never loses events and never re-sends ones already accepted.
        //
        // coerceAtLeast(1): effectiveMaxRetryAttempts is server-controlled
        // (remote?.maxRetryAttempts), and a `while (attempt < N)` loop with
        // N == 0 never runs its body at all - not one send attempt, and not
        // the persist on failure either, so the drained events were silently
        // dropped. The database now rejects 0 for a fresh write (migration
        // 161), but an already-installed app cannot be patched, and a cached
        // 0 must not turn "don't retry" into "don't even try once".
        val effectiveAttempts = effectiveMaxRetryAttempts.coerceAtLeast(1)
        val batches = SendPolicy.batches(events, effectiveBatchSize)

        for ((index, batch) in batches.withIndex()) {
            var attempt = 0
            var backoffMs = 1_000L
            while (true) {
                try {
                    val result = apiClient.send(batch, secret)
                    logger.debug("Flush success: ${result.size} events sent")
                    break
                } catch (e: CancellationException) {
                    persistentStore.saveAll(batches.drop(index).flatten())
                    throw e
                } catch (e: Exception) {
                    attempt++
                    logger.error("Flush attempt $attempt failed: ${e.message}")
                    // A 4xx other than 408/429 is the server refusing this
                    // batch, and a retry a second later sends the same bytes
                    // to the same answer. Stop the ladder and keep the events
                    // rather than spending the whole ladder on a metered link.
                    val status = (e as? IngestApiClient.IngestException)?.status ?: 0
                    if (status in 400..499 && status != 408 && status != 429) {
                        val unsent = batches.drop(index).flatten()
                        persistentStore.saveAll(unsent)
                        logger.error("Ingest refused the batch (HTTP $status), not retried - persisted ${unsent.size} events")
                        return
                    }
                    if (attempt >= effectiveAttempts) {
                        // Persist this batch and every one behind it for retry
                        val unsent = batches.drop(index).flatten()
                        persistentStore.saveAll(unsent)
                        logger.error("Max retries exceeded - persisted ${unsent.size} events")
                        return
                    }
                    delay(backoffMs)
                    backoffMs *= 2  // exponential backoff
                }
            }
        }
    }

    private val persistedFlushLock = Mutex()

    internal val persistedDrainSends = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun flushPersistedEvents(): Boolean = persistedFlushLock.withLock {
        flushPersistedEventsLocked()
    }

    /**
     * Drains the offline queue in batches the server accepts, oldest first,
     * deleting exactly the rows each accepted batch carried. Returns true when
     * the queue is empty afterwards.
     *
     * Two faults this replaces:
     *  - It sent EVERY stored row as one request. /ingest refuses a batch over
     *    50 events, so a queue that grew past 50 was rejected on every attempt
     *    and never drained again.
     *  - It ignored Wi-Fi only. /resolve-key answering triggered it, so on a
     *    cold start on mobile data the whole backlog went out over the metered
     *    connection the setting exists to protect.
     *
     * Rows are deleted by id, so a row a concurrent doFlush() persists while a
     * batch is in flight is never touched (see PersistentEventStore.deleteIds).
     */
    private suspend fun flushPersistedEventsLocked(): Boolean {
        val secret = sdkSecret ?: return false // will retry on next flush cycle
        if (!networkAllowsSend()) {
            logger.debug("Network does not allow sending - offline queue held")
            return false
        }

        var sentAny = false
        while (true) {
            val (loaded, ids) = persistentStore.loadOldest(SendPolicy.batchSize(effectiveBatchSize))
            if (loaded.isEmpty()) return true
            if (!sentAny) logger.debug("Flushing persisted events from a previous session")
            val batch = stampResolvedIds(loaded)
            try {
                persistedDrainSends.incrementAndGet()
                apiClient.send(batch, secret)
                persistentStore.deleteIds(ids)
                sentAny = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to flush persisted events: ${e.message}")
                return false
            }
        }
    }

    /** Wi-Fi only and connectivity, as one rule for every send path. */
    private fun networkAllowsSend(): Boolean = SendPolicy.networkAllowsSend(
        wifiOnly = effectiveWifiOnly,
        connected = networkMonitor.isConnected(),
        onWifi = networkMonitor.isWifiConnected(),
    )

    // Events built before /resolve-key completed carry empty customer/app IDs
    private fun stampResolvedIds(events: List<Map<String, Any>>): List<Map<String, Any>> {
        val gid = advertisingId
        val cid = customerId
        val aid = appId
        if (cid == null || aid == null) return events.map { stampGaid(it, gid) }
        return events.map { event ->
            val withIds = if ((event["customer_id"] as? String).isNullOrEmpty()) {
                event + mapOf("customer_id" to cid, "app_id" to aid)
            } else {
                event
            }
            stampGaid(withIds, gid)
        }
    }

    private fun stampGaid(event: Map<String, Any>, gid: String?): Map<String, Any> {
        if (gid == null) return event
        if (!(event["gaid"] as? String).isNullOrEmpty()) return event
        return event + mapOf("gaid" to gid)
    }

    // Server-controlled settings, null until /resolve-key answers. Every read
    // falls back to the compiled MeasuraConfig, so the SDK behaves exactly as
    // before if the server says nothing or cannot be reached.
    @Volatile private var remote: dev.measura.sdk.network.RemoteConfig? = null

    private val sampler = java.util.Random()

    private fun applyRemoteConfig(rc: dev.measura.sdk.network.RemoteConfig?) {
        if (rc == null) return
        remote = rc

        // The endpoint is the one setting that must take effect immediately:
        // it decides where every subsequent batch goes. Today this is
        // protected only by a server-side CHECK constraint
        // (sdk_config_endpoint_https) requiring https:// - a real but
        // remote guarantee this client has no way to verify stayed correct.
        // Rejecting anything but an https:// URL here too means the SDK does
        // not depend solely on the database layer never regressing.
        rc.ingestEndpoint?.let {
            if (it.startsWith("https://")) {
                apiClient.endpoint = it
            } else {
                logger.error("Ignoring remote ingest_endpoint override - not https://: $it")
            }
        }

        // Restart the scheduler only if the interval actually changed, so a
        // config fetch does not reset the flush timer on every launch.
        val newInterval = rc.flushIntervalMs
        if (newInterval != null && newInterval != effectiveFlushIntervalMs) {
            effectiveFlushIntervalMs = newInterval
            if (!BuildConfig.MEASURA_DIAGNOSTICS || !config.debugSuppressInProcessFlush) startFlushScheduler()
        }

        logger.debug(
            "Remote config applied: endpoint=" + apiClient.endpoint +
                " tracking=" + (rc.trackingEnabled ?: true) +
                " sampling=" + (rc.samplingRate ?: 1.0)
        )
    }

    // Effective values: server first, compiled default second.
    private val effectiveBatchSize: Int
        get() = remote?.batchSize ?: config.batchSize
    private val effectiveWifiOnly: Boolean
        get() = remote?.wifiOnlyMode ?: config.wifiOnlyMode
    private val effectiveSuppressOnLowBattery: Boolean
        get() = remote?.suppressOnLowBattery ?: config.suppressOnLowBattery
    private val effectiveLowBatteryThreshold: Int
        get() = remote?.lowBatteryThreshold ?: config.lowBatteryThreshold
    private val effectiveMaxRetryAttempts: Int
        get() = remote?.maxRetryAttempts ?: config.maxRetryAttempts

    @Volatile private var effectiveFlushIntervalMs: Long = config.flushIntervalMs

    /**
     * The server-side kill switch. Distinct from setTrackingEnabled(), which is
     * the end user's consent choice: a user opt-out must never be overridden by
     * a server that says tracking is on, so both have to agree.
     */
    private fun remoteTrackingAllowed(): Boolean = remote?.trackingEnabled ?: true

    /**
     * Load shedding. Sampling is decided per event rather than per session so a
     * single unlucky roll cannot silence a device for its whole lifetime.
     */
    private fun passesSampling(): Boolean {
        val rate = remote?.samplingRate ?: 1.0
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        return sampler.nextDouble() < rate
    }

    private fun startFlushScheduler() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(effectiveFlushIntervalMs)
                // One failed flush (e.g. SQLite full) must not kill the
                // scheduler for the rest of the session (audit L-1)
                try {
                    doFlush()
                } catch (e: Exception) {
                    logger.error("Scheduled flush failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun buildEvent(
        eventType: String,
        properties: Map<String, Any>,
        topLevelExtras: Map<String, Any> = emptyMap(),
    ): Map<String, Any> {
        val fp = deviceFingerprint.getComponents()
        val timestamp = System.currentTimeMillis()

        return buildMap {
            putAll(topLevelExtras)
            put("event_type", eventType)
            put("timestamp", timestamp)
            // Monotonic mark taken at capture time. Converted to an age at send
            // time (see IngestApiClient.signEvent) and stripped before the wire,
            // so the server can derive when this actually happened from its own
            // clock rather than trusting the device's. Unaffected by the user
            // changing the system clock, which otherwise corrupts the click to
            // install delta feeding the confidence score.
            put("_elapsed_at_event", SystemClock.elapsedRealtime())
            put("customer_id", customerId ?: "")
            put("app_id", appId ?: "")
            put("event_id", generateEventId(eventType, timestamp))
            put("sdk_version", SDK_VERSION)

            // Device identity
            put("device_fingerprint_components", mapOf(
                "user_agent"        to fp.userAgent,
                "device_model"      to fp.deviceModel,
                "os_version"        to fp.osVersion,
                "screen_resolution" to fp.screenResolution,
            ))
            put("device_model", fp.deviceModel)
            put("os_name", "android")
            put("os_version", fp.osVersion)
            put("app_version", fp.appVersion)
            // Google Ads app conversions require the device country.
            val locale = java.util.Locale.getDefault()
            if (locale.country.length == 2) put("country", locale.country)
            put("locale", locale.toString())

            advertisingId?.let { put("gaid", it) }

            // Event properties
            if (properties.isNotEmpty()) put("event_properties", properties)
            // Promoted as a number, which is what /ingest validates. A
            // String "49.99" used to be promoted as-is and rejected inside an
            // HTTP 200, losing the purchase silently. A value that is not a
            // number stays in event_properties and is not promoted.
            revenueValue(properties["revenue"])?.let { put("revenue", it) }
            properties["currency"]?.let { put("currency", it) }
            if (userId != null) put("user_id", userId!!)
        }
    }

    // ------------------------------------------------------------------
    // DEFERRED DEEP LINKING
    //
    // The chain this completes, end to end:
    //
    //   dashboard writes short_links.deep_link_path / deferred_params
    //     -> resolver mints a click and puts measura_click_id in the Play
    //        referrer (and ONLY that: no customer data on a URL)
    //     -> Play preserves the referrer through the install
    //     -> InstallReferrerReader recovers it on first launch
    //     -> DeferredLinkFetcher exchanges the click id for the payload
    //     -> the payload is held until a listener exists
    //     -> the integrator's listener is called once, on the main thread
    //
    // Before this, the last four steps did not exist: the SDK read the
    // referrer, extracted the click id for attribution, and discarded
    // everything else.
    // ------------------------------------------------------------------

    /**
     * Looks up the deferred payload for this install, once.
     *
     * Guarded by its own preference rather than PREF_INSTALL_TRACKED so a
     * lookup that failed on a dead connection is retried on the next launch,
     * while the install event (which is queued and drained separately) is not
     * re-sent.
     */
    private suspend fun resolveDeferredLink(clickId: String?) {
        if (clickId == null) return
        if (prefs.getBoolean(PREF_DEFERRED_RESOLVED, false)) return
        if (!trackingEnabled.get()) {
            logger.debug("Tracking disabled - skipping deferred link lookup")
            return
        }

        val link = DeferredLinkFetcher.fetch(apiKey, apiClient.endpoint, clickId, logger)

        // Recorded only on a definite answer. A null from a network failure and
        // a null meaning "no deferred link" are indistinguishable here by
        // design (the fetcher never throws), so this deliberately marks the
        // lookup done either way EXCEPT when there is something to deliver.
        // The cost of a redundant lookup on a later launch is one request; the
        // cost of never retrying is a lost first-run destination.
        if (link == null) {
            // A definite "nothing here" and a failed request are deliberately
            // not distinguished (the fetcher never throws). Marking the lookup
            // done costs at most a redirect-free launch; NOT marking it would
            // retry forever on every launch of every install that came from
            // outside a Measura link, which is most of them.
            prefs.edit().putBoolean(PREF_DEFERRED_RESOLVED, true).apply()
            logger.debug("No deferred deep link for this install")
            return
        }

        // Only the click id is written to disk, not the payload.
        //
        // The payload is re-fetched rather than serialised because the server
        // is the authority on it and answers idempotently, so a launch that
        // dies before the app navigates re-reads the same answer. It also keeps
        // a customer's campaign parameters out of the host app's SharedPreferences,
        // and keeps ~2 KB of serialise/restore code out of an AAR that is
        // gated against a published size.
        prefs.edit().putString(PREF_DEFERRED_CLICK, link.clickId).apply()
        pendingDeferred = link
        deliverDeferredIfReady()
    }

    /**
     * A direct deep link: the app was opened by a Measura link while already
     * installed. The link resolver adds `measura_click_id` and `measura_path`
     * to the app URL (backend/lib/link-resolution.ts); this records the open
     * as a re_engagement carrying the click id at the TOP level, the field
     * /ingest matches on, and returns the path for the app to navigate to.
     *
     * Returns null for a URL that carries no Measura path. Records nothing for
     * a URL with no click id, or while tracking is off or killed server side.
     */
    fun handleDeepLink(uri: android.net.Uri): String? {
        val clickId = uri.getQueryParameter("measura_click_id")?.trim()?.takeIf { it.isNotEmpty() }
        val path = uri.getQueryParameter("measura_path")?.trim()?.takeIf { it.isNotEmpty() }
        if (clickId == null || !trackingEnabled.get() || !remoteTrackingAllowed()) return path
        scope.launch {
            val props = if (path != null) mapOf<String, Any>("deep_link_path" to path) else emptyMap()
            eventQueue.enqueue(buildEvent(EventType.RE_ENGAGEMENT.value, props, mapOf("click_id" to clickId)))
            doFlush()
        }
        return path
    }

    fun setDeferredLinkListener(listener: MeasuraDeferredLinkListener?) {
        deferredListener = listener
        if (listener != null) deliverDeferredIfReady()
    }

    /**
     * Delivers the payload when both halves are present.
     *
     * AtomicBoolean rather than a null check because the listener can be set
     * from the main thread while the lookup completes on an IO thread, and
     * delivering a deep link twice would navigate the user twice.
     */
    private fun deliverDeferredIfReady() {
        val listener = deferredListener ?: return
        val link = pendingDeferred ?: return
        if (!deferredDelivered.compareAndSet(false, true)) return

        // Cleared BEFORE dispatch. If the integrator's callback throws, the
        // link is still consumed: replaying a crash on every launch would be
        // worse than losing one navigation.
        pendingDeferred = null
        prefs.edit().remove(PREF_DEFERRED_CLICK).apply()

        mainHandler.post {
            try {
                listener.onDeferredDeepLink(link)
            } catch (e: Exception) {
                logger.error("Deferred deep link listener threw: ${e.message}")
            }
        }
        logger.debug("Delivered deferred deep link (path=${link.path})")
    }

    /**
     * Re-attempts delivery for an install whose payload was fetched on an
     * earlier launch but never reached a listener, because the process died
     * first or no listener was ever registered.
     *
     * The Play referrer is long gone by now - Google only guarantees it briefly
     * after install - which is exactly why the click id was kept. The server
     * answers the same payload for the same id, so this is a re-read rather
     * than a second source of truth.
     */
    private fun retryPendingDeferred() {
        val clickId = prefs.getString(PREF_DEFERRED_CLICK, null) ?: return
        if (!trackingEnabled.get()) return
        scope.launch {
            val link = DeferredLinkFetcher.fetch(apiKey, apiClient.endpoint, clickId, logger)
            if (link == null) {
                // The link was deleted, the click expired, or the app lost its
                // entitlement to it. Stop asking.
                prefs.edit().remove(PREF_DEFERRED_CLICK).apply()
                return@launch
            }
            pendingDeferred = link
            deliverDeferredIfReady()
        }
    }

    private fun generateEventId(eventType: String, timestamp: Long): String {
        return "${eventType}_${timestamp}_${UUID.randomUUID().toString().take(8)}"
    }

    companion object {
        /** A revenue value /ingest accepts: a number, or a String that is one. */
        internal fun revenueValue(raw: Any?): Double? = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }

        const val SDK_VERSION = "0.1.2"
        private const val PREF_INSTALL_TRACKED = "install_tracked"
        private const val PREF_TRACKING_ENABLED = "tracking_enabled"
        private const val PREF_PUSH_TOKEN = "push_token"
        private const val PREF_INTEGRITY_TOKEN = "integrity_token"
        // Separate from PREF_INSTALL_TRACKED: the install event and the
        // deferred lookup succeed and fail independently, and collapsing them
        // would either re-send installs or permanently skip the lookup after
        // one bad connection.
        private const val PREF_DEFERRED_RESOLVED = "deferred_resolved"
        private const val PREF_DEFERRED_CLICK = "deferred_click_id"
        private const val INSTALL_REFERRER_TIMEOUT_MS = 5_000L

        // Mirrors backend/functions/ingest/index.ts VALID_EVENT_TYPES exactly.
        // Kept as the full server set rather than EventType.values().map{it.value}
        // because "click" is a valid server type with no public enum constant -
        // it is produced by deep-link handling, never by an integrator's own
        // trackEvent call, but excluding it here would misclassify it if one ever did.
        private val KNOWN_EVENT_TYPES = setOf(
            "click", "install", "open", "session_start", "session_end",
            "purchase", "custom", "re_engagement", "uninstall",
        )
    }
}
