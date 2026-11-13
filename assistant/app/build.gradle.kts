import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val secretProps: Properties = Properties().apply {
    val env = System.getenv("LOCAL_PROPERTIES")
    if (!env.isNullOrBlank()) {
        load(ByteArrayInputStream(Base64.getMimeDecoder().decode(env)))
    } else {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
}
fun secret(name: String): String = secretProps.getProperty(name) ?: ""

val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "tech.izi20.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.izi20.assistant"
        minSdk = 24
        targetSdk = 35
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")
        base.archivesName.set("Izi-${versionName}")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = secret("KEYSTORE_PASS")
            keyAlias = secret("ALIAS_NAME")
            keyPassword = secret("ALIAS_PASS")
            // Старая подпись v1 нужна установщикам Realme/OPPO/Xiaomi, иначе «не удалось проанализировать APK»
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (secret("KEYSTORE_PASS").isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // «Привет, Изи»: офлайн-распознавание Vosk (Apache 2.0)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
