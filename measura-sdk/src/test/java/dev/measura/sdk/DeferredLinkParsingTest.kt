package dev.measura.sdk

import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.network.DeferredLinkFetcher
import dev.measura.sdk.utils.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK half of the deferred deep link contract.
 *
 * This is the step that did not exist before 2026-09-04: the resolver emitted
 * `measura_deferred` into the Play referrer and the SDK never read it, so the
 * feature had a producer and a transport but no consumer. The transport is now
 * a click id exchanged at /deferred-link, and this is the code that turns the
 * answer into something an integrator's app can route on.
 *
 * Everything here drives DeferredLinkFetcher.parse directly. The HTTP half
 * needs a live server and is exercised against staging; the parsing half is
 * where every malformed,
 * hostile or unexpected input lands, and it must never throw into an
 * integrator's first launch.
 *
 * LogLevel.NONE is deliberate: it keeps Logger away from android.util.Log,
 * which is an unmocked stub in a JVM unit test.
 */
class DeferredLinkParsingTest {

    private val log = Logger(MeasuraConfig.LogLevel.NONE)
    private val clickId = "11111111-2222-3333-4444-555555555555"

    private fun parse(body: String) = DeferredLinkFetcher.parse(body, clickId, log)

    @Test
    fun `a complete payload parses into a routable link`() {
        val link = parse(
            """
            {"version":1,"deferred":{
              "click_id":"$clickId","slug":"promo","deep_link_path":"/product/123",
              "params":{"source":"whatsapp","tier":3,"vip":true},
              "campaign_id":"c-1","channel":"whatsapp","clicked_at":"2026-09-04T00:00:00Z"}}
            """.trimIndent()
        )
        requireNotNull(link)
        assertEquals("/product/123", link.path)
        assertEquals(clickId, link.clickId)
        assertEquals("promo", link.slug)
        assertEquals("c-1", link.campaignId)
        assertEquals("whatsapp", link.channel)
        assertEquals("whatsapp", link.param("source"))
    }

    @Test
    fun `numbers and booleans are coerced to strings rather than dropped`() {
        // The stored contract allows string, number and boolean. Dropping the
        // non-strings would silently lose campaign parameters.
        val link = parse("""{"version":1,"deferred":{"params":{"tier":3,"vip":true,"rate":1.5}}}""")
        requireNotNull(link)
        assertEquals("3", link.param("tier"))
        assertEquals("true", link.param("vip"))
        assertEquals("1.5", link.param("rate"))
    }

    @Test
    fun `no deferred link is a normal answer, not an error`() {
        // This is what the great majority of installs get.
        assertNull(parse("""{"version":1,"deferred":null}"""))
        assertNull(parse("""{"version":1}"""))
    }

    @Test
    fun `a payload with neither a path nor parameters is nothing to deliver`() {
        assertNull(parse("""{"version":1,"deferred":{"params":{},"deep_link_path":null}}"""))
        assertNull(parse("""{"version":1,"deferred":{"slug":"promo"}}"""))
    }

    @Test
    fun `a path alone is enough to deliver`() {
        val link = parse("""{"version":1,"deferred":{"deep_link_path":"myapp://open"}}""")
        requireNotNull(link)
        assertEquals("myapp://open", link.path)
        assertTrue(link.params.isEmpty())
    }

    @Test
    fun `parameters alone are enough to deliver`() {
        val link = parse("""{"version":1,"deferred":{"params":{"source":"qr"}}}""")
        requireNotNull(link)
        assertNull(link.path)
        assertEquals("qr", link.param("source"))
    }

    @Test
    fun `an unknown contract version is refused rather than guessed at`() {
        // Guessing at a shape we do not know is how an app routes somewhere
        // the customer never configured.
        assertNull(parse("""{"version":2,"deferred":{"deep_link_path":"/x"}}"""))
        assertNull(parse("""{"deferred":{"deep_link_path":"/x"}}"""))
    }

    @Test
    fun `malformed and hostile bodies never throw`() {
        // Each of these must return null, not propagate into the first launch.
        val bodies = listOf(
            "", "   ", "not json at all", "{", "[]", "null", "true", "42",
            """{"version":1,"deferred":"a string not an object"}""",
            """{"version":1,"deferred":{"params":"not an object"}}""",
            """{"version":1,"deferred":{"deep_link_path":""}}""",
        )
        for (body in bodies) {
            assertNull("expected null for: $body", parse(body))
        }
    }

    @Test
    fun `the requested click id stands in when the server omits it`() {
        val link = parse("""{"version":1,"deferred":{"deep_link_path":"/x"}}""")
        requireNotNull(link)
        assertEquals(clickId, link.clickId)
    }

    @Test
    fun `explicit nulls are treated as absent, not as the string null`() {
        val link = parse(
            """{"version":1,"deferred":{"deep_link_path":"/x","slug":null,"campaign_id":null,"channel":null}}"""
        )
        requireNotNull(link)
        assertNull(link.slug)
        assertNull(link.campaignId)
        assertNull(link.channel)
    }
}
