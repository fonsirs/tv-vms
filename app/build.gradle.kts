plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clave de firma NO está en el repositorio: llega por variables de entorno (secretos de GitHub).
val ksPath: String = System.getenv("KS_PATH") ?: ""

android {
    namespace = "com.fonsi.tvvms"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fonsi.tvvms"
        minSdk = 23
        targetSdk = 35
        versionCode = 16
        versionName = "1.15"
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
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    // El módulo RTSP de Media3 va incluido en el código (androidx/media3/exoplayer/rtsp) con un parche
    // para cámaras que no envían sprop-parameter-sets. Necesita estas librerías:
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-container:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.annotation:annotation:1.8.2")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.31.0")
    implementation("androidx.media3:media3-ui:$media3")
}
