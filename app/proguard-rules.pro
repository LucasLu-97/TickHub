# TickHub release rules.
# Shrink unused code/dex/resources to cut APK/AAB size and ART (odex/vdex)
# footprint. Obfuscation stays OFF: readable stack traces + safe reflection.
-dontobfuscate
-dontwarn

# ---- kotlinx.serialization (CRITICAL: v1.0.0/v1.0.1 launch crashes) ----
# @Serializable classes generate a $$serializer nested class and a Companion
# with serializer(). R8 must keep both or DataStore decode fails at runtime.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
    *** serializer();
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# All our serializable data models + their serializer companions.
-keep class com.lucaslu.tickhub.data.** { *; }
-keepclassmembers class com.lucaslu.tickhub.data.** { *; }
-keep class com.lucaslu.tickhub.domain.** { *; }

# Enums are referenced by name from serialized state — keep values()/valueOf().
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Android components instantiated by framework by name ----
-keep class com.lucaslu.tickhub.scheduler.DispatchReceiver { *; }
-keep class com.lucaslu.tickhub.scheduler.BootReceiver { *; }
-keep class * extends android.app.BroadcastReceiver { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.lucaslu.tickhub.TickHubApp { *; }
-keep class com.lucaslu.tickhub.ui.MainActivity { *; }

# ---- Compose / runtime reflection ----
-keep class androidx.compose.ui.platform.ComposeView { *; }
-dontwarn androidx.compose.**
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
