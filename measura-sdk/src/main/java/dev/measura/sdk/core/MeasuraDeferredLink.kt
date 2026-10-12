package dev.measura.sdk.core

/**
 * A deferred deep link: where the user was heading before they installed.
 *
 * ## What this is for
 *
 * A user taps a Measura link, does not have your app, installs it from Play,
 * and opens it. Without a deferred deep link they land on your home screen and
 * the intent of the campaign is lost. With one, you can send them to the exact
 * product, offer or screen the link pointed at.
 *
 * ## How the value gets here
 *
 * The Measura resolver records the click and puts ONE opaque token,
 * `measura_click_id`, into the Play Store referrer. That token survives the
 * install and the SDK recovers it on first launch through the Play Install
 * Referrer API. The SDK then exchanges the token for this payload over an
 * authenticated HTTPS call.
 *
 * Your campaign parameters are therefore never carried in the referrer itself.
 * That matters for two reasons: Play truncates long referrer strings, and a
 * referrer is readable and editable by anyone who has the link. The token is
 * the only thing exposed, and it is useless without your API key.
 *
 * ## Delivery guarantees
 *
 * - Delivered AT MOST ONCE per install. Once your listener has received it the
 *   SDK records that and will not deliver it again, on this launch or any
 *   later one.
 * - Delivered on the MAIN thread, so you can navigate straight from it.
 * - Not delivered at all for the great majority of installs, which do not come
 *   from a Measura link. Treat "never called" as the normal case and make sure
 *   your app has a sensible default destination.
 * - Never delivered when tracking is disabled via `Measura.disableTracking()`.
 *
 * @property path        Where the app should navigate. Either a path
 *                       ("/product/123") or a custom-scheme URI
 *                       ("myapp://product/123"), exactly as configured on the
 *                       link. Null when the link carried only parameters.
 * @property params      The link's deferred parameters. Values are delivered as
 *                       strings; the dashboard accepts strings, numbers and
 *                       booleans, and a number or boolean arrives here in its
 *                       string form ("3", "true").
 * @property clickId     The click this install was attributed to. Useful if you
 *                       want to correlate your own analytics with Measura's.
 * @property slug        The link's slug, e.g. the "promo" in
 *                       link.measura.dev/promo.
 * @property campaignId  The campaign attached to the link, when there is one.
 * @property channel     The channel the link was tagged with, e.g. "whatsapp".
 */
class MeasuraDeferredLink(
    @JvmField val path: String?,
    @JvmField val params: Map<String, String>,
    @JvmField val clickId: String,
    @JvmField val slug: String?,
    @JvmField val campaignId: String?,
    @JvmField val channel: String?,
) {
    /** Convenience for Java callers and for the common single-parameter case. */
    fun param(key: String): String? = params[key]

    override fun toString(): String =
        "MeasuraDeferredLink(path=$path, params=$params, clickId=$clickId, " +
            "slug=$slug, campaignId=$campaignId, channel=$channel)"
}
// DELIBERATELY NOT A `data class`, and @JvmField rather than properties.
//
// The published AAR is gated in CI against the size the marketing site
// advertises (SDK_SIZE_BUDGET_BYTES), and when this was added, against the
// 64 KB ceiling of the time, the module was 230 bytes under it. A data class generates componentN(), copy(), copy$default(),
// equals(), hashCode() and toString(); the consumer keep rule below pins all of
// them, and together they cost more than the whole feature. @JvmField publishes
// the values as fields instead of generating six getters.
//
// Nothing is lost that a deep-link payload needs: it is read once, on first
// launch, to decide where to navigate. It is never compared, copied or
// destructured. toString() is kept by hand because it is what an integrator
// will put in a log line while wiring this up.

/**
 * Receives the deferred deep link for this install, if there is one.
 *
 * Register it with [dev.measura.sdk.Measura.setDeferredDeepLinkListener]. A
 * `fun interface`, so Kotlin callers can pass a lambda and Java callers can
 * pass a lambda or an anonymous class.
 *
 * Called on the main thread, at most once per install.
 */
fun interface MeasuraDeferredLinkListener {
    fun onDeferredDeepLink(link: MeasuraDeferredLink)
}
