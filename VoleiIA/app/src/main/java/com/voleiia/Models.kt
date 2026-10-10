package com.voleiia

enum class Team {
    A, B;
    fun other() = if (this == A) B else A
}

/** Lado físico da quadra visto pela câmera (esquerda/direita da imagem). */
enum class Side {
    LEFT, RIGHT;
    fun other() = if (this == LEFT) RIGHT else LEFT
}

data class BallObs(
    val tMs: Long,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val score: Float
) {
    val bottomY: Float get() = y + h / 2f
}

data class Candidate(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val score: Float
)

data class LandingEvent(
    val tMs: Long,
    val courtX: Float,
    val courtY: Float,
    val inside: Boolean,
    val landingSide: Side,
    val scoringSide: Side,
    val confidence: Float,
    val crossings: Int,
    val rallyStartMs: Long,
    val seenSettle: Boolean
)
