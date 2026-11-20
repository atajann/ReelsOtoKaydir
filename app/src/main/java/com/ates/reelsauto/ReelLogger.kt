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
    val result: String,
    val lang: String,
    val isProduct: Boolean,
    val confidence: String,
    val score: Int,
    val signals: List<String>,
    val cta: String?,
    val liked: Boolean,
    val followed: Boolean,
    val sponsored: Boolean,
    val user: String,
    val audio: String,
    val metrics: List<String>,
    val caption: String,
    val ocr: Collection<String>,
    val labels: Collection<String>,
    val watchMs: Long,
    val mood: String
)

/** Android/data/com.ates.reelscout/files/reels_analiz_v3.csv (Excel uyumlu, ; ayraçlı) */
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
                        w.write("﻿")
                        w.write(HEADER.joinToString(";"))
                        w.newLine()
                    }
                    val row = listOf(
                        fmt.format(Date()), r.result, r.lang,
                        if (r.isProduct) "EVET" else "hayır", r.confidence, r.score.toString(),
                        r.signals.joinToString(", "), r.cta ?: "",
                        if (r.liked) "EVET" else "hayır", if (r.followed) "EVET" else "hayır",
                        if (r.sponsored) "EVET" else "hayır",
                        r.user, r.audio, r.metrics.joinToString(" / "), r.caption,
                        r.ocr.joinToString(" | "), r.labels.joinToString(", "),
                        String.format(Locale.US, "%.1f", r.watchMs / 1000.0), r.mood
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
        const val FILE_NAME = "reels_analiz_v3.csv"
        private val HEADER = listOf(
            "zaman", "sonuc", "dil", "urun_mu", "guven", "puan", "sinyaller", "yorum_anahtar_kelime",
            "begenildi", "takip_edildi", "reklam", "hesap", "ses", "begeni/yorum/paylasim", "aciklama",
            "videodaki_yazilar", "gorsel_etiketler", "izleme_sn", "ruh_hali"
        )

        fun file(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

        private fun esc(s: String): String =
            "\"" + s.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""
    }
}
