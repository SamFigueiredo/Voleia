package com.voleiia

import android.graphics.PointF
import kotlin.math.abs

object Court {
    const val LENGTH = 16f   // metros (linha de fundo a linha de fundo)
    const val WIDTH = 8f     // metros (linha lateral a linha lateral)
}

/** Homografia 3x3 (h33 = 1) resolvida a partir de 4 pontos. */
class Homography private constructor(private val m: DoubleArray, private val sign: Double) {

    fun apply(x: Float, y: Float): PointF? {
        val w = m[6] * x + m[7] * y + 1.0
        if (w * sign < 1e-6) return null // acima do horizonte / inválido
        val px = (m[0] * x + m[1] * y + m[2]) / w
        val py = (m[3] * x + m[4] * y + m[5]) / w
        return PointF(px.toFloat(), py.toFloat())
    }

    companion object {
        fun solve(src: List<PointF>, dst: List<PointF>): Homography? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val u = src[i].x.toDouble()
                val v = src[i].y.toDouble()
                val x = dst[i].x.toDouble()
                val y = dst[i].y.toDouble()
                a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
            }
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (c in col..8) a[r][c] -= f * a[col][c]
                }
            }
            val m = DoubleArray(8) { a[it][8] / a[it][it] }
            val w0 = m[6] * src[0].x + m[7] * src[0].y + 1.0
            return Homography(m, if (w0 >= 0) 1.0 else -1.0)
        }
    }
}

/**
 * Calibração da quadra: 4 cantos tocados na tela (coordenadas normalizadas 0..1), na ordem:
 * distante-esquerdo, distante-direito, próximo-direito, próximo-esquerdo.
 * Mapeia imagem -> metros da quadra (x: 0..16 da esquerda p/ direita; y: 0..8 do lado distante p/ o próximo).
 */
class CourtCalibration(val norm: FloatArray) {
    private var imgToCourt: Homography? = null
    private var courtToImg: Homography? = null
    private var fw = 0
    private var fh = 0

    @Synchronized
    fun prepare(frameW: Int, frameH: Int): Boolean {
        if (frameW == fw && frameH == fh && imgToCourt != null) return true
        fw = frameW
        fh = frameH
        val img = (0..3).map { PointF(norm[it * 2] * frameW, norm[it * 2 + 1] * frameH) }
        val court = listOf(
            PointF(0f, 0f), PointF(Court.LENGTH, 0f),
            PointF(Court.LENGTH, Court.WIDTH), PointF(0f, Court.WIDTH)
        )
        imgToCourt = Homography.solve(img, court)
        courtToImg = Homography.solve(court, img)
        return imgToCourt != null && courtToImg != null
    }

    fun toCourt(x: Float, y: Float): PointF? = imgToCourt?.apply(x, y)
    fun toImage(cx: Float, cy: Float): PointF? = courtToImg?.apply(cx, cy)

    /** Linha da rede (base) projetada na imagem. */
    fun netLine(): Pair<PointF, PointF>? {
        val a = toImage(Court.LENGTH / 2f, 0f) ?: return null
        val b = toImage(Court.LENGTH / 2f, Court.WIDTH) ?: return null
        return a to b
    }

    /** Posição X da rede na imagem para uma dada altura Y da imagem. */
    fun netX(y: Float): Float? {
        val (a, b) = netLine() ?: return null
        if (abs(b.y - a.y) < 1e-3f) return (a.x + b.x) / 2f
        val t = (y - a.y) / (b.y - a.y)
        return a.x + t * (b.x - a.x)
    }
}
