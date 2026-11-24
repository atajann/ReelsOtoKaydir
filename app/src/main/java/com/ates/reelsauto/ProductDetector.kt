package com.ates.reelsauto

enum class Confidence(val label: String) {
    NONE("yok"), POSSIBLE("olası"), LIKELY("büyük ihtimal"), CERTAIN("kesin")
}

data class ProductVerdict(
    val score: Int,
    val strong: Int,
    val confidence: Confidence,
    val signals: List<String>,
    val ctaKeyword: String?,
    /** Seçili hassasiyet + anahtar kelime filtresine göre beğenilmeye değer mi */
    val likeWorthy: Boolean,
    /** Eşleşen anahtar kelime */
    val keyword: String? = null,
    /** Hariç tutulan kelime yüzünden ya da zorunlu anahtar kelime eksikliğinden elendi */
    val gated: Boolean = false
)

/** Videonun dışından gelen ek kanıt (ör. yorum analizi) */
data class ExtraSignal(val name: String, val weight: Int, val strong: Boolean)

/** Hesap ve kullanıcı filtresi bağlamı */
data class MatchContext(
    val user: String = "",
    val metrics: List<String> = emptyList(),
    val keywords: String = "",
    val exclude: String = "",
    val keywordRequired: Boolean = true
)

/** Türkçe karakter duyarsız anahtar kelime eşleştirme ("motosiklet" = "Motosiklet", "ışık" = "isik") */
object KeywordMatcher {
    private val TR = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u', 'â' to 'a', 'î' to 'i', 'û' to 'u')

    fun norm(s: String): String {
        val low = s.replace('İ', 'i').replace('I', 'i').lowercase()
        val sb = StringBuilder(low.length)
        for (c in low) sb.append(TR[c] ?: c)
        return sb.toString()
    }

    fun parse(raw: String): List<String> =
        raw.split(',', ';', '\n').map { norm(it.trim().removePrefix("#")) }.filter { it.length >= 2 }.distinct()

    /** Kelime başında eşleşir (çoğul ekleri yakalar); 3 harf ve altı kelimeler tam kelime olmalı. Hashtag birleşik halini de dener. */
    fun matchAny(text: String, keywords: List<String>): String? {
        if (keywords.isEmpty()) return null
        val t = norm(text)
        val compact = t.replace(" ", "")
        for (k in keywords) {
            val end = if (k.length <= 3) "(?![\\p{L}\\p{N}])" else ""
            if (Regex("(?:^|[^\\p{L}\\p{N}])" + Regex.escape(k) + end).containsMatchIn(t)) return k
            val joined = k.replace(" ", "")
            if (joined.length >= 4 && compact.contains("#" + joined)) return k
        }
        return null
    }
}

/**
 * Ürün videosu puanlaması (v5).
 * EN kalıplar: "Comment WINGS if you want the link", "Link in bio to get yours", "I'll DM you the link"
 * TR kalıplar: "Yorumlara LİNK yaz", "Linki DM'den atayım", "Kapıda ödeme", "299 TL", "%40 indirim"
 * Ek sinyaller: mağaza tipi hesap adı (.us / shop. / official), yüksek yorum/beğeni oranı (yorum→DM hunisi),
 * kullanıcı anahtar kelimeleri (+), hariç tutulan kelimeler (eleme).
 */
object ProductDetector {

    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    private val CTA_EN = rx("""\b(?:comment|type|drop|write|reply)\s+(?:the\s+word\s+|with\s+)?["'“”‘’«]?([A-Za-z0-9]{2,15})["'“”‘’»]?(?=[\s!.,:;)]|$|\p{So})""")
    private val CTA_TR = rx("""(?:yorumlara|yoruma|yorum\s+olarak)\s+["'“”‘’«]?([\p{L}0-9]{2,15})["'“”‘’»]?\s+(?:yaz|yazın|yazan|bırak)""")
    private val CTA_STOP = setOf(
        "below", "down", "your", "if", "and", "for", "this", "it", "the", "me", "us", "now", "here",
        "what", "on", "in", "to", "with", "some", "any", "my", "a", "an", "you", "or", "which", "who", "all",
        "bir", "bu", "şu", "ve"
    )

    private class Signal(val name: String, val regex: Regex, val weight: Int)

