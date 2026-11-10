plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.soundtracker.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    ndkPath = rootProject.file(".tools/android-ndk").absolutePath

    defaultConfig {
        applicationId = "com.soundtracker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math", "-fvisibility=hidden")
                cFlags += listOf("-O3", "-ffast-math", "-fvisibility=hidden")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with debug key so user can directly `adb install` the release-optimized APK too
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Media3 ExoPlayer for instant studio A/B waveform playback & seeking
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")

    // WorkManager for WeChat background upload grace-period deferred cleanup
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val outApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (outApk.exists()) {
            val dest = rootProject.layout.buildDirectory.file("SoundTracker.apk").get().asFile
            dest.parentFile.mkdirs()
            outApk.copyTo(dest, overwrite = true)
        }
    }
}
