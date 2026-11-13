import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing.
 *
 * Credentials come from `keystore.properties` (local, git-ignored) or from the environment
 * (CI). Both are optional: without them the release build still compiles, it just produces
 * an unsigned APK, so a fresh clone can run `assembleRelease` and the unit tests without
 * needing anyone's private key.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { stream -> keystoreProperties.load(stream) }
}

fun signingValue(property: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(property)
        ?: providers.environmentVariable(environmentVariable).orNull

val signingStoreFile = signingValue("storeFile", "KSCANNER_KEYSTORE_FILE")
val signingStorePassword = signingValue("storePassword", "KSCANNER_STORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "KSCANNER_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "KSCANNER_KEY_PASSWORD")

val releaseSigningReady = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all { !it.isNullOrBlank() } && file(signingStoreFile!!).exists()

// Tag-driven versioning: CI passes -PversionName / -PversionCode so every release is
// distinguishable on device. Local builds fall back to the checked-in defaults.
val versionNameOverride = providers.gradleProperty("versionName").orNull
val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

android {
    namespace = "com.kscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOverride ?: 1
        versionName = versionNameOverride ?: "1.0.0"

        vectorDrawables { useSupportLibrary = true }
        // Only the ABIs ML Kit ships natives for; keeps the APK lean.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // minSdk is 26, and APK Signature Scheme v2 landed in API 24, so every
                // supported device verifies without v1. Leaving JAR signing off avoids
                // the extra META-INF bulk and the v1-only downgrade class it carries.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val composeVersion = "1.7.6"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.runtime:runtime:$composeVersion")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.6")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