    private val STRONG = listOf(
        // İngilizce
        Signal("istek-huni", rx("""(?:\bif\s+you(?:'d|\s+would)?\s+(?:want|need|like)\s+(?:one|it|this|the\s+link|a\s+link)|\bfor\s+the\s+link\b|\b(?:dm|send|message|text)\s+you\s+(?:the|a)\s+link\b|\byou'd\s+want\s+one\b)"""), 4),
        Signal("link", rx("""\b(?:link\s+(?:is\s+)?in\s+(?:my\s+|our\s+|the\s+)?(?:bio|profile|comments?|description|stor(?:y|ies))|links?\s+(?:below|above)|(?:check|tap|click|shop)\s+(?:the\s+|my\s+)?link|links?\s+in\s+bio)\b"""), 4),
        Signal("dm", rx("""\b(?:(?:i'?ll|we'?ll|i\s+will|we\s+will|will)\s+(?:send|dm|message|inbox)\s+(?:you|it|the\s+link)|dm\s+(?:me|us)\s+(?:for|to)|send\s+you\s+the\s+link)\b"""), 4),
        Signal("satin-al", rx("""\b(?:shop\s+now|order\s+now|buy\s+now|get\s+yours|grab\s+(?:yours|one)|add\s+to\s+cart|order\s+(?:yours|here|today)|available\s+(?:now|on\s+amazon|on\s+tiktok|at|in\s+(?:my|our))|in\s+stock|limited\s+stock|low\s+stock|selling\s+fast|sold\s+out|restock(?:ed)?|shop\s+(?:here|the\s+look)|buy\s+(?:it\s+)?(?:here|today)|get\s+(?:it|yours|one)\s+(?:here|now|today|on|at))\b"""), 4),
        Signal("magaza", rx("""(?:amazon\s+storefront|my\s+storefront|\bltk\b|shopltk|liketoknow\.?it|\bshopmy\b|amzn\.to|tiktok\s+shop\s+link|myshopify\.com|/products/)"""), 4),
        Signal("fiyat", rx("""(?:[$£€]\s?\d+|\b\d+(?:[.,]\d+)?\s?(?:usd|dollars|bucks)\b)"""), 3),
        Signal("indirim", rx("""(?:\bdiscount\b|use\s+(?:my\s+)?code|promo\s+code|coupon|\b\d{1,2}\s?%\s?off\b|free\s+shipping|limited[\s-]time\s+(?:offer|deal|sale)|\bon\s+sale\b|flash\s+sale)"""), 3),
        Signal("affiliate", rx("""(?:#ad\b|#affiliate|#sponsored|#amazonassociate|as\s+an\s+amazon\s+associate|i\s+(?:may\s+)?earn\s+(?:a\s+)?(?:small\s+)?commission|affiliate\s+link)"""), 3),
        // Türkçe
        Signal("tr-huni", rx("""(?:link(?:i)?\s+(?:dm(?:'?den)?|mesaj(?:la)?|özelden)\s+(?:atay[ıi]m|gönder(?:eyim|iyorum)?)|dm(?:'?den)?\s+(?:atay[ıi]m|göndereyim|ulaş)|özelden\s+(?:yaz|ulaş|bilgi))"""), 4),
        Signal("tr-link", rx("""(?:link(?:i)?\s+(?:bio'?da|bioda|profilde|profilimde|açıklamada)|bio(?:'?daki|daki)\s+link|profildeki\s+link)"""), 4),
        Signal("tr-satin-al", rx("""(?:sipariş\s+(?:için|vermek\s+için|ver)|satın\s+al|hemen\s+al|kapıda\s+ödeme|stok(?:larla)?\s+sınırlı|son\s+\d+\s+adet|tükenmeden|ücretsiz\s+kargo|aynı\s+gün\s+kargo|kargo\s+bedava)"""), 4),
        Signal("tr-fiyat", rx("""(?:\b\d+(?:[.,]\d+)?\s?(?:tl|₺)(?!\p{L})|₺\s?\d+)"""), 3),
        Signal("tr-indirim", rx("""(?:\bindirim|\bkampanya|%\s?\d{1,2}\b)"""), 3)
    )

