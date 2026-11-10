plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "win.wintertools.videocatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "win.wintertools.videocatcher"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "2.2"
    }

    // Fester Schluessel im Repo: jede gebaute APK hat dieselbe Signatur,
    // damit sich Updates ueber die alte Version installieren lassen.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/videocatcher.jks")
            storePassword = "videocatcher"
            keyAlias = "videocatcher"
            keyPassword = "videocatcher"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Eingebauter Player der Mediathek
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Auf dem Rechner gibt es kein Android: echtes org.json fuer die Tests
    testImplementation("org.json:json:20240303")
}
