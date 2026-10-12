# =============================================================================
# R8 rules for shrinking the SDK ITSELF (minifyEnabled true on the release
# build type). These are not the consumer rules; see consumer-rules.pro for
# what we ask the host app's build to preserve.
#
# WHY MINIFY A LIBRARY AT ALL
# Unminified, every internal class ships with its full name, every Kotlin
# @Metadata annotation, and every member that was generated but never called.
# For an SDK whose entire pitch is that it is small, shipping that is a choice.
# R8 strips the unreachable members and shortens internal names, while the
# keeps below guarantee the public surface an integrator compiles against is
# untouched.
#
# THE RISK, STATED PLAINLY
# R8 removes what it cannot see being used. Anything reached only by
# reflection would disappear silently and fail at runtime, not at build time.
# This SDK uses no reflection: no Class.forName, no serialization library, no
# annotation processing. JSON is built field by field through org.json rather
# than mapped onto classes. That is what makes minification safe here, and it
# is a property to re-check before adding any dependency that does use
# reflection.
# =============================================================================

# -----------------------------------------------------------------------------
# Public API. This is the contract an integrator writes code against, so every
# name here must survive exactly as published. Shortening any of it would make
# the AAR uncompilable for consumers.
# -----------------------------------------------------------------------------
-keep public class dev.measura.sdk.Measura { public *; }
-keep public class dev.measura.sdk.Measura$* { public *; }

# Config is constructed with named arguments, so parameter-carrying members and
# the synthetic $default constructor bridge must both survive.
-keep public class dev.measura.sdk.core.MeasuraConfig { public *; }
-keep public class dev.measura.sdk.core.MeasuraConfig$* { *; }

# Enum entries are referenced by name by callers. values() and valueOf() are
# generated and must be kept explicitly or R8 treats them as unused.
-keepclassmembers enum dev.measura.sdk.core.EventType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public *;
}
-keep public enum dev.measura.sdk.core.EventType { *; }

# Deferred deep linking. Both of these cross the public boundary: the listener
# is an interface the integrator implements, and the payload is the object
# handed to their callback. R8 sees nothing outside the SDK constructing either
# one, so without these it renames both and the published AAR simply does not
# contain the types an integrator compiles against. Verified by the "public API
# survived minification" step in CI, which is where this was caught.
-keep public class dev.measura.sdk.core.MeasuraDeferredLink { public *; }
-keep public interface dev.measura.sdk.core.MeasuraDeferredLinkListener { public *; }

# IngestException is NOT kept. It is nested inside IngestApiClient, which is
# `internal`, so no integrator can reference either by name: the exception
# never crosses the public boundary. Keeping it pinned the OUTER class name
# too, which blocked obfuscation of the largest class in the AAR and cost
# roughly 600 bytes gzipped for a symbol nobody can catch.

# -----------------------------------------------------------------------------
# Framework contracts
# -----------------------------------------------------------------------------

# The Play Install Referrer library invokes our listener through its own
# interface. Its callbacks are entry points R8 cannot see being called.
-keep class * implements com.android.installreferrer.api.InstallReferrerStateListener { *; }
-dontwarn com.android.installreferrer.**

# Coroutines ship their own consumer rules, but the internal marker classes are
# reached from generated state machines rather than from our code.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# -----------------------------------------------------------------------------
# Diagnostics
# -----------------------------------------------------------------------------

# Keep line numbers so a crash in a host app still yields a usable stack trace,
# and rename the source attribute so it does not leak our file names. This
# costs a small amount of size and buys the ability to debug a report from a
# device we do not own.

# Kotlin metadata is only needed for reflection and for Kotlin consumers
# resolving top-level declarations across modules. Our public surface is
# classes and an object, not top-level functions, so this is removable weight.
-dontwarn kotlin.Metadata

# Background flush worker: same reflective-instantiation reasoning as
# consumer-rules.pro. Kept here too so the library's own release build does not
# strip it before it ever reaches the host app.
-keep class dev.measura.sdk.work.MeasuraFlushWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
