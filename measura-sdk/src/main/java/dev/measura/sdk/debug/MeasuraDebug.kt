package dev.measura.sdk.debug

import dev.measura.sdk.BuildConfig

/**
 * Opt-in glass-box diagnostics.
 *
 * WHY THIS EXISTS
 *
 * Everything interesting inside this SDK is `internal`: MeasuraClient,
 * IngestApiClient, ApiKeyResolver, Logger. That is correct, and it is why a
 * separate harness module cannot see how an event is canonicalised or signed.
 *
 * The wrong fix would be to widen those classes to public so a test app can
 * reach them, which permanently enlarges the surface every integrator sees in
 * order to serve a laboratory. This is the deliberate alternative: one small
 * public seam that reports what the SDK did, without exposing the machinery
 * that did it.
 *
 * COST WHEN UNUSED
 *
 * Zero, in a published build. `BuildConfig.MEASURA_DIAGNOSTICS` is false unless
 * a build asks for it with `-PmeasuraDiagnostics=true`, so R8 folds every guard
 * to a constant and removes this class and its call sites from the release AAR
 * outright.
 *
 * That gate is load-bearing, not tidiness. This comment previously claimed the
 * cost was "a single volatile null check", which was false: every call site
 * built a map of string literals BEFORE calling in, so the work happened
 * whether or not anyone was listening. Measured 2026-08-23, the release AAR had
 * grown to 72,107 bytes gzipped against a 65,536 byte budget, and the site
 * advertises that figure.
 *
 * SECRETS
 *
 * Records carry the canonical payload and the resulting HMAC. The canonical
 * payload is exactly what goes on the wire, and the HMAC is a digest, so
 * neither reveals the signing key. The signing key, the API key and the
 * Authorization header are never passed to emit(), and [scrub] is a second
 * line of defence in case a future call site forgets: any field whose name
 * looks like a credential has its value replaced before a listener ever sees
 * it. See [SENSITIVE_KEY] and the allowlist beside it.
 */
object MeasuraDebug {

    /** Where in the pipeline a record was produced. */
    enum class Stage {
        /** /resolve-key answered: identity and server-controlled config. */
        RESOLVE,

        /** An event was accepted into the queue. */
        EVENT_QUEUED,

        /** The canonical byte form the signature is computed over. */
        CANONICALIZE,

        /** The HMAC-SHA256 result for one event. */
        SIGN,

        /** An HTTP request is about to leave the device. */
        REQUEST,

        /** An HTTP response came back. */
        RESPONSE,

        /** Something failed. */
        ERROR,
    }

    /**
     * One observation. Deliberately flat strings rather than a typed payload
     * per stage: the harness renders these directly, and a flat map cannot
     * drift out of sync with a display that was written for an older shape.
     */
    class Record(
        val stage: Stage,
        val atMs: Long,
        val summary: String,
        val fields: Map<String, String>,
    )

    fun interface Listener {
        fun onRecord(record: Record)
    }

    @Volatile
    private var listener: Listener? = null

    /** Attach a listener. Call from a debug build only. */
    @JvmStatic
    fun attach(listener: Listener) {
        this.listener = listener
    }

    /** Detach and stop recording. */
    @JvmStatic
    fun detach() {
        this.listener = null
    }

    /**
     * Checks a compile-time constant FIRST, on purpose.
     *
     * `BuildConfig.MEASURA_DIAGNOSTICS` is false in a published build, so R8
     * folds this to `false`, every `if (MeasuraDebug.isAttached) { ... }` block
     * becomes dead code, and the whole seam plus its call sites is removed from
     * the release AAR rather than shipped inert.
     *
     * This is not a micro-optimisation. Measured on 2026-08-23: with the guards
     * absent the release AAR was 72,107 bytes gzipped against a 65,536 budget,
     * because every emit site built a map of string literals before calling in.
     * An earlier comment here claimed unused diagnostics cost "a volatile null
     * check". That was wrong: the maps were constructed eagerly whether or not
     * anyone was listening.
     */
    @JvmStatic
    val isAttached: Boolean
        get() = BuildConfig.MEASURA_DIAGNOSTICS && listener != null

    // Matches names that plausibly carry a credential. Broad on purpose: a
    // false positive costs a redacted line in a debug view, a false negative
    // prints a signing key into a screen that exists to be recorded on video.
    private val SENSITIVE_KEY = Regex(
        "secret|password|passwd|authorization|bearer|credential|(^|_)key($|_)|api_?key|token",
        RegexOption.IGNORE_CASE,
    )

    // Values that LOOK sensitive by name but are safe and are the entire point
    // of a glass-box view. A signature is a digest of the payload, not the key.
    private val ALWAYS_ALLOWED = setOf("signature", "signature_alg", "key_prefix", "public_key_id")

    private fun scrub(fields: Map<String, String>): Map<String, String> =
        fields.mapValues { (k, v) ->
            when {
                k in ALWAYS_ALLOWED -> v
                SENSITIVE_KEY.containsMatchIn(k) -> "[redacted]"
                else -> v
            }
        }

    /**
     * Record an observation. Never throws into the SDK: a listener that fails
     * must not be able to break ingestion, because the listener exists only to
     * watch it.
     */
    internal fun emit(
        stage: Stage,
        summary: String,
        fields: Map<String, String> = emptyMap(),
    ) {
        if (!BuildConfig.MEASURA_DIAGNOSTICS) return
        val l = listener ?: return
        try {
            l.onRecord(Record(stage, System.currentTimeMillis(), summary, scrub(fields)))
        } catch (_: Throwable) {
            // Intentionally swallowed.
        }
    }
}
