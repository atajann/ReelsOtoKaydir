package com.ates.reelsauto

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var txtStatus: TextView
    private lateinit var txtLast: TextView
    private lateinit var edtMin: EditText
    private lateinit var edtMax: EditText
    private lateinit var edtDaily: EditText
    private lateinit var edtHourly: EditText
    private lateinit var txtLikeChance: TextView
    private lateinit var seekLike: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        txtStatus = findViewById(R.id.txtStatus)
        txtLast = findViewById(R.id.txtLast)
        edtMin = findViewById(R.id.edtMin)
        edtMax = findViewById(R.id.edtMax)
        edtDaily = findViewById(R.id.edtDailyCap)
        edtHourly = findViewById(R.id.edtHourlyCap)
        txtLikeChance = findViewById(R.id.txtLikeChance)
        seekLike = findViewById(R.id.seekLikeChance)

        bindSwitch(R.id.swEnabled, prefs.enabled) { prefs.enabled = it; refreshStatus() }
        bindSwitch(R.id.swLike, prefs.likeEnabled) { prefs.likeEnabled = it }
        bindSwitch(R.id.swEnglish, prefs.englishOnly) { prefs.englishOnly = it }
        bindSwitch(R.id.swSkipUnknown, prefs.skipUnknown) { prefs.skipUnknown = it }
        bindSwitch(R.id.swLikeAds, prefs.likeAds) { prefs.likeAds = it }
        bindSwitch(R.id.swBreaks, prefs.breaksEnabled) { prefs.breaksEnabled = it }
        bindSwitch(R.id.swLog, prefs.logEnabled) { prefs.logEnabled = it }

        edtMin.setText(prefs.minWatchSec.toString())
        edtMax.setText(prefs.maxWatchSec.toString())
        edtDaily.setText(prefs.dailyLikeCap.toString())
        edtHourly.setText(prefs.hourlyLikeCap.toString())
        seekLike.progress = prefs.likeChance
        updateLikeLabel(prefs.likeChance)

        seekLike.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateLikeLabel(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { prefs.likeChance = s?.progress ?: 70 }
        })

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Listeden \"Reels Oto Kaydır Servisi\"ni bulup aç", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(true) }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnOpenInstagram).setOnClickListener {
            save(false)
            if (!isServiceEnabled()) {
                Toast.makeText(this, "Önce erişilebilirlik iznini ver", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val i = packageManager.getLaunchIntentForPackage(ReelsAccessibilityService.IG_PACKAGE)
            if (i != null) startActivity(i) else Toast.makeText(this, "Instagram yüklü değil", Toast.LENGTH_LONG).show()
        }

        val visionNote = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            "✅ Görüntü analizi (video üstündeki yazılar + görsel etiketler) bu telefonda çalışır."
        else
            "⚠️ Android 11 altı: görüntü analizi yok, sadece açıklama metni incelenir."
        findViewById<TextView>(R.id.txtInfo).text =
            "$visionNote\n\n• Reels açıkken SES KISMA tuşu: duraklat / devam et\n" +
                    "• Ürün tespiti: \"Comment LINK\", \"want one?\", \"link in bio\", fiyat, indirim kodu, mağaza butonu ve görsel ipuçları puanlanır\n" +
                    "• Analiz dosyası telefonda kalır, hiçbir yere gönderilmez"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun updateLikeLabel(p: Int) {
        txtLikeChance.text = "Ürün videosu beğenme olasılığı: %$p"
    }

    private fun save(showToast: Boolean) {
        val min = edtMin.text.toString().toIntOrNull()?.coerceIn(2, 120) ?: 3
        val max = edtMax.text.toString().toIntOrNull()?.coerceIn(min + 1, 180) ?: maxOf(min + 1, 25)
        val daily = edtDaily.text.toString().toIntOrNull()?.coerceIn(0, 1000) ?: 80
        val hourly = edtHourly.text.toString().toIntOrNull()?.coerceIn(0, 200) ?: 20
        prefs.minWatchSec = min
        prefs.maxWatchSec = max
        prefs.dailyLikeCap = daily
        prefs.hourlyLikeCap = hourly
        prefs.likeChance = seekLike.progress
        edtMin.setText(min.toString()); edtMax.setText(max.toString())
        edtDaily.setText(daily.toString()); edtHourly.setText(hourly.toString())
        if (showToast) Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun exportCsv() {
        val f = ReelLogger.file(this)
        if (f == null || !f.exists() || f.length() == 0L) {
            Toast.makeText(this, "Henüz kayıt yok", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Reels analiz raporu")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Analiz dosyasını gönder"))
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        txtStatus.text = buildString {
            append(if (on) "✅ Erişilebilirlik izni verildi" else "❌ Erişilebilirlik izni YOK")
            append("\n").append(if (prefs.enabled) "▶ Otomasyon açık" else "⏸ Otomasyon kapalı")
            append("\nBugün: ${prefs.scrollsToday} Reels • ${prefs.productsToday} ürün videosu")
            append("\n${prefs.likesToday}/${prefs.dailyLikeCap} beğeni • ${prefs.langSkipsToday} yabancı dil geçildi")
        }
        txtStatus.setBackgroundColor(if (on) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
        val last = prefs.lastProduct
        txtLast.text = if (last.isEmpty()) "Son ürün videosu: henüz yok" else "Son ürün videosu:\n$last"
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, ReelsAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) {
            if (ComponentName.unflattenFromString(s) == expected) return true
        }
        return false
    }
}
