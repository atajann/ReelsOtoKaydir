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

    // Genel
    var enabled: Boolean get() = bool("enabled", true); set(v) = setBool("enabled", v)
    var likeEnabled: Boolean get() = bool("like_enabled", true); set(v) = setBool("like_enabled", v)

    // Tespit
    /** 0 = Sıkı, 1 = Dengeli, 2 = Geniş */
    var sensitivity: Int get() = int("sensitivity", 0); set(v) = setInt("sensitivity", v)
    var englishOnly: Boolean get() = bool("english_only", true); set(v) = setBool("english_only", v)
    var skipUnknown: Boolean get() = bool("skip_unknown", true); set(v) = setBool("skip_unknown", v)
    var likeAds: Boolean get() = bool("like_ads", false); set(v) = setBool("like_ads", v)
    var captionExpand: Boolean get() = bool("caption_expand", true); set(v) = setBool("caption_expand", v)

    // Takip
    var autoFollow: Boolean get() = bool("auto_follow", true); set(v) = setBool("auto_follow", v)
    var followVideosNeeded: Int get() = int("follow_needed", 1); set(v) = setInt("follow_needed", v)
    var dailyFollowCap: Int get() = int("daily_follow_cap", 8); set(v) = setInt("daily_follow_cap", v)
    /** Takipten önce hesabın profilini açıp incele, sonra Reels'e geri dön */
    var profileCheck: Boolean get() = bool("profile_check", true); set(v) = setBool("profile_check", v)
    /** Emin olunamayan ürün videolarında yorumlara göz atıp doğrula */
    var commentPeek: Boolean get() = bool("comment_peek", true); set(v) = setBool("comment_peek", v)

    // İnsan gibi davranış
    var minWatchSec: Int get() = int("min_watch", 3); set(v) = setInt("min_watch", v)
    var maxWatchSec: Int get() = int("max_watch", 25); set(v) = setInt("max_watch", v)
    var likeChance: Int get() = int("like_chance", 85); set(v) = setInt("like_chance", v)
    var rewatchEnabled: Boolean get() = bool("rewatch", true); set(v) = setBool("rewatch", v)
    var breaksEnabled: Boolean get() = bool("breaks", true); set(v) = setBool("breaks", v)

    // Limitler
    var dailyLikeCap: Int get() = int("daily_cap", 80); set(v) = setInt("daily_cap", v)
    var hourlyLikeCap: Int get() = int("hourly_cap", 20); set(v) = setInt("hourly_cap", v)

    var logEnabled: Boolean get() = bool("log_enabled", true); set(v) = setBool("log_enabled", v)

    var lastProduct: String
        get() = sp.getString("last_product", "") ?: ""
        set(v) = sp.edit().putString("last_product", v).apply()

    // Günlük sayaçlar
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun counter(key: String): Int =
        if (sp.getString("day", "") == today()) sp.getInt(key, 0) else 0

    private fun inc(key: String) {
        if (sp.getString("day", "") != today()) {
            sp.edit()
                .putString("day", today())
                .putInt("c_like", 0).putInt("c_scroll", 0).putInt("c_product", 0)
                .putInt("c_lang_skip", 0).putInt("c_follow", 0).putInt("c_rewatch", 0)
                .putInt("c_profile", 0).putInt("c_comments", 0)
                .apply()
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

    fun addLike() = inc("c_like")
    fun addScroll() = inc("c_scroll")
    fun addProduct() = inc("c_product")
    fun addLangSkip() = inc("c_lang_skip")
    fun addFollow() = inc("c_follow")
    fun addRewatch() = inc("c_rewatch")
    fun addProfileVisit() = inc("c_profile")
    fun addCommentPeek() = inc("c_comments")
}
