import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version people see (major.minor.patch), set by hand before each release in version.txt;
// Android's version number comes from it (1.2.4 → 10204), so every build of a version has the same one.
val appVersion = rootProject.file("version.txt").readText().trim()
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "ua.hramota"
    // No list of dependencies (encrypted for Google) inside the APK
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    compileSdk = 36
    // Pinned so the PDF bridge (src/main/cpp) builds the same everywhere
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "ua.hramota"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Built unsigned: CI signs it in a separate step with the Android SDK's apksigner.
        }
        debug {
            // Debug builds also run on x86_64 emulators, where the PDF engine is tested in CI
            ndk { abiFilters += "x86_64" }
        }
    }

    // The bridge between Kotlin and PDFium (runs only in the isolated PDF process)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // PDFium itself: the reviewed prebuilt library in /pdfium (see pdfium/ATTESTATION.txt)
    sourceSets["main"].jniLibs.srcDir(rootProject.file("pdfium/lib"))
    // Made-up large documents, shared by the computer and the emulator tests
    sourceSets["test"].java.srcDir("src/sharedTest/java")
    sourceSets["androidTest"].java.srcDir("src/sharedTest/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    // Screens are drawn on the computer (Robolectric) and need the app's resources
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Screens drawn on the computer (Robolectric) with made-up documents, for review
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.16.1")

    // On-device tests (emulator in CI): the isolated PDF engine
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
