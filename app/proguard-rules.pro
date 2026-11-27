# TickHub release rules.
# Shrink unused code/dex/resources to cut APK/AAB size and ART (odex/vdex)
# footprint. Obfuscation stays OFF: readable stack traces + safe reflection.
-dontobfuscate
-dontwarn

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

# ---- Android components instantiated by framework by name ----
-keep class * extends android.app.BroadcastReceiver { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.lucaslu.tickhub.TickHubApp { *; }
-keep class com.lucaslu.tickhub.ui.MainActivity { *; }

# ---- Compose / networking ----
-keep class androidx.compose.ui.platform.ComposeView { *; }
-dontwarn androidx.compose.**
-dontwarn okhttp3.**
