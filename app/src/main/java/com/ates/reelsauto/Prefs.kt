package com.ates.reelsauto

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ayarlar. İki mod vardır:
 *  0 = Hesap Isındırma (seçilen dildeki videolarla doğal etkileşim, kademeli artan limitler)
 *  1 = Ürün Bulma (ürün videolarını tespit et, beğen, ürün hesaplarını takip et)
 * Arayüz değişiklikleri önce taslakta tutulur, "Kaydet" ile putAll() üzerinden yazılır.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reels_auto", Context.MODE_PRIVATE)

    companion object {
        val DEF: Map<String, Any> = mapOf(
            "enabled" to true, "mode" to 1,
            // Genel
            "skip_unknown" to true, "rewatch" to true, "breaks" to true, "log_enabled" to true,
            // Hesap ısındırma
            "w_lang" to "en", "w_interests" to "", "w_like_enabled" to true, "w_like_chance" to 20,
            "w_daily_cap" to 60, "w_hourly_cap" to 15, "w_ramp" to true, "w_min" to 5, "w_max" to 35,
            // Ürün bulma
            "p_lang" to "en", "p_keywords" to "", "p_exclude" to "", "p_kw_required" to true,
            "sensitivity" to 0, "like_enabled" to true, "caption_expand" to true, "comment_peek" to true,
            "like_ads" to false, "auto_follow" to true, "profile_check" to true, "follow_needed" to 1,
            "daily_follow_cap" to 8, "like_chance" to 85, "daily_cap" to 80, "hourly_cap" to 20,
            "min_watch" to 3, "max_watch" to 25
        )
        val LANGS = listOf(
            "en" to "English", "tr" to "Türkçe", "de" to "Deutsch", "es" to "Español",
            "fr" to "Français", "ru" to "Русский", "ar" to "العربية", "any" to "Tüm diller"
        )
        fun langName(code: String) = LANGS.firstOrNull { it.first == code }?.second ?: code
    }

    fun b(k: String): Boolean = sp.getBoolean(k, DEF[k] as? Boolean ?: false)
    fun i(k: String): Int = sp.getInt(k, DEF[k] as? Int ?: 0)
    fun s(k: String): String = sp.getString(k, DEF[k] as? String ?: "") ?: ""

    fun putAll(m: Map<String, Any>) {
        val e = sp.edit()
        for ((k, v) in m) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    // ---------------- Genel
    var enabled: Boolean
        get() = b("enabled")
        set(v) { sp.edit().putBoolean("enabled", v).apply() }
    val mode get() = i("mode")
    val isWarmup get() = mode == 0
    /** Etkileşim kurulacak dil (ML Kit kodu) ya da "any" */
    val targetLang get() = if (isWarmup) s("w_lang") else s("p_lang")
    val skipUnknown get() = b("skip_unknown")
    val rewatchEnabled get() = b("rewatch")
    val breaksEnabled get() = b("breaks")
    val logEnabled get() = b("log_enabled")

    // ---------------- Mod'a göre geçerli değerler (servis bunları kullanır)
    val interests get() = s("w_interests")
    val keywords get() = s("p_keywords")
    val excludeKeywords get() = s("p_exclude")
    val keywordRequired get() = b("p_kw_required")
    val sensitivity get() = i("sensitivity")
    val likeEnabled get() = if (isWarmup) b("w_like_enabled") else b("like_enabled")
    val likeAds get() = !isWarmup && b("like_ads")
    val captionExpand get() = !isWarmup && b("caption_expand")
    val commentPeek get() = !isWarmup && b("comment_peek")
    val autoFollow get() = !isWarmup && b("auto_follow")
    val profileCheck get() = b("profile_check")
    val followVideosNeeded get() = i("follow_needed")
    val dailyFollowCap get() = i("daily_follow_cap")
    val likeChance get() = if (isWarmup) i("w_like_chance") else i("like_chance")
    val minWatchSec get() = if (isWarmup) i("w_min") else i("min_watch")
    val maxWatchSec get() = if (isWarmup) i("w_max") else i("max_watch")
    val hourlyLikeCap get() = if (isWarmup) i("w_hourly_cap") else i("hourly_cap")
    val dailyLikeCap get() = if (isWarmup) warmupCap() else i("daily_cap")

    // ---------------- Isındırma: kademeli artış (1. gün %30 → 8. gün %100)
    private fun epochDay() = System.currentTimeMillis() / 86_400_000L
    fun warmupDay(): Int {
        val start = sp.getLong("w_start_day", 0L)
        return if (start == 0L) 1 else (epochDay() - start).toInt().coerceAtLeast(0) + 1
    }
    fun warmupCap(): Int {
        val cap = i("w_daily_cap")
        if (!b("w_ramp")) return cap
        return (cap * minOf(1.0, 0.3 + 0.1 * (warmupDay() - 1))).toInt()
    }
    fun ensureWarmStart() { if (sp.getLong("w_start_day", 0L) == 0L) sp.edit().putLong("w_start_day", epochDay()).apply() }
    fun resetWarmStart() { sp.edit().putLong("w_start_day", epochDay()).apply() }

    // ---------------- Başlatma sinyali ve Instagram kısıtlaması
    var startToken: Long
        get() = sp.getLong("start_token", 0L)
        set(v) { sp.edit().putLong("start_token", v).apply() }
    var blockUntil: Long
        get() = sp.getLong("block_until", 0L)
        set(v) { sp.edit().putLong("block_until", v).apply() }
    val isBlocked get() = blockUntil > System.currentTimeMillis()

    var lastProduct: String
        get() = sp.getString("last_product", "") ?: ""
        set(v) { sp.edit().putString("last_product", v).apply() }

    // ---------------- Günlük sayaçlar
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private val COUNTERS = listOf("c_like", "c_scroll", "c_product", "c_lang_skip", "c_follow", "c_rewatch", "c_profile", "c_comments", "c_interest")

    private fun counter(key: String): Int = if (sp.getString("day", "") == today()) sp.getInt(key, 0) else 0

    private fun inc(key: String) {
        if (sp.getString("day", "") != today()) {
            val e = sp.edit().putString("day", today())
            for (c in COUNTERS) e.putInt(c, 0)
            e.apply()
        }
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    val likesToday get() = counter("c_like")
    val scrollsToday get() = counter("c_scroll")
    val productsToday get() = counter("c_product")
    val langSkipsToday get() = counter("c_lang_skip")
    val followsToday get() = counter("c_follow")
    val rewatchesToday get() = counter("c_rewatch")
    val profileVisitsToday get() = counter("c_profile")
    val commentPeeksToday get() = counter("c_comments")
    val interestsToday get() = counter("c_interest")

    fun addLike() = inc("c_like")
    fun addScroll() = inc("c_scroll")
    fun addProduct() = inc("c_product")
    fun addLangSkip() = inc("c_lang_skip")
    fun addFollow() = inc("c_follow")
    fun addRewatch() = inc("c_rewatch")
    fun addProfileVisit() = inc("c_profile")
    fun addCommentPeek() = inc("c_comments")
    fun addInterest() = inc("c_interest")
}
