import java.util.Base64

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
            ?: "https://rakshak-signal-command.lovable.app"
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    // Real release signing — reads from env vars so the actual keystore/passwords never sit in
    // the repo. CI sources these from GitHub Actions secrets (see .github/workflows/build-apk.yml);
    // locally, export the same four env vars before running ./gradlew assembleRelease.
    // SIGNING_KEYSTORE_BASE64 is the keystore file, base64-encoded onto a single line.
    val signingKeystoreBase64 = System.getenv("SIGNING_KEYSTORE_BASE64")
    val hasReleaseSigning = !signingKeystoreBase64.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val decodedKeystore = File(System.getProperty("java.io.tmpdir"), "rakshakx-release-${System.nanoTime()}.keystore")
                decodedKeystore.writeBytes(Base64.getDecoder().decode(signingKeystoreBase64))
                storeFile = decodedKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to debug signing when the real signing secrets aren't configured yet,
            // so the build never breaks — but an APK built this way is NOT the one to hand out
            // to real users; see README for how to wire up real signing.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // Lists for messages / support chat / plans.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Loads support-chat photos (admin's replies + the user's own sent photos) from the
    // backend's short-lived signed URLs — handles caching/placeholders/memory safely so this
    // app doesn't hand-roll bitmap downloading on a background thread.
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
