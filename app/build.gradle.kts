import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseVersionName = (findProperty("versionNameOverride") as String?)?.takeIf { it.isNotBlank() }
val releaseVersionCode = (findProperty("versionCodeOverride") as String?)?.toIntOrNull()

fun signingValue(env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() }

val keystoreFile = signingValue("KEYSTORE_FILE")
val keystorePassword = signingValue("KEYSTORE_PASSWORD")
val keystoreAlias = signingValue("KEY_ALIAS") ?: keystoreFile?.let { path ->
    val f = file(path)
    if (f.isFile) {
        KeyStore.getInstance("PKCS12").run {
            f.inputStream().use { load(it, keystorePassword?.toCharArray()) }
            aliases().toList().firstOrNull()
        }
    } else null
}

android {
    namespace = "com.lcebot.ferryqr"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lcebot.ferryqr"
        minSdk = 30
        targetSdk = 37
        versionCode = releaseVersionCode ?: 100
        versionName = releaseVersionName ?: "0.1.0"

        // Applies to every native library in the APK: OpenCV, zxing-cpp, CameraX and AndroidX.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreAlias != null) create("release") {
            storeFile = file(keystoreFile!!)
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            keyPassword = signingValue("KEY_PASSWORD") ?: keystorePassword
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            if (keystoreAlias != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Every bundled native library ships already stripped, so there is nothing left to strip.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    implementation(libs.zxing.cpp)
    implementation(libs.zxing.core)
    implementation(libs.wechat.opencv)
    implementation(libs.wechat.opencv.arm64)
    implementation(libs.wechat.qrcode)
}