    private val WEAK = listOf(
        Signal("nis-etiket", rx("""#?(?:amazonfinds?|amazonmusthaves?|founditonamazon|tiktokmademebuyit|tiktokshop(?:finds)?|musthaves?|gadgets?|homefinds|kitchengadgets?|homegadgets|coolgadgets|techgadgets|productreview|unboxing|\bhaul\b|shopwithme|trendyolbulgular|amazonbulgular|pratikürünler|hediyelik)"""), 3),
        Signal("hediye-sezon", rx("""(?:#?(?:christmasgifts?|christmasgiftideas|giftideas?|giftsfor(?:him|her|men|women|kids|dad|mom)|stockingstuffers?|whiteelephant(?:gifts)?|secretsanta|grinchmas|giftguide|blackfriday|cybermonday)|\bblack\s+friday\b|\bcyber\s+monday\b|\bperfect\s+gift\b|\bsend\s+(?:it|this)\s+to\s+(?:your|a)\s+friend\b|\btag\s+(?:a|your)\s+friend\s+who\b|yılbaşı\s+hediye|harika\s+bir\s+hediye)"""), 2),
        Signal("urun-dili", rx("""(?:\b(?:you\s+need\s+(?:this|one|these)|best\s+purchase|worth\s+every\s+penny|i\s+(?:just\s+)?(?:bought|ordered|got)\s+(?:this|these|it|one)|this\s+(?:product|gadget|device|tool|thing)|game[\s-]?changer|life[\s-]?changer|obsessed\s+with\s+(?:this|these|it)|run\s+don'?t\s+walk|holy\s+grail|highly\s+recommend)\b|bunu\s+(?:mutlaka|kesinlikle)\s+(?:almalısın|deneyin|alın)|hayat\s+kurtaran|çok\s+pratik|herkese\s+öneririm|en\s+iyi\s+alışveriş)"""), 2),
        Signal("kullanim", rx("""(?:\b(?:how\s+to\s+use|comes\s+with|it\s+(?:has|comes)|works\s+(?:on|with|great)|perfect\s+for|great\s+gift|gift\s+idea)\b|nasıl\s+kullanılır|kutu\s+açılımı)"""), 1)
    )
    private const val WEAK_CAP = 6

    private val FEATURES = rx("""\b(?:rechargeable|portable|waterproof|wireless|usb[- ]?c?|battery|mah|led|stainless\s+steel|silicone|magnetic|adjustable|foldable|ergonomic|bluetooth|cordless|non[- ]?stick|leak[- ]?proof|bpa[- ]?free|şarjlı|kablosuz|su\s+geçirmez|taşınabilir|paslanmaz)\b""")
    private val NEGATIVE = rx("""\b(?:pov|storytime|prank|meme|lmao|lol|dance|choreo|vlog|motivation|quotes?|podcast|interview|news|breaking|football|soccer|nba|recipe|skeç|şaka|dans|maç|gol|haber|röportaj|tarif)\b""")
    private val SHOP_HANDLE = rx("""(?:\.us$|\.shop$|^shop[._]|[._]shop$|shop$|store$|official$|[._]co$|deals?$|finds?$|gadgets?$)""")

    private val PRODUCT_LABELS = listOf(
        "bag", "handbag", "shoe", "sneakers", "jewelry", "jewellery", "watch", "glasses", "sunglasses",
        "cosmetics", "lipstick", "bottle", "toy", "gadget", "mobile phone", "headphones", "kitchen",
        "cup", "mug", "lamp", "furniture", "box", "tool", "appliance", "electronic", "cushion", "textile"
    )

    private fun normalize(t: String) = t.replace('’', '\'').replace('‘', '\'')

    fun extractCta(text: String): String? {
        val t = normalize(text)
        val en = CTA_EN.findAll(t).map { it.groupValues[1] }.firstOrNull { it.lowercase() !in CTA_STOP }
        val tr = CTA_TR.findAll(t).map { it.groupValues[1] }.firstOrNull { it.lowercase() !in CTA_STOP }
        return (en ?: tr)?.uppercase()
    }

    /** "32,7 B" / "32.7K" / "1,2 Mn" / "151" → sayı */
    fun parseCount(raw: String): Long? {
        val s = raw.trim().replace(" ", "")
        val mult = when {
            s.endsWith("Mn", true) -> 1_000_000.0
            s.endsWith("M") || s.endsWith("m") -> 1_000_000.0
            s.endsWith("K") || s.endsWith("k") || s.endsWith("B") || s.endsWith("b") -> 1_000.0
            else -> 1.0
        }
        val num = s.trimEnd('K', 'k', 'M', 'm', 'B', 'b', 'n', 'N')
        val v = if (mult > 1.0) num.replace(',', '.').toDoubleOrNull() else num.replace(".", "").replace(",", "").toDoubleOrNull()
        return v?.let { (it * mult).toLong() }
    }

