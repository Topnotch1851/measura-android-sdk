package dev.measura.sizeprobe

import android.app.Activity
import android.os.Bundle

/**
 * Baseline variant: no dependency on measura-sdk at all. CI builds this,
 * then the withSdk variant, and diffs the two release APK sizes to measure
 * what integrating the SDK actually costs (SDK_APK_IMPACT_BYTES in
 * frontend/lib/sdk-size.ts).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
