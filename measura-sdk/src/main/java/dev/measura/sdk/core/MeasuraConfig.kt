package dev.measura.sdk.core

/**
 * Configuration object for Measura SDK initialisation.
 * All fields have sensible defaults suitable for production.
 */
class MeasuraConfig(
    /**
     * Maximum events to buffer before forcing a flush.
     * Default: 50 (matches server-side MAX_BATCH_SIZE)
     */
    val batchSize: Int = 50,

    /**
     * Maximum time (ms) to wait before flushing buffered events,
     * even if batchSize has not been reached.
     * Default: 30_000 (30 seconds)
     */
    val flushIntervalMs: Long = 30_000L,

    /**
     * If true, events are only sent over Wi-Fi.
     * On mobile data, events are buffered until Wi-Fi is available.
     * Recommended: true (reduces user data usage - important in NG/KE)
     * Default: true
     */
    val wifiOnlyMode: Boolean = true,

    /**
     * If true, events are suppressed while the device battery is below
     * LOW_BATTERY_THRESHOLD. Events are queued and sent when battery recovers.
     */
    val suppressOnLowBattery: Boolean = true,

    /** Battery % below which events are suppressed when suppressOnLowBattery = true */
    val lowBatteryThreshold: Int = 15,

    /**
     * Maximum retry attempts for failed network requests.
     * Uses exponential backoff: 1s, 2s, 4s, 8s, 16s
     */
    val maxRetryAttempts: Int = 5,

    /**
     * Maximum events to persist in local SQLite storage while offline.
     * Older events are dropped when limit is exceeded (FIFO).
     */
    val maxOfflineQueueSize: Int = 10_000,


    val collectAdvertisingId: Boolean = true,

    /**
     * Ingest endpoint. Override only if you're self-hosting or pointing at a
     * staging project.
     *
     * The live production host (api-proxy/ forwards it to the functions).
     */
    val ingestEndpoint: String =
        "https://api.measura.dev/v1/ingest",

    /**
     * TESTING ONLY. Stands in for the Google Play Install Referrer.
     *
     * The referrer is the deterministic tier-1 attribution signal, and it is
     * genuinely unobtainable outside a real Play install: a sideloaded build on
     * an emulator gets responseCode 2, FEATURE_NOT_SUPPORTED, no matter what.
     * That made the strongest signal in the whole engine impossible to exercise
     * from this repository, which is how it stayed unverified.
     *
     * When set, and ONLY when the real Play referrer is absent, this value is
     * used in its place so the install path, the click-ID extraction and the
     * engine's deterministic matching can be driven end to end.
     *
     * It never overrides a genuine referrer, so it cannot mask a real one. Any
     * install attributed this way is marked `referrer_simulated: true` in the
     * payload, so a row in the database can never be mistaken for a real Play
     * install. Leave it null in every release build.
     *
     * Format is the raw referrer string Play would hand back, e.g.
     *   "measura_click_id=<uuid>&measura_deferred=%7B...%7D"
     */
    val debugInstallReferrer: String? = null,

    /**
     * TESTING ONLY. Disables every in-process flush path.
     *
     * When true the SDK still initialises, resolves its key and persists
     * events, but neither the post-resolve drain nor the periodic timer will
     * send anything. The ONLY remaining way the queue can empty is
     * MeasuraFlushWorker.
     *
     * This exists because both paths fire during the same process start, so a
     * queue that drains proves only that *something* drained it. Suppressing
     * the in-process side is what makes "the background worker did this" a
     * claim rather than an assumption. Never set it in a release build.
     */
    val debugSuppressInProcessFlush: Boolean = false,

    /**
     * Log level. Use NONE in production.
     */
    val logLevel: LogLevel = LogLevel.NONE,

    /**
     * Your Measura customer UUID. Auto-populated from API key in normal use.
     * Set explicitly if you need to override (advanced use only).
     */
    val customerId: String? = null,

    /**
     * Your Measura app UUID. Auto-populated from API key lookup.
     */
    val appId: String? = null,
) {
    enum class LogLevel { NONE, ERROR, DEBUG, VERBOSE }
}

/**
 * Standard Measura event types.
 * Use these constants for type safety. Custom events use string overload.
 */
enum class EventType(val value: String) {
    INSTALL("install"),
    OPEN("open"),
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    PURCHASE("purchase"),
    RE_ENGAGEMENT("re_engagement"),
    UNINSTALL("uninstall"),
    CUSTOM("custom"),
}
