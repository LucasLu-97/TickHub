import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.x has built-in Kotlin support; org.jetbrains.kotlin.android must NOT be applied.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Public OAuth client ID (GitHub CLI's official one) is bundled by default so
// end users can sign in straight from the app with zero setup.
// OAuth client IDs are public by design; no secret is embedded.
val defaultGithubClientId = "178c6fc778ccc68e1d6a"
val githubClientId = (project.findProperty("githubClientId") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: defaultGithubClientId

android {
    namespace = "com.lucaslu.tickhub"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lucaslu.tickhub"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode/versionName are injected by CI, with local fallback
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String) ?: "0.0.1-local"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GitHub OAuth App client ID is public by design (no secret).
        // Inject via -PgithubClientId=... at build time; empty = not configured.
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                val ksFile = layout.buildDirectory.file("tickhub-release.p12").get().asFile
                ksFile.parentFile.mkdirs()
                ksFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "tickhub"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                storeType = System.getenv("KEYSTORE_TYPE") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            // R8 enabled for store release + smaller APK. The v1.0.0/v1.0.1
            // launch crashes were caused by missing kotlinx.serialization keep
            // rules; those rules are now explicit in proguard-rules.pro.
            // Obfuscation stays off (-dontobfuscate) so stack traces remain
            // readable and serialization is safe — shrink alone yields the size win.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fail fast: never publish a debug-signed "release" — it would break
            // overwrite installs for existing users (signature mismatch).
            signingConfig = if (System.getenv("KEYSTORE_BASE64") != null) {
                signingConfigs.getByName("release")
            } else {
                throw GradleException("KEYSTORE_BASE64 missing: release build requires the signing key")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("sh.calvin.reorderable:reorderable-android:3.1.0")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    // Room will be added in M2 together with a KSP version aligned to the Kotlin toolchain
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
