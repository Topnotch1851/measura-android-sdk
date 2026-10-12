package dev.measura.sizeprobe

import android.app.Activity
import android.os.Bundle
import dev.measura.sdk.Measura
import dev.measura.sdk.core.EventType
import dev.measura.sdk.core.MeasuraConfig

/**
 * Integrated variant. measura-sdk's consumer-rules.pro already force-keeps
 * the entire public API of Measura for every consumer, so these calls are
 * belt-and-suspenders against that rule narrowing later, not what makes R8
 * retain the coroutines, install-referrer and WorkManager chains here.
 *
 * This app is built only to be measured, never installed or run, so the
 * calls below are never executed and the placeholder key is never used
 * against a real endpoint.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Measura.init(applicationContext, "size-probe-key", MeasuraConfig())
        Measura.trackEvent(EventType.PURCHASE, mapOf("revenue" to 1.0, "currency" to "USD"))
        Measura.identify("size-probe-user")
        Measura.setPushToken("size-probe-token")
    }
}
