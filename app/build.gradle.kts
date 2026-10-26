import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// signing/release.jks is encrypted; its password comes from the SIGNING_PASSWORD
// secret. Every build must use this same key so it can update the installed app
// in place: uninstalling would delete the secure space.
// Trimmed: a pasted secret easily picks up a trailing newline.
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.tunnellock"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tunnellock"
        minSdk = 28
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (signingPassword != null) {
            create("release") {
                storeFile = rootProject.file("signing/release.jks")
                storePassword = signingPassword
                keyAlias = "tunnellock"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the secret the APK is debug-signed: fine for checking the build, not for installing.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    packaging {
        // Compress the Go core (~33 MB per ABI uncompressed).
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

base {
    archivesName.set("TunnelLock-0.1.$buildNumber")
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/libv2ray.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.android.embedded)
}
