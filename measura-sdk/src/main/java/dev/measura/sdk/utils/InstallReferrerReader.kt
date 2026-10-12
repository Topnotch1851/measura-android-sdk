package dev.measura.sdk.utils

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the Google Play Install Referrer once, on first launch.
 *
 * When the install came through a Measura short link, the deep-link resolver
 * encodes the click ID into the Play Store URL's &referrer= parameter, and
 * Play hands it back here as "measura_click_id=<uuid>&measura_deferred=…".
 * This is the deterministic attribution signal for WhatsApp/QR/SMS/USSD
 * campaigns (attribution hierarchy tier 1 - audit S-2/S-8).
 *
 * Play only guarantees the value for a limited period after install, so the
 * caller must read it on first launch and persist the result immediately.
 */
internal class InstallReferrerReader(
    private val context: Context,
    private val logger: Logger,
) {

    /**
     * Connects to the Play Install Referrer service and returns the raw
     * referrer string, or null when unavailable (sideload, emulator without
     * Play, non-Play store, API error). Never throws.
     */
    suspend fun read(): String? = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(context).build()

        val listener = object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                val referrer = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    try {
                        client.installReferrer.installReferrer
                    } catch (e: Exception) {
                        logger.error("Install Referrer read failed: ${e.message}")
                        null
                    }
                } else {
                    logger.debug("Install Referrer unavailable (code $responseCode)")
                    null
                }
                try { client.endConnection() } catch (_: Exception) { }
                if (continuation.isActive) continuation.resume(referrer)
            }

            override fun onInstallReferrerServiceDisconnected() {
                if (continuation.isActive) continuation.resume(null)
            }
        }

        // startConnection itself can throw (a SecurityException on some builds
        // without a working Play Store). Treated as "no referrer", so the
        // install event is still sent rather than lost with this launch.
        try {
            client.startConnection(listener)
        } catch (e: Exception) {
            logger.error("Install Referrer connection failed: ${e.message}")
            if (continuation.isActive) continuation.resume(null)
        }

        continuation.invokeOnCancellation {
            try { client.endConnection() } catch (_: Exception) { }
        }
    }

    companion object {
        // Tolerates both decoded ("measura_click_id=…") and still-encoded
        // ("measura_click_id%3D…") referrer strings.
        private val CLICK_ID_REGEX = Regex(
            "measura_click_id(?:=|%3D)([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
        )

        /** Extracts the Measura click ID from a referrer string, if present. */
        fun extractClickId(referrer: String?): String? =
            referrer?.let { CLICK_ID_REGEX.find(it)?.groupValues?.get(1) }
    }
}
