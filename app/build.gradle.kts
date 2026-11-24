plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val stableStoreFile = System.getenv("SENE_KEYSTORE_PATH")
val stableStorePassword = System.getenv("SENE_STORE_PASSWORD")
val stableKeyAlias = System.getenv("SENE_KEY_ALIAS")
val stableKeyPassword = System.getenv("SENE_KEY_PASSWORD")
val hasStableSigning = !stableStoreFile.isNullOrBlank()

if (hasStableSigning) {
    require(!stableStorePassword.isNullOrBlank()) { "Falta SENE_STORE_PASSWORD para la firma permanente." }
    require(!stableKeyAlias.isNullOrBlank()) { "Falta SENE_KEY_ALIAS para la firma permanente." }
    require(!stableKeyPassword.isNullOrBlank()) { "Falta SENE_KEY_PASSWORD para la firma permanente." }
}

android {
    namespace = "com.sene.ventas"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sene.ventas"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }
    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(stableStoreFile!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
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
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
