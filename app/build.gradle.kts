import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version people see (major.minor.patch), set by hand before each release in version.txt;
// Android's version number comes from it (1.2.4 → 10204), so every build of a version has the same one.
val appVersion = rootProject.file("version.txt").readText().trim()
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// The voices for reading aloud are not in the app people install: they download them once (VoiceFiles). The
// Ukrainian voice is kept in the repository in parts (a repository takes files below 100 MB), where it is also the
// source of the downloadable file; the build joins it and checks it against the sum written when the voice was made
// (voice-assets.yml), and only debug builds (the device tests) carry both voices as assets.
val voiceParts = layout.projectDirectory.dir("src/main/voice")
val joinedVoice = layout.buildDirectory.dir("generated/voice")
val joinVoice by tasks.registering {
    inputs.dir(voiceParts)
    inputs.file(layout.projectDirectory.file("src/debug/assets/voice/en-cori.onnx"))
    outputs.dir(joinedVoice)
    doLast {
        val dir = voiceParts.asFile
        val parts = dir.listFiles { f -> f.name.startsWith("uk-tetiana.onnx.part") }.orEmpty().sortedBy { it.name.substringAfterLast("part").toIntOrNull() ?: 0 }
        check(parts.isNotEmpty()) { "voice parts missing in $dir" }
        val out = joinedVoice.get().file("voice/uk-tetiana.onnx").asFile
        out.parentFile.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        out.outputStream().use { o -> parts.forEach { p -> p.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); md.update(b, 0, n) } } } }
        val got = md.digest().joinToString("") { b -> "%02x".format(b) }
        val want = File(dir, "uk-tetiana.onnx.sha256").readText().trim()
        check(got == want) { "voice model sum $got, expected $want" }
        // The English voice, as it came from piper-voices (en_GB-cori-medium)
        val cori = layout.projectDirectory.file("src/debug/assets/voice/en-cori.onnx").asFile
        val coriSum = MessageDigest.getInstance("SHA-256").let { d -> cori.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }; d.digest().joinToString("") { b -> "%02x".format(b) } }
        check(coriSum == "1899f98e5fb8310154f3c2973f4b8a929ba7245e722b3d3a85680b833d95f10d") { "English voice sum $coriSum" }
    }
}
tasks.named("preBuild") { dependsOn(joinVoice) }

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

    sourceSets { getByName("debug").assets.srcDir(joinedVoice) }

    androidResources {
        generateLocaleConfig = true
        // The voice is read straight from the APK (mapped, not copied): it must not be compressed
        noCompress += listOf("onnx", "bin")
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
    // Reading aloud: the voice model runs on the phone (MIT); nothing is sent anywhere
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

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
