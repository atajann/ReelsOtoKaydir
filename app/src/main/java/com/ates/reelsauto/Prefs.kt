package com.ates.reelsauto

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ayarlar ve günlük sayaçlar */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reels_auto", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun setInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    // --- Ana anahtarlar
    var enabled: Boolean get() = bool("enabled", true); set(v) = setBool("enabled", v)
    var likeEnabled: Boolean get() = bool("like_enabled", true); set(v) = setBool("like_enabled", v)

    // --- Filtreler
    /** Sadece İngilizce videolar izlenir, diğerleri hemen geçilir */
    var englishOnly: Boolean get() = bool("english_only", true); set(v) = setBool("english_only", v)
    /** Dili tespit edilemeyen (yazısız) videoları da geç */
    var skipUnknown: Boolean get() = bool("skip_unknown", true); set(v) = setBool("skip_unknown", v)
    /** Sponsorlu/reklam ürün videolarını da beğen */
    var likeAds: Boolean get() = bool("like_ads", false); set(v) = setBool("like_ads", v)

    // --- Doğal davranış
    var minWatchSec: Int get() = int("min_watch", 3); set(v) = setInt("min_watch", v)
    var maxWatchSec: Int get() = int("max_watch", 25); set(v) = setInt("max_watch", v)
    /** 0-100: tespit edilen bir ürün videosunun beğenilme olasılığı */
    var likeChance: Int get() = int("like_chance", 70); set(v) = setInt("like_chance", v)
    var dailyLikeCap: Int get() = int("daily_cap", 80); set(v) = setInt("daily_cap", v)
    var hourlyLikeCap: Int get() = int("hourly_cap", 20); set(v) = setInt("hourly_cap", v)
    /** Rastgele aralıklarla mola ver (12–35 dk kullanım, 3–12 dk mola) */
    var breaksEnabled: Boolean get() = bool("breaks", true); set(v) = setBool("breaks", v)

    var logEnabled: Boolean get() = bool("log_enabled", true); set(v) = setBool("log_enabled", v)

    var lastProduct: String
        get() = sp.getString("last_product", "") ?: ""
        set(v) = sp.edit().putString("last_product", v).apply()

    // --- Günlük sayaçlar
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun counter(key: String): Int =
        if (sp.getString("day", "") == today()) sp.getInt(key, 0) else 0

    private fun inc(key: String) {
        if (sp.getString("day", "") != today()) {
            // Yeni gün: ayarlara dokunmadan sadece sayaçları sıfırla
            sp.edit()
                .putString("day", today())
                .putInt("c_like", 0).putInt("c_scroll", 0)
                .putInt("c_product", 0).putInt("c_lang_skip", 0)
                .apply()
        }
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    val likesToday get() = counter("c_like")
    val scrollsToday get() = counter("c_scroll")
    val productsToday get() = counter("c_product")
    val langSkipsToday get() = counter("c_lang_skip")

    fun addLike() = inc("c_like")
    fun addScroll() = inc("c_scroll")
    fun addProduct() = inc("c_product")
    fun addLangSkip() = inc("c_lang_skip")
}
