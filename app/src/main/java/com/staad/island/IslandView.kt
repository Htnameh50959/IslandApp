package com.staad.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The island itself: a black, rounded pill that morphs between sizes with a
 * springy animation and cross-fades the content that lives inside it.
 *
 * Content layers are laid out at their *final* size and centred inside the
 * pill, so they never reflow while the pill grows — the pill's rounded clip
 * simply reveals them.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class IslandView(context: Context) : FrameLayout(context) {

    interface Callbacks {
        fun onTap()
        fun onLongPress()
        fun onSwipeUp()
        fun onOutsideTouch()
        fun onPrev()
        fun onPlayPause()
        fun onNext()
    }

    enum class Mode { IDLE, COMPACT_MEDIA, CHARGING, NOTIFICATION, MEDIA }

    var callbacks: Callbacks? = null
    var cfg: IslandConfig = IslandConfig.DEFAULT
    var mode: Mode = Mode.IDLE
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    private val green = 0xFF30D158.toInt()
    private val grey = 0xFF98989F.toInt()

    // ── Pill ────────────────────────────────────────────────────────────────
    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val pill = FrameLayout(context).apply {
        background = bg
        clipToOutline = true
    }

    // ── Compact layer (art ◀ camera ▶ bars / charging %) ────────────────────
    private val compactIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        rounded(7f)
    }
    private val compactBars = EqualizerView(context)
    private val compactText = label(14f, Color.WHITE, bold = true)
    private val compactLayer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(compactIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(compactBars, LinearLayout.LayoutParams(dp(20), dp(15)))
        addView(compactText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    // ── Notification layer ──────────────────────────────────────────────────
    private val notifIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        oval()
    }
    private val notifTitle = label(15f, Color.WHITE, bold = true)
    private val notifText = label(13.5f, grey, lines = 2)
    private val notifLayer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(26), dp(10))
        addView(notifIcon, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(notifTitle)
            addView(notifText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    // ── Expanded media layer ────────────────────────────────────────────────
    private val mediaArt = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        rounded(14f)
    }
    private val mediaTitle = label(16f, Color.WHITE, bold = true)
    private val mediaArtist = label(14f, grey)
    private val mediaBars = EqualizerView(context)
    private val mediaProgress = ProgressLine(context)
    private val mediaElapsed = label(11.5f, grey).apply { fontFeatureSettings = "tnum" }
    private val mediaRemaining = label(11.5f, grey).apply { fontFeatureSettings = "tnum" }
    private val btnPrev = iconButton(R.drawable.ic_prev, 50) { callbacks?.onPrev() }
    private val btnPlay = iconButton(R.drawable.ic_play, 58) { callbacks?.onPlayPause() }
    private val btnNext = iconButton(R.drawable.ic_next, 50) { callbacks?.onNext() }
    private val mediaLayer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(12))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaArt, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                addView(mediaTitle)
                addView(mediaArtist, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(mediaBars, LinearLayout.LayoutParams(dp(26), dp(22)))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaElapsed)
            addView(mediaProgress, LinearLayout.LayoutParams(0, dp(5), 1f).apply {
                marginStart = dp(10); marginEnd = dp(10)
            })
            addView(mediaRemaining)
        }, LinearLayout.LayoutParams(-1, WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(btnPrev, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(btnPlay, LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                marginStart = dp(28); marginEnd = dp(28)
            })
            addView(btnNext, LinearLayout.LayoutParams(dp(50), dp(50)))
        }, LinearLayout.LayoutParams(-1, WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private val layers = listOf(compactLayer, notifLayer, mediaLayer)

    // ── Animation state ─────────────────────────────────────────────────────
    private var curW = 0
    private var curH = 0
    private var curR = 0f
    private var targetW = -1
    private var targetH = -1
    private var targetLayer: View? = null
    private var morphAnim: ValueAnimator? = null
    private var pillTargetAlpha = 1f

    init {
        clipChildren = false
        val w = dp(cfg.widthDp)
        val h = dp(cfg.heightDp)
        addView(pill, LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (l in layers) {
            l.alpha = 0f
            l.visibility = INVISIBLE
            pill.addView(l, LayoutParams(1, 1, Gravity.CENTER))
        }
        applySize(w, h, h / 2f)
        targetW = w; targetH = h

        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                callbacks?.onTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                callbacks?.onLongPress()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -500 && abs(vy) > abs(vx)) {
                    callbacks?.onSwipeUp()
                    return true
                }
                return false
            }
        })
        pill.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            callbacks?.onOutsideTouch()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Public states ───────────────────────────────────────────────────────

    fun showIdle() {
        mode = Mode.IDLE
        compactBars.playing = false
        mediaBars.playing = false
        val h = dp(cfg.heightDp)
        morph(dp(cfg.widthDp), h, h / 2f, null)
        setPillVisible(cfg.showIdle)
    }

    fun showCompactMedia(m: MediaState) {
        mode = Mode.COMPACT_MEDIA
        prepareCompact()
        compactIcon.setPadding(0, 0, 0, 0)
        if (m.art != null) {
            compactIcon.imageTintList = null
            compactIcon.setImageBitmap(m.art)
        } else {
            compactIcon.setImageResource(R.drawable.ic_music)
            compactIcon.imageTintList = ColorStateList.valueOf(m.accent)
        }
        compactText.visibility = GONE
        compactBars.visibility = VISIBLE
        compactBars.color = m.accent
        compactBars.playing = m.playing
        mediaBars.playing = false
        setPillVisible(true)
        val h = dp(cfg.heightDp)
        morph(compactWidth(), h, h / 2f, compactLayer)
    }

    fun showCharging(level: Int, plugged: Boolean) {
        mode = Mode.CHARGING
        prepareCompact()
        val color = if (plugged) green else Color.WHITE
        compactIcon.setPadding(dp(2), dp(2), dp(2), dp(2))
        compactIcon.setImageResource(if (plugged) R.drawable.ic_bolt else R.drawable.ic_battery)
        compactIcon.imageTintList = ColorStateList.valueOf(color)
        compactText.text = "$level%"
        compactText.setTextColor(color)
        compactText.visibility = VISIBLE
        compactBars.visibility = GONE
        compactBars.playing = false
        mediaBars.playing = false
        setPillVisible(true)
        val h = dp(cfg.heightDp)
        morph(compactWidth(), h, h / 2f, compactLayer)
    }

    fun showNotification(icon: Drawable?, title: String, text: String) {
        val wasShowing = mode == Mode.NOTIFICATION
        mode = Mode.NOTIFICATION
        val w = expandedWidth()
        val h = dp(84)
        sizeLayer(notifLayer, w, h)
        notifIcon.setImageDrawable(icon ?: context.getDrawable(R.mipmap.ic_launcher))
        notifTitle.text = title
        notifText.text = text
        notifText.visibility = if (text.isEmpty()) GONE else VISIBLE
        compactBars.playing = false
        mediaBars.playing = false
        setPillVisible(true)
        if (wasShowing) {
            // A newer notification replaced the one on screen: give it a little pulse.
            notifLayer.alpha = 0.2f
            notifLayer.animate().alpha(1f).setStartDelay(0).setDuration(220).start()
            bounce()
        }
        morph(w, h, h / 2f, notifLayer)
    }

    fun showMedia(m: MediaState) {
        mode = Mode.MEDIA
        val w = expandedWidth()
        val h = dp(196)
        sizeLayer(mediaLayer, w, h)
        updateMediaContent(m)
        compactBars.playing = false
        setPillVisible(true)
        morph(w, h, dp(46).toFloat(), mediaLayer)
    }

    fun updateMediaProgress(posMs: Long, durMs: Long) {
        val hasDur = durMs > 0
        mediaProgress.fraction = if (hasDur) posMs.toFloat() / durMs else 0f
        mediaElapsed.text = fmt(posMs)
        mediaRemaining.text = if (hasDur) "-" + fmt(durMs - posMs) else "--:--"
    }

    /** Small "nope" wiggle for taps that have nothing to expand into. */
    fun bounce() {
        if (morphAnim?.isRunning == true) return
        val w = curW
        val h = curH
        val r = curR
        morphAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 360
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                applySize(w + (dp(18) * t).roundToInt(), h + (dp(3) * t).roundToInt(), r + dp(1.5f) * t)
            }
            start()
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun updateMediaContent(m: MediaState) {
        if (m.art != null) {
            mediaArt.setImageBitmap(m.art)
            mediaArt.imageTintList = null
            mediaArt.background = null
            mediaArt.setPadding(0, 0, 0, 0)
        } else {
            mediaArt.setImageResource(R.drawable.ic_music)
            mediaArt.imageTintList = ColorStateList.valueOf(m.accent)
            mediaArt.setBackgroundColor(0xFF2C2C2E.toInt())
            mediaArt.setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        mediaTitle.text = m.title.ifBlank { "Now playing" }
        mediaArtist.text = m.artist
        mediaArtist.visibility = if (m.artist.isBlank()) GONE else VISIBLE
        mediaBars.color = m.accent
        mediaBars.playing = m.playing
        mediaProgress.color = m.accent
        btnPlay.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateMediaProgress(m.currentPosition(), m.durationMs)
    }

    private fun prepareCompact() {
        val h = dp(cfg.heightDp)
        sizeLayer(compactLayer, compactWidth(), h)
        val icon = (h - dp(10)).coerceAtLeast(dp(12))
        compactIcon.layoutParams = (compactIcon.layoutParams as LinearLayout.LayoutParams).apply {
            width = icon; height = icon
        }
        compactLayer.setPadding(dp(5), 0, dp(14), 0)
    }

    private fun compactWidth() = dp(cfg.widthDp) + 2 * dp(cfg.heightDp) + dp(28)

    private fun expandedWidth() = min(resources.displayMetrics.widthPixels - dp(20), dp(420))

    private fun sizeLayer(layer: View, w: Int, h: Int) {
        val lp = layer.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            layer.layoutParams = lp
        }
    }

    private fun morph(w: Int, h: Int, r: Float, layer: View?) {
        if (w == targetW && h == targetH && layer === targetLayer) return
        val layerChanged = layer !== targetLayer
        targetW = w; targetH = h; targetLayer = layer

        if (layerChanged) {
            for (l in layers) if (l !== layer) {
                l.animate().cancel()
                l.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .setStartDelay(0).setDuration(110)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { l.visibility = INVISIBLE }
                    .start()
            }
            layer?.let {
                it.animate().cancel()
                it.visibility = VISIBLE
                it.alpha = 0f
                it.scaleX = 0.88f
                it.scaleY = 0.88f
                it.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(140).setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        morphAnim?.cancel()
        val sw = curW.toFloat()
        val sh = curH.toFloat()
        val sr = curR
        val growing = w * h > curW * curH
        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (growing) 520 else 440
            interpolator = OvershootInterpolator(if (growing) 1.25f else 0.9f)
            addUpdateListener {
                val t = it.animatedValue as Float
                applySize(
                    (sw + (w - sw) * t).roundToInt().coerceAtLeast(dp(10)),
                    (sh + (h - sh) * t).roundToInt().coerceAtLeast(dp(10)),
                    sr + (r - sr) * t.coerceIn(0f, 1f),
                )
            }
            start()
        }
    }

    private fun applySize(w: Int, h: Int, r: Float) {
        curW = w; curH = h; curR = r
        val lp = pill.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            pill.layoutParams = lp
        }
        bg.cornerRadius = min(r, h / 2f)
        pill.invalidateOutline()
    }

    private fun setPillVisible(visible: Boolean) {
        val target = if (visible) 1f else 0f
        if (target == pillTargetAlpha) return
        pillTargetAlpha = target
        pill.animate().cancel()
        pill.animate().alpha(target)
            .setStartDelay(if (visible) 0L else 380L)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false, lines: Int = 1) =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(color)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun iconButton(res: Int, sizeDp: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(sizeDp * 0.2f)
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ShapeDrawable(OvalShape()))
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    private fun View.rounded(radiusDp: Float) {
        val r = radiusDp * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
    }

    private fun View.oval() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }
}
