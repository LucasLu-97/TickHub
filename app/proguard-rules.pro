# TickHub release rules.
# Shrink unused code/dex/resources to cut APK/AAB size and ART (odex/vdex)
# footprint. Obfuscation stays OFF: readable stack traces + safe reflection.
-dontobfuscate
# No global -dontwarn: it hid the R8 warnings that caused three launch crashes.
# Targeted -dontwarn lines below cover the known noisy libraries.

# ---- kotlinx.serialization (CRITICAL: launch crash v1.0.0/v1.0.1/v1.3.1-beta) ----
# AppViewModel reads serialized rules/logs synchronously at construction. If R8
# strips the generated $$serializer or its descriptor classes, that read throws
# immediately and the app flash-crashes on launch. Use the official rule set.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep ALL generated serializers (incl. descriptor classes) for our models.
-keep,includedescriptorclasses class com.lucaslu.tickhub.data.**$$serializer { *; }
-keepclassmembers class com.lucaslu.tickhub.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep every one of our own classes/members (cheap insurance; our code is small).
-keep class com.lucaslu.tickhub.** { *; }

# Enums referenced by name from serialized state.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- DataStore / coroutines: reflectively constructed internals ----
-keep class androidx.datastore.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn androidx.datastore.**
-dontwarn kotlinx.coroutines.**

# ---- WorkManager / Room (CRITICAL: launch crash v1.3.1/v1.3.2-beta) ----
# WorkManagerInitializer reflectively calls WorkDatabase_Impl's no-arg ctor.
# R8 strips it as "unused" -> NoSuchMethodException -> crash in ContentProvider
# before Application.onCreate. This is the official WorkManager+R8 keep rule.
-keep class androidx.work.impl.** { *; }
-keep class androidx.room.** { *; }
# App Startup reflectively instantiates initializers (Class.forName via manifest).
-keep class androidx.startup.** { *; }
-keep class androidx.work.WorkManagerInitializer { *; }
-keep class * extends androidx.startup.Initializer { *; }
# WorkManager reflectively constructs InputMerger via no-arg ctor.
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker { *; }
-keepattributes InnerClasses, Signature, *Annotation*

# ---- Android components instantiated by framework by name ----
-keep class * extends android.app.BroadcastReceiver { *; }
-keep class com.lucaslu.tickhub.TickHubApp { *; }
-keep class com.lucaslu.tickhub.ui.MainActivity { *; }

# ---- Compose / networking ----
-keep class androidx.compose.ui.platform.ComposeView { *; }
-dontwarn androidx.compose.**
-dontwarn okhttp3.**
