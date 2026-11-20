package com.ates.reelsauto

/** Bir videonun ürün tanıtımı olup olmadığına karar veren puanlama */
data class ProductVerdict(
    val score: Int,
    val isProduct: Boolean,
    val signals: List<String>,
    val ctaKeyword: String?
)

object ProductDetector {
    const val THRESHOLD = 4

    /** "Comment LINK", "comment 'WANT' below", "type YES" ... → anahtar kelimeyi yakalar */
    private val CTA_COMMENT = Regex(
        """\b(?:comment|type|drop|write|reply)\s+(?:the\s+word\s+|with\s+)?["'“”‘’«]?([A-Za-z0-9]{2,15})["'“”‘’»]?(?=[\s!.,:;)]|$|\p{So})""",
        RegexOption.IGNORE_CASE
    )
    private val CTA_STOP = setOf(
        "below", "down", "your", "if", "and", "for", "this", "it", "the", "me", "us", "now", "here",
        "what", "on", "in", "to", "with", "some", "any", "my", "a", "an", "you", "or", "which", "who"
    )

    private data class Signal(val name: String, val regex: Regex, val weight: Int)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val SIGNALS = listOf(
        Signal("istek-cta", rx("""\b(?:want|need|get)\s+(?:one|it|this|yours|the\s+link)\b"""), 3),
        Signal("dm-gonderim", rx("""\b(?:i'?ll|we'?ll|i\s+will|we\s+will|will)\s+(?:send|dm|message|inbox)\s+(?:you|it|the\s+link)"""), 3),
        Signal("link-bio", rx("""\blink\s+(?:is\s+)?in\s+(?:my\s+|our\s+|the\s+)?(?:bio|profile)"""), 3),
        Signal("satin-al", rx("""\b(?:shop\s+now|order\s+now|buy\s+now|get\s+yours|grab\s+(?:yours|one)|add\s+to\s+cart|order\s+yours|available\s+(?:now|on|at|in)|in\s+stock|limited\s+stock|selling\s+fast|sold\s+out|restock(?:ed)?)\b"""), 3),
        Signal("urun-nis", rx("""(?:amazon\s?finds?|founditonamazon|tiktok\s?made\s?me\s?buy\s?it|tiktok\s?shop|must[- ]?have|gadget|unboxing|product\s+review|\bhaul\b|game\s?changer)"""), 2),
        Signal("indirim", rx("""(?:use\s+(?:my\s+)?code|discount|promo\b|coupon|\b\d{1,2}\s?%\s?off\b|free\s+shipping|\bon\s+sale\b)"""), 2),
        Signal("fiyat", rx("""(?:[$£€]\s?\d+|\b\d+(?:[.,]\d+)?\s?(?:usd|dollars|bucks)\b)"""), 2),
        Signal("urun-sozu", rx("""\b(?:this\s+product|this\s+(?:little\s+)?(?:gadget|device|tool)|you\s+need\s+(?:this|one)|best\s+purchase|worth\s+every\s+penny|i\s+bought\s+(?:this|it)|obsessed\s+with\s+this)\b"""), 2)
    )

    /** ML Kit görsel etiketlerinden ürün çağrıştıranlar */
    private val PRODUCT_LABELS = listOf(
        "bag", "handbag", "shoe", "sneakers", "jewelry", "jewellery", "watch", "glasses", "sunglasses",
        "cosmetics", "lipstick", "bottle", "toy", "gadget", "mobile phone", "headphones", "kitchen",
        "cup", "mug", "lamp", "furniture", "box", "tool", "appliance", "electronic"
    )

    fun extractCta(text: String): String? =
        CTA_COMMENT.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.lowercase() !in CTA_STOP }
            ?.uppercase()

    fun evaluate(text: String, hasShopButton: Boolean, labels: Collection<String>): ProductVerdict {
        var score = 0
        val signals = mutableListOf<String>()

        val cta = extractCta(text)
        if (cta != null) { score += 4; signals += "yorum-cta:$cta" }

        for (s in SIGNALS) {
            if (s.regex.containsMatchIn(text)) { score += s.weight; signals += s.name }
        }
        if (hasShopButton) { score += 4; signals += "magaza-butonu" }

        val hitLabels = labels.filter { l -> PRODUCT_LABELS.any { l.lowercase().contains(it) } }.distinct().take(2)
        if (hitLabels.isNotEmpty()) {
            score += hitLabels.size
            signals += "gorsel:" + hitLabels.joinToString("+")
        }
        return ProductVerdict(score, score >= THRESHOLD, signals, cta)
    }
}
