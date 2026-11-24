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
 *  SETTLING  → göz ekrana otursun
 *  EXPANDING → açıklamanın devamını aç, oku, sonra KAPAT
 *  ANALYZING → metin + ekran görüntüsü (OCR, etiket) + dil
 *  WATCHING  → izle; emin değilse yorumlara göz at; ürünse beğen;
 *              takip adayıysa profili aç → incele → takip et → Reels'e geri dön
 *  sonunda   → kaydet → (ara sıra) önceki videoyu tekrar izle → kaydır
 *
 * "Gezinti" (excursion): Reels dışına kısa çıkışlar (yorum paneli, profil, açıklama paneli).
 * Dönüş: sistem GERİ komutu (tuşlu ve hareketli gezinmede çalışır) → Instagram geri oku →
 * kenardan kaydırma → Reels sekmesi. Hiçbiri olmazsa güvenli şekilde duraklar.
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        const val IG_PACKAGE = "com.instagram.android"
        private const val TAG = "ReelsAuto"
        private const val TICK_MS = 400L
        private const val DETECT_INTERVAL_MS = 700L
        private const val ANALYZE_TIMEOUT_MS = 7000L
        private const val EXCURSION_TIMEOUT_MS = 25_000L
        private const val MAX_NODES = 2500

        private val REELS_ID_HINTS = listOf("clips_viewer", "clips_video_container", "clips_media_component")
        private val LIKE_WORDS = setOf("like", "beğen")
        private val UNLIKE_WORDS = setOf("unlike", "liked", "beğenildi", "beğenmekten vazgeç")
        private val BACK_WORDS = setOf("back", "geri", "navigate up", "yukarı git", "geri git")
    }

    private enum class Phase { SETTLING, EXPANDING, ANALYZING, WATCHING, SKIPPING, REWATCH }
    private enum class Kind { NORMAL, BACK, RETURN }
    private enum class Exc { NONE, CAPTION, COMMENTS, PROFILE }

    private class ReelState {
        var screen: ReelScreen? = null
        var lang = "?"
        val ocr = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        val extras = mutableListOf<ExtraSignal>()
        var verdict: ProductVerdict? = null
        val frameTimes = mutableListOf<Long>()
        var profileNote = ""
        var warmInterest = false
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
    private var commentAtMs = -1L
    private var commentDecided = false
    private var frameBusy = false

    // Gezinti durumu
    private var exc = Exc.NONE
    private var excStage = 0          // 0: içerik bekleniyor/inceleniyor, 1: işlem sonrası bekleme, 2: geri dönülüyor, 3: açıklama kapatma kontrolü
    private var excStart = 0L
    private var excAt = 0L
    private var returnTries = 0
    private var reelsTabUsed = false
    private var expandedTarget: AccessibilityNodeInfo? = null
    private var captionLenBefore = 0
    private var captionLenExpanded = 0
    private val checkedProfiles = HashSet<String>()

    private var prevWorthy = false
    private var prevWatched = false

    private var holdUntil = 0L
    private var swipeAfterHold = false
    private var sessionEndsAt = 0L

    private val likeTimes = ArrayDeque<Long>()
    private val followTimes = ArrayDeque<Long>()
    private val profileTimes = ArrayDeque<Long>()
    private val commentTimes = ArrayDeque<Long>()

    private var screenW = 1080
    private var screenH = 1920

    // Başlatma güvenilirliği ve kısıtlama tespiti
    private var startRequestedAt = 0L
    private var lastStartToken = 0L
    private var lastReelsTabTry = 0L
    private var nextBlockCheck = 0L
    private val BLOCK_PHRASES = listOf(
        "try again later", "daha sonra tekrar dene", "we restrict certain activity", "belirli etkinlikleri kısıtlıyoruz",
        "bazı etkinlikleri kısıtlıyoruz", "action blocked", "işlem engellendi", "your account has been temporarily",
        "hesabın geçici olarak", "we limit how often", "ne sıklıkta yapabileceğini sınırlıyoruz"
    )
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "start_token") handler.post { onStartRequested() }
    }

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
        lastStartToken = prefs.startToken
        prefs.register(prefListener)
        Log.i(TAG, "ReelScout bağlandı (görüntü analizi: $visionAvailable)")
    }

    override fun onInterrupt() = stopTicking()

    override fun onDestroy() {
        try { prefs.unregister(prefListener) } catch (e: Exception) { }
        stopTicking()
        vision?.close()
        super.onDestroy()
    }

    // ================================================================ olaylar

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != IG_PACKAGE) {
            if (onReels && exc == Exc.NONE) leaveReels()
            return
        }
        if (exc != Exc.NONE) return   // gezinti sırasında Reels algılaması askıda
        val now = SystemClock.uptimeMillis()

        if (onReels && !ticking) startTicking()   // döngü durmuşsa kendini toparla
        // "Başlat"a basıldıysa ve Instagram Reels dışında açıldıysa Reels sekmesine geç
        if (!onReels && now - startRequestedAt < 25_000 && now - lastReelsTabTry > 2_500) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() == IG_PACKAGE && !isReelsScreen(r)) {
                lastReelsTabTry = now
                clickReelsTab(r)
                reelsTabUsed = false
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && onReels && !gestureBusy &&
            phase != Phase.EXPANDING && now - gestures.lastGestureAt > 1500 && now - reelStart > 1000
        ) {
            val cls = event.className?.toString() ?: ""
            if (cls.contains("ViewPager") || cls.contains("RecyclerView")) startNewReel(Kind.NORMAL)
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || now - lastDetectAt > DETECT_INTERVAL_MS) {
            lastDetectAt = now
            updateReelsState()
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || !onReels) return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                paused = !paused
                if (!paused) { exc = Exc.NONE; startNewReel(Kind.NORMAL) }
                toast(if (paused) "⏸ ReelScout duraklatıldı" else "▶ ReelScout devam ediyor")
            }
            return true
        }
        return false
    }

    // ================================================================ Reels ekranı

    private fun updateReelsState() {
        if (exc != Exc.NONE) return
        val root = rootInActiveWindow
        val isReels = root != null && root.packageName?.toString() == IG_PACKAGE && isReelsScreen(root)
        if (isReels && !onReels) enterReels()
        else if (!isReels && onReels && phase != Phase.EXPANDING) leaveReels()
    }

    private fun enterReels() {
        if (prefs.isBlocked) {
            toast("⚠️ Instagram kısıtlaması nedeniyle ReelScout beklemede")
            return
        }
        paused = false
        onReels = true
        if (prefs.enabled) toast("▶ ReelScout başladı (Ses kısma = duraklat)")
        startNewReel(Kind.NORMAL)
        startTicking()
    }

    private fun leaveReels() {
        onReels = false
        gestureBusy = false
        exc = Exc.NONE
        stopTicking()
    }

    /** Uygulamadaki "Başlat" butonu: her şeyi sıfırla ve Reels'te başla */
    private fun onStartRequested() {
        val token = prefs.startToken
        if (token == lastStartToken) return
        lastStartToken = token
        paused = false
        exc = Exc.NONE
        excStage = 0
        gestureBusy = false
        holdUntil = 0L
        swipeAfterHold = false
        startRequestedAt = SystemClock.uptimeMillis()
        lastReelsTabTry = 0L
        Log.i(TAG, "Başlat isteği alındı (mod: " + (if (prefs.isWarmup) "ısındırma" else "ürün bulma") + ", dil: " + prefs.targetLang + ")")
        if (onReels) { startNewReel(Kind.NORMAL); startTicking() } else updateReelsState()
    }

    /** Instagram "işlem engellendi / daha sonra tekrar dene" uyarısı → 24 saat dur */
    private fun checkActionBlock(root: AccessibilityNodeInfo): Boolean {
        val texts = visibleTexts(root, screenW, screenH, 1500)
        val hit = texts.firstOrNull { t -> val l = t.text.lowercase(); BLOCK_PHRASES.any { l.contains(it) } } ?: return false
        Log.w(TAG, "Instagram kısıtlaması algılandı: " + hit.text)
        prefs.blockUntil = System.currentTimeMillis() + 24 * 3_600_000L
        performGlobalAction(GLOBAL_ACTION_BACK)   // uyarıyı kapat
        leaveReels()
        toast("⚠️ Instagram kısıtlaması algılandı. ReelScout 24 saat duracak, hesabı elle normal kullan.")
        return true
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
        commentAtMs = -1L; commentDecided = false
        frameBusy = false
        expandedTarget = null
    }

    private fun step() {
        if (!prefs.enabled || paused || !onReels) return
        val now = SystemClock.uptimeMillis()

        if (gestureBusy) {
            if (now - gestures.lastGestureAt > 4000) { gestureBusy = false; startNewReel(Kind.NORMAL) }
            return
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        if (exc != Exc.NONE && now - excStart > EXCURSION_TIMEOUT_MS) { giveUp(); return }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG_PACKAGE) {
            if (exc == Exc.NONE) leaveReels()
            return
        }

        if (now >= nextBlockCheck) {
            nextBlockCheck = now + 5_000
            if (checkActionBlock(root)) return
        }

        if (exc != Exc.NONE) { stepExcursion(root, now); return }

        HumanTiming.updateMood(now)

        if (holdUntil > now) return
        if (swipeAfterHold) { swipeAfterHold = false; moveNext(); return }
        if (prefs.breaksEnabled && sessionEndsAt == 0L) sessionEndsAt = now + HumanTiming.sessionMs()

        val el = now - reelStart
        when (phase) {
            Phase.SETTLING -> if (el >= settleMs) onSettled(root, now)

            Phase.EXPANDING -> if (now >= expandUntil) {
                val again = ReelScreenReader.read(root, screenW, screenH)
                cur.screen = merge(cur.screen, again)
                captionLenExpanded = again.caption.length
                beginCaptionCollapse(now)
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
                if (commentAtMs >= 0 && el >= commentAtMs) {
                    commentAtMs = -1
                    if (startCommentPeek(root, now)) return
                }
                if (likeAtMs >= 0 && !liked && el >= likeAtMs) {
                    likeAtMs = -1
                    if (canLike() && tryLike(root)) { liked = true; registerLike() }
                }
                if (followAtMs >= 0 && !followed && el >= followAtMs) {
                    followAtMs = -1
                    if (prefs.profileCheck) {
                        if (startProfileVisit(root, now)) return
                    } else if (canFollow() && tryFollowOnReel(root)) registerFollow()
                }
                if (el >= watchMs) finishReel("izlendi")
            }
        }
    }

    private fun onSettled(root: AccessibilityNodeInfo, now: Long) {
        if (kind != Kind.NORMAL) {
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
                expandedTarget = target
                captionLenBefore = s.caption.length
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
            following = a.following || b.following,
            userNode = b.userNode ?: a.userNode,
            commentButton = b.commentButton ?: a.commentButton
        )
    }

    // ================================================================ gezintiler

    /** Açıklama okunduktan sonra kapat: önce tekrar dokun, hâlâ açıksa GERİ */
    private fun beginCaptionCollapse(now: Long) {
        val t = expandedTarget
        if (captionLenExpanded <= captionLenBefore + 5 || t == null) { startAnalysis(); return }
        gestures.markOwnAction()
        try { t.refresh(); t.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (e: Exception) { }
        exc = Exc.CAPTION
        excStage = 3
        excStart = now
        excAt = now + HumanTiming.between(600, 1_000)
    }

    private fun startCommentPeek(root: AccessibilityNodeInfo, now: Long): Boolean {
        val btn = ReelScreenReader.read(root, screenW, screenH).commentButton ?: cur.screen?.commentButton ?: return false
        val target = clickableAncestor(btn) ?: btn
        gestures.markOwnAction()
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        commentTimes.addLast(System.currentTimeMillis())
        prefs.addCommentPeek()
        exc = Exc.COMMENTS
        excStage = 0
        excStart = now
        excAt = now + HumanTiming.between(1_800, 3_200)
        return true
    }

    private fun startProfileVisit(root: AccessibilityNodeInfo, now: Long): Boolean {
        val s = ReelScreenReader.read(root, screenW, screenH)
        val expected = cur.screen?.user ?: return false
        if (s.user.isNotEmpty() && !s.user.equals(expected, ignoreCase = true)) return false
        val node = s.userNode ?: cur.screen?.userNode ?: return false
        val target = clickableAncestor(node) ?: node
        gestures.markOwnAction()
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        checkedProfiles += expected.lowercase()
        profileTimes.addLast(System.currentTimeMillis())
        prefs.addProfileVisit()
        exc = Exc.PROFILE
        excStage = 0
        excStart = now
        excAt = now + HumanTiming.between(1_500, 2_500)
        return true
    }

    private fun stepExcursion(root: AccessibilityNodeInfo, now: Long) {
        if (now - excStart > EXCURSION_TIMEOUT_MS) { giveUp(); return }
        if (now < excAt) return
        when (excStage) {
            0 -> when (exc) {
                Exc.COMMENTS -> {
                    val texts = visibleTexts(root, screenW, screenH)
                    if (!CommentAnalyzer.isCommentSheet(texts) && now - excStart < 5_000) { excAt = now + 500; return }
                    val r = CommentAnalyzer.analyze(root, screenW, screenH, cur.verdict?.ctaKeyword ?: cur.screen?.let { ProductDetector.extractCta(it.caption) })
                    cur.extras.addAll(CommentAnalyzer.toSignals(r))
                    Log.i(TAG, "Yorumlar: anahtar=" + r.keywordHits + " (" + r.topWord + "), satın alma soruları=" + r.buyQuestions + "/" + r.total)
                    excStage = 1
                    excAt = now + HumanTiming.between(600, 1_800)   // biraz daha "okuyormuş" gibi
                }
                Exc.PROFILE -> {
                    val user = cur.screen?.user ?: ""
                    val r = ProfileAnalyzer.analyze(root, screenW, screenH, user)
                    if (!r.isProfile) {
                        if (now - excStart > 6_000) beginReturn(now) else excAt = now + 600
                        return
                    }
                    val v = cur.verdict
                    val needed = if (v?.confidence == Confidence.CERTAIN) 5 else 7
                    cur.profileNote = "profil:" + r.score + " " + r.reasons.joinToString("+")
                    Log.i(TAG, "Profil @" + user + ": puan " + r.score + " " + r.reasons)
                    if (r.score >= needed && !r.following && r.followButton != null && canFollow()) {
                        val btn = clickableAncestor(r.followButton) ?: r.followButton
                        gestures.markOwnAction()
                        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) registerFollow()
                    }
                    excStage = 1
                    excAt = now + HumanTiming.between(900, 2_200)
                }
                else -> beginReturn(now)
            }
            1 -> beginReturn(now)
            3 -> {
                // Açıklama kapandı mı?
                val again = ReelScreenReader.read(root, screenW, screenH)
                val stillOpen = isReelsScreen(root) && again.caption.length >= captionLenExpanded - 3
                if (stillOpen || !isReelsScreen(root)) beginReturn(now) else endExcursion(now)
            }
            2 -> {
                if (returnedToReels(root)) { endExcursion(now); return }
                returnTries++
                when (returnTries) {
                    1 -> clickBackArrow(root)
                    2 -> gestures.edgeBack()
                    3 -> clickReelsTab(root)
                    4 -> performGlobalAction(GLOBAL_ACTION_BACK)
                    else -> { giveUp(); return }
                }
                excAt = now + 1_400
            }
        }
    }

    private fun beginReturn(now: Long) {
        excStage = 2
        returnTries = 0
        gestures.markOwnAction()
        performGlobalAction(GLOBAL_ACTION_BACK)   // tuşlu ve hareketli gezinmede aynı şekilde çalışır
        excAt = now + 1_300
    }

    private fun returnedToReels(root: AccessibilityNodeInfo): Boolean {
        if (!isReelsScreen(root)) return false
        val texts = visibleTexts(root, screenW, screenH)
        return !CommentAnalyzer.isCommentSheet(texts) && !ProfileAnalyzer.isProfileScreen(texts)
    }

    private fun clickBackArrow(root: AccessibilityNodeInfo) {
        val arrow = visibleTexts(root, screenW, screenH)
            .firstOrNull { it.rect.centerY() < screenH * 0.15 && it.text.trim().lowercase() in BACK_WORDS }
        val target = arrow?.let { clickableAncestor(it.node) ?: it.node }
        gestures.markOwnAction()
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun clickReelsTab(root: AccessibilityNodeInfo) {
        var tab: AccessibilityNodeInfo? = null
        walk(root) { n ->
            val id = n.viewIdResourceName ?: ""
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.centerY() > screenH * 0.85 && (id.contains("clips_tab") || n.contentDescription?.toString().equals("Reels", true))) tab = n
            tab != null
        }
        val t = tab ?: return
        reelsTabUsed = true
        gestures.markOwnAction()
        (clickableAncestor(t) ?: t).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun endExcursion(now: Long) {
        val type = exc
        exc = Exc.NONE
        excStage = 0
        if (reelsTabUsed) { reelsTabUsed = false; startNewReel(Kind.NORMAL); return }
        when (type) {
            Exc.CAPTION -> startAnalysis()
            Exc.COMMENTS -> {
                val was = cur.verdict?.likeWorthy == true
                val v = evaluateProduct()
                if (v.likeWorthy && !was) planLike()
                planFollow()
                continueWatching(now)
            }
            else -> continueWatching(now)
        }
    }

    private fun continueWatching(now: Long) {
        val el = now - reelStart
        watchMs = maxOf(watchMs, el + HumanTiming.between(1_200, 4_000))
    }

    private fun giveUp() {
        exc = Exc.NONE
        excStage = 0
        onReels = false
        gestureBusy = false
        stopTicking()
        toast("ReelScout Reels'e geri dönemedi. Reels sekmesini açınca kendiliğinden devam eder.")
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
        if (prefs.targetLang == "any") { decide("-"); return }
        val text = (cur.screen?.caption ?: "") + " " + cur.ocr.joinToString(" ")
        val v = vision
        if (v == null) { decide("und"); return }
        v.identifyLanguage(text) { lang -> if (id == reelId && phase == Phase.ANALYZING) decide(lang) }
    }

    private fun decide(lang: String) {
        cur.lang = lang
        val now = SystemClock.uptimeMillis()
        val target = prefs.targetLang
        val pass = target == "any" || lang == target || (lang == "und" && !prefs.skipUnknown)
        if (!pass) {
            phase = Phase.SKIPPING
            skipAt = now + HumanTiming.langSkipDelayMs()
            prefs.addLangSkip()
            Log.i(TAG, "Dil: $lang → geçiliyor")
            return
        }
        if (prefs.isWarmup) { decideWarmup(now, lang); return }

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
        planCommentPeek()
        planFollow()
        phase = Phase.WATCHING
        Log.i(TAG, "Dil: $lang | ürün: " + v.confidence.label + " (" + v.score + ") " + v.signals + " | mod: " + HumanTiming.mood.label)
    }

    /** Hesap ısındırma: ürün analizi yok; ilgi alanına göre daha uzun izle ve daha sık beğen */
    private fun decideWarmup(now: Long, lang: String) {
        val s = cur.screen
        val text = listOfNotNull(s?.caption, s?.allText, s?.user).joinToString(" ") + " " + cur.ocr.joinToString(" ")
        val interest = KeywordMatcher.matchAny(text, KeywordMatcher.parse(prefs.interests))
        cur.warmInterest = interest != null
        val el = now - reelStart
        watchMs = el + HumanTiming.watchMs(prefs.minWatchSec, prefs.maxWatchSec, cur.warmInterest)
        val sponsored = s?.sponsored == true
        val p = minOf(0.9, prefs.likeChance / 100.0 * (if (cur.warmInterest) 1.8 else 1.0))
        likeDecided = true
        if (prefs.likeEnabled && !sponsored && HumanTiming.chance(p)) {
            likeAtMs = maxOf(el + 1_500, el + ((watchMs - el) * HumanTiming.likeFraction()).toLong())
        }
        phase = Phase.WATCHING
        Log.i(TAG, "Isındırma | dil: $lang | ilgi: " + (interest ?: "-") + " | beğeni planı: " + (likeAtMs >= 0) + " | mod: " + HumanTiming.mood.label)
    }

    private fun evaluateProduct(): ProductVerdict {
        val s = cur.screen
        val text = listOfNotNull(s?.caption, s?.allText).joinToString(" ") + " " + cur.ocr.joinToString(" ")
        val user = s?.user ?: ""
        val history = if (user.isBlank()) 0
            else accounts.get(user).products + (if (user.lowercase() in ProfileAnalyzer.SEEDS) 2 else 0)
        val ctx = MatchContext(user, s?.metrics ?: emptyList(), prefs.keywords, prefs.excludeKeywords, prefs.keywordRequired)
        val v = ProductDetector.evaluate(text, s?.shopButton == true, cur.labels, history, prefs.sensitivity, cur.extras, ctx)
        cur.verdict = v
        return v
    }

    private fun onExtraFrame(id: Int) {
        if (id != reelId || phase != Phase.WATCHING) return
        val was = cur.verdict?.likeWorthy == true
        val v = evaluateProduct()
        if (v.likeWorthy && !was) {
            val el = SystemClock.uptimeMillis() - reelStart
            watchMs = maxOf(watchMs, el + HumanTiming.between(4_000, 10_000))
            planLike()
        }
        planCommentPeek()
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

    /** Emin olunamayan (olası / büyük ihtimal) videolarda yorumlara göz at */
    private fun planCommentPeek() {
        if (commentDecided || !prefs.commentPeek) return
        val v = cur.verdict ?: return
        if (v.gated || v.confidence == Confidence.CERTAIN || v.confidence == Confidence.NONE) return
        if (v.strong == 0 && v.ctaKeyword == null) return
        if (cur.screen?.commentButton == null) return
        prune(commentTimes)
        if (commentTimes.size >= 6) return
        commentDecided = true
        if (!HumanTiming.chance(0.75)) return
        val el = SystemClock.uptimeMillis() - reelStart
        val span = maxOf(2_500L, (watchMs - el) / 2)
        commentAtMs = el + HumanTiming.between(2_000, span)
        watchMs = maxOf(watchMs, commentAtMs + 6_000)
    }

    /** Takip adayı: kesin ürün (ya da profil kontrolü açıksa büyük ihtimal + profil onayı) */
    private fun planFollow() {
        if (followDecided || !prefs.autoFollow) return
        val v = cur.verdict ?: return
        if (v.gated) return
        val s = cur.screen ?: return
        if (s.user.isBlank() || s.following) return
        val key = s.user.lowercase()
        val info = accounts.get(s.user)
        if (info.followed) return
        if (prefs.profileCheck) {
            if (v.confidence < Confidence.LIKELY) return
            if (key in checkedProfiles) return
            if (info.products + 1 < prefs.followVideosNeeded && key !in ProfileAnalyzer.SEEDS) return
            prune(profileTimes)
            if (profileTimes.size >= 4) return
            if (s.userNode == null) return
        } else {
            if (v.confidence != Confidence.CERTAIN) return
            if (info.certain + 1 < prefs.followVideosNeeded) return
        }
        if (!canFollow()) return
        followDecided = true
        val el = SystemClock.uptimeMillis() - reelStart
        val at = el + ((watchMs - el) * HumanTiming.followFraction()).toLong()
        followAtMs = maxOf(at, el + 2_000, if (likeAtMs >= 0) likeAtMs + 1_500 else 0L, if (commentAtMs >= 0) commentAtMs + 5_000 else 0L)
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
        nextBlockCheck = SystemClock.uptimeMillis() + 2_500
    }

    private fun registerFollow() {
        followed = true
        followTimes.addLast(System.currentTimeMillis())
        prefs.addFollow()
        nextBlockCheck = SystemClock.uptimeMillis() + 2_500
        cur.screen?.user?.let { accounts.markFollowed(it) }
        Log.i(TAG, "Takip edildi: " + (cur.screen?.user ?: "?"))
    }

    private fun finishReel(result: String) {
        val s = cur.screen
        val v = cur.verdict
        val user = s?.user ?: ""
        val worthy = v?.likeWorthy == true

        if (result == "izlendi" && v != null) accounts.record(user, v.confidence)
        if (cur.warmInterest) prefs.addInterest()
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
            val signals = (v?.signals ?: emptyList()) + (if (cur.profileNote.isNotEmpty()) listOf(cur.profileNote) else emptyList()) +
                    (if (prefs.isWarmup) listOf("isindirma" + (if (cur.warmInterest) ":ilgi" else "")) else emptyList())
            logger.log(
                ReelRecord(
                    result = result, lang = cur.lang, isProduct = worthy,
                    confidence = v?.confidence?.label ?: "-", score = v?.score ?: 0,
                    signals = signals, cta = v?.ctaKeyword,
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
        prevWorthy = worthy || cur.warmInterest
        prevWatched = result == "izlendi"

        if (prefs.breaksEnabled && sessionEndsAt in 1L until now) {
            val b = HumanTiming.breakMs()
            holdUntil = now + b
            sessionEndsAt = holdUntil + HumanTiming.sessionMs()
            swipeAfterHold = true
            phase = Phase.SETTLING; settleMs = Long.MAX_VALUE
            toast("☕ " + (b / 60000) + " dk mola, sonra kendiliğinden devam eder")
            return
        }
        if (prefs.rewatchEnabled && wasPrevWatched && HumanTiming.chance(if (wasPrevWorthy) 0.15 else 0.03)) {
            prefs.addRewatch()
            prevWatched = false
            swipe(up = false, nextKind = Kind.BACK)
            return
        }
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

    private fun tryFollowOnReel(root: AccessibilityNodeInfo): Boolean {
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
