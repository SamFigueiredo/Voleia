import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voleiia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voleiia.placar"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress.add("tflite")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Detector de objetos (COCO tem a classe "sports ball")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
}

// Baixa o modelo de detecção automaticamente na hora de compilar (precisa de internet).
// Se falhar, coloque manualmente o arquivo em app/src/main/assets/efficientdet_lite0.tflite
val downloadModel by tasks.registering {
    doLast {
        val modelFile = file("src/main/assets/efficientdet_lite0.tflite")
        if (modelFile.exists() && modelFile.length() > 1_000_000) return@doLast
        modelFile.parentFile.mkdirs()
        val urls = listOf(
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite",
            "https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite"
        )
        var ok = false
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.inputStream.use { input -> modelFile.outputStream().use { input.copyTo(it) } }
                if (modelFile.length() > 1_000_000) { ok = true; break }
            } catch (e: Exception) {
                logger.warn("Falha ao baixar modelo de $u: ${e.message}")
            }
        }
        if (!ok) {
            modelFile.delete()
            logger.warn("ATENCAO: modelo nao baixado. O app abrira em modo manual. Veja o README.")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadModel) }
