package com.voleiia

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Rastreia a bola quadro a quadro, conta travessias da rede e detecta o momento em que a bola
 * toca a areia (queda -> contato -> "assentou" ou sumiu), calculando a confiança da decisão.
 *
 * Todos os limiares estão em [Companion] para facilitar o ajuste fino com vídeos reais.
 */
class BallTracker {
    companion object {
        const val START_SCORE = 0.20f      // confiança mínima para iniciar uma trilha
        const val CONFIRM_OBS = 4          // observações para confirmar que é a bola (e não algo parado)
        const val LOST_DROP_MS = 900L      // sem detecção por tanto tempo -> descarta trilha
        const val LOST_LANDING_MS = 600L   // bola sumiu perto do chão -> assume pouso (confiança menor)
        const val SETTLE_MS = 250L         // bola "assentou" na areia por tanto tempo -> pouso confirmado
        const val COOLDOWN_MS = 3500L      // ignora detecções logo após marcar um pouso
        const val MIN_VY = 80f             // px/s (em 1280 de largura): velocidade de queda mínima
        const val RISE_MULT = 2.5f         // subida > RISE_MULT x altura da bola = defesa/quique (não é pouso)
    }

    private enum class Phase { FLIGHT, CONTACT }
    private class Spot(val x: Float, val y: Float, val until: Long)

    private val track = ArrayList<BallObs>()
    private var confirmed = false
    private var phase = Phase.FLIGHT
    private var lowest: BallObs? = null
    private var cooldownUntil = 0L
    private val ignoreSpots = ArrayList<Spot>()
    private var lastSideSign = 0
    private var lastCrossTo: Side? = null

    @Volatile var crossings = 0
        private set
    @Volatile var rallyStartMs = 0L
        private set

    @Synchronized
    fun predict(tMs: Long): PointF? {
        if (track.isEmpty()) return null
        return predictInternal(tMs)
    }

    private fun predictInternal(tMs: Long): PointF {
        val n = track.size
        val last = track[n - 1]
        if (n < 2) return PointF(last.x, last.y)
        val p = track[max(0, n - 3)]
        val dt = max(1L, last.tMs - p.tMs)
        val vx = (last.x - p.x) / dt
        val vy = (last.y - p.y) / dt
        val ahead = (tMs - last.tMs).coerceIn(0L, 400L)
        return PointF(last.x + vx * ahead, last.y + vy * ahead)
    }

    /** 0 = procurando, 1 = possível bola (ainda não confirmada), 2 = bola rastreada. */
    @Synchronized
    fun state(): Int = when {
        track.isEmpty() -> 0
        !confirmed -> 1
        else -> 2
    }

    @Synchronized
    fun lastObs(): BallObs? = track.lastOrNull()

    @Synchronized
    fun snapshotTrail(): List<BallObs> = ArrayList(track.takeLast(40))

    @Synchronized
    fun resetRally() {
        crossings = 0
        rallyStartMs = 0L
        lastCrossTo = null
        lastSideSign = 0
    }

    private fun resetTrack() {
        track.clear()
        confirmed = false
        phase = Phase.FLIGHT
        lowest = null
    }

    private fun obsOf(tMs: Long, c: Candidate) = BallObs(tMs, c.x, c.y, c.w, c.h, c.score)

    @Synchronized
    fun update(tMs: Long, cands: List<Candidate>, cal: CourtCalibration, frameW: Int): LandingEvent? {
        val scale = frameW / 1280f
        if (tMs < cooldownUntil) return null
        ignoreSpots.removeAll { it.until < tMs }

        if (track.isEmpty()) {
            val best = cands
                .filter { c -> ignoreSpots.none { hypot(c.x - it.x, c.y - it.y) < 30f * scale } }
                .maxByOrNull { it.score }
            if (best != null && best.score >= START_SCORE) track.add(obsOf(tMs, best))
            return null
        }

        val last = track.last()
        val pred = predictInternal(tMs)
        val sinceLast = tMs - last.tMs
        val gate = (90f + 0.25f * min(sinceLast, 600L)) * scale
        val match = cands
            .filter { hypot(it.x - pred.x, it.y - pred.y) <= gate }
            .minByOrNull { hypot(it.x - pred.x, it.y - pred.y) - 40f * it.score }

        if (match != null) {
            track.add(obsOf(tMs, match))
            if (track.size > 150) track.removeAt(0)
            if (!confirmed) {
                val first = track.first()
                val cur = track.last()
                if (track.size >= CONFIRM_OBS && hypot(cur.x - first.x, cur.y - first.y) > 20f * scale) {
                    confirmed = true
                    if (rallyStartMs == 0L) rallyStartMs = first.tMs
                } else if (track.size >= 8) {
                    // Parado demais: provavelmente um objeto estático parecido com bola.
                    ignoreSpots.add(Spot(cur.x, cur.y, tMs + 30_000))
                    resetTrack()
                    return null
                }
            }
            if (confirmed) updateCrossings(cal, scale)
        } else if (!confirmed && sinceLast > 500) {
            resetTrack()
            return null
        }

        if (!confirmed) return null
        val ev = checkLanding(tMs, cal, scale)
        if (ev != null) return ev
        if (track.isNotEmpty() && tMs - track.last().tMs > LOST_DROP_MS) resetTrack()
        return null
    }

