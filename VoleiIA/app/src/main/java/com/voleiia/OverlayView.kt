package com.voleiia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Desenha a calibração da quadra, rede, trilha da bola e captura os toques de calibração. */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    @Volatile var frameW = 1280
    @Volatile var frameH = 720
    @Volatile var calibNorm: FloatArray? = null
    @Volatile var netLine: Pair<PointF, PointF>? = null
    @Volatile var trail: List<BallObs> = emptyList()
    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var showDebug = false

    var calibrating = false
    var calibPoints: List<PointF> = emptyList() // normalizados 0..1
    var onCalibTap: ((Float, Float) -> Unit)? = null

    private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 230, 118); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val netPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 200, 255); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.FILL
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 152, 0); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f; isFakeBoldText = true
    }

    private fun scale() = min(width / frameW.toFloat(), height / frameH.toFloat())
    private fun ox() = (width - frameW * scale()) / 2f
    private fun oy() = (height - frameH * scale()) / 2f
    private fun vx(x: Float) = ox() + x * scale()
    private fun vy(y: Float) = oy() + y * scale()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrating) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val nx = (event.x - ox()) / scale() / frameW
            val ny = (event.y - oy()) / scale() / frameH
            if (nx in 0f..1f && ny in 0f..1f) onCalibTap?.invoke(nx, ny)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        calibNorm?.let { n ->
            val path = Path()
            for (i in 0..3) {
                val x = vx(n[i * 2] * frameW)
                val y = vy(n[i * 2 + 1] * frameH)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, quadPaint)
        }
        netLine?.let { (a, b) ->
            canvas.drawLine(vx(a.x), vy(a.y), vx(b.x), vy(b.y), netPaint)
        }

        val t = trail
        for ((i, o) in t.withIndex()) {
            ballPaint.alpha = 60 + (195 * i / maxOf(1, t.size)).toInt()
            canvas.drawCircle(vx(o.x), vy(o.y), 6f, ballPaint)
        }
        if (showDebug) {
            for (c in candidates) {
                canvas.drawRect(
                    vx(c.x - c.w / 2), vy(c.y - c.h / 2), vx(c.x + c.w / 2), vy(c.y + c.h / 2), boxPaint
                )
            }
        }

        if (calibrating) {
            for ((i, p) in calibPoints.withIndex()) {
                val x = vx(p.x * frameW)
                val y = vy(p.y * frameH)
                canvas.drawCircle(x, y, 12f, pointPaint)
                canvas.drawText("${i + 1}", x + 16f, y - 12f, textPaint)
            }
        }
    }
}
