package dev.measura.sdk

import android.content.Context
import dev.measura.sdk.core.MeasuraClient
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.core.EventType
import dev.measura.sdk.core.MeasuraDeferredLinkListener

/**
 * Measura MMP Android SDK
 *
 * Usage:
 *   // In Application.onCreate():
 *   Measura.init(context, "YOUR_API_KEY")
 *
 *   // Track events anywhere:
 *   Measura.trackEvent(EventType.PURCHASE, mapOf("revenue" to 49.99, "currency" to "USD"))
 *
 * SDK guarantees:
 *   - Batch events (up to 50, or every 30s) before sending
 *   - Wi-Fi-first by default (configurable)
 *   - HMAC-signed requests
 *   - Exponential backoff retry (max 5 attempts)
 *   - AAR under 100 KB gzipped, gated in CI. Integrating it grows a minified
 *     APK by roughly 312 KB, the difference being WorkManager and its Room
 *     storage, which an AAR does not carry
 */
object Measura {

    @Volatile
    private var client: MeasuraClient? = null

    /**
     * Initialize the Measura SDK.
     *
     * Call once in `Application.onCreate()`. Thread-safe (double-checked
     * locking).
     *
     * **`Application.onCreate` is a requirement, not a convention.** Events that
     * cannot be sent immediately are persisted and drained later by
     * [dev.measura.sdk.work.MeasuraFlushWorker], which the system may start when
     * your app is not running. That worker can only send if init already ran in
     * that process, and the SDK deliberately does not persist your API key to
     * disk. Initialise from an activity, or behind a lazy check, and the worker
     * will wake on schedule, find no credentials and do nothing: a queue built
     * up on a bad connection never empties. Nothing throws. The only sign is an
     * error line in logcat from the worker.
     *
     * If you need consent before tracking, initialise here anyway and call
     * [disableTracking] until the user agrees. That decision is committed to
     * disk synchronously and survives process death, so events are dropped
     * rather than queued, and the background drain still works afterwards.
     *
     * @param context Application context
     * @param apiKey  Your Measura SDK API key (found in dashboard → Settings)
     * @param config  Optional configuration overrides
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        apiKey: String,
        config: MeasuraConfig = MeasuraConfig(),
    ) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            // Logged, not thrown: init() is documented above to run inside
            // Application.onCreate(), where an uncaught exception crashes
            // every single launch of the host app - a missing/empty API key
            // (an unset CI secret, a wrong Gradle property, an empty build
            // flavor field) should disable the SDK for that build, not the
            // app it is integrated into. Matches the "nothing throws" promise
            // this same doc comment already makes for the WorkManager path.
            if (apiKey.isBlank()) {
                android.util.Log.e("MeasuraSDK", "Measura.init() called with a blank API key - the SDK will not start")
                return
            }
            client = MeasuraClient(context.applicationContext, apiKey, config)
            client!!.start()
        }
    }

    /**
     * Track a custom or standard event.
     *
     * @param eventType  One of the EventType constants or a custom string
     * @param properties Optional key-value event properties
     */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(
        eventType: String,
        properties: Map<String, Any> = emptyMap(),
    ) {
        requireInitialised().trackEvent(eventType, properties)
    }

    /** Convenience overload using the EventType enum */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(
        eventType: EventType,
        properties: Map<String, Any> = emptyMap(),
    ) = trackEvent(eventType.value, properties)

    /**
     * Set user identity attributes for attribution enrichment.
     * Call after login/registration.
     */
    @JvmStatic
    fun identify(userId: String, traits: Map<String, Any> = emptyMap()) {
        requireInitialised().identify(userId, traits)
    }

    /**
     * Register this device's FCM token so uninstalls can be detected.
     *
     * An app cannot observe its own uninstall: by the time it happens the
     * process and its code are gone. Every MMP solves this the same way, and so
     * does Measura: the server sends a silent data-only push to the token on a
     * schedule, and when FCM answers `NotRegistered` or `UNREGISTERED` that IS
     * the uninstall signal. The absence of a device is what gets measured.
     *
     * Deliberately a token you pass in rather than a Firebase dependency here.
     * Adding firebase-messaging to this SDK would drag a large transitive tree
     * into every integrator's APK to serve one signal, and an app that wants
     * uninstall tracking already has FCM. Call this from your
     * `FirebaseMessagingService.onNewToken`, and once at startup with
     * `FirebaseMessaging.getInstance().token`.
     *
     * Costs nothing if never called: no token, no pings, no uninstall
     * detection, and no other behaviour changes.
     */
    @JvmStatic
    fun setPushToken(token: String) {
        requireInitialised().setPushToken(token)
    }

    /**
     * Measura's Google Cloud project number, for [setIntegrityToken].
     *
     * You MUST pass this to `setCloudProjectNumber()` when you build the
     * integrity request, or Measura cannot decode the token you send and the
     * verdict is silently unavailable. See [setIntegrityToken] for the full
     * snippet and for why this is the value rather than your own.
     *
     * Not a secret. A Cloud project number is an identifier that Google
     * expects to appear inside client apps; it grants nothing on its own.
     */
    const val CLOUD_PROJECT_NUMBER: Long = 1065747069718L

