package dev.measura.sdk.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.measura.sdk.core.MeasuraConfig

// =============================================================================
// DEVICE FINGERPRINT
// Collects device signals for probabilistic attribution.
// No PII is collected - only device characteristics.
// =============================================================================

internal class FingerprintComponents(
    val userAgent: String,
    val deviceModel: String,
    val osVersion: String,
    val screenResolution: String,
    val appVersion: String,
)

internal class DeviceFingerprint(private val context: Context) {

    private var cached: FingerprintComponents? = null

    fun getComponents(): FingerprintComponents {
        cached?.let { return it }
        val components = FingerprintComponents(
            userAgent = buildUserAgent(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            osVersion = Build.VERSION.RELEASE,
            screenResolution = getScreenResolution(),
            appVersion = getAppVersion(),
        )
        cached = components
        return components
    }

    private fun buildUserAgent(): String {
        val appVersion = getAppVersion()
        val model = Build.MODEL.replace(" ", "_")
        val osVersion = Build.VERSION.RELEASE
        return "MeasuraSDK/1.0.0 (Android $osVersion; $model) App/$appVersion"
    }

    private fun getScreenResolution(): String {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            "${metrics.widthPixels}x${metrics.heightPixels}"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getAppVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}

// =============================================================================
// NETWORK MONITOR
// Checks connectivity state before attempting flush.
// =============================================================================

internal class NetworkMonitor(private val context: Context) {

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ConnectivityManager.activeNetwork is API 23. The SDK's floor is 21, and
    // on Android 5.x the call threw NoSuchMethodError - an Error, not an
    // Exception, so it escaped every catch block and crashed the host app on
    // the first flush. Below 23 the (deprecated, but present) NetworkInfo API
    // answers the same two questions.
    fun isConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            return connectivityManager.activeNetworkInfo?.isConnected == true
        }
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isWifiConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val info = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
        }
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

// =============================================================================
// BATTERY MONITOR
// Suppresses events when battery is critically low.
// Reduces user-visible data usage on low-end devices common in African markets.
// =============================================================================

internal class BatteryMonitor(private val context: Context) {

    fun getBatteryPercent(): Int {
        return try {
            // ACTION_BATTERY_CHANGED first. It is a sticky broadcast present on
            // every Android version (a null receiver returns the last value
            // with nothing to unregister), and it is the level the system
            // publishes to every app and shows the user.
            //
            // BATTERY_PROPERTY_CAPACITY is only the fallback. It is not
            // universally implemented - emulators and a number of low-end
            // devices return Integer.MIN_VALUE, which is how suppression once
            // silently never ran (2026-08-23, Android 30 emulator) - and where
            // it is implemented it reads the fuel gauge directly, so it ignores
            // `dumpsys battery set level`. Read first, it made the low-battery
            // path impossible to prove on the Android 5 emulator (2026-09-24).
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) return (level * 100) / scale

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct != Int.MIN_VALUE && pct in 0..100) return pct

            // Still unknown. Assume charged rather than flat: treating unknown as
            // low suppressed every event for the process lifetime (audit M-13),
            // which loses data, and losing data is worse than using battery.
            100
        } catch (e: Exception) {
            100
        }
    }

    fun isBelowThreshold(threshold: Int): Boolean {
        return getBatteryPercent() < threshold
    }
}

// =============================================================================
// LOGGER
// Wraps Android Log with configurable level. NONE in production.
// =============================================================================

internal class Logger(private val level: MeasuraConfig.LogLevel) {

    private val TAG = "MeasuraSDK"

    fun debug(message: String) {
        if (level == MeasuraConfig.LogLevel.DEBUG || level == MeasuraConfig.LogLevel.VERBOSE) {
            android.util.Log.d(TAG, message)
        }
    }

    fun error(message: String) {
        if (level != MeasuraConfig.LogLevel.NONE) {
            android.util.Log.e(TAG, message)
        }
    }
}
