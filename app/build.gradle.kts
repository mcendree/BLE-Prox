plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt") // required by the Wiliot SDK
}

android {
    namespace = "com.example.pixelproximity"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.pixelproximity"
        minSdk = 29          // Wiliot SDK requires API 29+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module"
        )
    }
}

// ── Wiliot SDK version ────────────────────────────────────────────────────
// Bump this to the latest shown at:
// https://central.sonatype.com/artifact/com.wiliot/wiliot-bom
val wiliotBom = "3.9.0"

dependencies {
    // ── Wiliot SDK (published on Maven Central) ──
    implementation(platform("com.wiliot:wiliot-bom:$wiliotBom"))
    implementation("com.wiliot:wiliot-core")          // models + init
    implementation("com.wiliot:wiliot-queue")         // MQTT transport (required by upstream)
    implementation("com.wiliot:wiliot-upstream")      // BLE scanner
    implementation("com.wiliot:wiliot-network-meta")  // REST client used for ID resolution
    implementation("com.wiliot:wiliot-resolve-data")  // resolves payload -> Pixel ID; exposes data Flows

    // ── AndroidX / Compose ──
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.android.material:material:1.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
