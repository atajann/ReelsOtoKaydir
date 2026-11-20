package com.ates.reelsauto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class ReelRecord(
    val result: String,          // izlendi / dil-gecildi / mola-oncesi
    val lang: String,
    val isProduct: Boolean,
    val score: Int,
    val signals: List<String>,
    val cta: String?,
    val liked: Boolean,
    val sponsored: Boolean,
    val user: String,
    val audio: String,
    val metrics: List<String>,
    val caption: String,
    val ocr: Collection<String>,
    val labels: Collection<String>,
    val watchMs: Long
)

/** Telefonda Android/data/com.ates.reelsauto/files/reels_analiz.csv dosyasına yazar (Excel uyumlu, ; ayraçlı) */
class ReelLogger(private val ctx: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(r: ReelRecord) {
        io.execute {
            try {
                val f = file(ctx) ?: return@execute
                val isNew = !f.exists()
                FileOutputStream(f, true).bufferedWriter(Charsets.UTF_8).use { w ->
                    if (isNew) {
                        w.write("\uFEFF") // Excel'de Türkçe karakterler ve emojiler düzgün görünsün
                        w.write(HEADER.joinToString(";"))
                        w.newLine()
                    }
                    val row = listOf(
                        fmt.format(Date()), r.result, r.lang,
                        if (r.isProduct) "EVET" else "hayır", r.score.toString(),
                        r.signals.joinToString(", "), r.cta ?: "",
                        if (r.liked) "EVET" else "hayır", if (r.sponsored) "EVET" else "hayır",
                        r.user, r.audio, r.metrics.joinToString(" / "), r.caption,
                        r.ocr.joinToString(" | "), r.labels.joinToString(", "),
                        String.format(Locale.US, "%.1f", r.watchMs / 1000.0)
                    )
                    w.write(row.joinToString(";") { esc(it) })
                    w.newLine()
                }
            } catch (e: Exception) {
                Log.w("ReelsAuto", "CSV yazılamadı", e)
            }
        }
    }

    companion object {
        const val FILE_NAME = "reels_analiz.csv"
        private val HEADER = listOf(
            "zaman", "sonuc", "dil", "urun_mu", "puan", "sinyaller", "yorum_anahtar_kelime",
            "begenildi", "reklam", "hesap", "ses", "begeni/yorum/paylasim", "aciklama",
            "videodaki_yazilar", "gorsel_etiketler", "izleme_sn"
        )

        fun file(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

        private fun esc(s: String): String =
            "\"" + s.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""
    }
}
