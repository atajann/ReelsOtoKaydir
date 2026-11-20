package com.ates.reelsauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

/**
 * İnsan parmağına benzeyen hareketler.
 * Kaydırma, kavisli bir yol boyunca parçalara bölünür ve her parçanın süresi farklıdır:
 * başta hızlı, sona doğru yavaşlayan bir fiske (ease-out). Böylece hız sabit olmaz.
 */
class Gestures(private val svc: AccessibilityService, private val w: Int, private val h: Int) {

    @Volatile var lastGestureAt = 0L
        private set

    fun markOwnAction() { lastGestureAt = SystemClock.uptimeMillis() }

    fun swipeNext(onDone: (() -> Unit)? = null) =
        swipe(up = true, fraction = 1f, duration = HumanTiming.swipeDurationMs(), onDone = onDone)

    fun swipePrevious(onDone: (() -> Unit)? = null) =
        swipe(up = false, fraction = 1f, duration = HumanTiming.swipeDurationMs() + 60, onDone = onDone)

    /** Yarım bırakılan kaydırma: Instagram videoyu geri oturtur (insanlar bunu sık yapar) */
    fun abortedSwipe(onDone: (() -> Unit)? = null) =
        swipe(up = true, fraction = HumanTiming.nextFloat(0.15f, 0.28f), duration = HumanTiming.between(320, 560), onDone = onDone)

    private fun swipe(up: Boolean, fraction: Float, duration: Long, onDone: (() -> Unit)?) {
        val x0 = w / 2f + HumanTiming.nextInt(-w / 7, w / 7)
        val dist = h * HumanTiming.nextFloat(0.50f, 0.64f) * fraction
        val y0 = if (up) h * HumanTiming.nextFloat(0.66f, 0.80f) else h * HumanTiming.nextFloat(0.22f, 0.34f)
        val y1 = if (up) y0 - dist else y0 + dist
        val x1 = x0 + HumanTiming.nextInt(-45, 45)            // başparmak kayması
        val cx = (x0 + x1) / 2f + HumanTiming.nextInt(-60, 60) // kavis
        val cy = (y0 + y1) / 2f

        val n = 7
        val pts = (0..n).map { i ->
            val t = i / n.toFloat()
            val a = (1 - t) * (1 - t); val b = 2 * (1 - t) * t; val c = t * t
            PointF(a * x0 + b * cx + c * x1, a * y0 + b * cy + c * y1)
        }
        // Ease-out: ilk parçalar kısa sürer (hızlı), son parçalar uzun (yavaş)
        val weights = (0 until n).map { i -> 0.45 + 1.4 * Math.pow(i / (n - 1.0), 2.0) }
        val sum = weights.sum()
        val durs = weights.map { maxOf(8L, (duration * it / sum).toLong()) }

        lastGestureAt = SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dispatchSegments(pts, durs, onDone)
        } else {
            val p = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..n) lineTo(pts[i].x, pts[i].y) }
            dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, duration)).build(), onDone)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun dispatchSegments(pts: List<PointF>, durs: List<Long>, onDone: (() -> Unit)?) {
        fun seg(i: Int, prev: GestureDescription.StrokeDescription?) {
            val p = Path().apply { moveTo(pts[i].x, pts[i].y); lineTo(pts[i + 1].x, pts[i + 1].y) }
            val last = i == durs.size - 1
            val stroke = prev?.continueStroke(p, 0, durs[i], !last)
                ?: GestureDescription.StrokeDescription(p, 0, durs[i], !last)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            lastGestureAt = SystemClock.uptimeMillis()
            val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (last) { lastGestureAt = SystemClock.uptimeMillis(); onDone?.invoke() } else seg(i + 1, stroke)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lastGestureAt = SystemClock.uptimeMillis(); onDone?.invoke()
                }
            }, null)
            if (!ok) onDone?.invoke()
        }
        seg(0, null)
    }

    /** Ekranda rastgele bir noktaya çift dokunma (beğeni) */
    fun doubleTap() {
        val x = w / 2f + HumanTiming.nextInt(-w / 5, w / 5)
        val y = h * HumanTiming.nextFloat(0.33f, 0.56f)
        val gap = HumanTiming.between(100, 170)
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x + HumanTiming.nextInt(-7, 7), y + HumanTiming.nextInt(-7, 7)) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, HumanTiming.between(40, 75)))
            .addStroke(GestureDescription.StrokeDescription(p2, gap, HumanTiming.between(40, 75)))
            .build()
        dispatch(g, null)
    }

    private fun dispatch(g: GestureDescription, onDone: (() -> Unit)?) {
        lastGestureAt = SystemClock.uptimeMillis()
        val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { lastGestureAt = SystemClock.uptimeMillis(); onDone?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { lastGestureAt = SystemClock.uptimeMillis(); onDone?.invoke() }
        }, null)
        if (!ok) onDone?.invoke()
    }
}
