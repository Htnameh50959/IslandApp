package com.staad.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Little "now playing" bars, like the waveform next to the camera on iPhone. */
class EqualizerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bars = 4
    private val phase = FloatArray(bars) { Random.nextFloat() * 6.28f }
    private val speed = floatArrayOf(3.6f, 4.7f, 2.9f, 4.1f)
    private val rect = RectF()
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    var color: Int = Color.WHITE
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var playing = false
        set(value) {
            if (field == value) return
            field = value
            syncTicker()
            invalidate()
        }

    private fun syncTicker() {
        if (playing && isAttachedToWindow) {
            if (!ticker.isStarted) ticker.start()
        } else {
            ticker.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val barW = width / (bars * 2f - 1f)
        val t = (SystemClock.uptimeMillis() % 600_000L) / 1000f
        for (i in 0 until bars) {
            val amp = if (playing) 0.25f + 0.75f * abs(sin(t * speed[i] + phase[i])) else 0.2f
            val bh = max(barW, height * amp)
            val left = i * 2 * barW
            val top = (height - bh) / 2f
            rect.set(left, top, left + barW, top + bh)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}

/** Thin rounded progress track. Tapping/dragging seeks the track. */
class ProgressLine(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var color: Int
        get() = fill.color
        set(value) {
            fill.color = value
            invalidate()
        }

    /** Callback when the user taps/drags the track, with the new 0..1 fraction. */
    var onSeek: ((Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        if (fraction > 0f) {
            canvas.drawRoundRect(0f, 0f, max(height.toFloat(), width * fraction), height.toFloat(), r, r, fill)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val w = width.toFloat()
                if (w > 0f) {
                    val f = (ev.x / w).coerceIn(0f, 1f)
                    fraction = f
                    onSeek?.invoke(f)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}

/** A bright accent color pulled from album art (white for greyscale art). */
fun accentOf(bmp: Bitmap?): Int {
    if (bmp == null) return Color.WHITE
    return try {
        val src = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val px = Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(px, hsv)
        if (hsv[1] < 0.15f) return Color.WHITE
        hsv[1] = max(hsv[1], 0.5f)
        hsv[2] = max(hsv[2], 0.9f)
        Color.HSVToColor(hsv)
    } catch (_: Throwable) {
        Color.WHITE
    }
}
