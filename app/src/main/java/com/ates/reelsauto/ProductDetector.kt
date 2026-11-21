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
    /** Seçili hassasiyete göre beğenilmeye değer mi */
    val likeWorthy: Boolean
)

/** Videonun dışından gelen ek kanıt (ör. yorum analizi) */
data class ExtraSignal(val name: String, val weight: Int, val strong: Boolean)

/**
 * Ürün videosu puanlaması (v4 — gerçek dropshipping hesaplarının kalıplarına göre).
 * Tipik kalıp: "Comment WINGS if you want the link", "Comment HAIR if you need one",
 * "comment HARLEY and I'll DM you the link", "Link in bio to get yours!"
 * Yorum CTA'sı + istek/link ifadesi birlikteyse video doğrudan KESİN sayılır.
 */
object ProductDetector {

    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    private val CTA_COMMENT = rx("""\b(?:comment|type|drop|write|reply)\s+(?:the\s+word\s+|with\s+)?["'“”‘’«]?([A-Za-z0-9]{2,15})["'“”‘’»]?(?=[\s!.,:;)]|$|\p{So})""")
    private val CTA_STOP = setOf(
        "below", "down", "your", "if", "and", "for", "this", "it", "the", "me", "us", "now", "here",
        "what", "on", "in", "to", "with", "some", "any", "my", "a", "an", "you", "or", "which", "who", "all"
    )

    private class Signal(val name: String, val regex: Regex, val weight: Int)

    private val STRONG = listOf(
        Signal("istek-huni", rx("""(?:\bif\s+you(?:'d|\s+would)?\s+(?:want|need|like)\s+(?:one|it|this|the\s+link|a\s+link)|\bfor\s+the\s+link\b|\b(?:dm|send|message|text)\s+you\s+(?:the|a)\s+link\b|\byou'd\s+want\s+one\b)"""), 4),
        Signal("link", rx("""\b(?:link\s+(?:is\s+)?in\s+(?:my\s+|our\s+|the\s+)?(?:bio|profile|comments?|description|stor(?:y|ies))|links?\s+(?:below|above)|(?:check|tap|click|shop)\s+(?:the\s+|my\s+)?link|links?\s+in\s+bio)\b"""), 4),
        Signal("dm", rx("""\b(?:(?:i'?ll|we'?ll|i\s+will|we\s+will|will)\s+(?:send|dm|message|inbox)\s+(?:you|it|the\s+link)|dm\s+(?:me|us)\s+(?:for|to)|send\s+you\s+the\s+link)\b"""), 4),
        Signal("satin-al", rx("""\b(?:shop\s+now|order\s+now|buy\s+now|get\s+yours|grab\s+(?:yours|one)|add\s+to\s+cart|order\s+(?:yours|here|today)|available\s+(?:now|on\s+amazon|on\s+tiktok|at|in\s+(?:my|our))|in\s+stock|limited\s+stock|low\s+stock|selling\s+fast|sold\s+out|restock(?:ed)?|shop\s+(?:here|the\s+look)|buy\s+(?:it\s+)?(?:here|today)|get\s+(?:it|yours|one)\s+(?:here|now|today|on|at))\b"""), 4),
        Signal("magaza", rx("""(?:amazon\s+storefront|my\s+storefront|\bltk\b|shopltk|liketoknow\.?it|\bshopmy\b|amzn\.to|tiktok\s+shop\s+link|myshopify\.com|/products/)"""), 4),
        Signal("fiyat", rx("""(?:[$£€]\s?\d+|\b\d+(?:[.,]\d+)?\s?(?:usd|dollars|bucks)\b)"""), 3),
        Signal("indirim", rx("""(?:\bdiscount\b|use\s+(?:my\s+)?code|promo\s+code|coupon|\b\d{1,2}\s?%\s?off\b|free\s+shipping|limited[\s-]time\s+(?:offer|deal|sale)|\bon\s+sale\b|flash\s+sale)"""), 3),
        Signal("affiliate", rx("""(?:#ad\b|#affiliate|#sponsored|#amazonassociate|as\s+an\s+amazon\s+associate|i\s+(?:may\s+)?earn\s+(?:a\s+)?(?:small\s+)?commission|affiliate\s+link)"""), 3)
    )

