package dev.measura.harness

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
// Both request types are NESTED inside StandardIntegrityManager rather than
// top-level in the package, which is why the obvious import does not resolve.
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import dev.measura.sdk.Measura
import dev.measura.sdk.core.EventType
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.debug.MeasuraDebug
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The lab bench.
 *
 * Every control below calls the real SDK through its real public API. Nothing
 * here fakes an event, synthesises a response or writes to the database
 * directly. If a button appears to work, the pipeline worked.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var envBanner: TextView

    private var glassBoxOpen = true
    private var lastDeepLink: String? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ---------------------------------------------------------------------
    // Configuration, and the refusal that protects production
    // ---------------------------------------------------------------------
    private val endpoint: String get() = BuildConfig.MEASURA_ENDPOINT
    private val apiKey: String get() = BuildConfig.MEASURA_API_KEY
    private val envLabel: String get() = BuildConfig.MEASURA_ENV_LABEL

    private val isConfigured: Boolean
        get() = endpoint.isNotBlank() && apiKey.isNotBlank()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleDeepLink(intent)
        DebugLog.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        render()
    }

    override fun onDestroy() {
        DebugLog.onChange = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        envBanner = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 16f
        }
        root.addView(envBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }

        column.addView(sectionLabel("STATE"))
        statusView = mono(11f, Color.parseColor("#C8D3E0"))
        column.addView(statusView)

        column.addView(sectionLabel("LIFECYCLE"))
        column.addView(row(
            button("Initialize SDK") { onInitialize() },
            button("Flush now") { safely { Measura.flush(); toast("flush() called") } },
        ))
        column.addView(row(
            button("Reset install state") { onResetInstallState() },
            button("Clear SDK data") { onClearSdkData() },
        ))

        column.addView(sectionLabel("PLAY INTEGRITY"))
        column.addView(row(
            button("Request + send integrity token") { onRequestIntegrityToken() },
        ))

        column.addView(sectionLabel("EVENTS (real SDK calls)"))
        column.addView(row(
            button("Session start") { track(EventType.SESSION_START.value, emptyMap()) },
            button("App open") { track(EventType.OPEN.value, emptyMap()) },
        ))
        column.addView(row(
            button("Login") { identify("user_${short()}", mapOf("method" to "password")) },
            // `custom`, not "registration". event_type is a server-side enum
            // and anything outside its nine values is rejected with
            // VALIDATION_ERROR. The real name rides in the properties.
            button("Registration") {
                track(
                    EventType.CUSTOM.value,
                    mapOf("custom_event_name" to "registration", "plan" to "free"),
                )
            },
        ))
        column.addView(row(
            button("Purchase") {
                track(
                    EventType.PURCHASE.value,
                    mapOf("revenue" to 49.99, "currency" to "USD", "sku" to "pro_monthly"),
                )
            },
            button("Custom event") {
                track(
                    EventType.CUSTOM.value,
                    mapOf(
                        "custom_event_name" to "custom_demo",
                        "source" to "harness",
                        "nonce" to short(),
                    ),
                )
            },
        ))
        column.addView(row(
            button("Re-engagement") { track(EventType.RE_ENGAGEMENT.value, emptyMap()) },
            button("Simulate deep link") { onSimulateDeepLink() },
        ))

        column.addView(sectionLabel("GLASS BOX"))
        column.addView(row(
            button("Show / hide") { glassBoxOpen = !glassBoxOpen; render() },
            button("Clear log") { DebugLog.clear() },
        ))

        logView = mono(10f, Color.parseColor("#9FE8B0"))
        logView.setTextIsSelectable(true)
        column.addView(logView)

        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(16), 0, dp(6))
        setTextColor(Color.parseColor("#5B6B7F"))
        textSize = 11f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.14f
    }

    private fun mono(size: Float, colour: Int) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = size
        setTextColor(colour)
        setLineSpacing(0f, 1.15f)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    /**
     * The one refusal that matters.
     *
     * MeasuraConfig compiles in the production ingest host as its default, so
     * calling init without an explicit endpoint would resolve against
     * production and post test traffic into the real database. The harness
     * will not start without being told where to go.
     */
    private fun onInitialize() {
        if (!isConfigured) {
            toast("Refusing to initialise: endpoint or API key not configured")
            render()
            return
        }
        if (Measura.isInitialised()) {
            toast("Already initialised. Use Clear SDK data, then restart the app.")
            return
        }
        safely {
            Measura.init(
                this,
                apiKey,
                MeasuraConfig(
                    // Explicit, always. Never inherit the compiled default.
                    ingestEndpoint = endpoint,
                    // Stands in for the Play Install Referrer, which a
                    // sideloaded build can never obtain. Blank unless the build
                    // supplied one, and it never overrides a real referrer.
                    debugInstallReferrer = BuildConfig.MEASURA_SEED_REFERRER.ifBlank { null },
                    logLevel = MeasuraConfig.LogLevel.VERBOSE,
                    // A laboratory should not sit on events waiting for a
                    // batch to fill or for Wi-Fi to appear.
                    batchSize = 1,
                    flushIntervalMs = 5_000L,
                    wifiOnlyMode = false,
                    suppressOnLowBattery = false,
                ),
            )
            toast("Measura.init called")
        }
    }

    private fun track(type: String, props: Map<String, Any>) {
        if (!requireInit()) return
        safely {
            Measura.trackEvent(type, props)
            toast("trackEvent($type)")
        }
    }

    private fun identify(userId: String, traits: Map<String, Any>) {
        if (!requireInit()) return
        safely {
            Measura.identify(userId, traits)
            toast("identify($userId)")
        }
    }

    /**
     * Clears the SDK's own preferences so the next launch re-runs its
     * one-time install path, including the Install Referrer read.
     *
     * This is the honest way to re-test an install: it makes the SDK do the
     * real thing again, rather than the harness emitting an event that merely
     * looks like an install.
     */
    /**
     * The whole Play Integrity contract, exercised the way an integrator must.
     *
     * setCloudProjectNumber(Measura.CLOUD_PROJECT_NUMBER) is the line that
     * makes verification possible at all. Google mints the token against the
     * Cloud project named here and decodes it only for that project, so a token
     * minted against the integrator's own project reaches Measura unreadable
     * and the verdict is recorded `unavailable`. Google documents this for SDKs
     * by name: "Apps exclusively distributed outside of Google Play and SDKs
     * also have to specify their Google Cloud project number through the
     * setCloudProjectNumber() method."
     *
     * Nothing here decodes or inspects the token. It is opaque on purpose: a
     * verdict computed on the device is worth exactly as much as the honesty of
     * whoever is running the device.
     *
     * Expect this to FAIL on an ordinary emulator. Play Integrity requires
     * Google Play Services and a Play-certified image, and prepareIntegrityToken
     * answers with an IntegrityServiceException there. That is a real result
     * rather than a broken harness, and the message is surfaced verbatim so the
     * difference is visible.
     */
    private fun onRequestIntegrityToken() {
        if (!requireInit()) return
        toast("Requesting integrity token...")

        // Task callbacks rather than coroutines: await() would mean adding
        // kotlinx-coroutines-play-services purely for two calls, and the
        // listener form is what Google's own samples use.
        try {
            val manager = IntegrityManagerFactory.createStandard(applicationContext)

            manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(Measura.CLOUD_PROJECT_NUMBER)
                    .build()
            ).addOnSuccessListener { provider ->
                // A request hash binds the verdict to this specific request, so
                // a token captured from one request cannot be replayed against
                // another. Any value the server can correlate works.
                provider.request(
                    StandardIntegrityTokenRequest.builder()
                        .setRequestHash("measura-harness-" + short())
                        .build()
                ).addOnSuccessListener { response ->
                    val token = response.token()
                    Measura.setIntegrityToken(token)
                    toast("Token sent (${token.length} chars). Decoded server side.")
                }.addOnFailureListener { e ->
                    toast("Token request failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }.addOnFailureListener { e ->
                // Surfaced verbatim rather than swallowed. On a non-Play image
                // this exception IS the finding, and a harness that hid it would
                // look identical to one where the contract itself is broken.
                toast("prepareIntegrityToken failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (e: Exception) {
            toast("Integrity unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun onResetInstallState() {
        getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE)
            .edit().clear().apply()
        toast("SDK prefs cleared. Force-stop and relaunch to re-run install.")
    }

    private fun onClearSdkData() {
        getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE).edit().clear().apply()
        deleteDatabase("measura_events.db")
        DebugLog.clear()
        toast("SDK prefs and offline queue cleared. Restart to re-init.")
    }

    /**
     * Fires a VIEW intent at ourselves with a synthetic click ID.
     *
     * SIMULATED, and labelled so on screen. A private-scheme intent is not a
     * Google Play install carrying a genuine referrer, and pretending
     * otherwise would misrepresent the strongest attribution signal the engine
     * has. See the note in the STATE panel.
     */
    private fun onSimulateDeepLink() {
        val clickId = UUID.randomUUID().toString()
        val uri = Uri.parse("measura-harness://open?measura_click_id=$clickId&campaign=harness_demo")
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName))
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        lastDeepLink = data.toString()

        if (!Measura.isInitialised()) return
        val clickId = data.getQueryParameter("measura_click_id")
        val props = buildMap<String, Any> {
            put("custom_event_name", "deep_link_opened")
            put("url", data.toString())
            // Labelled at the point of capture, not just in the UI, so a row in
            // the database cannot later be mistaken for a genuine Play referrer.
            put("signal_origin", "simulated_harness_intent")
            clickId?.let { put("click_id", it) }
            data.getQueryParameter("campaign")?.let { put("campaign", it) }
        }
        safely { Measura.trackEvent(EventType.CUSTOM.value, props) }
    }

    private fun requireInit(): Boolean {
        if (!Measura.isInitialised()) {
            toast("SDK not initialised. Press Initialize SDK first.")
            return false
        }
        return true
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            toast("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun short() = UUID.randomUUID().toString().take(8)

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------
    private fun render() {
        renderBanner()
        renderStatus()
        renderLog()
    }

    private fun renderBanner() {
        if (!isConfigured) {
            envBanner.text = "MEASURA ENVIRONMENT: UNCONFIGURED"
            envBanner.setBackgroundColor(Color.parseColor("#7F1D1D"))
            envBanner.setTextColor(Color.WHITE)
            return
        }
        val isProdish = envLabel.equals("PRODUCTION", ignoreCase = true)
        envBanner.text = "MEASURA ENVIRONMENT: ${envLabel.uppercase(Locale.US)}"
        envBanner.setBackgroundColor(
            if (isProdish) Color.parseColor("#7F1D1D") else Color.parseColor("#14532D")
        )
        envBanner.setTextColor(Color.WHITE)
    }

    private fun renderStatus() {
        val resolve = DebugLog.latestOf(MeasuraDebug.Stage.RESOLVE)
        val canonical = DebugLog.latestOf(MeasuraDebug.Stage.CANONICALIZE)
        val response = DebugLog.latestOf(MeasuraDebug.Stage.RESPONSE)

        // Device and session identity are read out of the canonical payload,
        // which is what actually went on the wire. Nothing is inferred.
        val wire = canonical?.fields?.get("canonical")?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        val effective = resolve?.fields?.get("endpoint_effective")
        val moved = resolve?.fields?.get("endpoint_moved_by_server") == "true"

        val sb = StringBuilder()
        fun line(k: String, v: String?) = sb.append(k.padEnd(20)).append(v ?: "-").append('\n')

        line("SDK status", if (Measura.isInitialised()) "INITIALISED" else "not initialised")
        line("Configured", if (isConfigured) "yes" else "NO (see below)")
        line("Endpoint (build)", endpoint.ifBlank { "NOT SET" })
        line("Endpoint (live)", effective ?: "(awaiting /resolve-key)")
        if (moved) line("!! REDIRECTED", "server moved the SDK off the build endpoint")
        line("Backend", when {
            response?.fields?.get("status") != null -> "HTTP ${response.fields["status"]}"
            resolve != null -> "resolve-key OK"
            else -> "no contact yet"
        })
        line("Customer", resolve?.fields?.get("customer_id"))
        line("App", resolve?.fields?.get("app_id"))
        line("Device", wire?.optString("device_id", null))
        line("Session", wire?.optString("session_id", null))
        line("Last event", wire?.optString("event_type", null))
        line("Last deep link", lastDeepLink)
        line("Records", DebugLog.snapshot().size.toString())

        sb.append('\n')
        sb.append("Install Referrer: REAL only on a Google Play system image after\n")
        sb.append("a genuine Play install. On this build it is expected to be absent,\n")
        sb.append("and deep links fired from this app are SIMULATED test signals.\n")

        if (!isConfigured) {
            sb.append('\n')
            sb.append("Not configured. Build with:\n")
            sb.append("  -PmeasuraEndpoint=https://<ref>.supabase.co/functions/v1/ingest\n")
            sb.append("  -PmeasuraApiKey=msr_...\n")
            sb.append("  -PmeasuraEnvLabel=STAGING\n")
            sb.append("Init is refused until then, so the SDK cannot fall back to\n")
            sb.append("its compiled production host.\n")
        }

        statusView.text = sb.toString()
    }

    private fun renderLog() {
        if (!glassBoxOpen) {
            logView.text = "(glass box hidden)"
            return
        }
        val sb = StringBuilder()
        DebugLog.snapshot().asReversed().forEach { r ->
            sb.append(timeFmt.format(Date(r.atMs)))
                .append("  ").append(r.stage.name).append('\n')
            sb.append("  ").append(r.summary).append('\n')
            r.fields.forEach { (k, v) ->
                sb.append("    ").append(k).append(" = ")
                    .append(if (v.length > 400) v.take(400) + "..." else v)
                    .append('\n')
            }
            sb.append('\n')
        }
        logView.text = if (sb.isEmpty()) "(no records yet)" else sb.toString()
    }
}
