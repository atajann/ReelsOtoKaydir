package com.ates.reelsauto

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Erişilebilirlik ağacından o anki Reels'in metin bilgileri */
data class ReelScreen(
    val user: String,
    val caption: String,
    val audio: String,
    val metrics: List<String>,
    val allText: String,
    val sponsored: Boolean,
    val shopButton: Boolean
)

object ReelScreenReader {
    private const val MAX_NODES = 2500

    /** Instagram arayüz yazıları (EN/TR) — içerik analizine dahil edilmez */
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
    private val METRIC = Regex("""^[\d.,]+\s?[KMBkmb]?$|^[\d.,]+\s?(?:B|Mn)$""")

    fun isUiWord(s: String): Boolean = s.trim().lowercase() in UI_WORDS

    fun read(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): ReelScreen {
        val screen = Rect(0, 0, screenW, screenH)
        var user = ""
        var audio = ""
        val captions = mutableListOf<String>()
        val metrics = mutableListOf<String>()
        val texts = LinkedHashSet<String>()
        var sponsored = false
        var shop = false

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
            // Alt menü çubuğunu (son %7) atla
            if (r.top > screenH * 0.93) continue

            val raw = (n.text ?: n.contentDescription ?: "").toString().trim()
            if (raw.isEmpty()) continue
            val low = raw.lowercase()
            val id = n.viewIdResourceName ?: ""

            if (low in SPONSORED) { sponsored = true; continue }
            if (SHOP_HINTS.any { low.contains(it) }) shop = true

            // Sağdaki buton sütunundaki sayılar: beğeni / yorum / paylaşım
            if (METRIC.matches(raw) && r.centerX() > screenW * 0.75) { metrics += raw; continue }

            if (isUiWord(raw)) continue

            when {
                id.contains("caption", true) -> captions += raw
                user.isEmpty() && (id.contains("username", true) || id.contains("author", true) || id.contains("owner", true)) -> user = raw
                audio.isEmpty() && (id.contains("audio", true) || id.contains("music", true)) -> audio = raw
            }
            texts += raw
        }

        var caption = captions.maxByOrNull { it.length } ?: ""
        if (caption.isEmpty()) {
            // Yedek: en uzun anlamlı metin muhtemelen açıklamadır
            caption = texts.filter { it.length >= 15 && it != user && it != audio }.maxByOrNull { it.length } ?: ""
        }
        return ReelScreen(user, caption, audio, metrics.take(3), texts.joinToString(" | "), sponsored, shop)
    }
}
