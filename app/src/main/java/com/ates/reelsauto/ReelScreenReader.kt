package com.ates.reelsauto

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs

/** Erişilebilirlik ağacından o anki Reels'in bilgileri */
class ReelScreen(
    val user: String,
    val caption: String,
    val audio: String,
    val metrics: List<String>,
    val allText: String,
    val sponsored: Boolean,
    val shopButton: Boolean,
    /** Açıklamanın "… more / devamı" kısmı */
    val moreButton: AccessibilityNodeInfo?,
    /** Hesabın "Follow / Takip et" butonu */
    val followButton: AccessibilityNodeInfo?,
    val following: Boolean
)

object ReelScreenReader {
    private const val MAX_NODES = 2500

    private val UI_WORDS = setOf(
        "like", "likes", "unlike", "liked", "comment", "comments", "share", "send", "more", "… more", "...more",
        "reels", "audio", "follow", "following", "save", "remix", "reply", "home", "search", "profile",
        "explore", "camera", "back", "options", "mute", "unmute", "original audio",
        "beğen", "beğenildi", "beğenmekten vazgeç", "yorum", "yorumlar", "paylaş", "gönder", "devamı",
        "daha fazla", "takip et", "takip ediliyor", "kaydet", "ses", "ana sayfa", "ara", "profil",
        "kamera", "geri", "seçenekler", "orijinal ses"
    )
    private val SPONSORED = setOf("sponsored", "sponsorlu", "reklam", "paid partnership", "ücretli ortaklık")
    private val SHOP_HINTS = listOf("view products", "view product", "shop now", "ürünleri gör", "ürünü gör", "alışveriş yap")
    private val FOLLOW = setOf("follow", "takip et")
    private val FOLLOWING = setOf("following", "takip ediliyor", "requested", "istek gönderildi")
    private val MORE_SUFFIX = Regex("""(?:…|\.\.\.)?\s*(?:more|devamı|daha fazla)$""", RegexOption.IGNORE_CASE)
    private val METRIC = Regex("""^[\d.,]+\s?[KMBkmb]?$|^[\d.,]+\s?(?:B|Mn)$""")
    private val PROFILE_PIC = Regex("""(?:profile\s+picture\s+of\s+|profil\s+resmi:?\s*)@?([A-Za-z0-9._]{2,30})""", RegexOption.IGNORE_CASE)
    private val USERNAME = Regex("""^@?[A-Za-z0-9._]{2,30}$""")

    fun isUiWord(s: String): Boolean = s.trim().lowercase() in UI_WORDS

    fun read(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): ReelScreen {
        val screen = Rect(0, 0, screenW, screenH)
        var user = ""
        var audio = ""
        val captions = mutableListOf<String>()
        val metrics = mutableListOf<String>()
        val texts = LinkedHashSet<String>()
        val positioned = mutableListOf<Pair<String, Rect>>()
        var sponsored = false
        var shop = false
        var more: AccessibilityNodeInfo? = null
        var follow: AccessibilityNodeInfo? = null
        var followRect: Rect? = null
        var following = false

        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count < MAX_NODES) {
            val n = q.poll() ?: break
            count++
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            if (!n.isVisibleToUser) continue

            val r = Rect()
            n.getBoundsInScreen(r)
            if (!Rect.intersects(r, screen)) continue
            if (r.top > screenH * 0.93) continue   // alt menü

            val raw = (n.text ?: n.contentDescription ?: "").toString().trim()
            if (raw.isEmpty()) continue
            val low = raw.lowercase()
            val id = n.viewIdResourceName ?: ""

            if (low in SPONSORED) { sponsored = true; continue }
            if (SHOP_HINTS.any { low.contains(it) }) shop = true

            if (r.centerY() > screenH * 0.45) {
                if (low in FOLLOW) { follow = n; followRect = r; continue }
                if (low in FOLLOWING) { following = true; continue }
                if (more == null && MORE_SUFFIX.containsMatchIn(low) && r.centerY() > screenH * 0.55) more = n
            }

            if (user.isEmpty()) PROFILE_PIC.find(raw)?.let { user = it.groupValues[1] }

            if (METRIC.matches(raw) && r.centerX() > screenW * 0.75) { metrics += raw; continue }
            if (isUiWord(raw)) continue

            val clean = raw.replace(MORE_SUFFIX, "").trim()
            when {
                id.contains("caption", true) -> captions += clean
                user.isEmpty() && (id.contains("username", true) || id.contains("author", true) || id.contains("owner", true)) && USERNAME.matches(raw) -> user = raw.removePrefix("@")
                audio.isEmpty() && (id.contains("audio", true) || id.contains("music", true)) -> audio = raw
            }
            texts += clean
            positioned += clean to r
        }

        // Kullanıcı adı yedeği: "Follow" butonunun aynı satırında, solundaki kısa metin
        val fr = followRect
        if (user.isEmpty() && fr != null) {
            user = positioned
                .filter { (t, r) -> USERNAME.matches(t) && abs(r.centerY() - fr.centerY()) < screenH * 0.035 && r.right <= fr.left + 12 }
                .minByOrNull { (_, r) -> fr.left - r.right }
                ?.first?.removePrefix("@") ?: ""
        }

        var caption = captions.maxByOrNull { it.length } ?: ""
        if (caption.isEmpty()) {
            caption = texts.filter { it.length >= 15 && it != user && it != audio }.maxByOrNull { it.length } ?: ""
        }
        return ReelScreen(user, caption, audio, metrics.take(3), texts.joinToString(" | "), sponsored, shop, more, follow, following)
    }
}
