# Consumer ProGuard/R8 rules for the Measura Android SDK.
#
# build.gradle has always referenced this file via consumerProguardFiles, but it
# did not exist. That is silently tolerated by some AGP versions and fails the
# build on others, and it meant host apps got no shrinking rules from us at all.
#
# These rules ship to every app that depends on the SDK, so they are kept as
# narrow as possible. Over-broad keep rules would inflate the host app.

# ---------------------------------------------------------------------------
# Public API surface.
#
# Callers reference these by name, and some integrations resolve them
# reflectively from Java. Keep the entry points and their signatures.
# ---------------------------------------------------------------------------
-keep public class dev.measura.sdk.Measura { public *; }
-keep public class dev.measura.sdk.core.MeasuraConfig { public *; }
-keep public class dev.measura.sdk.core.MeasuraConfig$LogLevel { *; }
-keep public class dev.measura.sdk.core.EventType { *; }

# Deferred deep linking. The listener is a `fun interface` an integrator
# implements, and the payload is handed to their code, so both are part of the
# compile-time surface even though nothing inside the SDK constructs them from
# outside. Without these, an integrator's own R8 pass can rename the callback
# method and the link is simply never delivered - a silent failure, on first
# launch only, that no build error would reveal.
-keep public class dev.measura.sdk.core.MeasuraDeferredLink { public *; }
-keep public interface dev.measura.sdk.core.MeasuraDeferredLinkListener { public *; }

# Enum values() and valueOf() are used reflectively by the Kotlin runtime.
-keepclassmembers enum dev.measura.sdk.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Event serialisation.
#
# Event payloads are built with org.json and signed with an HMAC over a
# canonical JSON string. The signature is computed from field NAMES, so
# obfuscating any class whose fields reach the wire would change the canonical
# payload and break signature verification server side.
#
# Internal implementation classes are deliberately left shrinkable: they build
# JSON explicitly rather than via reflection over field names.
# ---------------------------------------------------------------------------
-keepclassmembers class dev.measura.sdk.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------
# Play Install Referrer.
#
# Loaded reflectively and optional at runtime: the SDK degrades gracefully when
# the dependency is absent, so warnings about it must not fail the build.
# ---------------------------------------------------------------------------
-dontwarn com.android.installreferrer.**
-keep class com.android.installreferrer.api.** { *; }

# ---------------------------------------------------------------------------
# Kotlin coroutines internals used by the flush scheduler.
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------------------------------------------------------------------------
# Background flush worker.
#
# WorkManager instantiates workers REFLECTIVELY, by class name, from the name it
# persisted in its own database when the work was enqueued. R8 in the host app
# sees no direct construction of this class and would strip or rename it, and
# the failure would not appear at build time: the app compiles, the SDK runs,
# and the queue simply never drains in the background. That is the same shape of
# fault as the missing INTERNET permission, so it gets an explicit rule rather
# than a hope.
#
# The four-argument constructor signature is the one WorkManager reflects on.
# ---------------------------------------------------------------------------
-keep class dev.measura.sdk.work.MeasuraFlushWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
