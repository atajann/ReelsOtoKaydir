package com.ates.reelsauto

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * İnsan davranışı modeli.
 * - Log-normal süreler: çoğu ortalama civarında, ara sıra çok kısa / çok uzun
 * - Ruh hali (mood): birkaç dakikada bir değişir ve izleme temposunu etkiler
 *   (odaklı = uzun izler, gezinme = normal, hızlı = çabuk geçer)
 */
object HumanTiming {
    private val rnd = Random()

    enum class Mood(val factor: Double, val label: String) {
        FOCUSED(1.35, "odaklı"), BROWSING(1.0, "gezinme"), FAST(0.6, "hızlı")
    }

    var mood: Mood = Mood.BROWSING
        private set
    private var moodUntil = 0L

    fun updateMood(now: Long) {
        if (now < moodUntil) return
        val r = rnd.nextDouble()
        mood = when {
            r < 0.25 -> Mood.FOCUSED
            r < 0.75 -> Mood.BROWSING
            else -> Mood.FAST
        }
        moodUntil = now + between(3 * 60_000L, 9 * 60_000L)
    }

    fun between(a: Long, b: Long): Long = if (b <= a) a else a + (rnd.nextDouble() * (b - a)).toLong()
    fun chance(p: Double): Boolean = rnd.nextDouble() < p
    fun nextInt(from: Int, until: Int): Int = from + rnd.nextInt(maxOf(1, until - from))
    fun nextFloat(from: Float, until: Float): Float = from + rnd.nextFloat() * (until - from)
    fun nextDouble(from: Double, until: Double): Double = from + rnd.nextDouble() * (until - from)

    fun logNormal(medianMs: Double, sigma: Double, minMs: Long, maxMs: Long): Long {
        val v = exp(ln(maxOf(medianMs, 1.0)) + sigma * rnd.nextGaussian())
        return v.toLong().coerceIn(minMs, maxMs)
    }

    /** Yeni Reels yüklendikten sonra gözün ekrana oturması */
    fun settleMs(): Long = logNormal(1100.0, 0.3, 600, 2000)

    /** İngilizce olmayan videoyu fark edip geçme süresi */
    fun langSkipDelayMs(): Long = logNormal(900.0 * sqrt(mood.factor), 0.45, 350, 2800)

    /** Bir Reels'in izlenme süresi */
    fun watchMs(minSec: Int, maxSec: Int, product: Boolean): Long {
        val min = minSec * 1000L
        val max = maxOf(maxSec * 1000L, min + 1000)
        if (chance(if (product) 0.03 else 0.12)) return between(min / 2 + 300, min + 800)   // sıkıldı, hemen geçti
        if (chance(if (product) 0.10 else 0.04)) return between(max, (max * 1.7).toLong())  // dalıp gitti
        val base = min + (max - min) * (if (product) 0.55 else 0.28)
        val median = (base * mood.factor).coerceIn(min.toDouble(), max.toDouble())
        return logNormal(median, 0.5, min, max)
    }

    fun likeFraction(): Double = nextDouble(0.30, 0.85)
    fun followFraction(): Double = nextDouble(0.60, 0.92)

    /** Kaydırma hareketinin süresi (çoğu hızlı fiske, ara sıra yavaş) */
    fun swipeDurationMs(): Long = if (chance(0.1)) between(450, 750) else logNormal(280.0, 0.25, 170, 440)

    /** Telefondan başını kaldırma: kısa dikkat dağınıklığı */
    fun distractionMs(): Long = between(5_000, 25_000)

    fun sessionMs(): Long = between(12 * 60_000L, 35 * 60_000L)
    fun breakMs(): Long = between(3 * 60_000L, 12 * 60_000L)
}
