package com.voleiia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File

/** Envolve o detector COCO (EfficientDet-Lite0) e devolve apenas candidatos "sports ball". */
class BallDetector(private val context: Context) {
    companion object {
        private const val TAG = "BallDetector"
        private const val MODEL = "efficientdet_lite0.tflite"
    }

    @Volatile private var detector: ObjectDetector? = null
    @Volatile var available = false
        private set

    init {
        tryLoad()
    }

    /** Modelo baixado pelo próprio app (usado quando não veio embutido no APK). */
    fun modelFile() = File(context.filesDir, MODEL)

    private fun options() = ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
        .setMaxResults(8)
        .setScoreThreshold(0.15f)
        .build()

    @Synchronized
    fun tryLoad(): Boolean {
        if (available) return true
        try {
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL, options())
            available = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Sem modelo nos assets: ${e.message}")
        }
        val f = modelFile()
        if (f.exists() && f.length() > 1_000_000) {
            try {
                detector = ObjectDetector.createFromFileAndOptions(f, options())
                available = true
            } catch (e: Exception) {
                Log.e(TAG, "Modelo baixado inválido: ${e.message}")
                f.delete()
            }
        }
        return available
    }

    /** Detecta dentro de [region] (coordenadas do frame completo) e devolve candidatos em coordenadas do frame. */
    fun detect(frame: Bitmap, region: Rect): List<Candidate> {
        val d = detector ?: return emptyList()
        val l = region.left.coerceIn(0, frame.width - 1)
        val t = region.top.coerceIn(0, frame.height - 1)
        val w = region.width().coerceAtMost(frame.width - l)
        val h = region.height().coerceAtMost(frame.height - t)
        if (w < 16 || h < 16) return emptyList()

        val crop = if (l == 0 && t == 0 && w == frame.width && h == frame.height) frame
        else Bitmap.createBitmap(frame, l, t, w, h)
        val out = ArrayList<Candidate>()
        try {
            val results = d.detect(TensorImage.fromBitmap(crop))
            for (det in results) {
                val cat = det.categories.firstOrNull() ?: continue
                if (!cat.label.contains("ball", ignoreCase = true)) continue
                val b = det.boundingBox
                out.add(Candidate(l + b.centerX(), t + b.centerY(), b.width(), b.height(), cat.score))
            }
        } finally {
            if (crop !== frame) crop.recycle()
        }
        return out
    }
}
