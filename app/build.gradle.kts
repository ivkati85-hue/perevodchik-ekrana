plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.perevod.ekran"
    compileSdk = 34
    defaultConfig {
        applicationId = "ru.perevod.ekran"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // постоянный ключ подписи — чтобы обновления ставились поверх старой версии
    signingConfigs {
        create("app") {
            storeFile = rootProject.file("signing/app.keystore")
            storePassword = "perevod123"
            keyAlias = "app"
            keyPassword = "perevod123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("app") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // распознавание китайского текста, модель встроена в приложение (работает без интернета)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // перевод на телефоне; словарь качается один раз, дальше без интернета
    implementation("com.google.mlkit:translate:17.0.3")
}
