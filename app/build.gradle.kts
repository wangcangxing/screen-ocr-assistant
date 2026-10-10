import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放 local.properties（不入库）；缺了就退回 debug 签名，保证任何机器上都能构建
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = localProps.getProperty("release.storeFile")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.dsh.screenocr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.screenocr"
        // takeScreenshot() 是 API 30 引入的，minSdk 必须 >= 30
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 端侧中文 + 拉丁文字 OCR，模型随 APK 打包，离线可用。
    // 版本来自 https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/maven-metadata.xml
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
