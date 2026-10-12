package dev.measura.sdk

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import dev.measura.sdk.core.MeasuraClient
import dev.measura.sdk.core.MeasuraConfig
import dev.measura.sdk.core.MeasuraDeferredLink
import dev.measura.sdk.core.MeasuraDeferredLinkListener
import dev.measura.sdk.storage.PersistentEventStore
import dev.measura.sdk.utils.BatteryMonitor
import dev.measura.sdk.work.MeasuraFlushWorker
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every client behaviour that depends on what the server answers, against
 * [FakeMeasuraServer] on 127.0.0.1: the retry ladder and its exhaustion, a 4xx
 * that must not be retried, the kill switch and sampling, consent, low
 * battery, both flush triggers, the gzip fallback, identify and revenue
 * promotion, the background worker, deferred deep links in either order, and
 * direct deep links. Needs no credentials, so CI proves all of it on API 21
 * and 30 on every push.
 */
@RunWith(AndroidJUnit4::class)
class SdkBehaviourTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var server: FakeMeasuraServer
    private val store get() = PersistentEventStore(context, 10_000)
    private val prefs get() = context.getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        server = FakeMeasuraServer()
        store.clearAll()
        // A clean install that has already sent its install event, so start()
        // queues nothing but session_start.
        prefs.edit().clear()
            .putBoolean("install_tracked", true)
            .putBoolean("deferred_resolved", true)
            .commit()
    }

    @After
    fun tearDown() {
        server.close()
        shell("dumpsys battery reset")
        prefs.edit().clear().commit()
        store.clearAll()
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
            BufferedReader(InputStreamReader(it)).readText()
        }
    }

    private fun waitFor(timeoutMs: Long, what: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (what()) return true
            Thread.sleep(100)
        }
        return what()
    }

    private fun config(
        batchSize: Int = 50,
        flushIntervalMs: Long = 3_600_000L,
        maxRetryAttempts: Int = 5,
        suppressOnLowBattery: Boolean = false,
        collectAdvertisingId: Boolean = false,
    ) = MeasuraConfig(
        batchSize = batchSize,
        flushIntervalMs = flushIntervalMs,
        maxRetryAttempts = maxRetryAttempts,
        wifiOnlyMode = false,
        suppressOnLowBattery = suppressOnLowBattery,
        lowBatteryThreshold = 15,
        collectAdvertisingId = collectAdvertisingId,
        ingestEndpoint = server.ingestUrl,
        logLevel = MeasuraConfig.LogLevel.DEBUG,
    )

    /** A started client whose secret is resolved and whose session_start is already sent. */
    private fun ready(cfg: MeasuraConfig = config(), beforeStart: (MeasuraClient) -> Unit = {}): MeasuraClient {
        val c = MeasuraClient(context, "mk_test_fake", cfg)
        beforeStart(c)
        c.start()
        assertTrue("/resolve-key answered", waitFor(10_000) { c.canSend() })
        Thread.sleep(300)
        c.flushBlocking()
        server.requests.clear()
        return c
    }

    /** trackEvent enqueues on a background coroutine; give it a moment to land. */
    private fun track(c: MeasuraClient, name: String): Boolean {
        val accepted = c.trackEvent("custom", mapOf("custom_event_name" to name))
        Thread.sleep(300)
        return accepted
    }

    private fun persisted(name: String) = store.loadAll().count { it.toString().contains(name) }

    // --- advertising ID (GAID) ----------------------------------------------

    private fun sentEvents() = server.requests.filter { it.path.endsWith("/ingest") }.flatMap { it.events() }

    @Test
    fun advertisingIdIsNeverSentWhenCollectionIsOff() {
        val c = ready(config(collectAdvertisingId = false))
        Thread.sleep(2_000) // longer than a Play Services lookup takes
        track(c, "no_gaid")
        c.flushBlocking()
        val events = sentEvents()
        assertTrue("events were sent", events.isNotEmpty())
        assertTrue("no event carries gaid", events.none { it.has("gaid") })
    }

    @Test
    fun advertisingIdIsSentByDefaultWherePlayServicesProvidesOne() {
        val hasPlayServices = runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0) }.isSuccess
        org.junit.Assume.assumeTrue("needs a Google Play image", hasPlayServices)
        assertTrue("collection defaults to on", MeasuraConfig().collectAdvertisingId)
        val c = ready(config(collectAdvertisingId = true))
        assertTrue("advertising id resolved", waitFor(10_000) {
            track(c, "probe"); c.flushBlocking(); sentEvents().any { it.optString("gaid").isNotEmpty() }
        })
        val gaid = sentEvents().first { it.optString("gaid").isNotEmpty() }.getString("gaid")
        assertTrue("a real UUID, not the zero placeholder", Regex("[0-9a-f-]{36}").matches(gaid) && gaid != "00000000-0000-0000-0000-000000000000")
    }

    // --- retry ladder -------------------------------------------------------

    @Test
    fun aFailedBatchIsRetriedOnTheOneTwoFourSecondLadderThenDelivered() {
        val c = ready()
        server.ingestStatuses.addAll(listOf(500, 500, 500))
        track(c, "ladder")

        c.flushBlocking()

        val posts = server.postsWith("ladder")
        assertEquals("three failures and one success", 4, posts.size)
        val gaps = posts.zipWithNext { a, b -> b.atMs - a.atMs }
        listOf(1_000L, 2_000L, 4_000L).forEachIndexed { i, expected ->
            assertTrue("gap $i was ${gaps[i]}ms, expected ~${expected}ms", gaps[i] in (expected - 100)..(expected + 1_500))
        }
        val ids = posts.map { p -> p.events().single().optString("event_id") }.toSet()
        assertEquals("every retry re-sends the same event, not a copy", 1, ids.size)
        assertEquals(1, server.accepted("ladder").size)
        assertEquals("nothing left queued", 0, persisted("ladder"))
    }

    @Test
    fun anExhaustedLadderPersistsTheBatchAndTheNextFlushDeliversIt() {
        val c = ready(config(maxRetryAttempts = 3))
        server.ingestStatuses.addAll(listOf(503, 503, 503))
        track(c, "exhaust")

        c.flushBlocking()
        assertEquals("exactly maxRetryAttempts sends", 3, server.postsWith("exhaust").size)
        assertEquals("kept on disk, not dropped", 1, persisted("exhaust"))

        assertTrue("the backlog drains once the server recovers", c.flushBlocking())
        assertEquals(1, server.accepted("exhaust").size)
        assertEquals(0, persisted("exhaust"))
    }

    @Test
    fun aClientErrorIsNotRetriedButTheEventsAreKept() {
        val c = ready()
        server.ingestStatuses.add(422)
        track(c, "refused")

        c.flushBlocking()
        Thread.sleep(2_500)

        assertEquals("a 422 is sent once, no ladder", 1, server.postsWith("refused").size)
        assertEquals("the events are persisted, not lost", 1, persisted("refused"))
    }

    @Test
    fun aRateLimitIsRetried() {
        val c = ready()
        server.ingestStatuses.add(429)
        track(c, "limited")

        c.flushBlocking()

        assertEquals("429 then 200", 2, server.postsWith("limited").size)
        assertEquals(1, server.accepted("limited").size)
    }

    // --- server kill switch, sampling, consent ------------------------------

    @Test
    fun theServerKillSwitchDropsEvents() {
        server.remoteConfig = JSONObject().put("tracking_enabled", false)
        val c = ready()

        assertFalse("trackEvent reports the drop", track(c, "killed"))
        c.flushBlocking()
        assertEquals(0, server.postsWith("killed").size)
        assertEquals(0, persisted("killed"))
    }

    @Test
    fun samplingZeroDropsEverythingAndSamplingOneKeepsEverything() {
        server.remoteConfig = JSONObject().put("sampling_rate", 0.0)
        val none = ready()
        repeat(5) { assertFalse(track(none, "sampled0")) }
        none.flushBlocking()
        assertEquals(0, server.postsWith("sampled0").size)

        server.remoteConfig = JSONObject().put("sampling_rate", 1.0)
        val all = ready()
        repeat(5) { assertTrue(track(all, "sampled1")) }
        all.flushBlocking()
        assertEquals(5, server.accepted("sampled1").size)
    }

    @Test
    fun aUserOptOutDropsEventsAndDeepLinksAndOptingBackInResumes() {
        val c = ready()
        c.setTrackingEnabled(false)
        assertFalse(track(c, "optedOut"))
        assertEquals("/promo", c.handleDeepLink(Uri.parse("myapp://x?measura_click_id=clk_off&measura_path=/promo")))
        Thread.sleep(500)
        c.flushBlocking()
        assertEquals(0, server.postsWith("optedOut").size)
        assertTrue(
            "no re_engagement while opted out",
            server.ingestPosts().none { p -> p.events().any { it.optString("click_id") == "clk_off" } },
        )

        c.setTrackingEnabled(true)
        assertTrue(track(c, "optedIn"))
        c.flushBlocking()
        assertEquals(1, server.accepted("optedIn").size)
    }

    @Test
    fun optingOutDiscardsEventsQueuedBeforeIt() {
        val c = ready()
        assertTrue(track(c, "queuedThenOptOut"))
        c.setTrackingEnabled(false)
        Thread.sleep(500)
        c.setTrackingEnabled(true)
        c.flushBlocking()
        assertEquals("an event recorded before the opt-out is never sent", 0, server.postsWith("queuedThenOptOut").size)
        assertEquals("and nothing is left on disk", 0, persisted("queuedThenOptOut"))
    }

    // --- low battery --------------------------------------------------------

    @Test
    fun onLowBatteryEventsArePersistedNotSentThenDrainedLater() {
        shell("dumpsys battery unplug")
        shell("dumpsys battery set level 5")
        val low = waitFor(5_000) { BatteryMonitor(context).isBelowThreshold(15) }
        assertTrue("the device reports 5% (BatteryMonitor saw ${BatteryMonitor(context).getBatteryPercent()}%)", low)

        val c = ready(config(suppressOnLowBattery = true))
        assertTrue("accepted for later, not dropped", track(c, "lowBattery"))
        Thread.sleep(500)
        assertEquals("not sent on low battery", 0, server.postsWith("lowBattery").size)
        assertEquals("persisted", 1, persisted("lowBattery"))

        shell("dumpsys battery reset")
        assertTrue(c.flushBlocking())
        assertEquals(1, server.accepted("lowBattery").size)
    }

    // --- flush triggers -----------------------------------------------------

    @Test
    fun reachingTheBatchSizeFlushesWithoutBeingAsked() {
        val c = ready(config(batchSize = 5))
        repeat(4) { track(c, "batch") }
        Thread.sleep(1_000)
        assertEquals("four events wait", 0, server.postsWith("batch").size)

        track(c, "batch")
        assertTrue("the fifth sends the batch", waitFor(5_000) { server.accepted("batch").size == 5 })
        assertEquals("as one request", 1, server.postsWith("batch").size)
    }

    @Test
    fun theFlushIntervalSendsWithoutBeingAsked() {
        val c = ready(config(flushIntervalMs = 2_000L))
        track(c, "interval")
        assertTrue("sent by the timer", waitFor(6_000) { server.accepted("interval").size == 1 })
    }

    // --- gzip ---------------------------------------------------------------

    @Test
    fun bodiesAreGzippedAndAServerThatRefusesGzipGetsPlainJsonFromThenOn() {
        val c = ready()
        track(c, "gz1")
        c.flushBlocking()
        val first = server.postsWith("gz1").single()
        assertTrue("gzip by default", first.gzip)
        assertEquals("android/${MeasuraClient.SDK_VERSION}", first.headers["x-measura-sdk-version"])
        assertTrue("every event is signed", first.events().all { it.optString("signature").length == 64 })

        server.rejectGzip = true
        track(c, "gz2")
        c.flushBlocking()
        assertEquals("gzip refused, then plain", listOf(true, false), server.postsWith("gz2").map { it.gzip })
        assertEquals(1, server.accepted("gz2").size)

        track(c, "gz3")
        c.flushBlocking()
        assertEquals("gzip stays off for the session", listOf(false), server.postsWith("gz3").map { it.gzip })
    }

    // --- payload ------------------------------------------------------------

    @Test
    fun identifyStampsTheUserAndRevenueIsPromotedAsANumber() {
        val c = ready()
        c.identify("user-42", mapOf("plan" to "pro"))
        c.trackEvent("purchase", mapOf("revenue" to "49.99", "currency" to "NGN", "custom_event_name" to "buyString"))
        c.trackEvent("purchase", mapOf("revenue" to 12, "custom_event_name" to "buyInt"))
        c.trackEvent("purchase", mapOf("revenue" to "lots", "custom_event_name" to "buyJunk"))
        Thread.sleep(500)
        c.flushBlocking()

        val identify = server.accepted("identify").single()
        assertEquals("custom", identify.getString("event_type"))
        assertEquals("user-42", identify.getJSONObject("event_properties").getString("user_id"))

        val s = server.accepted("buyString").single()
        assertEquals("user-42", s.getString("user_id"))
        assertTrue("a numeric String becomes a JSON number", s.get("revenue") is Number)
        assertEquals(49.99, s.getDouble("revenue"), 0.0001)
        assertEquals("NGN", s.getString("currency"))

        assertEquals(12.0, server.accepted("buyInt").single().getDouble("revenue"), 0.0001)

        val junk = server.accepted("buyJunk").single()
        assertFalse("a non-number is not promoted", junk.has("revenue"))
        assertEquals("lots", junk.getJSONObject("event_properties").getString("revenue"))

        assertTrue(
            "the customer and app ids from /resolve-key are stamped",
            listOf(identify, s).all {
                it.optString("customer_id") == FakeMeasuraServer.CUSTOMER_ID &&
                    it.optString("app_id") == FakeMeasuraServer.APP_ID
            },
        )
    }

    @Test
    fun pushAndIntegrityTokensAreSentOnceAndARepeatIsNotResent() {
        val c = ready()
        repeat(2) {
            c.setPushToken("fcm-token-1")
            c.setIntegrityToken("integrity-token-1")
            Thread.sleep(300)
            c.flushBlocking()
        }

        val push = server.accepted("push_token")
        assertEquals("sent once, the repeat deduplicated", 1, push.size)
        assertEquals("fcm-token-1", push.single().getJSONObject("event_properties").getString("push_token"))
        val integrity = server.accepted("integrity_token")
        assertEquals(1, integrity.size)
        assertEquals("integrity-token-1", integrity.single().getJSONObject("event_properties").getString("integrity_token"))

        c.setPushToken("fcm-token-2")
        Thread.sleep(300)
        c.flushBlocking()
        assertEquals("a new token is sent", 2, server.accepted("push_token").size)
    }

    @Test
    fun resolveSendsTheApiKeyHeader() {
        val c = MeasuraClient(context, "mk_test_header", config())
        c.start()
        assertTrue(waitFor(10_000) { c.canSend() })
        val resolve = server.requests.first { it.path.endsWith("/resolve-key") }
        assertEquals("mk_test_header", resolve.headers["x-measura-api-key"])
    }

    // --- background worker --------------------------------------------------

    /** Runs [block] with [c] as the Measura singleton, then restores it, so other classes still see an uninitialised SDK. */
    private fun <T> withSingleton(c: MeasuraClient, block: () -> T): T {
        val field = Measura::class.java.declaredFields.first { it.type == MeasuraClient::class.java }
        field.isAccessible = true
        val before = field.get(Measura)
        field.set(Measura, c)
        try {
            return block()
        } finally {
            field.set(Measura, before)
        }
    }

    private fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<MeasuraFlushWorker>(context).build().startWork().get()

    private fun queued(name: String, id: String) = mapOf<String, Any>(
        "event_type" to "custom",
        "timestamp" to System.currentTimeMillis(),
        "event_id" to id,
        "sdk_version" to MeasuraClient.SDK_VERSION,
        "event_properties" to mapOf("custom_event_name" to name),
    )

    @Test
    fun theWorkerDrainsTheOfflineQueueAndReportsSuccess() {
        val c = ready()
        store.saveAll((1..7).map { queued("worker", "worker_$it") })

        val result = withSingleton(c) { runWorker() }

        assertTrue("worker returned $result", result is ListenableWorker.Result.Success)
        assertEquals(7, server.accepted("worker").size)
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun theWorkerAsksForARetryWhenTheServerIsDown() {
        val c = ready()
        store.saveAll(listOf(queued("workerDown", "worker_down")))
        repeat(10) { server.ingestStatuses.add(503) }

        val result = withSingleton(c) { runWorker() }

        assertTrue("worker returned $result", result is ListenableWorker.Result.Retry)
        assertEquals("the event is still queued", 1, persisted("workerDown"))
    }

    // --- deferred deep links ------------------------------------------------

    private val payload = JSONObject()
        .put("deep_link_path", "/promo/42")
        .put("params", JSONObject().put("coupon", "SAVE10"))
        .put("click_id", "clk_deferred")
        .put("slug", "summer")
        .put("campaign_id", JSONObject.NULL)
        .put("channel", "whatsapp")

    private class Recorder : MeasuraDeferredLinkListener {
        val links = CopyOnWriteArrayList<MeasuraDeferredLink>()
        @Volatile var onMain = false
        override fun onDeferredDeepLink(link: MeasuraDeferredLink) {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            links.add(link)
        }
    }

    /** A payload fetched on an earlier launch and not yet consumed: the path that re-reads it. */
    private fun pendingClick() = prefs.edit().putString("deferred_click_id", "clk_deferred").commit()

    @Test
    fun aDeferredLinkReachesAListenerRegisteredFirstExactlyOnceOnTheMainThread() {
        server.deferred = payload
        pendingClick()
        val rec = Recorder()
        val c = ready { it.setDeferredLinkListener(rec) }

        assertTrue(waitFor(5_000) { rec.links.size == 1 })
        val link = rec.links.single()
        assertEquals("/promo/42", link.path)
        assertEquals("SAVE10", link.param("coupon"))
        assertEquals("clk_deferred", link.clickId)
        assertEquals("whatsapp", link.channel)
        assertNull(link.campaignId)
        assertTrue("delivered on the main thread", rec.onMain)

        c.setDeferredLinkListener(rec)
        Thread.sleep(1_000)
        assertEquals("never delivered twice", 1, rec.links.size)
        assertNull("consumed", prefs.getString("deferred_click_id", null))
    }

    @Test
    fun aDeferredLinkFetchedBeforeAnyListenerIsHeldUntilOneRegisters() {
        server.deferred = payload
        pendingClick()
        val c = ready()
        Thread.sleep(2_000) // the lookup has long answered; nobody is listening yet
        assertEquals("held, not consumed", "clk_deferred", prefs.getString("deferred_click_id", null))

        val rec = Recorder()
        c.setDeferredLinkListener(rec)
        assertTrue("delivered on registration", waitFor(5_000) { rec.links.size == 1 })

        val late = Recorder()
        c.setDeferredLinkListener(late)
        Thread.sleep(1_000)
        assertEquals(1, rec.links.size)
        assertEquals("a second listener does not get it again", 0, late.links.size)
    }

    @Test
    fun aThrowingListenerDoesNotCrashAndTheLinkIsNotReplayedNextLaunch() {
        server.deferred = payload
        pendingClick()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        ready {
            it.setDeferredLinkListener { calls.incrementAndGet(); throw IllegalStateException("integrator bug") }
        }

        assertTrue(waitFor(5_000) { calls.get() == 1 })
        Thread.sleep(500)
        assertNull("consumed even though the callback threw", prefs.getString("deferred_click_id", null))

        val next = MeasuraClient(context, "mk_test_fake", config())
        server.requests.clear()
        next.start()
        assertTrue(waitFor(10_000) { next.canSend() })
        Thread.sleep(1_000)
        assertEquals("the next launch does not look it up again", 0, server.count("/deferred-link"))
    }

    // --- direct deep links --------------------------------------------------

    @Test
    fun aDirectDeepLinkRecordsAReEngagementWithTheClickIdAndReturnsThePath() {
        val c = ready()
        val path = c.handleDeepLink(Uri.parse("myapp://open?measura_click_id=clk_direct&measura_path=%2Fitems%2F9"))

        assertEquals("/items/9", path)
        assertTrue(
            waitFor(5_000) {
                server.ingestPosts().any { p -> p.events().any { it.optString("click_id") == "clk_direct" } }
            },
        )
        val event = server.ingestPosts().flatMap { it.events() }.single { it.optString("click_id") == "clk_direct" }
        assertEquals("re_engagement", event.getString("event_type"))
        assertEquals("/items/9", event.getJSONObject("event_properties").getString("deep_link_path"))
    }

    @Test
    fun aLinkWithoutAClickIdRecordsNothing() {
        val c = ready()
        assertEquals("/plain", c.handleDeepLink(Uri.parse("myapp://open?measura_path=/plain")))
        assertNull(c.handleDeepLink(Uri.parse("https://example.com/unrelated")))
        Thread.sleep(500)
        c.flushBlocking()
        assertTrue(server.ingestPosts().none { p -> p.events().any { it.optString("event_type") == "re_engagement" } })
    }
}
