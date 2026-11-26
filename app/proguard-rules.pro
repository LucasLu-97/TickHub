# TickHub release rules.
# Shrink unused code/dex to cut APK size and ART (odex/vdex) footprint,
# but DO NOT obfuscate — keeps stack traces readable and serialization safe.
-dontobfuscate
-dontwarn

# Keep data models used by kotlinx.serialization.
-keep class com.lucaslu.tickhub.data.** { *; }
-keepclassmembers class com.lucaslu.tickhub.data.** { *; }
-keepattributes *Annotation*
-keepattributes Signature

# Keep Android components instantiated by the framework by name.
-keep class com.lucaslu.tickhub.scheduler.DispatchReceiver { *; }
-keep class com.lucaslu.tickhub.scheduler.BootReceiver { *; }
-keep class com.lucaslu.tickhub.scheduler.DispatchWorker { *; }
-keep class com.lucaslu.tickhub.scheduler.RunTrackerWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep app entry points.
-keep class com.lucaslu.tickhub.TickHubApp { *; }
-keep class com.lucaslu.tickhub.ui.MainActivity { *; }