    private val WEAK = listOf(
        Signal("nis-etiket", rx("""#?(?:amazonfinds?|amazonmusthaves?|founditonamazon|tiktokmademebuyit|tiktokshop(?:finds)?|musthaves?|gadgets?|homefinds|kitchengadgets?|homegadgets|coolgadgets|techgadgets|productreview|unboxing|\bhaul\b|shopwithme)"""), 3),
        Signal("hediye-sezon", rx("""(?:#?(?:christmasgifts?|christmasgiftideas|giftideas?|giftsfor(?:him|her|men|women|kids|dad|mom)|stockingstuffers?|whiteelephant(?:gifts)?|secretsanta|grinchmas|giftguide|blackfriday|cybermonday)|\bblack\s+friday\b|\bcyber\s+monday\b|\bperfect\s+gift\b|\bsend\s+(?:it|this)\s+to\s+(?:your|a)\s+friend\b|\btag\s+(?:a|your)\s+friend\s+who\b)"""), 2),
        Signal("urun-dili", rx("""\b(?:you\s+need\s+(?:this|one|these)|best\s+purchase|worth\s+every\s+penny|i\s+(?:just\s+)?(?:bought|ordered|got)\s+(?:this|these|it|one)|this\s+(?:product|gadget|device|tool|thing)|game[\s-]?changer|life[\s-]?changer|obsessed\s+with\s+(?:this|these|it)|run\s+don'?t\s+walk|holy\s+grail|highly\s+recommend)\b"""), 2),
        Signal("kullanim", rx("""\b(?:how\s+to\s+use|comes\s+with|it\s+(?:has|comes)|works\s+(?:on|with|great)|perfect\s+for|great\s+gift|gift\s+idea)\b"""), 1)
    )
    private const val WEAK_CAP = 6

    private val FEATURES = rx("""\b(?:rechargeable|portable|waterproof|wireless|usb[- ]?c?|battery|mah|led|stainless\s+steel|silicone|magnetic|adjustable|foldable|ergonomic|bluetooth|cordless|non[- ]?stick|leak[- ]?proof|bpa[- ]?free)\b""")
    private val NEGATIVE = rx("""\b(?:pov|storytime|prank|meme|lmao|lol|dance|choreo|vlog|motivation|quotes?|podcast|interview|news|breaking|football|soccer|nba|recipe)\b""")

    private val PRODUCT_LABELS = listOf(
        "bag", "handbag", "shoe", "sneakers", "jewelry", "jewellery", "watch", "glasses", "sunglasses",
        "cosmetics", "lipstick", "bottle", "toy", "gadget", "mobile phone", "headphones", "kitchen",
        "cup", "mug", "lamp", "furniture", "box", "tool", "appliance", "electronic", "cushion", "textile"
    )

    private fun normalize(t: String) = t.replace('’', '\'').replace('‘', '\'')

    fun extractCta(text: String): String? =
        CTA_COMMENT.findAll(normalize(text))
            .map { it.groupValues[1] }
            .firstOrNull { it.lowercase() !in CTA_STOP }
            ?.uppercase()

    /**
     * @param accountHistory hesabın daha önce ürün videosu sayılan video sayısı
     * @param sensitivity 0 = Sıkı, 1 = Dengeli, 2 = Geniş
     * @param extras yorum analizi gibi ek kanıtlar
     */
    fun evaluate(
        rawText: String,
        hasShopButton: Boolean,
        labels: Collection<String>,
        accountHistory: Int,
        sensitivity: Int,
        extras: Collection<ExtraSignal> = emptyList()
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
        score += minOf(weak, WEAK_CAP)

        val feats = FEATURES.findAll(text).map { it.value.lowercase() }.toSet()
        if (feats.isNotEmpty()) { score += minOf(feats.size, 3); signals += "ozellik:" + feats.take(3).joinToString("+") }

        val hitLabels = labels.filter { l -> PRODUCT_LABELS.any { l.lowercase().contains(it) } }.distinct().take(2)
        if (hitLabels.isNotEmpty()) { score += hitLabels.size; signals += "gorsel:" + hitLabels.joinToString("+") }

        if (accountHistory > 0) { score += minOf(accountHistory * 2, 4); signals += "hesap-gecmisi:" + accountHistory }

        for (e in extras) { score += e.weight; if (e.strong) strong++; signals += e.name }

        val negs = NEGATIVE.findAll(text).map { it.value.lowercase() }.toSet()
        if (negs.isNotEmpty()) { score -= minOf(negs.size, 3); signals += "negatif:" + negs.take(3).joinToString("+") }

        val confidence = when {
            cta != null && strong >= 2 -> Confidence.CERTAIN
            strong >= 2 && score >= 9 -> Confidence.CERTAIN
            strong >= 1 && score >= 6 -> Confidence.LIKELY
            score >= 4 -> Confidence.POSSIBLE
            else -> Confidence.NONE
        }
        val likeWorthy = when (sensitivity) {
            0 -> confidence >= Confidence.LIKELY
            1 -> confidence >= Confidence.LIKELY || (confidence == Confidence.POSSIBLE && strong >= 1)
            else -> confidence >= Confidence.POSSIBLE
        }
        return ProductVerdict(score, strong, confidence, signals, cta, likeWorthy)
    }
}
