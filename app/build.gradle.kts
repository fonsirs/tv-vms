plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clave de firma NO está en el repositorio: llega por variables de entorno (secretos de GitHub).
val ksPath: String = System.getenv("KS_PATH") ?: ""

android {
    namespace = "com.fonsi.tvvms"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fonsi.tvvms"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (ksPath.isNotEmpty()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KS_PASS")
                keyAlias = System.getenv("KS_ALIAS")
                keyPassword = System.getenv("KS_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
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
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
