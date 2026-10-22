import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// mGBA (the emulation core) is downloaded instead of being copied into this
// repository. It happens automatically the first time Gradle configures the
// project (also during Android Studio's "Gradle sync").
// ---------------------------------------------------------------------------
val mgbaVersion = "0.10.5"
val mgbaUrl = "https://github.com/mgba-emu/mgba/archive/refs/tags/$mgbaVersion.tar.gz"
// SHA-256 of the archive above. Leave empty to skip verification.
val mgbaSha256 = "91d6fbd32abcbdf030d58d3f562de25ebbc9d56040d513ff8e5c19bee9dacf14"
val mgbaDir: File = rootDir.resolve("third_party/mgba")
// Bump when the list of extracted files changes, to force a fresh extraction.
val mgbaMarker = "$mgbaVersion-2"

fun ensureMgbaSources() {
    val marker = mgbaDir.resolve(".mgba-version")
    if (marker.isFile && marker.readText().trim() == mgbaMarker) return

    logger.lifecycle("Downloading mGBA $mgbaVersion from $mgbaUrl")
    val archive = layout.buildDirectory.file("tmp/mgba-$mgbaVersion.tar.gz").get().asFile
    archive.parentFile.mkdirs()
    URI(mgbaUrl).toURL().openStream().use { input ->
        archive.outputStream().use { output -> input.copyTo(output) }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(archive.readBytes())
        .joinToString("") { "%02x".format(it) }
    logger.lifecycle("mGBA archive SHA-256: $digest")
    if (mgbaSha256.isNotEmpty() && !digest.equals(mgbaSha256, ignoreCase = true)) {
        throw GradleException("mGBA archive checksum mismatch: expected $mgbaSha256, got $digest")
    }

    delete(mgbaDir)
    copy {
        from(tarTree(resources.gzip(archive)))
        // Only what the native build needs; drops desktop frontends and test media.
        include(
            // README.md and CHANGES are checked by mGBA's CPack setup.
            "*/CMakeLists.txt", "*/version.cmake", "*/LICENSE", "*/README.md", "*/CHANGES",
            "*/include/**", "*/src/**", "*/res/licenses/**",
        )
        exclude("*/src/platform/qt/**", "*/src/platform/3ds/**", "*/src/platform/wii/**",
            "*/src/platform/psp2/**", "*/src/platform/switch/**", "*/src/platform/windows/**")
        // Strip the leading "mgba-0.10.5/" directory.
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
        into(mgbaDir)
    }
    marker.writeText(mgbaMarker)
}

ensureMgbaSources()

android {
    namespace = "com.joseluixrax.gbaemu"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.joseluixrax.gbaemu"
        minSdk = 26 // Android 8.0: mGBA needs strtof_l from bionic (API 26+)
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            // Real phones (arm64 / older arm) + x86_64 for emulators and Chromebooks.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DMGBA_DIR=${mgbaDir.absolutePath}", "-DANDROID_STL=none")
            }
        }
    }

    signingConfigs {
        // A fixed key committed to the repo so every build (local or CI) can
        // update the app in place without losing saves. Fine for personal use;
        // create your own private key before publishing on Google Play.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
