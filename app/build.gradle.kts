plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.acwindows"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acwindows"
        minSdk = 26          // Android 8.0; covers ~99% of active devices
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Debug builds are signed with the auto-generated debug key, which is all
        // you need to sideload to your own phone over USB.
        release {
            isMinifyEnabled = false
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
    // Compose compiler 1.5.14 is the matched pair for Kotlin 1.9.24.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    // Robolectric needs the merged resources/assets/manifest to inflate layouts and load
    // fonts/drawables — without this the widget render tests see no resources.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // WorkManager drives the periodic background refresh of the widget.
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Jetpack Compose (the launcher app UI). The BOM pins the compose-* versions
    // to one known-good, mutually-compatible set.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // JVM unit tests for the pure logic (Score / Decision / ForecastBuilder) — they have
    // no Android dependencies, so they run on plain JUnit. `./gradlew test`.
    testImplementation("junit:junit:4.13.2")
    // Robolectric runs the widget bitmap renderers on the JVM with a real (native-graphics)
    // Canvas + resources, so a render that crashes or comes out blank fails `./gradlew test`
    // in seconds, no emulator. It does NOT fully reproduce launcher RemoteViews inflation —
    // that's the instrumented test's job (WidgetInflationTest).
    testImplementation("org.robolectric:robolectric:4.14.1")

    // Instrumented widget test: runs the real RemoteViews.apply() on a device/emulator, the
    // exact inflation the launcher does. `./gradlew connectedDebugAndroidTest`.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