    /**
     * Forward a Google Play Integrity token so the server can verify the device.
     *
     * Android only. Play Integrity is a Google Play service, so there is no
     * equivalent call on other platforms and none is implied by this API.
     *
     * ## The token must be minted against MEASURA's Cloud project
     *
     * ```kotlin
     * val manager = IntegrityManagerFactory.createStandard(context)
     * val provider = manager.prepareIntegrityToken(
     *     PrepareIntegrityTokenRequest.builder()
     *         .setCloudProjectNumber(Measura.CLOUD_PROJECT_NUMBER)   // <- required
     *         .build()
     * )
     * // later, per request:
     * val token = provider.request(
     *     StandardIntegrityTokenRequest.builder()
     *         .setRequestHash(yourRequestHash)
     *         .build()
     * ).await().token()
     * Measura.setIntegrityToken(token)
     * ```
     *
     * Google decodes an integrity token only for the Cloud project it was
     * minted against. Measura verifies with its own service account, so a
     * token minted against YOUR project cannot be decoded by us, and one
     * minted against ours can. Google documents exactly this for SDKs:
     *
     * > "Apps exclusively distributed outside of Google Play and SDKs also
     * > have to specify their Google Cloud project number through the
     * > `setCloudProjectNumber()` method."
     *
     * This is why you do not need to link a Cloud project, create a service
     * account, or grant Measura any access to your Google Cloud. If you omit
     * `setCloudProjectNumber`, the token is minted against your own project,
     * we receive something we cannot read, and the verdict is recorded as
     * unavailable rather than failing loudly. Nothing else breaks.
     *
     * ## What this SDK deliberately does not do
     *
     * Attestation is only worth anything if the device cannot influence the
     * answer, so this SDK never decodes the token or decides a verdict. It
     * forwards Google's opaque token and the server decodes it with a service
     * account the device never sees. A verdict computed here would be worth
     * exactly as much as the honesty of whoever is running the device.
     *
     * The Play Integrity library is your dependency, not ours:
     * com.google.android.play:integrity is well over 100 KB against a 102,400
     * byte gzipped AAR budget, and you already need it to produce a token at
     * all. Adding it here would charge every integrator for a library the
     * ones using this feature already have.
     *
     * Costs nothing if never called, and never blocks anything: Measura keeps
     * collecting events normally with or without Play Integrity. The verdict
     * feeds fraud scoring as a tag, and no event is discarded because of it.
     */
    @JvmStatic
    fun setIntegrityToken(token: String) {
        requireInitialised().setIntegrityToken(token)
    }

    /**
     * Receive the deferred deep link for this install, if there is one.
     *
     * A user taps a Measura link, does not have your app, installs it, and
     * opens it. This is how you find out where they were going, so you can send
     * them there instead of to your home screen.
     *
     * ```kotlin
     * Measura.init(this, "YOUR_API_KEY")
     * Measura.setDeferredDeepLinkListener { link ->
     *     // link.path is what you configured on the link in the dashboard,
     *     // e.g. "/product/123" or "myapp://product/123"
     *     link.path?.let { router.navigate(it) }
     *     val source = link.param("source")
     * }
     * ```
     *
     * ## What to expect
     *
     * - Called AT MOST ONCE per install, on the MAIN thread.
     * - NOT called for most installs. Only installs that came from a Measura
     *   link with a configured destination have anything to deliver, so treat
     *   "never called" as normal and keep a sensible default destination.
     * - Safe to register at any point. If the lookup finished before you called
     *   this, the payload is held and delivered as soon as you register; if it
     *   finishes afterwards, you are called then. A payload that arrives while
     *   your app is being killed survives to the next launch.
     * - Never called when tracking is disabled through [disableTracking].
     *
     * Pass null to clear a previously registered listener.
     *
     * Requires [init] to have been called first, like every other method here.
     */
    /**
     * Handle a Measura link that opened the app while it was already
     * installed. Call it from the Activity that receives the link, with the
     * intent's data:
     *
     * ```kotlin
     * intent?.data?.let { uri -> Measura.handleDeepLink(uri)?.let { path -> navigateTo(path) } }
     * ```
     *
     * Records the open against the click that brought the user back
     * (re_engagement, attributed by click id) and returns the link's in-app
     * path, or null when the URL carries none.
     */
    @JvmStatic
    fun handleDeepLink(uri: android.net.Uri): String? = requireInitialised().handleDeepLink(uri)

    @JvmStatic
    fun setDeferredDeepLinkListener(listener: MeasuraDeferredLinkListener?) {
        requireInitialised().setDeferredLinkListener(listener)
    }

    /**
     * Disable all tracking (e.g. user opted out of analytics).
     * All future events are silently dropped until re-enabled.
     */
    @JvmStatic
    fun disableTracking() {
        requireInitialised().setTrackingEnabled(false)
    }

    /** Re-enable tracking after disableTracking() was called. */
    @JvmStatic
    fun enableTracking() {
        requireInitialised().setTrackingEnabled(true)
    }

    /**
     * Force-flush the event queue immediately (e.g. before app goes to background).
     * Normally the SDK handles flushing automatically.
     */
    @JvmStatic
    fun flush() {
        requireInitialised().flush()
    }

    /** Returns true if SDK has been initialised. */
    @JvmStatic
    fun isInitialised(): Boolean = client != null

    /**
     * Flush and block until it finishes.
     *
     * Only for MeasuraFlushWorker, which runs on a WorkManager background thread
     * outside the app's lifetime. The public flush() returns immediately, which
     * would let the system reclaim the process mid-send.
     */
    /**
     * True once /resolve-key has returned and events can actually be signed.
     * Only for MeasuraFlushWorker, which must not give up on a cold start
     * merely because resolution is still in flight.
     */
    internal fun canSend(): Boolean = client?.canSend() == true

    /** The live client, for tests that assert on internal counters. */
    internal fun clientOrNull(): MeasuraClient? = client

    /** True when the offline queue is empty afterwards. */
    internal fun flushBlocking(): Boolean = requireInitialised().flushBlocking()

    private fun requireInitialised(): MeasuraClient {
        return client ?: error(
            "Measura SDK not initialised. Call Measura.init(context, apiKey) in Application.onCreate()"
        )
    }
}
