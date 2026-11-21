plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.dripwire.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.dripwire.vpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Keep APK size reasonable; drop abiFilters if you need x86 emulator support too.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // The licensing backend's base URL — baked in at build time, never shown or editable
        // in the app's UI (end users only ever see "paste your key"). Override it with
        // -PbackendBaseUrl=https://your-backend.example (CI does this from a repo variable;
        // see .github/workflows/build-apk.yml). Must be HTTPS: usesCleartextTraffic is false.
        val backendBaseUrl = (project.findProperty("backendBaseUrl") as String?)
            ?: "https://backend.rakshakx.example"
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // The jniLibs/<abi>/lib*.so files under this module are NOT all real JNI libraries —
    // libhysteria.so and libxray.so (produced by the CI workflow, see
    // .github/workflows/build-apk.yml) are actually standalone native executables, bundled
    // this way on purpose so the OS installer extracts them to a real, executable path
    // (applicationInfo.nativeLibraryDir) that this app can run with ProcessBuilder.
    // Only libhev-socks5-tunnel.so is a genuine JNI library, loaded in-process.
    // That trick only works if native libs are extracted uncompressed, hence this block.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Pure-Java YAML parser used only to read Clash-style "proxies:" configs.
    implementation("org.yaml:snakeyaml:2.2")
}
