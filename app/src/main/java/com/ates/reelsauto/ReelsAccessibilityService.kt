package com.ates.reelsauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.util.ArrayDeque

/**
 * Akış (her Reels için):
 *  SETTLING  → ekran otursun diye kısa, rastgele bekleme
 *  ANALYZING → metinleri oku + ekran görüntüsü al (OCR + görsel etiket) + dil tespiti
 *      İngilizce değilse → SKIPPING (kısa tepki süresiyle geç)
 *      İngilizce ise     → ürün puanı hesapla → WATCHING
 *  WATCHING  → rastgele süre izle, ek karelerle derin analiz, ürünse olasılıkla beğen
 *  sonunda   → CSV'ye kaydet → doğal kaydırma → sonraki Reels
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        const val IG_PACKAGE = "com.instagram.android"
        private const val TAG = "ReelsAuto"
        private const val TICK_MS = 400L
        private const val DETECT_INTERVAL_MS = 700L
        private const val ANALYZE_TIMEOUT_MS = 7000L
        private const val MAX_NODES = 2500

        private val REELS_ID_HINTS = listOf("clips_viewer", "clips_video_container", "clips_media_component")
        private val LIKE_WORDS = setOf("like", "beğen")
        private val UNLIKE_WORDS = setOf("unlike", "liked", "beğenildi", "beğenmekten vazgeç")
    }

    private enum class Phase { SETTLING, ANALYZING, WATCHING, SKIPPING }

    /** O anki Reels'e ait analiz verileri */
    private class ReelState {
        var screen: ReelScreen? = null
        var lang = "?"
        val ocr = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        var verdict: ProductVerdict? = null
        val frameTimes = mutableListOf<Long>()
    }

    private lateinit var prefs: Prefs
    private lateinit var logger: ReelLogger
    private var vision: VisionHelper? = null
    private val handler = Handler(Looper.getMainLooper())

    private var onReels = false
    private var paused = false
    private var ticking = false
    private var lastDetectAt = 0L
    private var lastOwnGestureAt = 0L

    // Reels durumu
    private var reelId = 0
    private var phase = Phase.SETTLING
    private var cur = ReelState()
    private var reelStart = 0L
    private var settleMs = 1000L
    private var analyzeStartedAt = 0L
    private var skipAt = 0L
    private var watchMs = Long.MAX_VALUE
    private var likeAtMs = -1L
    private var likeDecided = false
    private var liked = false
    private var frameBusy = false

    // Mola
    private var sessionEndsAt = 0L
    private var breakUntil = 0L
    private var swipeAfterBreak = false

    // Saatlik beğeni limiti
    private val likeTimes = ArrayDeque<Long>()

    private var screenW = 1080
    private var screenH = 1920

    private val visionAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vision != null

    // ================================================================ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        logger = ReelLogger(this)
        vision = try { VisionHelper() } catch (e: Throwable) { Log.w(TAG, "ML Kit başlatılamadı", e); null }
        readScreenSize()
        Log.i(TAG, "Servis bağlandı (görüntü analizi: $visionAvailable)")
    }

    override fun onInterrupt() = stopTicking()

    override fun onDestroy() {
        stopTicking()
        vision?.close()
        super.onDestroy()
    }

    // ================================================================ events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != IG_PACKAGE) {
            if (onReels) leaveReels()
            return
        }
        val now = SystemClock.uptimeMillis()

        // Kullanıcı elle kaydırdıysa (bizim hareketimiz değilse) yeni Reels say
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && onReels &&
            now - lastOwnGestureAt > 1500 && now - reelStart > 1000
        ) {
            val cls = event.className?.toString() ?: ""
            if (cls.contains("ViewPager") || cls.contains("RecyclerView")) startNewReel()
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || now - lastDetectAt > DETECT_INTERVAL_MS) {
            lastDetectAt = now
            updateReelsState()
        }
    }

    /** Ses kısma tuşu: Reels açıkken duraklat / devam et */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || !onReels) return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                paused = !paused
                if (!paused) startNewReel()
                toast(if (paused) "⏸ Duraklatıldı" else "▶ Devam ediyor")
            }
            return true
        }
        return false
    }

    // ================================================================ reels ekranı

    private fun updateReelsState() {
        val root = rootInActiveWindow
        val isReels = root != null && root.packageName?.toString() == IG_PACKAGE && isReelsScreen(root)
        if (isReels && !onReels) enterReels()
        else if (!isReels && onReels) leaveReels()
    }

    private fun enterReels() {
        onReels = true
        if (prefs.enabled) toast("▶ Reels otomasyonu başladı (Ses kısma = duraklat)")
        startNewReel()
        startTicking()
    }

    private fun leaveReels() {
        onReels = false
        stopTicking()
    }

    private fun isReelsScreen(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { n ->
            val id = n.viewIdResourceName ?: ""
            if (REELS_ID_HINTS.any { id.contains(it) } && n.isVisibleToUser) found = true
            if (!found && n.isSelected && (id.contains("clips_tab") ||
                        n.contentDescription?.toString().equals("Reels", ignoreCase = true))
            ) found = true
            found
        }
        return found
    }

    // ================================================================ döngü

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { step() } catch (e: Exception) { Log.w(TAG, "step hatası", e) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun startNewReel() {
        reelId++
        cur = ReelState()
        phase = Phase.SETTLING
        reelStart = SystemClock.uptimeMillis()
        settleMs = HumanTiming.settleMs()
        watchMs = Long.MAX_VALUE
        likeAtMs = -1L
        likeDecided = false
        liked = false
        frameBusy = false
    }

    private fun step() {
        if (!prefs.enabled || paused || !onReels) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG_PACKAGE) { leaveReels(); return }

        val now = SystemClock.uptimeMillis()

        // --- Mola yönetimi
        if (prefs.breaksEnabled) {
            if (breakUntil > now) return
            if (swipeAfterBreak) { swipeAfterBreak = false; doSwipe(); return }
            if (sessionEndsAt == 0L) sessionEndsAt = now + HumanTiming.sessionMs()
        }

        val el = now - reelStart
        when (phase) {
            Phase.SETTLING -> if (el >= settleMs) startAnalysis(root)

            Phase.ANALYZING -> if (now - analyzeStartedAt > ANALYZE_TIMEOUT_MS) decide("und")

            Phase.SKIPPING -> if (now >= skipAt) finishReel("dil-gecildi")

            Phase.WATCHING -> {
                // Derin analiz için ek kareler
                if (cur.frameTimes.isNotEmpty() && el >= cur.frameTimes[0] && !frameBusy) {
                    cur.frameTimes.removeAt(0)
                    val id = reelId
                    captureAndAnalyze(id) { onExtraFrame(id) }
                }
                // Beğeni
                if (likeAtMs >= 0 && !liked && el >= likeAtMs) {
                    likeAtMs = -1
                    if (canLike() && tryLike(root)) { liked = true; registerLike() }
                }
                // Sonraki Reels
                if (el >= watchMs) finishReel("izlendi")
            }
        }
    }

    // ================================================================ analiz

    private fun startAnalysis(root: AccessibilityNodeInfo) {
        phase = Phase.ANALYZING
        analyzeStartedAt = SystemClock.uptimeMillis()
        cur.screen = ReelScreenReader.read(root, screenW, screenH)
        val id = reelId
        if (visionAvailable) captureAndAnalyze(id) { runLanguage(id) } else runLanguage(id)
    }

    private fun runLanguage(id: Int) {
        if (id != reelId || phase != Phase.ANALYZING) return
        if (!prefs.englishOnly) { decide("-"); return }
        val text = (cur.screen?.caption ?: "") + " " + cur.ocr.joinToString(" ")
        val v = vision
        if (v == null) { decide("und"); return }
        v.identifyLanguage(text) { lang ->
            if (id == reelId && phase == Phase.ANALYZING) decide(lang)
        }
    }

    private fun decide(lang: String) {
        cur.lang = lang
        val now = SystemClock.uptimeMillis()
        val pass = !prefs.englishOnly || lang == "en" || (lang == "und" && !prefs.skipUnknown)
        if (!pass) {
            phase = Phase.SKIPPING
            skipAt = now + HumanTiming.langSkipDelayMs()
            prefs.addLangSkip()
            Log.i(TAG, "Dil: $lang → geçiliyor")
            return
        }

        val verdict = evaluateProduct()
        val el = now - reelStart
        watchMs = el + HumanTiming.watchMs(prefs.minWatchSec, prefs.maxWatchSec, verdict.isProduct)

        // Ürün videolarında 2, diğerlerinde 1 ek kare (sonradan çıkan yazılar/CTA için)
        if (visionAvailable) {
            val frames = if (verdict.isProduct) 2 else 1
            val from = el + 1500
            val to = watchMs - 800
            if (to > from) repeat(frames) { cur.frameTimes += HumanTiming.between(from, to) }
            cur.frameTimes.sort()
        }
        if (verdict.isProduct) planLike()
        phase = Phase.WATCHING
        Log.i(TAG, "Dil: $lang, ürün: ${verdict.isProduct} (${verdict.score}) ${verdict.signals}")
    }

    private fun evaluateProduct(): ProductVerdict {
        val s = cur.screen
        val text = listOfNotNull(s?.caption, s?.allText).joinToString(" ") + " " + cur.ocr.joinToString(" ")
        val v = ProductDetector.evaluate(text, s?.shopButton == true, cur.labels)
        cur.verdict = v
        return v
    }

    /** Ek kare analiz edildikten sonra: sonradan beliren CTA ile ürün tespit edilebilir */
    private fun onExtraFrame(id: Int) {
        if (id != reelId || phase != Phase.WATCHING) return
        val wasProduct = cur.verdict?.isProduct == true
        val v = evaluateProduct()
        if (v.isProduct && !wasProduct) {
            val el = SystemClock.uptimeMillis() - reelStart
            // Ürün olduğu sonradan anlaşıldı → biraz daha izle
            watchMs = maxOf(watchMs, el + HumanTiming.between(4000, 10000))
            planLike()
        }
    }

    private fun planLike() {
        if (likeDecided) return
        likeDecided = true
        val sponsored = cur.screen?.sponsored == true
        val want = prefs.likeEnabled &&
                (!sponsored || prefs.likeAds) &&
                HumanTiming.chance(prefs.likeChance / 100.0)
        if (!want) { likeAtMs = -1; return }
        val el = SystemClock.uptimeMillis() - reelStart
        val at = el + ((watchMs - el) * HumanTiming.likeFraction()).toLong()
        likeAtMs = maxOf(at, el + 1500)
    }

    private fun canLike(): Boolean {
        if (prefs.likesToday >= prefs.dailyLikeCap) return false
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        while (likeTimes.isNotEmpty() && likeTimes.peekFirst()!! < hourAgo) likeTimes.pollFirst()
        return likeTimes.size < prefs.hourlyLikeCap
    }

    private fun registerLike() {
        likeTimes.addLast(System.currentTimeMillis())
        prefs.addLike()
    }

    private fun finishReel(result: String) {
        val s = cur.screen
        val v = cur.verdict
        if (v?.isProduct == true) {
            prefs.addProduct()
            prefs.lastProduct = buildString {
                append(if (s?.user.isNullOrEmpty()) "?" else s!!.user)
                v.ctaKeyword?.let { append(" • Yorum: ").append(it) }
                append(" • ").append((s?.caption ?: "").take(80))
            }
        }
        if (prefs.logEnabled) {
            logger.log(
                ReelRecord(
                    result = result, lang = cur.lang,
                    isProduct = v?.isProduct == true, score = v?.score ?: 0,
                    signals = v?.signals ?: emptyList(), cta = v?.ctaKeyword,
                    liked = liked, sponsored = s?.sponsored == true,
                    user = s?.user ?: "", audio = s?.audio ?: "", metrics = s?.metrics ?: emptyList(),
                    caption = s?.caption ?: "", ocr = cur.ocr.toList(), labels = cur.labels.toList(),
                    watchMs = SystemClock.uptimeMillis() - reelStart
                )
            )
        }
        prefs.addScroll()

        // Mola zamanı geldiyse kaydırmadan önce ara ver
        val now = SystemClock.uptimeMillis()
        if (prefs.breaksEnabled && sessionEndsAt in 1L until now) {
            val b = HumanTiming.breakMs()
            breakUntil = now + b
            sessionEndsAt = breakUntil + HumanTiming.sessionMs()
            swipeAfterBreak = true
            phase = Phase.SETTLING
            reelStart = now
            settleMs = Long.MAX_VALUE
            toast("☕ ${b / 60000} dk mola, sonra kendiliğinden devam eder")
            return
        }
        doSwipe()
    }

    // ================================================================ ekran görüntüsü

    private fun captureAndAnalyze(id: Int, then: () -> Unit) {
        val v = vision
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || v == null) { then(); return }
        frameBusy = true
        captureFrame { bmp ->
            if (bmp == null || id != reelId) {
                bmp?.recycle()
                frameBusy = false
                if (id == reelId) then()
                return@captureFrame
            }
            v.analyze(bmp) { res ->
                bmp.recycle()
                frameBusy = false
                if (id != reelId) return@analyze
                cur.ocr.addAll(res.lines)
                cur.labels.addAll(res.labels)
                then()
            }
        }
    }

    private fun captureFrame(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null); return }
        captureFrameR(cb)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureFrameR(cb: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val out = try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                        sw?.let { prepare(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Ekran görüntüsü işlenemedi", e); null
                    }
                    cb(out)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Ekran görüntüsü alınamadı: $errorCode")
                    cb(null)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot hatası", e)
            cb(null)
        }
    }

    /** Üst çubuk ve alt menüyü kırp, 720 px genişliğe küçült */
    private fun prepare(src: Bitmap): Bitmap {
        val top = (src.height * 0.06).toInt()
        val h = (src.height * 0.86).toInt().coerceAtMost(src.height - top)
        val cropped = Bitmap.createBitmap(src, 0, top, src.width, h)
        if (cropped != src) src.recycle()
        if (cropped.width <= 720) return cropped
        val scale = 720f / cropped.width
        val scaled = Bitmap.createScaledBitmap(cropped, 720, (cropped.height * scale).toInt(), true)
        if (scaled != cropped) cropped.recycle()
        return scaled
    }

    // ================================================================ beğeni

    private fun label(n: AccessibilityNodeInfo): String =
        (n.contentDescription ?: n.text ?: "").toString().trim().lowercase()

    /** true → beğenildi */
    private fun tryLike(root: AccessibilityNodeInfo): Boolean {
        val screen = Rect(0, 0, screenW, screenH)
        var likeNode: AccessibilityNodeInfo? = null
        var alreadyLiked = false

        walk(root) { n ->
            if (!n.isVisibleToUser) return@walk false
            val r = Rect(); n.getBoundsInScreen(r)
            if (!Rect.intersects(r, screen) || r.centerY() < screenH * 0.25) return@walk false
            val l = label(n)
            if (l in UNLIKE_WORDS) alreadyLiked = true
            else if (l in LIKE_WORDS && (likeNode == null || r.centerX() > screenW * 0.6)) {
                if (n.isSelected) alreadyLiked = true else likeNode = n
            }
            false
        }

        if (alreadyLiked && likeNode == null) return false

        val target = likeNode?.let { clickableAncestor(it) }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "Beğenildi (buton)")
            return true
        }
        if (!alreadyLiked) {
            doubleTapCenter()
            Log.i(TAG, "Beğenildi (çift dokunma)")
            return true
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable) return n
            n = n.parent; depth++
        }
        return null
    }

    // ================================================================ hareketler

    private fun doSwipe() {
        swipeToNext()
        startNewReel()
    }

    private fun swipeToNext() {
        val x = screenW / 2f + HumanTiming.nextInt(-screenW / 8, screenW / 8)
        val startY = screenH * HumanTiming.nextFloat(0.66f, 0.82f)
        val endY = screenH * HumanTiming.nextFloat(0.14f, 0.30f)
        val path = Path().apply {
            moveTo(x, startY)
            // Hafif kavisli, başparmak hareketine benzer
            quadTo(
                x + HumanTiming.nextInt(-50, 50), (startY + endY) / 2,
                x + HumanTiming.nextInt(-40, 40), endY
            )
        }
        // Çoğu zaman hızlı, ara sıra yavaş/tereddütlü kaydırma
        val duration = if (HumanTiming.chance(0.1)) HumanTiming.between(450, 700) else HumanTiming.between(180, 420)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        lastOwnGestureAt = SystemClock.uptimeMillis()
        dispatchGesture(gesture, null, null)
    }

    private fun doubleTapCenter() {
        val x = screenW / 2f + HumanTiming.nextInt(-70, 70)
        val y = screenH * 0.45f + HumanTiming.nextInt(-90, 90)
        val gap = HumanTiming.between(110, 170)
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x + HumanTiming.nextInt(-6, 6), y + HumanTiming.nextInt(-6, 6)) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, HumanTiming.between(40, 70)))
            .addStroke(GestureDescription.StrokeDescription(p2, gap, HumanTiming.between(40, 70)))
            .build()
        lastOwnGestureAt = SystemClock.uptimeMillis()
        dispatchGesture(gesture, null, null)
    }

    // ================================================================ yardımcılar

    private fun walk(root: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Boolean) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count < MAX_NODES) {
            val n = q.poll() ?: break
            count++
            if (visitor(n)) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
