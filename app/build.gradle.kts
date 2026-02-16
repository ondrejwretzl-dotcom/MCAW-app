plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mcaw.app" // uprav dle svého
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mcaw.app" // uprav dle svého (com.mcaw.app)
        minSdk = 26
        targetSdk = 34
        // Pro update APK musí versionCode růst. V CI používáme GITHUB_RUN_NUMBER.
        // Lokálně zůstává default 1.
        val vc = (System.getenv("VERSION_CODE")
            ?: System.getenv("GITHUB_RUN_NUMBER")
            ?: "1").toInt()
        versionCode = vc
        versionName = System.getenv("VERSION_NAME") ?: "1.0.$vc"
        buildConfigField("String", "BUILD_ID", "\"${System.currentTimeMillis()}\"")
    }

    // Release signing: nutné pro to, aby šla aplikace aktualizovat bez odinstalace.
    // Keystore dodá CI (GitHub Secrets) a workflow ho před buildem uloží do app/mcaw-release.keystore.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("MCAW_KEYSTORE_PATH") ?: "mcaw-release.keystore"
            storeFile = file(ksPath)
            storePassword = System.getenv("MCAW_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MCAW_KEY_ALIAS")
            keyPassword = System.getenv("MCAW_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Pokud CI neposkytne proměnné, release build spadne (správně).
            signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")

    val camerax_version = "1.3.1"

    // CameraX (nutné)
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // Příklad – ponech dle svého projektu
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    
    // AI modely
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3")
    implementation("org.tensorflow:tensorflow-lite:2.13.0")

}
