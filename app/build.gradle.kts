import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
}

val defaultNotificationJournalUrl =
    "https://script.google.com/macros/s/AKfycbyIFgLekUH827G6LkgbEnYwh9fykejZjHzga5ce-roXruFwTHqr0-MlpPKaFTj_bgHo/exec"
val notificationJournalUrl = providers.gradleProperty("WEATHER_NOTIFICATION_JOURNAL_URL").orNull
    ?: System.getenv("WEATHER_NOTIFICATION_JOURNAL_URL")
    ?: defaultNotificationJournalUrl
val escapedNotificationJournalUrl = notificationJournalUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val slimReleaseApks = providers.gradleProperty("WEATHER_SLIM_RELEASE_APKS").orNull == "true"
val signDebugWithSuppliedKey =
    providers.gradleProperty("WEATHER_SIGN_DEBUG_WITH_SUPPLIED_KEY").orNull == "true"

android {
    namespace = "com.weather.metro"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.weather.metro"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField(
            "String",
            "NOTIFICATION_JOURNAL_URL",
            "\"$escapedNotificationJournalUrl\"",
        )
    }

    val suppliedSigningConfig = System.getenv("ANDROID_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
        ?.let { storePath ->
            signingConfigs.create("supplied") {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }

    buildTypes {
        debug {
            // CI test APKs keep the production package id but use the repository's
            // persistent supplied signing identity. This makes sideload updates
            // independent of ephemeral GitHub runner debug.keystore files.
            versionNameSuffix = "-debug"
            if (signDebugWithSuppliedKey && suppliedSigningConfig != null) {
                signingConfig = suppliedSigningConfig
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (suppliedSigningConfig != null) {
                signingConfig = suppliedSigningConfig
            }
        }
    }

    splits {
        abi {
            isEnable = slimReleaseApks
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
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
        jniLibs {
            useLegacyPackaging = slimReleaseApks
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.maplibre.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
