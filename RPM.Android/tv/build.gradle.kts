plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.rpm.player.tv"
    // Compose 1.12 (BOM 2026.09.00) cannot be compiled against an SDK older than 37.
    compileSdk = 37
    // The NDK that strips the FFmpeg library. Keep it equal to ndkVersion in ffmpeg/build.gradle.kts:
    // with an NDK that is not installed, the library ships with its symbols.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.rpm.player.tv"
        // Android 9. Fire OS 7, which most Fire TV sticks run, is API 28, and Google's TV
        // checklist asks for 31 or lower.
        minSdk = 28
        // Google Play refuses a bundle for this app that targets lower than 36. The Back key is handled
        // only through BackHandler, which works with the predictive back that API 36 turns on.
        targetSdk = 36
        versionCode = 4
        // A closed beta bundle (scripts/bundle-play.sh --beta) says so in its name, so it is seen before it is
        // promoted to production: its reports carry the tester's log and screenshot (ReportScope in core/diag).
        versionName = "0.6.1" + if (providers.gradleProperty("rpm.beta").orNull == "true") "-beta" else ""
    }

    // Bundles for Google Play are signed with the upload key. scripts/bundle-play.sh sets these variables.
    // Without them the release build is unsigned, which is enough to build from source.
    // The same block is in phone/build.gradle.kts.
    val uploadKeystore = providers.environmentVariable("RPM_UPLOAD_KEYSTORE").orNull
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = providers.environmentVariable("RPM_UPLOAD_PASSWORD").get()
                keyAlias = providers.environmentVariable("RPM_UPLOAD_KEY_ALIAS").get()
                // A PKCS12 keystore has one password for the store and the key.
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            // The bundle carries the native symbol table, so Google Play can show native crashes with
            // function names. Devices get the stripped library.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isMinifyEnabled = false
        }
        // The release build signed with the debug key, for test boxes. It installs over the debug build
        // and keeps the session. It is compiled and not debuggable, like the build a listener gets. The
        // debug build's launch switches do not work in it. Build it with `assembleBox`, then run on the
        // box `cmd package compile -m speed -f com.rpm.player.tv`.
        create("box") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    testOptions {
        // The rules under test read android.view.KeyEvent constants, which the JVM stub inlines.
        // Any other framework call returns a default value instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Everything but the screens, shared with the phone app.
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // FaceMarks asks for profile pictures with the rpm app key header.
    implementation(libs.coil.network.okhttp)
    // The sign-in QR code.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
