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
        versionCode = 4
        versionName = "1.1.2"
        buildConfigField("String", "GITHUB_OWNER", "\"seangritthy\"")
        buildConfigField("String", "GITHUB_REPO", "\"apkurl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("STORE_FILE") ?: "indra.jks")
            storePassword = keystoreProps.getProperty("STORE_PASSWORD") ?: ""
            keyAlias = keystoreProps.getProperty("KEY_ALIAS") ?: ""
            keyPassword = keystoreProps.getProperty("KEY_PASSWORD") ?: ""
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlin.coroutines)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}