import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.bongbee.apkurl"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bongbee.apkurl"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.3.0"
        buildConfigField("String", "GITHUB_OWNER", "\"seangritthy\"")
        buildConfigField("String", "GITHUB_REPO", "\"apkurl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Prefer local indra.jks; fall back to decoding the committed base64 copy (used in CI)
            val resolvedKeystoreFile: File = run {
                val localFile = file(System.getenv("STORE_FILE") ?: keystoreProps.getProperty("STORE_FILE") ?: "indra.jks")
                if (localFile.exists()) {
                    localFile
                } else {
                    val b64File = file("indra.jks.b64")
                    val decoded = file("${layout.buildDirectory.get()}/ci_keystore.jks")
                    if (b64File.exists()) {
                        decoded.parentFile.mkdirs()
                        decoded.writeBytes(Base64.getDecoder().decode(b64File.readText().trim()))
                        decoded
                    } else {
                        localFile // will fail at signing time if neither exists
                    }
                }
            }
            storeFile = resolvedKeystoreFile
            storePassword = System.getenv("STORE_PASSWORD") ?: keystoreProps.getProperty("STORE_PASSWORD") ?: "indra2026"
            keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProps.getProperty("KEY_ALIAS") ?: "indra"
            keyPassword = System.getenv("KEY_PASSWORD") ?: keystoreProps.getProperty("KEY_PASSWORD") ?: "indra2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val releaseDir = file("${layout.buildDirectory.get()}/outputs/apk/release")
            val original = File(releaseDir, "app-release.apk")
            val renamed = File(releaseDir, "apkurl.apk")
            if (original.exists()) {
                original.copyTo(renamed, overwrite = true)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlin.coroutines)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}