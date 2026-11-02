import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// clave HMAC del login y tabla XOR del QR de Yi: las genera tools/extract_sdk.sh, no va en git
val yi = Properties().apply { rootProject.file("yi.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.metalsyntax.yihomelocal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.metalsyntax.yihomelocal"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "0.7.0"
        buildConfigField("String", "YI_LOGIN_KEY", "\"${yi.getProperty("YI_LOGIN_KEY", "")}\"")
        buildConfigField("String", "YI_PAIR_XOR", "\"${yi.getProperty("YI_PAIR_XOR", "")}\"")
        // las .so del SDK de Yi solo están en arm64 (jniLibs/arm64-v8a); armeabi-v7a deja instalar en tablets de 32 bits
        // (p. ej. Fire 7): ahí abre la app, la nube y la IA, pero el video P2P necesita las .so de Yi para v7a
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // el jar del SDK viene de dex2jar con clases faltantes a propósito: R8 no lo tocaría bien
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    // el modelo de IA se mapea en memoria: no comprimirlo dentro del APK
    androidResources { noCompress += "tflite" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // SDK P2P de Yi Home (tools/extract_sdk.sh)
    implementation(files("libs/yisdk.jar"))
    // QR de emparejamiento que lee la cámara
    implementation("com.google.zxing:core:3.5.3")
    // detección de personas/vehículos/paquetes en el teléfono (EfficientDet-Lite0 en assets)
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // leer la etiqueta QR pegada en la cámara (escáner de Google Play services: sin permiso de cámara)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
