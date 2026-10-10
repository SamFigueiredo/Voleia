package com.voleiia

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Buffer circular em memória com os últimos ~30 s de vídeo (frames JPEG 640 px), para os replays. */
class ReplayBuffer(
    private val maxMs: Long = 30_000L,
    private val minIntervalMs: Long = 66L // ~15 fps
) {
    class Frame(val tMs: Long, val jpeg: ByteArray)

    private val frames = ArrayDeque<Frame>()
    private var lastAdded = Long.MIN_VALUE

    @Synchronized
    fun add(tMs: Long, bmp: Bitmap) {
        if (tMs - lastAdded < minIntervalMs) return
        lastAdded = tMs
        val w = 640
        val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream(40_000)
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (small !== bmp) small.recycle()
        frames.addLast(Frame(tMs, out.toByteArray()))
        while (frames.isNotEmpty() && tMs - frames.first().tMs > maxMs) frames.removeFirst()
    }

    @Synchronized
    fun clip(fromMs: Long, toMs: Long): List<Frame> = frames.filter { it.tMs in fromMs..toMs }
}
