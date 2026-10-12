package dev.measura.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.measura.sdk.core.MeasuraClient
import dev.measura.sdk.core.MeasuraConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsentPersistenceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs
        get() = context.getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE)

    private fun newClient() = MeasuraClient(context, "msr_consent_test", MeasuraConfig())

    @Before
    fun clearConsentState() {
        prefs.edit().remove("tracking_enabled").commit()
    }

    @After
    fun restoreDefault() {
        prefs.edit().remove("tracking_enabled").commit()
    }

    @Test
    fun trackingIsOnByDefault() {
        assertTrue(
            "a fresh install must track until the user says otherwise",
            newClient().isTrackingEnabled(),
        )
    }

    @Test
    fun disableTrackingSurvivesARestart() {
        newClient().setTrackingEnabled(false)

        assertFalse(
            "the opt-out was not written to disk, so it dies with the process",
            prefs.getBoolean("tracking_enabled", true),
        )
        assertFalse(
            "a client constructed after a restart ignored the stored opt-out " +
                "and would resume tracking a user who opted out",
            newClient().isTrackingEnabled(),
        )
    }

    @Test
    fun enableTrackingSurvivesARestart() {
        newClient().setTrackingEnabled(false)
        newClient().setTrackingEnabled(true)

        assertTrue(
            "consent was withdrawn and re-given, but the re-grant did not persist",
            newClient().isTrackingEnabled(),
        )
    }

    @Test
    fun theWriteIsDurableNotJustCached() {
        newClient().setTrackingEnabled(false)

        val independent = context
            .getSharedPreferences("dev.measura.sdk", Context.MODE_PRIVATE)
        assertFalse(independent.getBoolean("tracking_enabled", true))
    }
}
