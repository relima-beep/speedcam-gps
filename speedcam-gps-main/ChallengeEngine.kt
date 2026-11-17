package com.speedcam.gps

import android.content.Context
import kotlin.random.Random

/** Offline challenges: scored only from completed recordings; no speed-based rewards. */
class ChallengeEngine(context: Context) {
    private val prefs = context.getSharedPreferences("speedcam_challenges", Context.MODE_PRIVATE)
    private data class Goal(val title: String, val description: String, val xp: Int,
                            val passes: (Double, Long, Boolean) -> Boolean)
    private val goals = listOf(
        Goal("Första kilometern", "Kör 1 km", 50) { km, _, _ -> km >= 1.0 },
        Goal("Upptäcktsresande", "Kör 5 km", 100) { km, _, _ -> km >= 5.0 },
        Goal("Filmstjärnan", "Filma minst 5 minuter", 75) { _, sec, _ -> sec >= 300 },
        Goal("Maratonfilmaren", "Filma minst 20 minuter", 150) { _, sec, _ -> sec >= 1200 },
        Goal("Dubbelagenten", "Filma 10 minuter med dubbelkamera", 200) { _, sec, dual -> dual && sec >= 600 },
        Goal("GPS-mästaren", "Kör 10 km", 250) { km, _, _ -> km >= 10.0 }
    )
    private var active = listOf<Int>()
    fun startSession() { active = goals.indices.shuffled(Random.Default).take(3) }
    fun activeDescription(): String = if (active.isEmpty()) "Starta en inspelning för tre slumpade mål" else
        active.joinToString("\n") { "• ${goals[it].title}: ${goals[it].description} (+${goals[it].xp} XP)" }
    fun totalXp(): Int = prefs.getInt("total_xp", 0)
    fun finishSession(meters: Double, seconds: Long, dual: Boolean): String {
        val km = meters / 1000.0
        val wins = active.map { goals[it] }.filter { it.passes(km, seconds, dual) }
        val earned = wins.sumOf { it.xp }
        val total = (totalXp().toLong() + earned).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        prefs.edit().putInt("total_xp", total).apply()
        active = emptyList()
        return "Den här turen: +$earned XP\nTotalt: $total XP\n" +
            (if (wins.isEmpty()) "Inga mål avklarade denna gång" else wins.joinToString("\n") { "✓ ${it.title}" })
    }
}
