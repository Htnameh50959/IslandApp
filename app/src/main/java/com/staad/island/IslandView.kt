package com.staad.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.text.TextUtils
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The island itself: a black pill that springs between sizes and swaps the
 * content inside it.
 *
 * Motion is driven by real springs (width, height, corner radius, press
 * scale) stepped every frame, so a change that arrives mid-animation keeps
 * the current momentum instead of restarting — like iOS.
 *
 * The overlay window is only resized when the pill needs more room (at the
 * start of a grow) or when it has settled (to give touches back to the apps
 * underneath). Every frame in between only redraws: no layout passes.
 *
 * Geometry: the window is horizontally centred on the anchor (the camera, or
 * the top-centre of the screen). Its top edge is where a compact pill's top
 * edge sits. Pills shorter than the compact height (the idle pill around the
 * camera) are vertically centred on the anchor instead.
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
        /** Where the settled island is (window coordinates), or null when it shouldn't take touches. */
        fun onTouchArea(area: android.graphics.Rect?)
    }

    enum class Mode { IDLE, COMPACT_MEDIA, CHARGING, NOTIFICATION, MEDIA }

    /** Where the camera is, in pixels. holeW/holeH are 0 when there's no usable cutout. */
    data class Geometry(
        val holeW: Int = 0,
        val holeH: Int = 0,
        val compactH: Int = 0,
        /** Screen x the island is centred on, and the screen width. */
        val anchorX: Int = 0,
        val screenW: Int = 0,
    )

    var callbacks: Callbacks? = null
    var cfg: IslandConfig = IslandConfig.DEFAULT
    var mode: Mode = Mode.IDLE
        private set

    /** Set by the service; it re-renders afterwards so the new sizes take effect. */
    var geometry = Geometry()

    /** True when the idle island has nothing on screen and shouldn't take touches. */
    val idleHidden: Boolean
        get() = mode == Mode.IDLE && !idleVisible()

    private val density = resources.displayMetrics.density
    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    private val green = 0xFF30D158.toInt()
    private val grey = 0xFF98989F.toInt()

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
    private var currentLayer: View? = null
    private val layerAnims = HashMap<View, ValueAnimator>()
    private val fadeLevel = HashMap<View, Float>()
    private val blurLevel = HashMap<View, Float>()

    // ── Springs & drawing ───────────────────────────────────────────────────
    private val sw = Spring(0f)
    private val sh = Spring(0f)
    private val sr = Spring(0f)
    private val sPress = Spring(1f).apply { tune(response = 0.28f, damping = 0.6f) }
    private val springs = listOf(sw, sh, sr, sPress)
    private var running = false
    private var lastFrameNs = 0L
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var shown = true

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nowNs: Long) {
            val dt = if (lastFrameNs == 0L) 1f / 60f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 1f / 30f)
            lastFrameNs = nowNs
            var settled = true
            for (s in springs) {
                s.step(dt)
                if (!s.settled(if (s === sPress) 0.001f else 0.5f)) settled = false
            }
            if (settled) springs.forEach { it.snap() }
            redraw()
            if (settled) {
                running = false
                lastFrameNs = 0L
                onSettled()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    rect.left.roundToInt(), rect.top.roundToInt(),
                    rect.right.roundToInt(), rect.bottom.roundToInt(),
                    min(sr.value * sPress.value, rect.height() / 2f),
                )
            }
        }
        for (l in layers) {
            l.alpha = 0f
            l.visibility = INVISIBLE
            addView(l, LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        }

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
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !rect.contains(ev.x, ev.y)) {
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                // iOS-style squish: the island swells slightly under your finger
                MotionEvent.ACTION_DOWN -> press(1.05f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(1f)
            }
            detector.onTouchEvent(ev)
        }
    }

    // The window has a fixed size (set by the service); children get their own sizes.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams
            c.measure(
                MeasureSpec.makeMeasureSpec(max(lp.width, 0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(lp.height, 0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        }
        redraw()
    }

    /**
     * Touches arrive from the small touch window that sits over the settled
     * island; (dx, dy) moves them into this window's coordinates.
     */
    fun forwardTouch(ev: MotionEvent, dx: Float, dy: Float): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            callbacks?.onOutsideTouch()
            return true
        }
        val e = MotionEvent.obtain(ev)
        e.offsetLocation(dx, dy)
        val handled = dispatchTouchEvent(e)
        e.recycle()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(sr.value * sPress.value, rect.height() / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        layerAnims.values.forEach { it.cancel() }
        super.onDetachedFromWindow()
    }

    // ── Public states ───────────────────────────────────────────────────────

    fun showIdle() {
        mode = Mode.IDLE
        compactBars.playing = false
        mediaBars.playing = false
        val g = geometry
        when {
            // shrink back into the camera, like iPhone
            g.holeW > 0 -> morph(g.holeW, g.holeH, g.holeH / 2f, null)
            // no camera to hide in: keep a pill if wanted, otherwise melt into a dot
            cfg.showIdle && !cfg.fitCamera -> morph(dp(cfg.widthDp), compactH(), compactH() / 2f, null)
            else -> morph(compactH(), dp(6), dp(3).toFloat(), null)
        }
        setShown(idleVisible())
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
        setShown(true)
        morph(compactWidth(), compactH(), compactH() / 2f, compactLayer)
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
        setShown(true)
        morph(compactWidth(), compactH(), compactH() / 2f, compactLayer)
    }

    fun showNotification(icon: Drawable?, title: String, text: String) {
        val replacing = mode == Mode.NOTIFICATION
        mode = Mode.NOTIFICATION
        val w = expandedWidth()
        val h = dp(84)
        sizeLayer(notifLayer, w, h)
        compactBars.playing = false
        mediaBars.playing = false
        setShown(true)
        if (replacing) {
            // A newer notification takes over: the old content blurs away,
            // the new one blurs in, and the island gives a little kick.
            swapContent(notifLayer) {
                fillNotification(icon, title, text)
            }
            kick()
        } else {
            fillNotification(icon, title, text)
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
        setShown(true)
        morph(w, h, dp(46).toFloat(), mediaLayer)
    }

    fun updateMediaProgress(posMs: Long, durMs: Long) {
        val hasDur = durMs > 0
        mediaProgress.fraction = if (hasDur) posMs.toFloat() / durMs else 0f
        mediaElapsed.text = fmt(posMs)
        mediaRemaining.text = if (hasDur) "-" + fmt(durMs - posMs) else "--:--"
    }

    /** Small "nope" wiggle for taps that have nothing to expand into. */
    fun bounce() = kick()

    // ── Internals ───────────────────────────────────────────────────────────

    private fun idleVisible() = cfg.showIdle && (geometry.holeW > 0 || !cfg.fitCamera)

    private fun compactH() = max(dp(cfg.heightDp), geometry.compactH)

    private fun fillNotification(icon: Drawable?, title: String, text: String) {
        notifIcon.setImageDrawable(icon ?: context.getDrawable(R.mipmap.ic_launcher))
        notifTitle.text = title
        notifText.text = text
        notifText.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

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
        val h = compactH()
        sizeLayer(compactLayer, compactWidth(), h)
        val icon = (h - dp(10)).coerceAtLeast(dp(12))
        compactIcon.layoutParams = (compactIcon.layoutParams as LinearLayout.LayoutParams).apply {
            width = icon; height = icon
        }
        compactLayer.setPadding(dp(5), 0, dp(14), 0)
    }

    // Art and bars sit either side of the camera, with the camera's own
    // width (or the idle width when there's no camera) as the gap between.
    private fun compactWidth(): Int {
        val gap = if (geometry.holeW > 0) geometry.holeW + dp(40) else dp(cfg.widthDp)
        return gap + 2 * compactH() + dp(28)
    }

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
        if (layer !== currentLayer) swapContent(layer, null)

        val first = sw.value == 0f
        val growing = w.toFloat() * h > sw.target * sh.target
        if (growing) {
            // Opening: it stretches sideways first, then drops down, with one
            // soft bounce at the end.
            sw.tune(response = 0.42f, damping = 0.70f)
            sh.tune(response = 0.52f, damping = 0.76f)
            sr.tune(response = 0.50f, damping = 0.80f)
        } else {
            // Closing: it lifts up first, then narrows back into the camera.
            sh.tune(response = 0.34f, damping = 0.92f)
            sw.tune(response = 0.44f, damping = 0.88f)
            sr.tune(response = 0.40f, damping = 0.90f)
        }
        if (!first && !running && sw.value == w.toFloat() && sh.value == h.toFloat() && sr.value == r) {
            return // already there (e.g. the same state rendered again)
        }
        sw.target = w.toFloat()
        sh.target = h.toFloat()
        sr.target = r
        if (first) {
            springs.forEach { it.snap() }
            redraw()
            onSettled()
            return
        }
        callbacks?.onTouchArea(null) // until it settles in its new shape
        start()
    }

    private fun press(scale: Float) {
        sPress.target = scale
        start()
    }

    /** Give the island a nudge using the springs' own physics. */
    private fun kick() {
        sw.velocity += dp(260)
        sh.velocity += dp(60)
        start()
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun onSettled() {
        if (!shown) alpha = 0f
        val area = if (idleHidden || !shown) null else android.graphics.Rect(
            rect.left.toInt(), rect.top.toInt(), ceil(rect.right).toInt(), ceil(rect.bottom).toInt(),
        )
        callbacks?.onTouchArea(area)
    }

    private fun topFor(h: Float, ch: Float) = max(0f, (ch - h) / 2f)

    private fun redraw() {
        val ps = sPress.value
        val w = sw.value * ps
        val h = sh.value * ps
        val ch = compactH().toFloat()
        // the window spans the screen width, so window x == screen x
        val left = geometry.anchorX - w / 2f
        val top = topFor(h, ch)
        rect.set(left, top, left + w, top + h)
        for (l in layers) if (l.visibility == VISIBLE) applyLayer(l)
        invalidateOutline()
        invalidate()
    }

    /**
     * Content lives *inside* the island: it is scaled to fit the pill's
     * current size, so it grows out of the camera as the island opens and
     * shrinks back into it as it closes, blurred while it's small. On top of
     * that, `fadeLevel` cross-fades content that is arriving or leaving.
     */
    private fun applyLayer(l: View) {
        val lp = l.layoutParams
        if (lp.width <= 1 || lp.height <= 1) return
        val a = fadeLevel[l] ?: l.alpha
        val fit = min(rect.width() / lp.width, rect.height() / lp.height).coerceIn(0.3f, 1f)
        val readable = ((fit - 0.45f) / 0.4f).coerceIn(0f, 1f)
        val s = fit * (0.94f + 0.06f * a)
        l.scaleX = s
        l.scaleY = s
        l.alpha = a * readable
        l.translationX = rect.centerX() - lp.width / 2f
        l.translationY = rect.centerY() - lp.height / 2f
        if (Build.VERSION.SDK_INT >= 31) {
            val amount = max(1f - a, (1f - fit) * 1.6f).coerceIn(0f, 1f)
            val b = (amount * dp(14)).let { if (it < 0.5f) 0f else it }
            if (abs(b - (blurLevel[l] ?: 0f)) > 0.4f || (b == 0f && blurLevel[l] != 0f)) {
                blurLevel[l] = b
                l.setRenderEffect(if (b == 0f) null else RenderEffect.createBlurEffect(b, b, Shader.TileMode.DECAL))
            }
        }
    }

    private fun setShown(on: Boolean) {
        if (shown == on) return
        shown = on
        animate().cancel()
        if (on) {
            alpha = 1f
        } else {
            // shrink first, then fade away (onSettled finishes the job)
            animate().alpha(0f).setStartDelay(260).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /**
     * Blur the old content out and the new content in. `update` runs while
     * the outgoing content is invisible (used to swap a notification's text
     * without the new words flashing up in the old layout).
     */
    private fun swapContent(layer: View?, update: (() -> Unit)?) {
        val old = currentLayer
        currentLayer = layer
        if (old != null && old !== layer) fade(old, show = false, delay = 0)
        if (layer == null) return
        if (old === layer && update != null) {
            fade(layer, show = false, delay = 0, onEnd = {
                update()
                fade(layer, show = true, delay = 0)
            })
        } else {
            update?.invoke()
            fade(layer, show = true, delay = if (old != null) 60L else 30L)
        }
    }

    private val easeOut = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun fade(l: View, show: Boolean, delay: Long, onEnd: (() -> Unit)? = null) {
        layerAnims.remove(l)?.cancel()
        if (show) l.visibility = VISIBLE
        val from = fadeLevel[l] ?: 0f
        val to = if (show) 1f else 0f
        layerAnims[l] = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = if (show) 280 else 120
            interpolator = if (show) easeOut else DecelerateInterpolator()
            addUpdateListener {
                fadeLevel[l] = from + (to - from) * (it.animatedValue as Float)
                applyLayer(l)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (layerAnims[l] === animation) layerAnims.remove(l)
                    if (cancelled) return
                    if (!show && currentLayer !== l) l.visibility = INVISIBLE
                    onEnd?.invoke()
                }
            })
            start()
        }
        redraw()
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

/**
 * Damped harmonic spring. `response` is roughly how long one swing takes
 * (seconds) and `damping` is the damping ratio: 1 = no overshoot, lower =
 * bouncier. Same model as SwiftUI's spring(response:dampingFraction:).
 * Changing the target keeps the current velocity.
 */
private class Spring(var value: Float) {
    var target = value
    var velocity = 0f
    private var stiffness = 170f
    private var dampingCoef = 20f

    fun tune(response: Float, damping: Float) {
        val omega = (2.0 * Math.PI / response).toFloat()
        stiffness = omega * omega
        dampingCoef = 2f * damping * omega
    }

    fun step(dt: Float) {
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 240f) // small sub-steps keep it stable
            val accel = -stiffness * (value - target) - dampingCoef * velocity
            velocity += accel * h
            value += velocity * h
            left -= h
        }
    }

    fun settled(eps: Float) = abs(value - target) < eps && abs(velocity) < eps * 10

    fun snap() {
        value = target
        velocity = 0f
    }
}
