package com.voleiia

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("volei_ia", Context.MODE_PRIVATE)

    private fun intPref(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    private fun floatPref(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            sp.edit().putFloat(key, value).apply()
        }
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun strPref(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    // Regras do jogo
    var bestOf by intPref("bestOf", 3)               // 1, 3 ou 5 sets
    var pointsSet by intPref("pointsSet", 21)
    var pointsDecider by intPref("pointsDecider", 15)
    var winBy by intPref("winBy", 2)
    var teamA by strPref("teamA", "Time A")
    var teamB by strPref("teamB", "Time B")

    // IA
    /** 0 = sempre automático; 1 = sempre pede confirmação. */
    var confThreshold by floatPref("confThreshold", 0.70f)

    // Replay
    var replayEnabled by boolPref("replayEnabled", true)
    var replayMinCrossings by intPref("replayMinCrossings", 3) // 0 = todos os pontos
    var replayBeforeSec by intPref("replayBeforeSec", 10)
    var slowFactor by floatPref("slowFactor", 0.5f)

    var debug by boolPref("debug", false)

    var calib: FloatArray?
        get() = sp.getString("calib", null)
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 8 }
            ?.toFloatArray()
        set(v) {
            sp.edit().putString("calib", v?.joinToString(",")).apply()
        }

    fun matchConfig() = MatchConfig(bestOf, pointsSet, pointsDecider, winBy)
}
