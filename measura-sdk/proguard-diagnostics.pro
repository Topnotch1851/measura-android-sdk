# Applied ONLY when -PmeasuraDiagnostics=true.
#
# Kept out of proguard-rules.pro on purpose: a -keep rule forces a class to
# survive regardless of reachability, so keeping MeasuraDebug unconditionally
# pinned it into every published AAR even after its call sites were folded away.
# That cost 6 KB gzipped against a 64 KB public budget.
-keep public class dev.measura.sdk.debug.MeasuraDebug { public *; }
-keep public class dev.measura.sdk.debug.MeasuraDebug$Record { public *; }
-keep public class dev.measura.sdk.debug.MeasuraDebug$Stage { *; }
-keep public interface dev.measura.sdk.debug.MeasuraDebug$Listener { *; }
