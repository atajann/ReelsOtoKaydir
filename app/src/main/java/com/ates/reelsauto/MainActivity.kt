package com.ates.reelsauto

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var accounts: AccountStore
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var txtStatusChip: TextView
    private lateinit var swEnabled: MaterialSwitch
    private lateinit var cardPermission: View
    private lateinit var statReels: TextView
    private lateinit var statProducts: TextView
    private lateinit var statLikes: TextView
    private lateinit var statFollows: TextView
    private lateinit var txtStatsExtra: TextView
    private lateinit var txtLast: TextView
    private lateinit var txtSensitivityHint: TextView
    private lateinit var btnFollowed: Button

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        accounts = AccountStore(this)

        txtStatusChip = findViewById(R.id.txtStatusChip)
        swEnabled = findViewById(R.id.swEnabled)
        cardPermission = findViewById(R.id.cardPermission)
        statReels = findViewById(R.id.statReels)
        statProducts = findViewById(R.id.statProducts)
        statLikes = findViewById(R.id.statLikes)
        statFollows = findViewById(R.id.statFollows)
        txtStatsExtra = findViewById(R.id.txtStatsExtra)
        txtLast = findViewById(R.id.txtLast)
        txtSensitivityHint = findViewById(R.id.txtSensitivityHint)
        btnFollowed = findViewById(R.id.btnFollowed)

        // Ana anahtar
        swEnabled.isChecked = prefs.enabled
        swEnabled.setOnCheckedChangeListener { _, c -> prefs.enabled = c; refresh() }

        // Anahtarlar
        bindSwitch(R.id.swLike, prefs.likeEnabled) { prefs.likeEnabled = it }
        bindSwitch(R.id.swEnglish, prefs.englishOnly) { prefs.englishOnly = it }
        bindSwitch(R.id.swSkipUnknown, prefs.skipUnknown) { prefs.skipUnknown = it }
        bindSwitch(R.id.swCaption, prefs.captionExpand) { prefs.captionExpand = it }
        bindSwitch(R.id.swLikeAds, prefs.likeAds) { prefs.likeAds = it }
        bindSwitch(R.id.swFollow, prefs.autoFollow) { prefs.autoFollow = it }
        bindSwitch(R.id.swRewatch, prefs.rewatchEnabled) { prefs.rewatchEnabled = it }
        bindSwitch(R.id.swBreaks, prefs.breaksEnabled) { prefs.breaksEnabled = it }
        bindSwitch(R.id.swLog, prefs.logEnabled) { prefs.logEnabled = it }

        // Hassasiyet
        val sensIds = listOf(R.id.btnStrict, R.id.btnBalanced, R.id.btnBroad)
        val tgSens = findViewById<MaterialButtonToggleGroup>(R.id.tgSensitivity)
        tgSens.check(sensIds[prefs.sensitivity.coerceIn(0, 2)])
        updateSensitivityHint()
        tgSens.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) { prefs.sensitivity = sensIds.indexOf(checkedId).coerceAtLeast(0); updateSensitivityHint() }
        }

        // Takip için gereken video sayısı
        val needIds = listOf(R.id.btnNeed1, R.id.btnNeed2, R.id.btnNeed3)
        val tgNeed = findViewById<MaterialButtonToggleGroup>(R.id.tgFollowNeed)
        tgNeed.check(needIds[(prefs.followVideosNeeded - 1).coerceIn(0, 2)])
        tgNeed.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) prefs.followVideosNeeded = needIds.indexOf(checkedId).coerceAtLeast(0) + 1
        }

        // Kaydırıcılar
        bindSlider(R.id.sliderFollowCap, R.id.lblFollowCap, prefs.dailyFollowCap, 1, 0, 30,
            { "Günlük takip sınırı: " + it }) { prefs.dailyFollowCap = it }
        bindSlider(R.id.sliderLike, R.id.lblLikeChance, prefs.likeChance, 5, 0, 100,
            { "Ürün videosunu beğenme olasılığı: %" + it }) { prefs.likeChance = it }
        bindSlider(R.id.sliderDaily, R.id.lblDaily, prefs.dailyLikeCap, 10, 0, 300,
            { "Günlük beğeni sınırı: " + it }) { prefs.dailyLikeCap = it }
        bindSlider(R.id.sliderHourly, R.id.lblHourly, prefs.hourlyLikeCap, 1, 0, 60,
            { "Saatlik beğeni sınırı: " + it }) { prefs.hourlyLikeCap = it }

        val range = findViewById<RangeSlider>(R.id.rangeWatch)
        val lblWatch = findViewById<TextView>(R.id.lblWatch)
        val minW = prefs.minWatchSec.coerceIn(2, 59)
        val maxW = prefs.maxWatchSec.coerceIn(minW + 1, 60)
        range.setMinSeparationValue(1f)
        range.values = listOf(minW.toFloat(), maxW.toFloat())
        lblWatch.text = watchLabel(minW, maxW)
        range.addOnChangeListener { s, _, _ ->
            val v = s.values
            prefs.minWatchSec = v[0].toInt(); prefs.maxWatchSec = v[1].toInt()
            lblWatch.text = watchLabel(v[0].toInt(), v[1].toInt())
        }

        // Butonlar
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
        btnFollowed.setOnClickListener { showFollowed() }
        findViewById<Button>(R.id.btnOpenInstagram).setOnClickListener {
            if (!isServiceEnabled()) { openAccessibility(); return@setOnClickListener }
            if (!prefs.enabled) { prefs.enabled = true; swEnabled.isChecked = true }
            val i = packageManager.getLaunchIntentForPackage(ReelsAccessibilityService.IG_PACKAGE)
            if (i != null) startActivity(i) else Toast.makeText(this, "Instagram yüklü değil", Toast.LENGTH_LONG).show()
        }

        val vision = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Görüntü analizi açık" else "Görüntü analizi için Android 11+ gerekir"
        findViewById<TextView>(R.id.txtInfo).text =
            "Reels açıkken Ses Kısma tuşu duraklatır / devam ettirir\n" + vision + " • Tüm analizler cihazda yapılır"
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun bindSlider(
        sliderId: Int, labelId: Int, value: Int, step: Int, min: Int, max: Int,
        label: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val slider = findViewById<Slider>(sliderId)
        val lbl = findViewById<TextView>(labelId)
        val v = ((value.coerceIn(min, max) - min) / step * step + min)
        slider.value = v.toFloat()
        lbl.text = label(v)
        slider.addOnChangeListener { _, f, _ -> val i = f.toInt(); onChange(i); lbl.text = label(i) }
    }

    private fun watchLabel(a: Int, b: Int) = "İzleme süresi: " + a + "–" + b + " sn (aralık içinde insan gibi rastgele)"

    private fun updateSensitivityHint() {
        txtSensitivityHint.text = when (prefs.sensitivity) {
            0 -> "Sıkı (önerilen): yalnızca satış niyeti net olan videolar beğenilir: yorum CTA'sı, link, fiyat, indirim kodu, mağaza butonu…"
            1 -> "Dengeli: en az bir güçlü satış sinyali olan ürün videoları beğenilir."
            else -> "Geniş: ürün dili güçlü videolar da beğenilir; arada yanlış tespit olabilir."
        }
    }

    private fun refresh() {
        val on = isServiceEnabled()
        cardPermission.visibility = if (on) View.GONE else View.VISIBLE
        txtStatusChip.text = when {
            !on -> "●  İzin gerekli"
            !prefs.enabled -> "●  Duraklatıldı"
            else -> "●  Aktif"
        }
        statReels.text = prefs.scrollsToday.toString()
        statProducts.text = prefs.productsToday.toString()
        statLikes.text = prefs.likesToday.toString() + "/" + prefs.dailyLikeCap
        statFollows.text = prefs.followsToday.toString()
        txtStatsExtra.text = prefs.langSkipsToday.toString() + " yabancı dil geçildi  •  " +
                prefs.rewatchesToday + " tekrar izleme  •  " + accounts.productAccountCount() + " ürün hesabı tanındı"
        val last = prefs.lastProduct
        txtLast.text = if (last.isEmpty()) "Henüz ürün videosu tespit edilmedi." else last
        btnFollowed.text = "Takip edilen hesaplar (" + accounts.followedList().size + ")"
    }

    private fun showFollowed() {
        val list = accounts.followedList()
        val b = MaterialAlertDialogBuilder(this).setTitle("Takip edilen hesaplar")
        if (list.isEmpty()) b.setMessage("Henüz otomatik takip edilen hesap yok.")
        else b.setItems(list.toTypedArray(), null)
        b.setPositiveButton("Tamam", null).show()
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Yüklü uygulamalar → ReelScout → Aç", Toast.LENGTH_LONG).show()
    }

    private fun exportCsv() {
        val f = ReelLogger.file(this)
        if (f == null || !f.exists() || f.length() == 0L) {
            Toast.makeText(this, "Henüz kayıt yok", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ReelScout analiz raporu")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Analiz dosyasını gönder"))
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, ReelsAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) if (ComponentName.unflattenFromString(s) == expected) return true
        return false
    }
}
