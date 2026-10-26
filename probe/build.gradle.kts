import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Same key as the main app (see app/build.gradle.kts).
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Lets the apps post their logs to the developer (GitHub issue #1 of this repo).
// LOG_TOKEN is a repository secret: a fine-grained token limited to this repo's issues.
val logToken = System.getenv("LOG_TOKEN")?.trim().orEmpty()
val logRepo = System.getenv("GITHUB_REPOSITORY") ?: "origamigraph-del/TunnelLock"

android {
    namespace = "app.tunnellock.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tunnellock.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "LOG_TOKEN", "\"$logToken\"")
        buildConfigField("String", "LOG_REPO", "\"$logRepo\"")
        buildConfigField("int", "LOG_ISSUE", "1")
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

base {
    archivesName.set("TunnelProbe-0.1.$buildNumber")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
