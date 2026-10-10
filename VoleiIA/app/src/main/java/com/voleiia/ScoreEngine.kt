package com.voleiia

import kotlin.math.max

data class MatchConfig(
    val bestOf: Int,
    val pointsSet: Int,
    val pointsDecider: Int,
    val winBy: Int
)

data class MatchState(
    val setsA: Int = 0,
    val setsB: Int = 0,
    val ptsA: Int = 0,
    val ptsB: Int = 0,
    /** false: time A está no lado esquerdo da imagem; true: trocaram de lado. */
    val swapped: Boolean = false,
    val winner: Team? = null,
    val finishedSets: List<Pair<Int, Int>> = emptyList()
) {
    val finished: Boolean get() = winner != null
}

data class PointResult(
    val setWonBy: Team?,
    val matchWonBy: Team?,
    val sideSwitch: Boolean
)

/** Regras de vôlei de praia: set até N (diferença mínima), set decisivo menor, troca de lado a cada N/3 pontos. */
class ScoreEngine(var config: MatchConfig) {
    var state = MatchState()
        private set
    private val undoStack = ArrayDeque<MatchState>()

    fun currentTarget(): Int {
        val setIndex = state.setsA + state.setsB + 1
        return if (config.bestOf > 1 && setIndex == config.bestOf) config.pointsDecider else config.pointsSet
    }

    /** 21 pontos -> troca a cada 7; 15 pontos -> a cada 5. */
    fun switchEvery(): Int = max(1, currentTarget() / 3)

    fun teamOnSide(side: Side): Team =
        if (!state.swapped) (if (side == Side.LEFT) Team.A else Team.B)
        else (if (side == Side.LEFT) Team.B else Team.A)

    fun addPoint(team: Team): PointResult? {
        if (state.finished) return null
        undoStack.addLast(state)
        if (undoStack.size > 300) undoStack.removeFirst()

        var s = state
        s = if (team == Team.A) s.copy(ptsA = s.ptsA + 1) else s.copy(ptsB = s.ptsB + 1)
        val target = currentTarget()
        val a = s.ptsA
        val b = s.ptsB
        val setWon: Team? = when {
            a >= target && a - b >= config.winBy -> Team.A
            b >= target && b - a >= config.winBy -> Team.B
            else -> null
        }

        var sideSwitch = false
        var matchWon: Team? = null
        if (setWon != null) {
            s = s.copy(
                setsA = s.setsA + if (setWon == Team.A) 1 else 0,
                setsB = s.setsB + if (setWon == Team.B) 1 else 0,
                finishedSets = s.finishedSets + (a to b),
                ptsA = 0,
                ptsB = 0
            )
            val need = config.bestOf / 2 + 1
            if (s.setsA >= need) matchWon = Team.A else if (s.setsB >= need) matchWon = Team.B
            if (matchWon != null) {
                s = s.copy(winner = matchWon)
            } else {
                s = s.copy(swapped = !s.swapped) // troca de lado ao fim do set
                sideSwitch = true
            }
        } else if ((a + b) % switchEvery() == 0) {
            s = s.copy(swapped = !s.swapped)
            sideSwitch = true
        }
        state = s
        return PointResult(setWon, matchWon, sideSwitch)
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        state = undoStack.removeLast()
        return true
    }

    fun toggleSides() {
        state = state.copy(swapped = !state.swapped)
    }

    fun reset() {
        state = MatchState()
        undoStack.clear()
    }
}
