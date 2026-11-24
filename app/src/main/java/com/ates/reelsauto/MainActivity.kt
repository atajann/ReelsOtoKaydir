package com.ates.reelsauto

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText

/**
 * Ayar ekranı. Tüm değişiklikler önce "taslak"ta tutulur; "Kaydet" ile uygulanır.
 * Ana anahtar (çalıştır/duraklat) ise anında etkilidir.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var accounts: AccountStore
    private val handler = Handler(Looper.getMainLooper())
    private val draft = HashMap<String, Any>()
    private var loading = true

    private lateinit var txtStatusChip: TextView
    private lateinit var swEnabled: MaterialSwitch
    private lateinit var cardPermission: View
    private lateinit var cardBlock: View
    private lateinit var containerWarm: View
    private lateinit var containerProduct: View
    private lateinit var txtModeHint: TextView
    private lateinit var lblStatProducts: TextView
    private lateinit var lblStatFollows: TextView
    private lateinit var statReels: TextView
    private lateinit var statProducts: TextView
    private lateinit var statLikes: TextView
    private lateinit var statFollows: TextView
    private lateinit var txtStatsExtra: TextView
    private lateinit var txtLast: TextView
    private lateinit var txtSensitivityHint: TextView
    private lateinit var txtRampInfo: TextView
    private lateinit var btnSave: Button
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
        cardBlock = findViewById(R.id.cardBlock)
        containerWarm = findViewById(R.id.containerWarm)
        containerProduct = findViewById(R.id.containerProduct)
        txtModeHint = findViewById(R.id.txtModeHint)
        lblStatProducts = findViewById(R.id.lblStatProducts)
        lblStatFollows = findViewById(R.id.lblStatFollows)
        statReels = findViewById(R.id.statReels)
        statProducts = findViewById(R.id.statProducts)
        statLikes = findViewById(R.id.statLikes)
        statFollows = findViewById(R.id.statFollows)
        txtStatsExtra = findViewById(R.id.txtStatsExtra)
        txtLast = findViewById(R.id.txtLast)
        txtSensitivityHint = findViewById(R.id.txtSensitivityHint)
        txtRampInfo = findViewById(R.id.txtRampInfo)
        btnSave = findViewById(R.id.btnSave)
        btnFollowed = findViewById(R.id.btnFollowed)

        // Ana anahtar: anında etkili
        swEnabled.isChecked = prefs.enabled
        swEnabled.setOnCheckedChangeListener { _, c -> prefs.enabled = c; refresh() }

        // Mod
        val modeIds = listOf(R.id.btnModeWarm, R.id.btnModeProduct)
        val tgMode = findViewById<MaterialButtonToggleGroup>(R.id.tgMode)
        val mode = prefs.i("mode").coerceIn(0, 1)
        tgMode.check(modeIds[mode])
        showMode(mode)
        tgMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { val m = modeIds.indexOf(id).coerceAtLeast(0); put("mode", m); showMode(m) }
        }

        // ---- Hesap ısındırma
        langChips(R.id.chipsWarmLang, "w_lang")
        text(R.id.edtInterests, "w_interests")
        switch(R.id.swWarmLike, "w_like_enabled")
        slider(R.id.sliderWarmLike, R.id.lblWarmLike, "w_like_chance", 5, 0, 60) { "Beğenme olasılığı: %" + it + " (ilgi alanı videolarında yaklaşık 2 katı)" }
        slider(R.id.sliderWarmDaily, R.id.lblWarmDaily, "w_daily_cap", 10, 0, 200) { "Günlük beğeni hedefi: " + it }
        slider(R.id.sliderWarmHourly, R.id.lblWarmHourly, "w_hourly_cap", 1, 0, 40) { "Saatlik beğeni sınırı: " + it }
        switch(R.id.swWarmRamp, "w_ramp")
        range(R.id.rangeWarmWatch, R.id.lblWarmWatch, "w_min", "w_max")

        // ---- Ürün bulma
        langChips(R.id.chipsProdLang, "p_lang")
        text(R.id.edtKeywords, "p_keywords")
        text(R.id.edtExclude, "p_exclude")
        switch(R.id.swKeywordRequired, "p_kw_required")
        toggle(R.id.tgSensitivity, listOf(R.id.btnStrict, R.id.btnBalanced, R.id.btnBroad), "sensitivity", 0) { updateSensitivityHint(it) }
        updateSensitivityHint(prefs.i("sensitivity"))
        switch(R.id.swLike, "like_enabled")
        switch(R.id.swCaption, "caption_expand")
        switch(R.id.swCommentPeek, "comment_peek")
        switch(R.id.swLikeAds, "like_ads")
        switch(R.id.swFollow, "auto_follow")
        switch(R.id.swProfileCheck, "profile_check")
        toggle(R.id.tgFollowNeed, listOf(R.id.btnNeed1, R.id.btnNeed2, R.id.btnNeed3), "follow_needed", 1) { }
        slider(R.id.sliderFollowCap, R.id.lblFollowCap, "daily_follow_cap", 1, 0, 30) { "Günlük takip sınırı: " + it }
        range(R.id.rangeWatch, R.id.lblWatch, "min_watch", "max_watch")
        slider(R.id.sliderLike, R.id.lblLikeChance, "like_chance", 5, 0, 100) { "Ürün videosunu beğenme olasılığı: %" + it }
        slider(R.id.sliderDaily, R.id.lblDaily, "daily_cap", 10, 0, 300) { "Günlük beğeni sınırı: " + it }
        slider(R.id.sliderHourly, R.id.lblHourly, "hourly_cap", 1, 0, 60) { "Saatlik beğeni sınırı: " + it }

        // ---- Genel
        switch(R.id.swSkipUnknown, "skip_unknown")
        switch(R.id.swRewatch, "rewatch")
        switch(R.id.swBreaks, "breaks")
        switch(R.id.swLog, "log_enabled")

        // ---- Butonlar
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnClearBlock).setOnClickListener { prefs.blockUntil = 0L; refresh() }
        btnFollowed.setOnClickListener { showFollowed() }
        btnSave.setOnClickListener { saveDraft(true) }
        findViewById<Button>(R.id.btnOpenInstagram).setOnClickListener { onStartClicked() }

        val vision = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Görüntü analizi açık" else "Görüntü analizi için Android 11+ gerekir"
        findViewById<TextView>(R.id.txtInfo).text =
            "Reels açıkken Ses Kısma tuşu duraklatır / devam ettirir\n" + vision + " • Tüm analizler cihazda yapılır"

        loading = false
        updateSaveState()
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    // ================================================================ taslak

    private fun put(key: String, value: Any) {
        if (loading) return
        val stored: Any = when (value) {
            is Boolean -> prefs.b(key)
            is Int -> prefs.i(key)
            else -> prefs.s(key)
        }
        if (stored == value) draft.remove(key) else draft[key] = value
        updateSaveState()
    }

    private fun updateSaveState() {
        val dirty = draft.isNotEmpty()
        btnSave.isEnabled = dirty
        btnSave.text = if (dirty) "Kaydet  •  " + draft.size else "Kaydedildi ✓"
    }

    private fun saveDraft(showToast: Boolean) {
        if (draft.isEmpty()) return
        prefs.putAll(HashMap(draft))
        if ((draft["mode"] as? Int ?: prefs.i("mode")) == 0) prefs.ensureWarmStart()
        draft.clear()
        updateSaveState()
        refresh()
        if (showToast) Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
    }

    private fun current(key: String): Any = draft[key] ?: (Prefs.DEF[key] ?: "").let {
        when (it) { is Boolean -> prefs.b(key); is Int -> prefs.i(key); else -> prefs.s(key) }
    }

    // ================================================================ bağlayıcılar

    private fun switch(id: Int, key: String) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = prefs.b(key)
        sw.setOnCheckedChangeListener { _, c -> put(key, c) }
    }

    private fun text(id: Int, key: String) {
        val e = findViewById<TextInputEditText>(id)
        e.setText(prefs.s(key))
        e.doAfterTextChanged { put(key, it?.toString()?.trim() ?: "") }
    }

    private fun slider(sliderId: Int, labelId: Int, key: String, step: Int, min: Int, max: Int, label: (Int) -> String) {
        val slider = findViewById<Slider>(sliderId)
        val lbl = findViewById<TextView>(labelId)
        val v = ((prefs.i(key).coerceIn(min, max) - min) / step * step + min)
        slider.value = v.toFloat()
        lbl.text = label(v)
        slider.addOnChangeListener { _, f, _ -> val i = f.toInt(); put(key, i); lbl.text = label(i) }
    }

    private fun range(sliderId: Int, labelId: Int, minKey: String, maxKey: String) {
        val r = findViewById<RangeSlider>(sliderId)
        val lbl = findViewById<TextView>(labelId)
        val a = prefs.i(minKey).coerceIn(2, 59)
        val b = prefs.i(maxKey).coerceIn(a + 1, 60)
        r.setMinSeparationValue(1f)
        r.values = listOf(a.toFloat(), b.toFloat())
        lbl.text = watchLabel(a, b)
        r.addOnChangeListener { s, _, _ ->
            val v = s.values
            put(minKey, v[0].toInt()); put(maxKey, v[1].toInt())
            lbl.text = watchLabel(v[0].toInt(), v[1].toInt())
        }
    }

    private fun toggle(groupId: Int, ids: List<Int>, key: String, offset: Int, onChange: (Int) -> Unit) {
        val g = findViewById<MaterialButtonToggleGroup>(groupId)
        g.check(ids[(prefs.i(key) - offset).coerceIn(0, ids.size - 1)])
        g.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { val v = ids.indexOf(id).coerceAtLeast(0) + offset; put(key, v); onChange(v) }
        }
    }

    private fun langChips(groupId: Int, key: String) {
        val g = findViewById<ChipGroup>(groupId)
        val selected = prefs.s(key)
        for ((code, name) in Prefs.LANGS) {
            val c = layoutInflater.inflate(R.layout.item_lang_chip, g, false) as Chip
            c.id = View.generateViewId()
            c.text = name
            c.tag = code
            g.addView(c)
            if (code == selected) c.isChecked = true
        }
        g.setOnCheckedStateChangeListener { grp, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val code = grp.findViewById<Chip>(id)?.tag as? String ?: return@setOnCheckedStateChangeListener
            put(key, code)
        }
    }

    private fun watchLabel(a: Int, b: Int) = "İzleme süresi: " + a + "–" + b + " sn (aralık içinde insan gibi rastgele)"

    private fun updateSensitivityHint(v: Int) {
        txtSensitivityHint.text = when (v) {
            0 -> "Sıkı (önerilen): yalnızca satış niyeti net olan videolar: yorum CTA'sı, link, fiyat, indirim, mağaza butonu…"
            1 -> "Dengeli: en az bir güçlü satış sinyali olan ürün videoları da beğenilir."
            else -> "Geniş: ürün dili güçlü videolar da beğenilir; arada yanlış tespit olabilir."
        }
    }

    private fun showMode(m: Int) {
        containerWarm.visibility = if (m == 0) View.VISIBLE else View.GONE
        containerProduct.visibility = if (m == 1) View.VISIBLE else View.GONE
        txtModeHint.text = if (m == 0)
            "Yeni ya da uzun süre kullanılmamış hesapları doğal bir kullanıcı gibi ısındırır: seçilen dildeki videoları izler, ara sıra beğenir, limitleri her gün kademeli artırır. Takip yapılmaz."
        else
            "Seçilen dildeki ürün videolarını tespit eder; anahtar kelimelerine uyanları beğenir ve ürün satan hesapları takip eder."
        lblStatProducts.text = if (m == 0) "İlgi alanı videosu" else "Ürün videosu"
        lblStatFollows.text = if (m == 0) "Isındırma günü" else "Takip"
        refresh()
    }

    // ================================================================ durum

    private fun refresh() {
        if (!::prefs.isInitialized) return
        val on = isServiceEnabled()
        val warm = (current("mode") as? Int ?: 1) == 0
        cardPermission.visibility = if (on) View.GONE else View.VISIBLE
        cardBlock.visibility = if (prefs.isBlocked) View.VISIBLE else View.GONE

        val modeName = if (prefs.isWarmup) "Isındırma • " + Prefs.langName(prefs.s("w_lang")) else "Ürün bulma • " + Prefs.langName(prefs.s("p_lang"))
        txtStatusChip.text = when {
            !on -> "●  İzin gerekli"
            prefs.isBlocked -> "●  Kısıtlama: " + ((prefs.blockUntil - System.currentTimeMillis()) / 3_600_000L + 1) + " sa bekleniyor"
            !prefs.enabled -> "●  Duraklatıldı • " + modeName
            else -> "●  Aktif • " + modeName
        }
        statReels.text = prefs.scrollsToday.toString()
        if (warm) {
            statProducts.text = prefs.interestsToday.toString()
            statLikes.text = prefs.likesToday.toString() + "/" + prefs.warmupCap()
            statFollows.text = prefs.warmupDay().toString()
            txtStatsExtra.text = prefs.langSkipsToday.toString() + " farklı dil geçildi  •  " + prefs.rewatchesToday + " tekrar izleme"
        } else {
            statProducts.text = prefs.productsToday.toString()
            statLikes.text = prefs.likesToday.toString() + "/" + prefs.i("daily_cap")
            statFollows.text = prefs.followsToday.toString()
            txtStatsExtra.text = prefs.langSkipsToday.toString() + " farklı dil geçildi  •  " + prefs.profileVisitsToday + " profil incelendi  •  " +
                    prefs.commentPeeksToday + " yorum kontrolü  •  " + accounts.productAccountCount() + " ürün hesabı tanındı"
        }
        txtRampInfo.text = "Bugün " + prefs.warmupDay() + ". gün • bugünkü beğeni limiti: " + prefs.warmupCap()
        val last = prefs.lastProduct
        txtLast.text = if (last.isEmpty()) "Henüz ürün videosu tespit edilmedi." else last
        btnFollowed.text = "Takip edilen hesaplar (" + accounts.followedList().size + ")"
    }

    // ================================================================ başlat

    private fun onStartClicked() {
        if (!isServiceEnabled()) { openAccessibility(); return }
        if (prefs.isBlocked) {
            Toast.makeText(this, "Instagram kısıtlaması nedeniyle bekleniyor. Sıfırlamak için uyarı kartını kullan.", Toast.LENGTH_LONG).show()
            return
        }
        if (draft.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Kaydedilmemiş değişiklikler")
                .setMessage("Başlamadan önce yaptığın değişiklikler kaydedilsin mi?")
                .setPositiveButton("Kaydet ve başlat") { _, _ -> saveDraft(false); startNow() }
                .setNegativeButton("Kaydetmeden başlat") { _, _ -> startNow() }
                .show()
        } else startNow()
    }

    private fun startNow() {
        prefs.enabled = true
        swEnabled.isChecked = true
        if (prefs.isWarmup) prefs.ensureWarmStart()
        prefs.startToken = System.currentTimeMillis()   // servise "baştan başla" sinyali
        val reels = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reels/"))
            .setPackage(ReelsAccessibilityService.IG_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(reels)
        } catch (e: ActivityNotFoundException) {
            val i = packageManager.getLaunchIntentForPackage(ReelsAccessibilityService.IG_PACKAGE)
            if (i != null) startActivity(i) else Toast.makeText(this, "Instagram yüklü değil", Toast.LENGTH_LONG).show()
        }
    }

    // ================================================================ diğer

    private fun showFollowed() {
        val list = accounts.followedList()
        val b = MaterialAlertDialogBuilder(this).setTitle("Takip edilen hesaplar")
        if (list.isEmpty()) b.setMessage("Henüz otomatik takip edilen hesap yok.\n\nReferans hesaplar: " + ProfileAnalyzer.SEEDS.joinToString(", ") { "@" + it })
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
