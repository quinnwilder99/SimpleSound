plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.simplesound.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        // Room's exported schema JSON (see the `room.schemaLocation` KSP arg below)
        // is checked into `data/schemas/`. Bundling it as an androidTest asset lets
        // AppDatabaseMigrationTest open every historical schema with MigrationTestHelper
        // and prove each migration produces exactly the schema Room expects.
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

ksp {
    // Export the compiled Room schema to a checked-in JSON per version. This is
    // what makes migrations reviewable (the diff shows the exact DDL change) and
    // testable. NEVER hand-edit these files.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Room - local persistence for the track/playlist/favorites/play-stats tables
    // backing MusicRepository (see data/db/AppDatabase.kt).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt - MusicRepository/SettingsStore are constructor-injected singletons;
    // this module needs its own Hilt annotation processing pass (multi-module Hilt).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager - drives the ContentObserver-triggered background library re-sync
    // (see data/sync/LibrarySyncWorker.kt), wired through HiltWorkerFactory.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // ---- Testing ----
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)

    // Instrumented Room migration tests (AppDatabaseMigrationTest) — these need a
    // real device/emulator, run with `./gradlew :data:connectedAndroidTest`.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