    /**
     * @param accountHistory hesabın daha önce ürün videosu sayılan video sayısı
     * @param sensitivity 0 = Sıkı, 1 = Dengeli, 2 = Geniş
     */
    fun evaluate(
        rawText: String,
        hasShopButton: Boolean,
        labels: Collection<String>,
        accountHistory: Int,
        sensitivity: Int,
        extras: Collection<ExtraSignal> = emptyList(),
        ctx: MatchContext = MatchContext()
    ): ProductVerdict {
        val text = normalize(rawText)
        var score = 0
        var strong = 0
        val signals = mutableListOf<String>()

        val cta = extractCta(text)
        if (cta != null) { score += 5; strong++; signals += "yorum-cta:" + cta }
        if (hasShopButton) { score += 5; strong++; signals += "magaza-butonu" }

        for (s in STRONG) if (s.regex.containsMatchIn(text)) { score += s.weight; strong++; signals += s.name }

        var weak = 0
        for (s in WEAK) if (s.regex.containsMatchIn(text)) { weak += s.weight; signals += s.name }
        if (ctx.user.isNotBlank() && SHOP_HANDLE.containsMatchIn(ctx.user.lowercase())) { weak += 2; signals += "magaza-hesap-adi" }
        // Yorum→DM hunisi olan videolarda yorum/beğeni oranı yüksektir
        if (ctx.metrics.size >= 2) {
            val likes = parseCount(ctx.metrics[0]) ?: 0L
            val comments = parseCount(ctx.metrics[1]) ?: 0L
            if (likes >= 200 && comments.toDouble() / likes >= 0.05) { weak += 2; signals += "yorum-orani" }
        }
        score += minOf(weak, WEAK_CAP)

        val feats = FEATURES.findAll(text).map { it.value.lowercase() }.toSet()
        if (feats.isNotEmpty()) { score += minOf(feats.size, 3); signals += "ozellik:" + feats.take(3).joinToString("+") }

        val hitLabels = labels.filter { l -> PRODUCT_LABELS.any { l.lowercase().contains(it) } }.distinct().take(2)
        if (hitLabels.isNotEmpty()) { score += hitLabels.size; signals += "gorsel:" + hitLabels.joinToString("+") }

        if (accountHistory > 0) { score += minOf(accountHistory * 2, 4); signals += "hesap-gecmisi:" + accountHistory }

        for (e in extras) { score += e.weight; if (e.strong) strong++; signals += e.name }

        // Kullanıcının anahtar kelimeleri
        val searchText = text + " " + ctx.user
        val kws = KeywordMatcher.parse(ctx.keywords)
        val kwHit = KeywordMatcher.matchAny(searchText, kws)
        if (kwHit != null) { score += 2; signals += "anahtar:" + kwHit }
        val exHit = KeywordMatcher.matchAny(searchText, KeywordMatcher.parse(ctx.exclude))
        if (exHit != null) signals += "haric:" + exHit

        val negs = NEGATIVE.findAll(text).map { it.value.lowercase() }.toSet()
        if (negs.isNotEmpty()) { score -= minOf(negs.size, 3); signals += "negatif:" + negs.take(3).joinToString("+") }

        val confidence = when {
            cta != null && strong >= 2 -> Confidence.CERTAIN
            strong >= 2 && score >= 9 -> Confidence.CERTAIN
            strong >= 1 && score >= 6 -> Confidence.LIKELY
            score >= 4 -> Confidence.POSSIBLE
            else -> Confidence.NONE
        }
        val base = when (sensitivity) {
            0 -> confidence >= Confidence.LIKELY
            1 -> confidence >= Confidence.LIKELY || (confidence == Confidence.POSSIBLE && strong >= 1)
            else -> confidence >= Confidence.POSSIBLE
        }
        val gated = exHit != null || (kws.isNotEmpty() && ctx.keywordRequired && kwHit == null)
        if (gated && base) signals += if (exHit != null) "elendi:haric-kelime" else "elendi:anahtar-yok"
        return ProductVerdict(score, strong, confidence, signals, cta, base && !gated, kwHit, gated)
    }
}
