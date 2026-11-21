package com.ates.reelsauto

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Ekrandaki görünür metin düğümleri */
internal data class TextNode(val text: String, val rect: Rect, val node: AccessibilityNodeInfo)

internal fun visibleTexts(root: AccessibilityNodeInfo, w: Int, h: Int, max: Int = 3000): List<TextNode> {
    val screen = Rect(0, 0, w, h)
    val out = mutableListOf<TextNode>()
    val q = ArrayDeque<AccessibilityNodeInfo>()
    q.add(root)
    var count = 0
    while (q.isNotEmpty() && count < max) {
        val n = q.poll() ?: break
        count++
        for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        if (!n.isVisibleToUser) continue
        val r = Rect(); n.getBoundsInScreen(r)
        if (!Rect.intersects(r, screen)) continue
        val t = (n.text ?: n.contentDescription ?: "").toString().trim()
        if (t.isNotEmpty()) out += TextNode(t, r, n)
    }
    // Okuma sırası: yukarıdan aşağı, soldan sağa
    return out.sortedWith(compareBy({ it.rect.top / 12 }, { it.rect.left }))
}

/**
 * Profil analizi — incelenen 7 referans hesabın (wingglow.us, swoop.portable, helltrail.us,
 * hollyhats2, smokinsantaofficial, ghostlyft.us, shop.merrywick) ortak özelliklerine göre:
 * tek ürün satan marka, bio'da "Get yours / Order / Shop 👇", ürün sayfası linki,
 * çok az takip edilen, "How to order / FAQ / Reviews" öne çıkanları.
 */
object ProfileAnalyzer {

    val SEEDS = setOf(
        "wingglow.us", "swoop.portable", "helltrail.us", "hollyhats2",
        "smokinsantaofficial", "ghostlyft.us", "shop.merrywick"
    )

    data class Result(
        val isProfile: Boolean,
        val score: Int,
        val reasons: List<String>,
        val followButton: AccessibilityNodeInfo?,
        val following: Boolean
    )

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val BIO_CTA = rx("""(?:get\s+(?:yours|one|it)|order\s+(?:yours|now|here)|shop\s+(?:now|here|the|our)|buy\s+(?:now|here)|👇|link\s+below|tap\s+(?:the\s+)?link|free\s+shipping|%\s?off|low\s+stock|limited)""")
    private val PRODUCT_LINK = rx("""(?:/products?/|myshopify\.com|/collections/|\bshop\b|\bstore\b|amzn\.to|amazon\.com|linktr\.ee|beacons\.ai|stan\.store)""")
    private val ANY_LINK = rx("""(?:https?://)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|shop|store|us|co|net|io|uk|ca|au)(?:/\S*)?""")
    private val CATEGORY = rx("""^(?:product/service|ürün/hizmet|brand|marka|shopping\s*&\s*retail|alışveriş\s*ve\s*perakende|e-?commerce\s+website|e-?ticaret\s+sitesi|retail\s+company|perakende\s+şirketi)$""")
    private val HIGHLIGHT = rx("""^(?:how\s+to\s+order|order|orders|faqs?|ship(?:ping)?|reviews?|tracking|track|contact|sss|sipariş|kargo|yorumlar)$""")
    private val FOLLOW = setOf("follow", "takip et", "follow back", "sen de takip et")
    private val FOLLOWING = setOf("following", "takip ediliyor", "requested", "istek gönderildi")
    private val NUMBER = Regex("""^[\d.,]+\s?(?:[KkMmBb]|Mn)?$""")
    private val SINGLE_FOLLOWING = Regex("""^([\d.,]+\s?(?:[kmb]|mn)?)\s*(?:following|takip)$""")
    private val BIG = Regex("""[KkMm]|Mn|\sB$|\dB$""")

    private fun parseCount(s: String): Int {
        val t = s.trim()
        if (BIG.containsMatchIn(t)) return 100_000
        return t.replace(".", "").replace(",", "").toIntOrNull() ?: Int.MAX_VALUE
    }

    fun isProfileScreen(texts: List<TextNode>): Boolean {
        val low = texts.map { it.text.lowercase() }
        val hasFollowers = low.any { it.contains("followers") || it.contains("takipçi") }
        val hasPosts = low.any { it.contains("posts") || it.contains("gönderi") }
        return hasFollowers && hasPosts
    }

