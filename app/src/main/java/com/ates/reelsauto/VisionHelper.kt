package com.ates.reelsauto

import android.graphics.Bitmap
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Cihaz içinde çalışan ML Kit modelleri: dil tespiti, OCR, görsel etiketleme */
class VisionHelper {

    data class FrameResult(val lines: List<String>, val labels: List<String>)

    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build()
    )

    /** Dil kodu döner: "en", "tr", "es"... ya da emin değilse "und" */
    fun identifyLanguage(raw: String, cb: (String) -> Unit) {
        val t = clean(raw)
        if (t.length < 12) { cb("und"); return }
        langId.identifyLanguage(t)
            .addOnSuccessListener { cb(it) }
            .addOnFailureListener { cb("und") }
    }

    fun analyze(bmp: Bitmap, cb: (FrameResult) -> Unit) {
        val img = InputImage.fromBitmap(bmp, 0)
        ocr.process(img)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks
                    .flatMap { b -> b.lines.map { it.text.trim() } }
                    .filter { it.length >= 3 && !ReelScreenReader.isUiWord(it) }
                runLabels(img, lines, cb)
            }
            .addOnFailureListener { runLabels(img, emptyList(), cb) }
    }

    private fun runLabels(img: InputImage, lines: List<String>, cb: (FrameResult) -> Unit) {
        labeler.process(img)
            .addOnSuccessListener { ls -> cb(FrameResult(lines, ls.map { it.text })) }
            .addOnFailureListener { cb(FrameResult(lines, emptyList())) }
    }

    fun close() {
        langId.close(); ocr.close(); labeler.close()
    }

    companion object {
        private val URL = Regex("""https?://\S+""")
        private val TAG_MENTION = Regex("""[#@]\S+""")
        private val NON_LETTER = Regex("""[^\p{L}\s'.,!?]""")
        private val SPACES = Regex("""\s+""")

        /** Hashtag, kullanıcı adı, link, emoji ve rakamları temizler (dil tespiti için) */
        fun clean(s: String): String =
            s.replace(URL, " ").replace(TAG_MENTION, " ").replace(NON_LETTER, " ").replace(SPACES, " ").trim()
    }
}
