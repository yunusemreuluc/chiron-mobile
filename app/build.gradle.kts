import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kişisel kurulum değerleri — git'e girmez (bkz. .gitignore). Uygulama içindeki
// Ayarlar ekranından değiştirilebilir; buradakiler yalnızca ilk açılış varsayılanı.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.airon.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airon.mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_GEMINI_KEY", "\"${secrets.getProperty("GEMINI_API_KEY", "")}\"")
        // onnxruntime'ın yerel kütüphanesi ABI başına ~MB'larca; telefon arm64.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "DEFAULT_PC_URL", "\"${secrets.getProperty("PC_URL", "")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        // Telefona kurulan sürüm. Mağazaya gitmediği için debug anahtarıyla
        // imzalanıyor; R8 küçültmesi açık — material-icons-extended tek başına
        // debug APK'yı 55 MB yapıyordu.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // "Aıron" uyandırma sözü: openWakeWord modelleri (airon-wake projesinde eğitildi).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
}
