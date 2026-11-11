plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 32
        versionCode = 3
        versionName = "1.2"

        // The Galaxy M31 uses 64-bit ARM. sherpa-onnx native libraries in CI
        // are currently downloaded for arm64-v8a only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")

    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.8") {
        exclude(
            group = "com.github.k2-fsa.sherpa-onnx",
            module = "sherpa-onnx-jvm"
        )
    }
}
