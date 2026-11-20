package com.ates.reelsauto

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/**
 * İnsan davranışına benzeyen rastgele zamanlamalar.
 * Düz (uniform) rastgelelik yerine log-normal dağılım kullanılır:
 * çoğu değer ortalama civarında, ara sıra çok kısa ya da çok uzun.
 */
object HumanTiming {
    private val rnd = Random()

    fun between(a: Long, b: Long): Long = if (b <= a) a else a + (rnd.nextDouble() * (b - a)).toLong()

    fun chance(p: Double): Boolean = rnd.nextDouble() < p

    fun logNormal(medianMs: Double, sigma: Double, minMs: Long, maxMs: Long): Long {
        val v = exp(ln(medianMs) + sigma * rnd.nextGaussian())
        return v.toLong().coerceIn(minMs, maxMs)
    }

    /** Yeni Reels yüklendikten sonra ekranın oturması için bekleme */
    fun settleMs(): Long = between(700, 1700)

    /** İngilizce olmayan videoyu geçmeden önceki kısa, değişken tepki süresi */
    fun langSkipDelayMs(): Long = logNormal(800.0, 0.4, 350, 2200)

    /** Bir Reels'in toplam izlenme süresi */
    fun watchMs(minSec: Int, maxSec: Int, product: Boolean): Long {
        val min = minSec * 1000L
        val max = maxOf(maxSec * 1000L, min + 1000)
        // Ara sıra hızlı geçiş (ürün videolarında daha nadir)
        if (chance(if (product) 0.03 else 0.10)) return between(min / 2 + 300, min)
        // Ara sıra beklenenden uzun izleme / tekrar izleme
        if (chance(0.05)) return between(max, (max * 1.6).toLong())
        val median = min + (max - min) * (if (product) 0.55 else 0.30)
        return logNormal(median, 0.45, min, max)
    }

    /** Beğeninin izleme süresinin hangi anında yapılacağı (oran) */
    fun likeFraction(): Double = 0.35 + rnd.nextDouble() * 0.5

    /** Kesintisiz kullanım süresi ve mola süresi */
    fun sessionMs(): Long = between(12 * 60_000L, 35 * 60_000L)
    fun breakMs(): Long = between(3 * 60_000L, 12 * 60_000L)

    fun nextInt(from: Int, until: Int): Int = from + rnd.nextInt(maxOf(1, until - from))
    fun nextFloat(from: Float, until: Float): Float = from + rnd.nextFloat() * (until - from)
}
