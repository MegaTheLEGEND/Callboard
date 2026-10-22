plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rvcs25.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rvcs25.app"
        minSdk = 31            // Android 12+: Galaxy S25 ships Android 15
        targetSdk = 35
        // Must increase with every build, or Android refuses to install it over the old one.
        // Minutes since 1970 (fits an Int until the year 5000+), so local and CI builds always stay in order.
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../engine/CMakeLists.txt")
        }
    }

    // Baked-in base models: store them uncompressed (ONNX floats don't compress; saves minutes of build time)
    androidResources { noCompress += listOf("onnx", "f32") }

    // Every build (local, CI, debug, release) is signed with the SAME key, so a new APK installs over the old one
    // (keeps your voices and settings). Android only allows an update when the signing key matches.
    // - Default: android/signing/rvcs25.jks, committed to the repo (personal sideload key, no secrets needed).
    // - Optional: CI can pass its own key (KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD) -- but then use it
    //   for every build from then on, because changing the key means uninstalling once.
    signingConfigs {
        create("stable") {
            val f = System.getenv("KEYSTORE_FILE")
            storeFile = if (f != null) file(f) else file("../signing/rvcs25.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "rvcs25app"
            keyAlias = System.getenv("KEY_ALIAS") ?: "rvcs25"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "rvcs25app"
        }
    }

    buildFeatures { prefab = true; aidl = true }          // Oboe is consumed as a prefab package

    packaging {
        // The Hexagon DSP loads its skel libraries from files on disk, so the native
        // libs must be extracted at install time (android:extractNativeLibs=true).
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")   // sideload build
        }
        // app-debug.apk: debuggable, so `adb shell run-as com.rvcs25.app ...` can read files/voices etc.
        // Signed like release (same stable key) so the two install over each other. The native engine is still
        // built optimised (see engine/CMakeLists.txt); only the Java/Kotlin side is a debug build.
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("stable")
        }
    }
}

dependencies {
    // ONNX Runtime with the QNN execution provider. This AAR depends on Qualcomm's
    // `qnn-runtime` AAR, which carries libQnnHtp.so, the HTP stubs and the Hexagon skel
    // libraries (e.g. libQnnHtpV79Skel.so for Snapdragon 8 Elite). Gradle fetches both and
    // packages their native libs into the APK. Keep this version == ORT_QNN_VERSION in
    // scripts/fetch_ort.* (CMake links against the headers/lib that script unpacks).
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.42.0") // floor; Gradle uses the newer version ORT asks for
    implementation("com.google.oboe:oboe:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // Call-uplink injection runs in a shell-uid helper process started through Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}