    private fun updateCrossings(cal: CourtCalibration, scale: Float) {
        val last = track.last()
        val net = cal.netX(last.y) ?: return
        val diff = last.x - net
        val hyst = 12f * scale
        val s = if (diff > hyst) 1 else if (diff < -hyst) -1 else 0
        if (s != 0) {
            if (lastSideSign != 0 && s != lastSideSign) {
                crossings++
                lastCrossTo = if (s > 0) Side.RIGHT else Side.LEFT
            }
            lastSideSign = s
        }
    }

    private fun checkLanding(tMs: Long, cal: CourtCalibration, scale: Float): LandingEvent? {
        val n = track.size
        val cur = track[n - 1]
        val seenNow = cur.tMs == tMs
        val ref = track[max(0, n - 4)]
        val dt = max(1L, cur.tMs - ref.tMs)
        val vy = (cur.y - ref.y) * 1000f / dt // px/s, positivo = descendo
        val pt = cal.toCourt(cur.x, cur.bottomY)
        // "Faixa do chão": ponto projetado no plano da areia, dentro da quadra + margem.
        val inBand = pt != null && pt.x in -5f..21f && pt.y in -4f..12f

        when (phase) {
            Phase.FLIGHT -> {
                if (seenNow && inBand && vy > MIN_VY * scale) {
                    phase = Phase.CONTACT
                    lowest = cur
                }
            }
            Phase.CONTACT -> {
                val lw0 = lowest
                if (lw0 == null) {
                    phase = Phase.FLIGHT
                    return null
                }
                if (seenNow) {
                    if (cur.y > lw0.y) lowest = cur
                    val low = lowest!!
                    val rise = low.y - cur.y
                    if (rise > max(25f * scale, RISE_MULT * low.h)) {
                        // Bola voltou a subir bastante: defesa/ataque, não é pouso.
                        phase = Phase.FLIGHT
                        lowest = null
                        return null
                    }
                    if (tMs - low.tMs >= SETTLE_MS) return finishLanding(tMs, cal, true)
                } else if (tMs - cur.tMs >= LOST_LANDING_MS) {
                    return finishLanding(tMs, cal, false)
                }
            }
        }
        return null
    }

    private fun finishLanding(tMs: Long, cal: CourtCalibration, seen: Boolean): LandingEvent? {
        val lw = lowest
        val pt = if (lw != null) cal.toCourt(lw.x, lw.bottomY) else null
        if (lw == null || pt == null) {
            phase = Phase.FLIGHT
            lowest = null
            return null
        }
        val inside = pt.x in 0f..Court.LENGTH && pt.y in 0f..Court.WIDTH
        val margin = min(min(pt.x, Court.LENGTH - pt.x), min(pt.y, Court.WIDTH - pt.y)) // + dentro, - fora

        val recent = track.filter { it.tMs <= lw.tMs && it.tMs >= lw.tMs - 1000 }
        val continuity = min(1f, recent.size / 6f)
        val detMean = if (recent.isEmpty()) 0.3f else recent.map { it.score }.average().toFloat()
        // Distância até a linha mais próxima (dentro ou fora): perto da linha = menos certeza.
        val marginScore = min(1f, abs(margin) / 0.5f)

        var conf = 0.35f * continuity + 0.35f * marginScore + 0.30f * min(1f, detMean / 0.6f)
        if (!seen) conf *= 0.7f

        val landingSide = if (pt.x < Court.LENGTH / 2f) Side.LEFT else Side.RIGHT
        val scoring = if (inside || crossings == 0) landingSide.other() else (lastCrossTo ?: landingSide.other())

        val ev = LandingEvent(
            tMs = lw.tMs,
            courtX = pt.x,
            courtY = pt.y,
            inside = inside,
            landingSide = landingSide,
            scoringSide = scoring,
            confidence = conf.coerceIn(0f, 1f),
            crossings = crossings,
            rallyStartMs = rallyStartMs,
            seenSettle = seen
        )
        resetTrack()
        cooldownUntil = tMs + COOLDOWN_MS
        return ev
    }
}
