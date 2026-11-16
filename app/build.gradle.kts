import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.staad.island"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.staad.island"
        minSdk = 28
        targetSdk = 36
        // Release CI passes the tag through, so artefacts cannot share a version.
        // Local builds keep working without any Gradle properties.
        versionCode = (findProperty("island.versionCode") as String? ?: "1").toInt()
        versionName = findProperty("island.versionName") as String? ?: "1.0-dev"
    }

    signingConfigs {
        create("release") {
            // Keystore passwords live in local.properties (gitignored) or an env var,
            // never in version control. Fallback "android" only so the block parses;
            // a real release build must supply the password one of those two ways.
            val props = Properties()
            val localFile = rootProject.file("local.properties")
            if (localFile.exists()) {
                FileInputStream(localFile).use { props.load(it) }
            }
            val storePass = props.getProperty("island.release.storePassword",
                System.getenv("ISLAND_RELEASE_STORE_PASSWORD") ?: "android")
            val keyPass = props.getProperty("island.release.keyPassword",
                System.getenv("ISLAND_RELEASE_KEY_PASSWORD") ?: "android")
            val keyAlias = props.getProperty("island.release.keyAlias",
                System.getenv("ISLAND_RELEASE_KEY_ALIAS") ?: "release-key")
            storeFile = file("release-keystore.jks")
            storePassword = storePass
            this.keyAlias = keyAlias
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// No runtime dependencies: pure platform APIs + Kotlin stdlib.
dependencies {
    // Unit tests only; nothing is shipped in the APK.
    testImplementation("junit:junit:4.13.2")
}
