plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

// ---- versionCode: derived from the git commit count so it ALWAYS increases ----
// Every deploy must have a strictly higher versionCode than the build already on
// the phone, otherwise `adb install -r` fails with INSTALL_FAILED_VERSION_DOWNGRADE
// and the only way past that is `-d` (downgrade) or an uninstall — both wipe the
// playlists/history. Tying it to `git rev-list --count HEAD` means committing is
// the only thing needed to bump it, and it can never accidentally go backwards.
// VERSION_CODE_FLOOR is a manual ratchet: bump it if git history is ever squashed
// or a shallow clone reports a lower count than a build already in the wild.
val versionCodeFloor = 53
val gitCommitCount: Int =
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(0)

android {
    namespace = "com.simplesound.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simplesound.app"
        minSdk = 26
        targetSdk = 34
        versionCode = maxOf(gitCommitCount, versionCodeFloor)
        versionName = "1.2.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // The debug build is what gets sideloaded onto the phone (see DEPLOY.md),
        // so it must be signed with a STABLE key checked into the repo rather than
        // each machine's throwaway ~/.android/debug.keystore. If the signing key
        // changes, Android rejects the install as a different app and the user has
        // to uninstall first — losing every playlist and all play history. This is
        // the standard AOSP debug key (password "android"); it is not a secret.
        getByName("debug") {
            storeFile = rootProject.file("keystore/simplesound-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // simpleSOUND is now multi-module. :app is the thin shell that wires the
    // Application/Activity to the :ui, :data, :playback and :core modules.
    // Compose/Media3/Room/Navigation/Coil/etc. are encapsulated in those modules
    // and only what MainActivity/SimpleSoundApp touch directly lives here.
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(project(":playback"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    debugImplementation(libs.androidx.ui.tooling)

    // Coil — used to configure the process-wide ImageLoader in
    // SimpleSoundApp for album art and the static PNG fallback.
    implementation(libs.coil.compose)

    // Hilt - dependency injection for SimpleSoundApp/MainActivity (SimpleSoundApp is
    // @HiltAndroidApp; MainActivity is @AndroidEntryPoint).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager - SimpleSoundApp implements Configuration.Provider to hand
    // WorkManager a HiltWorkerFactory (see data/sync/LibrarySyncWorker.kt).
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // ---- Testing ----
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.test.manifest)
}
