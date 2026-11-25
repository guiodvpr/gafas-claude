import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.david.gafas"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.david.gafas"
        // 31 = Android 12: desde ahí existe setCommunicationDevice(), con el que se usa el micrófono de las gafas
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        // En el Developer Mode de Meta AI no se verifica la app, así que bastan ceros.
        // Si algún día la publicas, aquí van las credenciales del Wearables Developer Center.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"

        // Tu S26 Ultra es arm64: así el APK no carga librerías nativas de otras arquitecturas
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Firma fija guardada en el repo: así el APK de la nube y el de tu computador son la
    // "misma app" para Android y se pueden instalar uno encima del otro.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { compose = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    // Interfaz (Jetpack Compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    // SDK de Meta: core (registro, sesión, batería) + cámara
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)

    // Vosk: reconocimiento de voz offline (frase de activación + transcripción local)
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    // Pruebas de la lógica pura: ./gradlew test
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
