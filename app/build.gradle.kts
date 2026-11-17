import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// The emulation cores are downloaded instead of being copied into this
// repository: mGBA (GBA, GB, GBC) and melonDS (DS). It happens automatically
// the first time Gradle configures the project (also during Android Studio's
// "Gradle sync").
// ---------------------------------------------------------------------------
class CoreSource(
    val name: String,
    val version: String,
    val url: String,
    // SHA-256 of the archive. Leave empty to skip verification.
    val sha256: String,
    val dir: File,
    // Bump the suffix when the list of extracted files changes, to force a fresh extraction.
    val marker: String,
    val include: List<String>,
    val exclude: List<String> = emptyList(),
)

val mgba = CoreSource(
    name = "mGBA",
    version = "0.10.5",
    url = "https://github.com/mgba-emu/mgba/archive/refs/tags/0.10.5.tar.gz",
    sha256 = "91d6fbd32abcbdf030d58d3f562de25ebbc9d56040d513ff8e5c19bee9dacf14",
    dir = rootDir.resolve("third_party/mgba"),
    marker = "0.10.5-2",
    // Only what the native build needs; drops desktop frontends and test media.
    // README.md and CHANGES are checked by mGBA's CPack setup.
    include = listOf(
        "*/CMakeLists.txt", "*/version.cmake", "*/LICENSE", "*/README.md", "*/CHANGES",
        "*/include/**", "*/src/**", "*/res/licenses/**",
    ),
    exclude = listOf(
        "*/src/platform/qt/**", "*/src/platform/3ds/**", "*/src/platform/wii/**",
        "*/src/platform/psp2/**", "*/src/platform/switch/**", "*/src/platform/windows/**",
    ),
)

val melonds = CoreSource(
    name = "melonDS",
    version = "1.1",
    url = "https://github.com/melonDS-emu/melonDS/archive/refs/tags/1.1.tar.gz",
    sha256 = "61e339bcb18a68a17485973637d972ea628c5624d7e6b8adf6870f895d5e26fd",
    dir = rootDir.resolve("third_party/melonds"),
    marker = "1.1-1",
    // The core library only, without the Qt frontend.
    include = listOf("*/CMakeLists.txt", "*/LICENSE", "*/cmake/**", "*/src/**"),
    exclude = listOf("*/src/frontend/**"),
)

fun ensureSources(core: CoreSource) {
    val marker = core.dir.resolve(".core-version")
    if (marker.isFile && marker.readText().trim() == core.marker) return

    logger.lifecycle("Downloading ${core.name} ${core.version} from ${core.url}")
    val archive = layout.buildDirectory.file("tmp/${core.name}-${core.version}.tar.gz").get().asFile
    archive.parentFile.mkdirs()
    // A few tries: GitHub sometimes fails a download for a moment.
    var attempt = 1
    while (true) {
        try {
            URI(core.url).toURL().openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            break
        } catch (e: java.io.IOException) {
            if (attempt >= 4) throw e
            logger.warn("Download failed (${e.message}), retrying")
            Thread.sleep(5000L * attempt)
            attempt++
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(archive.readBytes())
        .joinToString("") { "%02x".format(it) }
    logger.lifecycle("${core.name} archive SHA-256: $digest")
    if (core.sha256.isNotEmpty() && !digest.equals(core.sha256, ignoreCase = true)) {
        throw GradleException("${core.name} archive checksum mismatch: expected ${core.sha256}, got $digest")
    }

    delete(core.dir)
    copy {
        from(tarTree(resources.gzip(archive)))
        include(core.include)
        exclude(core.exclude)
        // Strip the leading "name-version/" directory.
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
        into(core.dir)
    }
    marker.writeText(core.marker)
}

ensureSources(mgba)
ensureSources(melonds)

android {
    namespace = "com.joseluixrax.gbaemu"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.joseluixrax.gbaemu"
        minSdk = 26 // Android 8.0: mGBA needs strtof_l from bionic (API 26+)
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.0"

        ndk {
            // Real phones (arm64 / older arm) + x86_64 for emulators and Chromebooks.
            // DS support is built for arm64 and x86_64 only (see src/main/cpp/CMakeLists.txt).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DMGBA_DIR=${mgba.dir.absolutePath}",
                    "-DMELONDS_DIR=${melonds.dir.absolutePath}",
                    // melonDS is C++; its runtime goes inside libndsemu.so.
                    "-DANDROID_STL=c++_static",
                )
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