    fun analyze(root: AccessibilityNodeInfo, w: Int, h: Int, username: String): Result {
        val texts = visibleTexts(root, w, h)
        if (!isProfileScreen(texts)) return Result(false, 0, emptyList(), null, false)

        var score = 0
        val reasons = mutableListOf<String>()
        val upper = texts.filter { it.rect.centerY() < h * 0.62 }   // profil başlığı + bio + öne çıkanlar
        val all = upper.joinToString("\n") { it.text }

        if (username.removePrefix("@").lowercase() in SEEDS) { score += 10; reasons += "referans-hesap" }
        if (BIO_CTA.containsMatchIn(all)) { score += 3; reasons += "bio-satis-cagrisi" }
        when {
            PRODUCT_LINK.containsMatchIn(all) -> { score += 3; reasons += "urun-linki" }
            ANY_LINK.containsMatchIn(all) -> { score += 1; reasons += "link" }
        }
        if (upper.any { CATEGORY.matches(it.text.trim()) }) { score += 2; reasons += "kategori-urun/marka" }
        if (upper.any { HIGHLIGHT.matches(it.text.trim()) }) { score += 2; reasons += "siparis/sss-one-cikanlar" }
        if (all.contains("™") || all.contains("®")) { score += 1; reasons += "marka-isareti" }

        // Takip edilen sayısı: "5" + "takip" / "following" (ya da tek düğümde "5 takip")
        var followingCount: Int? = null
        for (i in upper.indices) {
            val t = upper[i].text.lowercase()
            val single = SINGLE_FOLLOWING.find(t)
            if (single != null) { followingCount = parseCount(single.groupValues[1]); break }
            if ((t == "following" || t == "takip") && i > 0 && NUMBER.matches(upper[i - 1].text.trim())) {
                followingCount = parseCount(upper[i - 1].text); break
            }
        }
        followingCount?.let {
            if (it <= 15) { score += 2; reasons += "az-takip:" + it }
            else if (it > 500) { score -= 2; reasons += "cok-takip" }
        }

        // Profil başlığındaki takip butonu (önerilen hesapların butonları değil → en üstteki)
        var followBtn: AccessibilityNodeInfo? = null
        var following = false
        for (t in texts.filter { it.rect.centerY() < h * 0.6 }) {
            val l = t.text.lowercase()
            if (l in FOLLOWING) { following = true; break }
            if (l in FOLLOW) { followBtn = t.node; break }
        }
        return Result(true, score, reasons, followBtn, following)
    }
}

/**
 * Yorum analizi — dropshipping hesaplarında yorumların çoğu CTA kelimesinin tekrarıdır
 * ("Wings", "Hair", "Xmas") ve kalanı satın alma sorularıdır ("How do I get this", "Quiero comprar").
 */
object CommentAnalyzer {

    data class Result(val keywordHits: Int, val topWord: String?, val buyQuestions: Int, val total: Int)

    private val BUY_Q = Regex(
        """(?:how\s+(?:do|can)\s+i\s+(?:get|buy|order)|where\s+(?:do|can|to)\b.*\b(?:get|buy|order)|where\s+can\s+i|\blink\s*\?|\bprice\b|how\s+much|\bquiero\b|\bcomprar\b|onde\s+compra|wo\s+kann\s+man|\bkaufen\b|i\s+(?:want|need)\s+(?:one|it|this)|eu\s+quero|me\s+interesa|nerden\s+al|nereden|fiyat)""",
        RegexOption.IGNORE_CASE
    )
    private val NOISE = Regex(
        """^(?:\d+\s?[a-zçğıöşü]{0,3}|reply|yanıtla|see\s+translation|çevirisine\s+bak|view\s+\d+.*|\d+\s+yanıtın.*|.*\d+\s+(?:likes?|beğenme)|add\s+a\s+comment.*|yorum\s+ekle.*|comments|yorumlar|liked\s+by.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val USERNAME_LIKE = Regex("""^[a-z0-9._]{3,30}$""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]""")

    fun isCommentSheet(texts: List<TextNode>): Boolean =
        texts.any {
            val l = it.text.lowercase()
            l.startsWith("add a comment") || l.startsWith("yorum ekle") || l.startsWith("join the conversation") || l.startsWith("konuşmayı başlat")
        }

    fun analyze(root: AccessibilityNodeInfo, w: Int, h: Int, cta: String?): Result {
        val texts = visibleTexts(root, w, h)
            .filter { it.rect.centerY() > h * 0.2 && it.rect.centerY() < h * 0.93 }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() && !NOISE.matches(it) && !ReelScreenReader.isUiWord(it) }

        var keywordHits = 0
        if (cta != null) keywordHits = texts.count { it.replace(NON_WORD, "").equals(cta, ignoreCase = true) }

        // CTA bilinmiyorsa: en sık tekrarlanan tek kelimelik yorum (kullanıcı adına benzemeyen)
        val counts = texts.map { it.replace(NON_WORD, "") }
            .filter { it.length in 2..15 && (!USERNAME_LIKE.matches(it) || it[0].isUpperCase()) }
            .groupingBy { it.lowercase() }.eachCount()
        val top = counts.maxByOrNull { it.value }
        if (cta == null && top != null && top.value >= 3) keywordHits = top.value

        val buy = texts.count { BUY_Q.containsMatchIn(it) }
        return Result(keywordHits, top?.key, buy, texts.size)
    }

    fun toSignals(r: Result): List<ExtraSignal> {
        val out = mutableListOf<ExtraSignal>()
        if (r.keywordHits >= 2) out += ExtraSignal("yorum-hunisi:" + r.keywordHits, 5, true)
        if (r.buyQuestions >= 2) out += ExtraSignal("satin-alma-sorulari:" + r.buyQuestions, 3, true)
        else if (r.buyQuestions == 1) out += ExtraSignal("satin-alma-sorusu", 1, false)
        return out
    }
}
