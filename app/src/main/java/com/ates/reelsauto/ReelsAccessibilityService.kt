package com.ates.reelsauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
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
 * ReelScout akışı (her Reels için):
 *  SETTLING  → göz ekrana otursun (rastgele)
 *  EXPANDING → açıklamanın "devamı"na dokun, tam metni oku (isteğe bağlı)
 *  ANALYZING → metin + ekran görüntüsü (OCR, görsel etiket) + dil tespiti
 *      İngilizce değil → SKIPPING (kısa tepki süresiyle geç)
 *      İngilizce       → ürün puanı → WATCHING
 *  WATCHING  → rastgele süre izle, ek karelerle derin analiz, ürünse beğen, kesin ürün hesabıysa takip et
 *  sonunda   → kaydet → (ara sıra) geri dön ve önceki videoyu tekrar izle (REWATCH) → kaydır
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

    private enum class Phase { SETTLING, EXPANDING, ANALYZING, WATCHING, SKIPPING, REWATCH }
    /** NORMAL: yeni video, BACK: tekrar izlemek için geri dönüldü, RETURN: tekrar izlemeden sonra ileri dönüldü */
    private enum class Kind { NORMAL, BACK, RETURN }

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
    private lateinit var accounts: AccountStore
    private lateinit var gestures: Gestures
    private var vision: VisionHelper? = null
    private val handler = Handler(Looper.getMainLooper())

    private var onReels = false
    private var paused = false
    private var ticking = false
    private var lastDetectAt = 0L
    private var gestureBusy = false

    private var reelId = 0
    private var phase = Phase.SETTLING
    private var kind = Kind.NORMAL
    private var cur = ReelState()
    private var reelStart = 0L
    private var settleMs = 1000L
    private var expandUntil = 0L
    private var analyzeStartedAt = 0L
    private var skipAt = 0L
    private var watchMs = Long.MAX_VALUE
    private var likeAtMs = -1L
    private var likeDecided = false
    private var liked = false
    private var followAtMs = -1L
    private var followDecided = false
    private var followed = false
    private var frameBusy = false

    // Bir önceki videonun sonucu (tekrar izleme kararı için)
    private var prevWorthy = false
    private var prevWatched = false

    // Mola / dikkat dağınıklığı
    private var holdUntil = 0L
    private var swipeAfterHold = false
    private var sessionEndsAt = 0L

    private val likeTimes = ArrayDeque<Long>()
    private val followTimes = ArrayDeque<Long>()

    private var screenW = 1080
    private var screenH = 1920

    private val visionAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vision != null

    // ================================================================ yaşam döngüsü

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        logger = ReelLogger(this)
        accounts = AccountStore(this)
        vision = try { VisionHelper() } catch (e: Throwable) { Log.w(TAG, "ML Kit başlatılamadı", e); null }
        readScreenSize()
        gestures = Gestures(this, screenW, screenH)
        Log.i(TAG, "ReelScout bağlandı (görüntü analizi: $visionAvailable)")
    }

    override fun onInterrupt() = stopTicking()

    override fun onDestroy() {
        stopTicking()
        vision?.close()
        super.onDestroy()
    }

    // ================================================================ olaylar

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != IG_PACKAGE) {
            if (onReels) leaveReels()
            return
        }
        val now = SystemClock.uptimeMillis()

        // Kullanıcı kendi eliyle kaydırdıysa yeni video say
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && onReels && !gestureBusy &&
            now - gestures.lastGestureAt > 1500 && now - reelStart > 1000
        ) {
            val cls = event.className?.toString() ?: ""
            if (cls.contains("ViewPager") || cls.contains("RecyclerView")) startNewReel(Kind.NORMAL)
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
                if (!paused) startNewReel(Kind.NORMAL)
                toast(if (paused) "⏸ ReelScout duraklatıldı" else "▶ ReelScout devam ediyor")
            }
            return true
        }
        return false
    }

    // ================================================================ Reels ekranı

    private fun updateReelsState() {
        val root = rootInActiveWindow
        val isReels = root != null && root.packageName?.toString() == IG_PACKAGE && isReelsScreen(root)
        if (isReels && !onReels) enterReels()
        else if (!isReels && onReels && phase != Phase.EXPANDING) leaveReels()
    }

    private fun enterReels() {
        onReels = true
        if (prefs.enabled) toast("▶ ReelScout başladı (Ses kısma = duraklat)")
        startNewReel(Kind.NORMAL)
        startTicking()
    }

    private fun leaveReels() {
        onReels = false
        gestureBusy = false
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

    private fun startNewReel(k: Kind) {
        reelId++
        cur = ReelState()
        kind = k
        phase = Phase.SETTLING
        reelStart = SystemClock.uptimeMillis()
        settleMs = HumanTiming.settleMs()
        watchMs = Long.MAX_VALUE
        likeAtMs = -1L; likeDecided = false; liked = false
        followAtMs = -1L; followDecided = false; followed = false
        frameBusy = false
    }

    private fun step() {
        if (!prefs.enabled || paused || !onReels) return
        val now = SystemClock.uptimeMillis()

        // Hareket sürerken bekle (takılırsa 4 sn sonra kurtar)
        if (gestureBusy) {
            if (now - gestures.lastGestureAt > 4000) { gestureBusy = false; startNewReel(Kind.NORMAL) }
            return
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG_PACKAGE) { leaveReels(); return }

        HumanTiming.updateMood(now)

        // Mola / dikkat dağınıklığı
        if (holdUntil > now) return
        if (swipeAfterHold) { swipeAfterHold = false; moveNext(); return }
        if (prefs.breaksEnabled && sessionEndsAt == 0L) sessionEndsAt = now + HumanTiming.sessionMs()

        val el = now - reelStart
        when (phase) {
            Phase.SETTLING -> if (el >= settleMs) onSettled(root, now)

            Phase.EXPANDING -> if (now >= expandUntil) {
                val again = ReelScreenReader.read(root, screenW, screenH)
                cur.screen = merge(cur.screen, again)
                startAnalysis()
            }

            Phase.ANALYZING -> if (now - analyzeStartedAt > ANALYZE_TIMEOUT_MS) decide("und")

            Phase.SKIPPING -> if (now >= skipAt) finishReel("dil-gecildi")

            Phase.REWATCH -> if (el >= watchMs) {
                if (kind == Kind.BACK) swipe(up = true, nextKind = Kind.RETURN) else moveNext()
            }

            Phase.WATCHING -> {
                if (cur.frameTimes.isNotEmpty() && el >= cur.frameTimes[0] && !frameBusy) {
                    cur.frameTimes.removeAt(0)
                    val id = reelId
                    captureAndAnalyze(id) { onExtraFrame(id) }
                }
                if (likeAtMs >= 0 && !liked && el >= likeAtMs) {
                    likeAtMs = -1
                    if (canLike() && tryLike(root)) { liked = true; registerLike() }
                }
                if (followAtMs >= 0 && !followed && el >= followAtMs) {
                    followAtMs = -1
                    if (canFollow() && tryFollow(root)) registerFollow()
                }
                if (el >= watchMs) finishReel("izlendi")
            }
        }
    }

    private fun onSettled(root: AccessibilityNodeInfo, now: Long) {
        if (kind != Kind.NORMAL) {
            // Tekrar izleme: geri dönülen videoyu birkaç saniye izle, ileri dönüşte kısa bak
            phase = Phase.REWATCH
            val el = now - reelStart
            watchMs = el + if (kind == Kind.BACK) HumanTiming.between(3_000, 12_000) else HumanTiming.between(700, 2_600)
            return
        }
        val s = ReelScreenReader.read(root, screenW, screenH)
        cur.screen = s
        val more = s.moreButton
        if (prefs.captionExpand && more != null && HumanTiming.chance(0.8)) {
            val target = clickableAncestor(more) ?: more
            gestures.markOwnAction()
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                phase = Phase.EXPANDING
                expandUntil = now + HumanTiming.between(900, 1_700)
                return
            }
        }
        startAnalysis()
    }

    private fun merge(a: ReelScreen?, b: ReelScreen): ReelScreen {
        if (a == null) return b
        return ReelScreen(
            user = b.user.ifEmpty { a.user },
            caption = if (b.caption.length > a.caption.length) b.caption else a.caption,
            audio = b.audio.ifEmpty { a.audio },
            metrics = b.metrics.ifEmpty { a.metrics },
            allText = a.allText + " | " + b.allText,
            sponsored = a.sponsored || b.sponsored,
            shopButton = a.shopButton || b.shopButton,
            moreButton = null,
            followButton = b.followButton ?: a.followButton,
            following = a.following || b.following
        )
    }

    // ================================================================ analiz

    private fun startAnalysis() {
        phase = Phase.ANALYZING
        analyzeStartedAt = SystemClock.uptimeMillis()
        val id = reelId
        if (visionAvailable) captureAndAnalyze(id) { runLanguage(id) } else runLanguage(id)
    }

    private fun runLanguage(id: Int) {
        if (id != reelId || phase != Phase.ANALYZING) return
        if (!prefs.englishOnly) { decide("-"); return }
        val text = (cur.screen?.caption ?: "") + " " + cur.ocr.joinToString(" ")
        val v = vision
        if (v == null) { decide("und"); return }
        v.identifyLanguage(text) { lang -> if (id == reelId && phase == Phase.ANALYZING) decide(lang) }
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

        val v = evaluateProduct()
        val el = now - reelStart
        watchMs = el + HumanTiming.watchMs(prefs.minWatchSec, prefs.maxWatchSec, v.likeWorthy)

        if (visionAvailable) {
            val frames = if (v.likeWorthy || v.confidence >= Confidence.POSSIBLE) 2 else 1
            val from = el + 1500
            val to = watchMs - 800
            if (to > from) repeat(frames) { cur.frameTimes += HumanTiming.between(from, to) }
            cur.frameTimes.sort()
        }
        if (v.likeWorthy) planLike()
        planFollow()
        phase = Phase.WATCHING
        Log.i(TAG, "Dil: $lang | ürün: " + v.confidence.label + " (" + v.score + ") " + v.signals + " | mod: " + HumanTiming.mood.label)
    }

    private fun evaluateProduct(): ProductVerdict {
        val s = cur.screen
        val text = listOfNotNull(s?.caption, s?.allText).joinToString(" ") + " " + cur.ocr.joinToString(" ")
        val user = s?.user ?: ""
        val history = if (user.isNotBlank()) accounts.get(user).products else 0
        val v = ProductDetector.evaluate(text, s?.shopButton == true, cur.labels, history, prefs.sensitivity)
        cur.verdict = v
        return v
    }

    private fun onExtraFrame(id: Int) {
        if (id != reelId || phase != Phase.WATCHING) return
        val was = cur.verdict?.likeWorthy == true
        val v = evaluateProduct()
        if (v.likeWorthy && !was) {
            val el = SystemClock.uptimeMillis() - reelStart
            watchMs = maxOf(watchMs, el + HumanTiming.between(4_000, 10_000))   // ürün olduğu sonradan anlaşıldı
            planLike()
        }
        planFollow()
    }

    private fun planLike() {
        if (likeDecided) return
        likeDecided = true
        val sponsored = cur.screen?.sponsored == true
        val want = prefs.likeEnabled && (!sponsored || prefs.likeAds) && HumanTiming.chance(prefs.likeChance / 100.0)
        if (!want) return
        val el = SystemClock.uptimeMillis() - reelStart
        val at = el + ((watchMs - el) * HumanTiming.likeFraction()).toLong()
        likeAtMs = maxOf(at, el + 1500)
    }

    /** Sadece KESİN ürün videosu paylaşan hesaplar takip edilir */
    private fun planFollow() {
        if (followDecided || !prefs.autoFollow) return
        val v = cur.verdict ?: return
        if (v.confidence != Confidence.CERTAIN) return
        val s = cur.screen ?: return
        if (s.user.isBlank() || s.following) return
        val info = accounts.get(s.user)
        if (info.followed) return
        if (info.certain + 1 < prefs.followVideosNeeded) return
        if (!canFollow()) return
        followDecided = true
        val el = SystemClock.uptimeMillis() - reelStart
        val at = el + ((watchMs - el) * HumanTiming.followFraction()).toLong()
        followAtMs = maxOf(at, el + 2_000, if (likeAtMs >= 0) likeAtMs + 1_500 else 0L)
        if (followAtMs >= watchMs) watchMs = followAtMs + HumanTiming.between(1_500, 4_000)
    }

    private fun canLike(): Boolean {
        if (prefs.likesToday >= prefs.dailyLikeCap) return false
        prune(likeTimes)
        return likeTimes.size < prefs.hourlyLikeCap
    }

    private fun canFollow(): Boolean {
        if (prefs.followsToday >= prefs.dailyFollowCap) return false
        prune(followTimes)
        return followTimes.size < 3
    }

    private fun prune(q: ArrayDeque<Long>) {
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        while (q.isNotEmpty() && q.peekFirst()!! < hourAgo) q.pollFirst()
    }

    private fun registerLike() {
        likeTimes.addLast(System.currentTimeMillis())
        prefs.addLike()
    }

    private fun registerFollow() {
        followed = true
        followTimes.addLast(System.currentTimeMillis())
        prefs.addFollow()
        cur.screen?.user?.let { accounts.markFollowed(it) }
        Log.i(TAG, "Takip edildi: " + (cur.screen?.user ?: "?"))
    }

    private fun finishReel(result: String) {
        val s = cur.screen
        val v = cur.verdict
        val user = s?.user ?: ""
        val worthy = v?.likeWorthy == true

        if (result == "izlendi" && v != null) accounts.record(user, v.confidence)
        if (worthy) {
            prefs.addProduct()
            prefs.lastProduct = buildString {
                append("@").append(user.ifEmpty { "?" })
                append(" • ").append(v!!.confidence.label)
                v.ctaKeyword?.let { append(" • Yorum: ").append(it) }
                if (liked) append(" • ♥")
                if (followed) append(" • takip edildi")
                append("\n").append((s?.caption ?: "").take(110))
            }
        }
        if (prefs.logEnabled) {
            logger.log(
                ReelRecord(
                    result = result, lang = cur.lang, isProduct = worthy,
                    confidence = v?.confidence?.label ?: "-", score = v?.score ?: 0,
                    signals = v?.signals ?: emptyList(), cta = v?.ctaKeyword,
                    liked = liked, followed = followed, sponsored = s?.sponsored == true,
                    user = user, audio = s?.audio ?: "", metrics = s?.metrics ?: emptyList(),
                    caption = s?.caption ?: "", ocr = cur.ocr.toList(), labels = cur.labels.toList(),
                    watchMs = SystemClock.uptimeMillis() - reelStart, mood = HumanTiming.mood.label
                )
            )
        }
        prefs.addScroll()

        val now = SystemClock.uptimeMillis()
        val wasPrevWorthy = prevWorthy
        val wasPrevWatched = prevWatched
        prevWorthy = worthy
        prevWatched = result == "izlendi"

        // Mola zamanı
        if (prefs.breaksEnabled && sessionEndsAt in 1L until now) {
            val b = HumanTiming.breakMs()
            holdUntil = now + b
            sessionEndsAt = holdUntil + HumanTiming.sessionMs()
            swipeAfterHold = true
            phase = Phase.SETTLING; settleMs = Long.MAX_VALUE
            toast("☕ " + (b / 60000) + " dk mola, sonra kendiliğinden devam eder")
            return
        }
        // Ara sıra geri dönüp bir önceki videoyu tekrar izle (ürünse daha olası)
        if (prefs.rewatchEnabled && wasPrevWatched && HumanTiming.chance(if (wasPrevWorthy) 0.15 else 0.03)) {
            prefs.addRewatch()
            prevWatched = false
            swipe(up = false, nextKind = Kind.BACK)
            return
        }
        // Kısa dikkat dağınıklığı (telefondan başını kaldırma)
        if (HumanTiming.chance(0.025)) {
            holdUntil = now + HumanTiming.distractionMs()
            swipeAfterHold = true
            phase = Phase.SETTLING; settleMs = Long.MAX_VALUE
            return
        }
        moveNext()
    }

    // ================================================================ hareket

    private fun moveNext() = swipe(up = true, nextKind = Kind.NORMAL)

    private fun swipe(up: Boolean, nextKind: Kind) {
        gestureBusy = true
        val done: () -> Unit = { gestureBusy = false; startNewReel(nextKind) }
        when {
            !up -> gestures.swipePrevious(done)
            nextKind == Kind.NORMAL && HumanTiming.chance(0.03) ->
                gestures.abortedSwipe { handler.postDelayed({ gestures.swipeNext(done) }, HumanTiming.between(300, 800)) }
            else -> gestures.swipeNext(done)
        }
    }

    // ================================================================ beğeni / takip

    private fun label(n: AccessibilityNodeInfo): String =
        (n.contentDescription ?: n.text ?: "").toString().trim().lowercase()

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
        if (alreadyLiked) return false

        // İnsanlar bazen kalbe dokunur, bazen çift dokunur
        val target = likeNode?.let { clickableAncestor(it) }
        gestures.markOwnAction()
        if (target != null && HumanTiming.chance(0.65) && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "Beğenildi (kalp)")
            return true
        }
        gestures.doubleTap()
        Log.i(TAG, "Beğenildi (çift dokunma)")
        return true
    }

    private fun tryFollow(root: AccessibilityNodeInfo): Boolean {
        val expected = cur.screen?.user ?: return false
        val s = ReelScreenReader.read(root, screenW, screenH)
        if (s.following) return false
        if (s.user.isNotEmpty() && !s.user.equals(expected, ignoreCase = true)) return false
        val btn = s.followButton ?: return false
        val target = clickableAncestor(btn) ?: btn
        gestures.markOwnAction()
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
