package com.voleiia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/** Envolve o detector COCO (EfficientDet-Lite0) e devolve apenas candidatos "sports ball". */
class BallDetector(context: Context) {
    companion object {
        private const val TAG = "BallDetector"
        private const val MODEL = "efficientdet_lite0.tflite"
    }

    private var detector: ObjectDetector? = null
    var available = false
        private set

    init {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                .setMaxResults(8)
                .setScoreThreshold(0.20f)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL, options)
            available = true
        } catch (e: Exception) {
            Log.e(TAG, "Modelo indisponível: ${e.message}")
        }
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
