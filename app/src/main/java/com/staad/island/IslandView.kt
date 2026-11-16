package com.staad.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
import kotlin.math.sin

/**
 * The island: a black pill that springs between shapes, with the content
 * living inside it and a soft coloured glow around it (OriginOS style).
 *
 * Motion is driven by real springs (width, height, corner radius, press
 * scale) stepped every frame, so a change that arrives mid-animation keeps
 * the current momentum. Content is scaled to the pill's current size, so it
 * grows out of the camera and shrinks back into it.
 *
 * The island is drawn into one fixed window across the top of the screen
 * that never moves or resizes (that's what keeps the motion smooth). Touches
 * come from a separate small window over the settled island: see
 * [forwardTouch] and [Callbacks.onTouchArea].
 *
 * Geometry: the pill is horizontally centred on the anchor (the camera, or
 * the top-centre of the screen). Its top edge is where a compact pill's top
 * edge sits; pills shorter than that (the idle pill around the camera) are
 * vertically centred on the anchor instead.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class IslandView(context: Context) : FrameLayout(context) {

    interface Callbacks {
        fun onTap()
        fun onLongPress()
        fun onSwipeUp()
        /** Flicked sideways: switch to the next activity (or dismiss when there's none). */
        fun onSwipeSideways(toLeft: Boolean)
        fun onOutsideTouch()
        fun onPrev()
        fun onPlayPause()
        fun onNext()
        /** User dragged the media progress bar: fraction 0..1 of the track. */
        fun onSeek(fraction: Float)
        /** Where the settled island is (window coordinates), or null when it shouldn't take touches. */
        /** The small companion bubble was tapped: open that event's card. */
        fun onSecondaryTap()
        fun onTouchArea(area: Rect?)
    }

    enum class Mode { IDLE, COMPACT_MEDIA, CHARGING, STATUS, LIVE, HOTSPOT, NOTIFICATION, ACTIONS, DROP, MEDIA }

    /** Where the camera is, in pixels. holeW/holeH are 0 when there's no usable cutout. */
    data class Geometry(
        val holeW: Int = 0,
        val holeH: Int = 0,
        val compactH: Int = 0,
        /** Screen x the island is centred on, and the screen width. */
        val anchorX: Int = 0,
        val screenW: Int = 0,
    )

    /** A button on an action card: an app icon (kept in colour) or a white glyph. */
    class Action(val label: String, val icon: Drawable?, val glyph: Int, val run: () -> Unit)

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
    private val blue = 0xFF0A84FF.toInt()
    private val grey = 0xFF98989F.toInt()
    private val chip = 0xFF2C2C2E.toInt()

    // ── Drawing surfaces ────────────────────────────────────────────────────
    private val rect = RectF()
    // onDraw runs on every frame while a spring settles. Rebuilding four
    // LinearGradients each frame meant thousands of short-lived objects per
    // second on the main thread; keep the last ones and only rebuild when the
    // geometry or colour they were built from actually changes.
    private var bodyShaderTop = Float.NaN
    private var bodyShaderBottom = Float.NaN
    private var bodyShaderAlpha = -1
    private var washShaderKey = 0
    private var sheenShaderKey = 0
    private var rimShaderKey = 0
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1) * 0.75f
    }
    private val rimRect = RectF()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Own paint for the wash: sharing one with the sheen let the later gradient
     *  overwrite the wash's cached shader, so a redraw drew the wash white and the
     *  card fell back to black. */
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowRect = RectF()

    /** Crisp companion bubble body drawn beside the pill (kept off glowView so the soft
     *  halo passes behind it don't wash the bubble out). */
    private val bubbleView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            layoutSecondaryBubble()
            if (secondaryKind == SecondaryKind.NONE || secondaryRect.width() <= 0f) return
            drawSecondaryBubbleBody(canvas)
        }
    }

    // Secondary bubble (dual layout): small circle to the right of main pill for another active event.
    enum class SecondaryKind { NONE, MEDIA, TIMER, CHARGING, HOTSPOT, CALL, NAV, STATUS }
    private var secondaryKind = SecondaryKind.NONE
    private var secondaryPlaying = false
    private val secondaryRect = RectF()
    private val secondaryIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val secondaryBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt(); style = Paint.Style.FILL }
    private val secondaryAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.FILL }
    private var secondaryPhase = 0f
    private var secondaryAnim: ValueAnimator? = null
    /** 0 → hidden, 1 → fully on screen. Drives the bubble's entry/exit animation. */
    private var secondaryPresence = 0f
    private var secondaryPresenceAnim: ValueAnimator? = null

    /** Grow the bubble out of the pill on entry, shrink it back on exit. */
    private fun animatePresence(target: Float, enter: Boolean) {
        secondaryPresenceAnim?.cancel()
        val anim = ValueAnimator.ofFloat(secondaryPresence, target).apply {
            duration = if (enter) 300 else 170
            interpolator = if (enter) OvershootInterpolator(1.8f) else AccelerateInterpolator(1.6f)
            addUpdateListener {
                secondaryPresence = it.animatedValue as Float
                bubbleView.invalidate(); glowView.invalidate(); pillView.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    secondaryPresence = target
                    if (target == 0f) secondaryKind = SecondaryKind.NONE
                    bubbleView.invalidate(); glowView.invalidate()
                }
            })
        }
        secondaryPresenceAnim = anim
        anim.start()
    }

    fun setSecondaryBubble(kind: SecondaryKind, playing: Boolean = false) {
        if (kind == SecondaryKind.NONE) {
            // exit: keep the last kind on screen while the bubble shrinks away
            if (secondaryKind != SecondaryKind.NONE) animatePresence(0f, enter = false)
            secondaryPlaying = false
            secondaryAnim?.cancel()
            secondaryAnim = null
            secondaryPhase = 0f
            return
        }
        if (kind != secondaryKind) animatePresence(1f, enter = true)
        secondaryKind = kind
        secondaryPlaying = playing
        val animate = kind == SecondaryKind.MEDIA
        if (kind != SecondaryKind.NONE && animate) {
            if (secondaryAnim == null) {
                secondaryAnim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { secondaryPhase = it.animatedValue as Float; pillView.invalidate(); glowView.invalidate(); bubbleView.invalidate() }
                    start()
                }
            } else if (secondaryAnim?.isStarted != true) {
                secondaryAnim?.start()
            }
        } else {
            secondaryAnim?.cancel()
            secondaryAnim = null
            secondaryPhase = 0f
        }
        pillView.invalidate(); glowView.invalidate(); bubbleView.invalidate()
    }

    /**
     * Soft light behind the pill. Built from concentric rounded rects rather than a
     * RenderEffect blur: on several GPUs a blurred view inside a translucent overlay
     * window renders as colour garbage while the island animates.
     */
    private val glowView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val a = glowStrength()
            glowPaint.color = glowColor
            layoutSecondaryBubble()
            if (secondaryKind != SecondaryKind.NONE && secondaryRect.width() > 0f) {
                // Soft halo only; the crisp body is drawn by bubbleView so it stays sharp.
                drawSecondaryBubbleHalo(canvas, a)
            }
            if (a > 0.01f) drawSoftHalo(canvas, rect, glowColor, a)
        }
    }

    private val haloRect = RectF()

    /** Airbrushed halo: faint concentric passes, so no GPU blur filter is needed. */
    private fun drawSoftHalo(canvas: Canvas, base: RectF, color: Int, strength: Float) {
        glowPaint.color = color
        for (pass in 5 downTo 1) {
            val grow = dp(1.8f).toFloat() * pass
            haloRect.set(base.left - grow, base.top - grow, base.right + grow, base.bottom + grow)
            val fall = 1f - (pass - 1) / 5f
            glowPaint.alpha = (strength * 66f * fall * fall).roundToInt().coerceIn(0, 255)
            if (glowPaint.alpha == 0) continue
            val r = min(cornerRadius() + grow, haloRect.height() / 2f)
            canvas.drawRoundRect(haloRect, r, r, glowPaint)
        }
    }

    /** Crisp bubble body (black circle + icon/bars), drawn by bubbleView so it stays sharp. */
    private fun drawSecondaryBubbleBody(canvas: Canvas) {
        val p = secondaryPresence.coerceIn(0f, 1f)
        if (p <= 0.02f) return
        val layer = canvas.saveLayerAlpha(secondaryRect, (p * 255).toInt().coerceIn(0, 255))
        val sr2 = secondaryRect.height() / 2f
        canvas.drawRoundRect(secondaryRect, sr2, sr2, shadowPaint)
        val cx = secondaryRect.centerX(); val cy = secondaryRect.centerY()
        when (secondaryKind) {
            SecondaryKind.MEDIA -> {
                val barW = dp(2.2f).toFloat(); val gap = dp(2.2f).toFloat()
                val baseH = dp(6f).toFloat(); val maxH = dp(13f).toFloat()
                val bars = 4; val totalW = bars * barW + (bars - 1) * gap
                var x = cx - totalW / 2f
                for (i in 0 until bars) {
                    val ph = secondaryPhase + i * 0.9f
                    val anim = if (secondaryPlaying) (0.35f + 0.65f * (0.5f + 0.5f * sin(ph))) else 0.35f
                    val h = baseH + (maxH - baseH) * anim
                    val top = cy - h / 2f
                    val paint = if (i == 2) secondaryBarPaint else secondaryIconPaint
                    paint.alpha = if (i == 2) 230 else 210
                    val rr = RectF(x, top, x + barW, top + h)
                    canvas.drawRoundRect(rr, barW / 2f, barW / 2f, paint)
                    x += barW + gap
                }
            }
            SecondaryKind.TIMER -> { secondaryIconPaint.alpha = 230; val s = dp(6f).toFloat(); val p = android.graphics.Path(); p.moveTo(cx-s,cy-s);p.lineTo(cx+s,cy-s);p.lineTo(cx,cy);p.close(); canvas.drawPath(p,secondaryIconPaint); val p2=android.graphics.Path(); p2.moveTo(cx-s,cy+s);p2.lineTo(cx+s,cy+s);p2.lineTo(cx,cy);p2.close(); canvas.drawPath(p2,secondaryIconPaint) }
            SecondaryKind.CHARGING -> { secondaryAccentPaint.color = green; secondaryAccentPaint.alpha=230; val s=dp(7f).toFloat(); val p=android.graphics.Path(); p.moveTo(cx+s*0.1f,cy-s);p.lineTo(cx-s*0.4f,cy+s*0.1f);p.lineTo(cx,cy+s*0.1f);p.lineTo(cx-s*0.1f,cy+s);p.lineTo(cx+s*0.4f,cy-s*0.1f);p.lineTo(cx,cy-s*0.1f);p.close(); canvas.drawPath(p,secondaryAccentPaint) }
            SecondaryKind.HOTSPOT -> { secondaryAccentPaint.color=green; secondaryAccentPaint.alpha=230; canvas.drawCircle(cx, cy + dp(3f).toFloat(), dp(1.8f).toFloat(), secondaryAccentPaint); val sp=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=dp(1.6f).toFloat();color=green;alpha=220}; val d5=dp(5f).toFloat(); val d2=dp(2f).toFloat(); val d8=dp(8f).toFloat(); val d11=dp(11f).toFloat(); canvas.drawArc(RectF(cx-d5,cy-d2,cx+d5,cy+d8),200f,140f,false,sp); canvas.drawArc(RectF(cx-d8,cy-d5,cx+d8,cy+d11),200f,140f,false,sp) }
            SecondaryKind.CALL -> { secondaryAccentPaint.color=green; secondaryAccentPaint.alpha=230; canvas.drawCircle(cx,cy,dp(5.5f).toFloat(),secondaryAccentPaint) }
            SecondaryKind.NAV -> { secondaryIconPaint.alpha=230; val p=android.graphics.Path(); val d7=dp(7f).toFloat(); val d5n=dp(5f).toFloat(); val d6n=dp(6f).toFloat(); val d3n=dp(3f).toFloat(); p.moveTo(cx,cy-d7);p.lineTo(cx+d5n,cy+d6n);p.lineTo(cx,cy+d3n);p.lineTo(cx-d5n,cy+d6n);p.close(); canvas.drawPath(p,secondaryIconPaint) }
            SecondaryKind.STATUS -> { secondaryIconPaint.alpha=230; canvas.drawCircle(cx,cy,dp(4.5f).toFloat(),secondaryIconPaint) }
            SecondaryKind.NONE -> {}
        }
        canvas.restoreToCount(layer)
    }

    /** Soft coloured halo around the companion bubble, drawn by glowView (blurred). */
    private fun drawSecondaryBubbleHalo(canvas: Canvas, a: Float) {
        if (a <= 0.01f) return
        val p = secondaryPresence.coerceIn(0f, 1f)
        if (p <= 0.02f) return
        val sr2 = secondaryRect.height() / 2f
        val g2 = RectF(secondaryRect)
        g2.inset(-dp(2).toFloat(), -dp(2).toFloat())
        glowPaint.alpha = (a * 120 * p).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(g2, sr2 + dp(2), sr2 + dp(2), glowPaint)
    }

    /** The black pill. It clips the content to its rounded shape. */
    private val pillView = object : FrameLayout(context) {
        override fun onDraw(canvas: Canvas) {
            val r = cornerRadius()
            if (rect.height() <= 0f) return
            // Dark glass: a deep, mostly opaque body (text behind it must never
            // compete with the island's own), lighter at the top like frosted
            // glass catching light. Compact pills stay near-black so they melt
            // into the camera.
            val big = ((rect.height() / max(1f, compactH().toFloat())) - 1.2f).coerceIn(0f, 1f)
            // Solid base first: a few GPU drivers composite the gradient shader
            // semi-transparently over a translucent overlay window, which made
            // the open cards see-through.
            bodyPaint.color = 0xFF08080A.toInt()
            canvas.drawRoundRect(rect, r, r, bodyPaint)
            val topAlpha = (0xF4 - 0x10 * big).roundToInt()
            if (bodyShaderTop != rect.top || bodyShaderBottom != rect.bottom ||
                bodyShaderAlpha != topAlpha
            ) {
                pillPaint.shader = LinearGradient(
                    0f, rect.top, 0f, rect.bottom,
                    Color.argb(topAlpha, 0x1E, 0x1E, 0x24), Color.argb(0xF8, 0x04, 0x04, 0x06),
                    Shader.TileMode.CLAMP,
                )
                bodyShaderTop = rect.top
                bodyShaderBottom = rect.bottom
                bodyShaderAlpha = topAlpha
            }
            canvas.drawRoundRect(rect, r, r, pillPaint)
            // Big cards get a soft wash of the app's / album's colour rising from
            // the bottom edge, like OriginOS 6's timer and music cards.
            val tall = ((rect.height() / max(1f, compactH().toFloat())) - 1.4f).coerceIn(0f, 1f)
            val tint = tintColor
            if (tint != null && tall > 0f && tintLevel > 0f) {
                val a = (0x5C * tall * tintLevel).roundToInt().coerceIn(0, 255)
                val washKey = (tint * 31 + a) * 31 + (rect.height() * 100).toInt()
                if (washShaderKey != washKey) {
                    washPaint.shader = LinearGradient(
                        0f, rect.top + rect.height() * 0.35f, 0f, rect.bottom,
                        Color.argb(0, Color.red(tint), Color.green(tint), Color.blue(tint)),
                        Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint)),
                        Shader.TileMode.CLAMP,
                    )
                    washShaderKey = washKey
                }
                canvas.drawRoundRect(rect, r, r, washPaint)
            }
            // Glass highlight along the top, and a hairline rim that is brighter
            // at the top edge (soft neumorphic depth). Only on the open cards.
            if (big > 0f) {
                val sheenKey = (rect.top * 31 + rect.height() * 31 + big * 7).toInt()
                if (sheenShaderKey != sheenKey) {
                    sheenPaint.shader = LinearGradient(
                        0f, rect.top, 0f, rect.top + min(rect.height() * 0.4f, dp(56).toFloat()),
                        Color.argb((0x1C * big).roundToInt(), 255, 255, 255), 0x00FFFFFF,
                        Shader.TileMode.CLAMP,
                    )
                    sheenShaderKey = sheenKey
                }
                canvas.drawRoundRect(rect, r, r, sheenPaint)
                val sw = rimPaint.strokeWidth / 2f
                rimRect.set(rect.left + sw, rect.top + sw, rect.right - sw, rect.bottom - sw)
                val rimKey = (rect.top * 31 + rect.bottom * 31 + big * 13).toInt()
                if (rimShaderKey != rimKey) {
                    rimPaint.shader = LinearGradient(
                        0f, rect.top, 0f, rect.bottom,
                        Color.argb((0x40 * big).roundToInt(), 255, 255, 255),
                        Color.argb((0x0E * big).roundToInt(), 255, 255, 255),
                        Shader.TileMode.CLAMP,
                    )
                    rimShaderKey = rimKey
                }
                canvas.drawRoundRect(rimRect, max(r - sw, 0f), max(r - sw, 0f), rimPaint)
            }

        }
    }

    // ── Indicator strip (hotspot / charging shown inside another pill) ──────
    // Several events share one pill: whatever is on screen keeps its content
    // and a small strip on the right shows the other active indicators.
    private class Strip(
        val row: LinearLayout,
        val hotspotIcon: ImageView,
        val hotspotCount: TextView,
        val chargeIcon: ImageView,
        val chargeText: TextView,
    )

    private fun newStrip(): Strip {
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_hotspot)
            imageTintList = ColorStateList.valueOf(green)
        }
        val count = label(11.5f, green, bold = true).apply { gravity = Gravity.END }
        val bolt = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_bolt)
        }
        val pct = label(11.5f, green, bold = true).apply { gravity = Gravity.END }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(icon, LinearLayout.LayoutParams(dp(15), dp(15)))
            addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT).apply { marginStart = dp(3) })
            addView(bolt, LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginStart = dp(8) })
            addView(pct, LinearLayout.LayoutParams(0, WRAP_CONTENT).apply { marginStart = dp(3) })
        }
        return Strip(row, icon, count, bolt, pct)
    }

    /** What else is active; the pill that is showing draws it as a strip. */
    private var stripHotspot = false
    private var stripClients = 0
    private var stripCharge: Pair<Int, Boolean>? = null

    fun setStrip(hotspot: Boolean, clients: Int, charge: Pair<Int, Boolean>?) {
        stripHotspot = hotspot
        stripClients = clients
        stripCharge = charge
    }

    @Deprecated("Use setSecondaryBubble")
    fun setSecondaryMediaBubble(show: Boolean, playing: Boolean = true) {
        setSecondaryBubble(if (show) SecondaryKind.MEDIA else SecondaryKind.NONE, playing)
    }

    private fun layoutSecondaryBubble() {
        if (secondaryKind == SecondaryKind.NONE || rect.height() <= 0f) {
            secondaryRect.setEmpty(); return
        }
        val d = dp(32).toFloat()
        val gap = dp(8).toFloat()
        // Entry/exit: the bubble grows out from just behind the pill and tucks
        // back in when it goes away, instead of popping in and out of existence.
        val p = secondaryPresence.coerceIn(0f, 1.08f)
        val dd = d * (0.55f + 0.45f * p)
        val left = rect.right + gap * p - (1f - p) * dp(6).toFloat()
        val cy = rect.centerY()
        secondaryRect.set(left, cy - dd / 2f, left + dd, cy + dd / 2f)
    }

    /**
     * Shows the strip's contents inside [s] and returns the width it takes.
     * [mainHotspot] / [mainCharge] mark what the pill itself already shows,
     * so an indicator is never drawn twice.
     */
    private fun applyStrip(s: Strip, mainHotspot: Boolean, mainCharge: Boolean): Int {
        val hs = stripHotspot && !mainHotspot
        // The hotspot pill shows its icon on the left; its device count still goes here.
        val showCount = stripHotspot && stripClients > 0
        s.hotspotIcon.visibility = if (hs) VISIBLE else GONE
        s.hotspotCount.visibility = if (showCount) VISIBLE else GONE
        s.hotspotCount.text = if (showCount) stripClients.toString() else ""
        (s.hotspotCount.layoutParams as LinearLayout.LayoutParams).width = if (showCount) dp(16) else 0

        val charge = stripCharge?.takeIf { !mainCharge }
        val plugged = charge?.second ?: false
        s.chargeIcon.visibility = if (charge != null) VISIBLE else GONE
        s.chargeText.visibility = if (charge != null) VISIBLE else GONE
        val tint = if (plugged) green else Color.WHITE
        s.chargeIcon.imageTintList = ColorStateList.valueOf(tint)
        s.chargeText.setTextColor(tint)
        if (charge != null) s.chargeText.text = "${charge.first}%"

        var w = 0
        if (hs) w += dp(15)
        if (showCount) w += dp(3 + 16)
        if (charge != null) w += dp(8 + 13 + 3 + 32)
        s.row.visibility = if (w > 0) VISIBLE else GONE
        return if (w > 0) w + dp(8) else 0
    }

    // ── Compact layer (art ◀ camera ▶ bars / charging %) ────────────────────
    private val compactStrip = newStrip()
    private val compactIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        rounded(7f)
    }
    private val compactBars = EqualizerView(context)
    private val compactText = label(14f, Color.WHITE, bold = true)
    private val compactPlay = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        visibility = GONE
    }
    /** True while the compact pill is the video variant (title + play/pause). */
    private var videoPill = false
    private val compactLayer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(compactIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(compactBars, LinearLayout.LayoutParams(dp(20), dp(15)))
        addView(compactText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(compactPlay, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
        addView(compactStrip.row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    // ── Face ID layer (glyph under the camera, like iOS) ────────────────────
    private val faceGlyph = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_face_scan)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
    }
    private val faceLayer = FrameLayout(context).apply {
        addView(faceGlyph, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
    }
    private var faceAnim: ValueAnimator? = null

    // ── Status layer (icon + name ◀ camera ▶ value · indicators) ────────────
    private val statusStrip = newStrip()
    private val statusIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val statusTitle = label(13.5f, Color.WHITE, bold = true)
    private val statusGap = View(context)
    private val statusValue = label(13.5f, Color.WHITE, bold = true).apply { gravity = Gravity.END }
    private val statusLayer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(16), 0)
        addView(statusIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(statusTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(statusGap, LinearLayout.LayoutParams(0, 1))
        addView(statusValue, LinearLayout.LayoutParams(0, WRAP_CONTENT))
        addView(statusStrip.row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    // ── Notification layer (Origin Island card) ─────────────────────────────
    //  [photo + app badge]  Title                         App
    //                       Message text (2 lines, more when opened)
    //                       [ Action ] [ Action ] [ Open ]      ← opened only
    // Top padding clears the camera hole so the content sits below it.
    private val notifIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        rounded(14f)
    }
    /** The app's icon on the corner of the sender's photo. */
    private val notifBadge = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        val ring = dp(2)
        setPadding(ring, ring, ring, ring)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
        oval()
    }
    private val notifTitle = label(15f, Color.WHITE, bold = true)
    private val notifApp = label(12f, grey).apply { gravity = Gravity.END }
    private val notifText = label(13.5f, 0xFFC7C7CC.toInt(), lines = 2).apply {
        setLineSpacing(dp(1.5f).toFloat(), 1f)
    }
    private val notifActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val notifLayer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(18), dp(14))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                addView(notifIcon, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START))
                addView(notifBadge, FrameLayout.LayoutParams(dp(21), dp(21), Gravity.BOTTOM or Gravity.END))
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(notifTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(notifApp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
                })
                addView(notifText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(notifActions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }
    /** Which notification the card shows, so an update of the same one doesn't blink. */
    private var notifKey: String? = null
    private var notifExpanded = false
    private var notifControls = false

    // ── Action card layer (copied link / number / email, dropped text) ──────
    private val actionBadge = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
    }
    private val actionKind = label(11.5f, grey, bold = true).apply { letterSpacing = 0.06f }
    private val actionText = label(15f, Color.WHITE, bold = true).apply {
        maxLines = 5
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private var actionKey: String? = null
    private var actionButtonsKey: String? = null
    private val actionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val actionLayer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(12))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(actionBadge, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(actionKind)
                addView(actionText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        addView(actionRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    // ── Drop target layer ───────────────────────────────────────────────────
    private val dropIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_drop)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(blue) }
        val pad = dp(6)
        setPadding(pad, pad, pad, pad)
    }
    private val dropTitle = label(14.5f, Color.WHITE, bold = true)
    private val dropLayer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(18), 0)
        addView(dropIcon, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(dropTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
    }

    // ── Expanded media layer ────────────────────────────────────────────────
    private val mediaArt = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        rounded(14f)
    }
    private val mediaTitle = label(16f, Color.WHITE, bold = true)
    private val mediaArtist = label(14f, grey)
    private val mediaNextTrack = label(12f, 0xFF98989F.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val mediaBars = EqualizerView(context)
    private val mediaProgress = ProgressLine(context).apply {
        isClickable = true
    }
    private val mediaElapsed = label(11.5f, grey).apply { fontFeatureSettings = "tnum" }
    private val mediaRemaining = label(11.5f, grey).apply { fontFeatureSettings = "tnum" }
    private val btnPrev = iconButton(R.drawable.ic_prev, 50) { callbacks?.onPrev() }
    private val btnPlay = iconButton(R.drawable.ic_play, 58) { callbacks?.onPlayPause() }
    private val btnNext = iconButton(R.drawable.ic_next, 50) { callbacks?.onNext() }
    private val mediaLayer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Top padding is set in showMedia() to clear the camera hole.
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
                addView(mediaNextTrack, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
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

    private val layers = listOf(
        compactLayer, faceLayer, statusLayer, notifLayer, actionLayer, dropLayer, mediaLayer,
    )
    private var currentLayer: View? = null
    private val layerAnims = HashMap<View, ValueAnimator>()
    private val fadeLevel = HashMap<View, Float>()
    private val blurLevel = HashMap<View, Float>()

    // ── Springs ─────────────────────────────────────────────────────────────
    private val sw = Spring(0f)
    private val sh = Spring(0f)
    private val sr = Spring(0f)
    private val sPress = Spring(1f).apply { tune(response = 0.28f, damping = 0.6f) }
    private val springs = listOf(sw, sh, sr, sPress)
    private var running = false
    private var lastFrameNs = 0L
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

    // ── Inner colour wash (see pillView) ───────────────────────────────────
    private var tintColor: Int? = null
    private var tintLevel = 0f
    private var tintAnim: ValueAnimator? = null

    /** Colour for the wash at the bottom of a big card (null = plain black). */
    private fun setTint(color: Int?) {
        val c = color?.takeIf { it != Color.WHITE }
        // A user-chosen accent overrides the auto-derived wash colour.
        val effective = if (c != null && accentOverride != 0) accentOverride else c
        if (effective != null) tintColor = effective
        val target = if (effective != null) 1f else 0f
        if (tintLevel == target && tintAnim?.isRunning != true) return
        tintAnim?.cancel()
        tintAnim = ValueAnimator.ofFloat(tintLevel, target).apply {
            duration = 360
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                tintLevel = it.animatedValue as Float
                pillView.invalidate()
            }
            start()
        }
    }

    // ── Glow ────────────────────────────────────────────────────────────────
    private var glowColor = Color.WHITE
    /** User-chosen accent override (0 = auto from album / app). Applied to glow + tint. */
    var accentOverride: Int = 0
    private var glowLevel = 0f
    private var glowAnim: ValueAnimator? = null
    private var pulsePhase = 0f
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            glowView.invalidate()
        }
    }

    init {
        clipChildren = false
        glowView.setWillNotDraw(false)
        addView(glowView, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        pillView.setWillNotDraw(false)
        pillView.clipToOutline = true
        pillView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    rect.left.roundToInt(), rect.top.roundToInt(),
                    rect.right.roundToInt(), rect.bottom.roundToInt(),
                    cornerRadius(),
                )
            }
        }
        addView(pillView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(bubbleView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        for (l in layers) {
            l.alpha = 0f
            l.visibility = INVISIBLE
            pillView.addView(l, LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        }

        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // companion bubble: open the event it stands for
                if (secondaryKind != SecondaryKind.NONE && secondaryPresence > 0.5f &&
                    secondaryRect.width() > 0f && secondaryRect.contains(e.x, e.y)
                ) {
                    callbacks?.onSecondaryTap()
                    return true
                }
                // video pill: the right half (title + button) toggles play/pause
                if (mode == Mode.COMPACT_MEDIA && videoPill && e.x > rect.centerX()) {
                    callbacks?.onPlayPause()
                    return true
                }
                callbacks?.onTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                callbacks?.onLongPress()
            }

            // Swipe it away: up, or flick it sideways
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val up = vy < -500 && abs(vy) > abs(vx)
                val sideways = abs(vx) > 700 && abs(vx) > abs(vy)
                if (up) {
                    performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                    callbacks?.onSwipeUp()
                    return true
                }
                if (sideways) {
                    performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                    callbacks?.onSwipeSideways(toLeft = vx < 0)
                    return true
                }
                return false
            }
        })
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !rect.contains(ev.x, ev.y) &&
                targetArea()?.contains(ev.x.toInt(), ev.y.toInt()) != true
            ) {
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                // the island swells slightly under your finger
                MotionEvent.ACTION_DOWN -> press(1.05f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(1f)
            }
            detector.onTouchEvent(ev)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
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

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        // Cancel a snapshot: an animator's end listener removes itself from the
        // map, which would otherwise throw ConcurrentModificationException and
        // take the whole service down with it.
        val anims = layerAnims.values.toList()
        layerAnims.clear()
        anims.forEach { runCatching { it.cancel() } }
        glowAnim?.cancel()
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    // ── Public states ───────────────────────────────────────────────────────

    fun showIdle() {
        mode = Mode.IDLE
        compactBars.playing = false
        mediaBars.playing = false
        setGlow(null)
        val g = geometry
        when {
            // Camera hole: width/gap grow the idle pill out of the hole (0 = exactly the hole).
            g.holeW > 0 -> {
                val w = centerSpan().coerceAtLeast(g.holeW)
                val h = if (w > g.holeW) max(g.holeH, compactH()) else g.holeH
                morph(w, h, h / 2f, null)
            }
            // no camera to hide in: keep a pill if wanted, otherwise melt into a dot
            cfg.showIdle && !cfg.fitCamera -> {
                val w = centerSpan().coerceAtLeast(dp(6))
                morph(w, compactH().coerceAtLeast(dp(6)), compactH() / 2f, null)
            }
            else -> morph(compactH().coerceAtLeast(dp(6)), dp(6), dp(3).toFloat(), null)
        }
        setShown(idleVisible()) // hides automatically when nothing is active; events re-open it
    }

    fun showCompactVideo(m: MediaState) {
        mode = Mode.COMPACT_MEDIA
        prepareCompact()
        videoPill = true
        sizeLayer(compactLayer, compactWidth() + dp(110), compactH())
        compactIcon.setPadding(0, 0, 0, 0)
        if (m.art != null) {
            compactIcon.imageTintList = null
            compactIcon.setImageBitmap(m.art)
        } else {
            compactIcon.setImageResource(R.drawable.ic_music)
            compactIcon.imageTintList = ColorStateList.valueOf(m.accent)
        }
        compactBars.visibility = GONE
        compactText.text = m.title.ifBlank { "Video" }
        compactText.setTextColor(Color.WHITE)
        compactText.textSize = 12.5f
        compactText.maxWidth = dp(96)
        compactText.maxLines = 1
        compactText.ellipsize = android.text.TextUtils.TruncateAt.END
        compactText.visibility = VISIBLE
        compactPlay.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        compactPlay.visibility = VISIBLE
        mediaBars.playing = false
        setGlow(m.accent, pulsing = m.playing)
        setShown(true)
        morph(compactWidth() + dp(110), compactH(), compactH() / 2f, compactLayer)
    }

    /** iOS-style Face ID: glyph breathes under the camera while scanning, then pops green. */
    fun showFaceId(unlocked: Boolean, unlockedIcon: Int = R.drawable.ic_face_ok) {
        val wasFace = mode == Mode.STATUS && currentLayer === faceLayer
        mode = Mode.STATUS
        compactBars.playing = false
        mediaBars.playing = false
        val clear = cameraClearance()
        val w = max(dp(124), centerSpan() + dp(50))
        val h = compactH() + dp(46) + max(0, clear - dp(8))
        sizeLayer(faceLayer, w, h)
        setShown(true)
        faceAnim?.cancel()
        if (!unlocked) {
            faceGlyph.setImageResource(R.drawable.ic_face_scan)
            faceGlyph.imageTintList = ColorStateList.valueOf(Color.WHITE)
            setGlow(Color.WHITE)
            faceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    if (currentLayer !== faceLayer) { cancel(); return@addUpdateListener }
                    val f = it.animatedValue as Float
                    faceGlyph.alpha = 0.55f + 0.45f * f
                    faceGlyph.scaleX = 0.92f + 0.12f * f
                    faceGlyph.scaleY = 0.92f + 0.12f * f
                }
                start()
            }
        } else {
            // Shows what unlocked the phone: face, fingerprint or PIN.
            faceGlyph.setImageResource(unlockedIcon)
            faceGlyph.imageTintList = ColorStateList.valueOf(green)
            faceGlyph.alpha = 1f
            faceGlyph.scaleX = 0.6f; faceGlyph.scaleY = 0.6f
            faceGlyph.animate().scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
            setGlow(green)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        if (wasFace) {
            rect.height() // already sized; just keep the morph target
        }
        morph(w, h, dp(26).toFloat(), faceLayer)
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
        val stripW = applyStrip(compactStrip, mainHotspot = false, mainCharge = false)
        setGlow(m.accent, pulsing = m.playing)
        setShown(true)
        morph(compactWidth() + stripW, compactH(), compactH() / 2f, compactLayer)
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
        val stripW = applyStrip(compactStrip, mainHotspot = false, mainCharge = true)
        setGlow(if (plugged) green else null)
        setShown(true)
        morph(compactWidth() + stripW, compactH(), compactH() / 2f, compactLayer)
    }

    /** A short status: name on one side of the camera, value on the other. */
    fun showStatus(icon: Int, tint: Int, title: String, value: String, valueColor: Int) {
        showStatusLike(Mode.STATUS, icon, tint, title, value, valueColor)
    }

    /**
     * Origin Island-style live pill for a call, timer, navigation or other ongoing activity.
     * Compact like status, but sticky until the source notification ends.
     */
    fun showLiveActivity(kind: LiveKind, title: String, text: String, badge: Drawable? = null) {
        val (icon, tint, label) = when (kind) {
            LiveKind.CALL -> Triple(R.drawable.ic_phone, green, "Call")
            LiveKind.TIMER -> Triple(R.drawable.ic_timer, 0xFFFF9F0A.toInt(), "Timer")
            LiveKind.NAVIGATION -> Triple(R.drawable.ic_nav, green, "Nav")
            // Messages and other live cards show their app's own icon (passed in as
            // `badge`); the fallback must not be the bolt, which reads as charging.
            LiveKind.ACTIVITY, LiveKind.NONE -> Triple(R.drawable.ic_message, blue, "Live")
        }
        // Prefer the dynamic bit (time / distance / instruction) as the right-hand value.
        val primary = text.ifBlank { title }.ifBlank { label }
        val secondary = when {
            text.isNotBlank() && title.isNotBlank() && title != text -> title.take(18)
            else -> label
        }
        showStatusLike(Mode.LIVE, icon, tint, secondary, primary.take(22), tint, badge)
    }

    private fun showStatusLike(
        target: Mode,
        icon: Int,
        tint: Int,
        title: String,
        value: String,
        valueColor: Int,
        /** A real drawable (an app icon) to show instead of the tinted glyph. */
        badge: Drawable? = null,
    ) {
        // Same pill already on screen (the island re-renders whenever anything
        // changes, e.g. music moving to the earbuds): don't animate it again.
        val onScreen = currentLayer === statusLayer && (mode == Mode.STATUS || mode == Mode.LIVE)
        val sameTitle = onScreen && statusTitle.text.toString() == title
        val unchanged = sameTitle && statusValue.text.toString() == value
        val replacing = onScreen && !sameTitle
        mode = target
        compactBars.playing = false
        mediaBars.playing = false
        // Every indicator pill (timer, charging, earbuds, hotspot…) is the same
        // compact size as the music pill, so nothing looks bigger than music.
        val titleWidth = statusTitle.paint.measureText(title).toInt() + dp(4)
        val valueWidth = statusValue.paint.measureText(value).toInt() + dp(4)
        val gapWidth = statusGapWidth()
        val stripW = applyStrip(statusStrip, mainHotspot = false, mainCharge = false)
        // The status icon is centered over the camera. Keep both text fields
        // outside the complete camera footprint so neither label crosses it.
        val centerGap = max(gapWidth, dp(72))
        val fixed = dp(12 + 20 + 8 + 16) + centerGap + stripW
        val w = min(expandedWidth(), max(compactWidth(), fixed + titleWidth + valueWidth))
        val h = compactH()
        sizeLayer(statusLayer, w, h)
        (statusTitle.layoutParams as LinearLayout.LayoutParams).width = titleWidth
        (statusGap.layoutParams as LinearLayout.LayoutParams).width = centerGap
        (statusValue.layoutParams as LinearLayout.LayoutParams).width = valueWidth
        statusLayer.requestLayout()
        val fill = {
            if (badge != null) {
                statusIcon.setImageDrawable(badge)
                statusIcon.imageTintList = null
            } else {
                statusIcon.setImageResource(icon)
                statusIcon.imageTintList = ColorStateList.valueOf(tint)
            }
            statusTitle.text = title
            statusValue.text = value
            statusValue.setTextColor(valueColor)
        }
        setGlow(tint)
        setShown(true)
        when {
            unchanged -> Unit
            // a new event takes over the pill: old content blurs out, new blurs in
            replacing -> {
                swapContent(statusLayer, fill)
                kick()
            }
            // same pill, new value (e.g. "Connected" → "86%"): just update it
            else -> fill()
        }
        morph(w + stripW, h, h / 2f, statusLayer)
    }

    /**
     * A notification grows out of the camera as a card. Its height follows the
     * content: a one-line message makes a short card, a long one or an opened
     * card with buttons a taller one.
     */
    fun showNotification(n: NotifEvent, expanded: Boolean, actions: List<Action>, controls: Boolean = false) {
        val replacing = mode == Mode.NOTIFICATION && currentLayer === notifLayer && notifKey != n.key
        mode = Mode.NOTIFICATION
        notifKey = n.key
        val clear = cameraClearance()
        val w = expandedWidth()
        notifLayer.setPadding(dp(16), clear, dp(18), dp(if (expanded) 16 else 14))
        // tap/long-press reshapes the same card: a quick spring nudge marks it
        val reshaped = mode == Mode.NOTIFICATION && notifKey == n.key && (notifExpanded != expanded || notifControls != controls)
        notifExpanded = expanded
        notifControls = controls
        compactBars.playing = false
        mediaBars.playing = false
        val fill = { fillNotification(n, expanded, actions) }
        if (replacing) {
            // A newer notification takes over: the new one blurs in and the island gives a little kick.
            refill(notifLayer, fill)
            kick()
        } else {
            fill()
        }
        val h = contentHeight(notifLayer, w)
        sizeLayer(notifLayer, w, h)
        setTint(n.accent)
        setGlow(n.accent.takeIf { it != Color.WHITE })
        setShown(true)
        if (reshaped) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        morph(w, h, dp(if (expanded) 34 else 30).toFloat(), notifLayer)
    }

    /**
     * Sticky compact hotspot pill: green hotspot icon (+ device count) around
     * the cut-out. Other active indicators (charging…) join as a strip so the
     * island shows several events at once instead of hiding them.
     */
    fun showHotspot(hotspotClients: Int = 0) {
        mode = Mode.HOTSPOT
        prepareCompact()
        compactIcon.setPadding(dp(2), dp(2), dp(2), dp(2))
        compactIcon.setImageResource(R.drawable.ic_hotspot)
        compactIcon.imageTintList = ColorStateList.valueOf(green)
        compactBars.visibility = GONE
        compactBars.playing = false
        mediaBars.playing = false
        compactText.visibility = GONE
        val stripW = applyStrip(compactStrip, mainHotspot = true, mainCharge = false)
        setGlow(green)
        setShown(true)
        morph(compactWidth() + stripW, compactH(), compactH() / 2f, compactLayer)
    }

    /** A card with suggestions, e.g. for a copied link: Open · Share. */
    fun showActions(badge: Int, tint: Int, kind: String, text: String, actions: List<Action>) {
        // The buttons (not the text) decide whether this is a new card: a ticking
        // timer updates its text every second and must not blur and bounce each time.
        val labels = actions.joinToString("|") { it.label }
        val contentKey = "$kind\u0000$labels"
        // A re-render of the card already on screen isn't a new card: no blur, no bounce.
        val replacing = mode == Mode.ACTIONS && currentLayer === actionLayer && actionKey != contentKey
        actionKey = contentKey
        mode = Mode.ACTIONS
        compactBars.playing = false
        mediaBars.playing = false
        val clear = cameraClearance()
        val w = expandedWidth()
        val hasActions = actions.isNotEmpty()
        actionLayer.setPadding(dp(20), clear + dp(4), dp(20), dp(14))
        actionRow.visibility = if (hasActions) VISIBLE else GONE
        val fill = {
            actionBadge.setImageResource(badge)
            actionBadge.imageTintList = ColorStateList.valueOf(Color.WHITE)
            actionBadge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint) }
            actionKind.text = kind.uppercase()
            actionText.text = text
            if (actionButtonsKey != labels) {
                actionButtonsKey = labels
                actionRow.removeAllViews()
                for (a in actions.take(4)) {
                    actionRow.addView(actionButton(a), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
            }
        }
        if (replacing) {
            refill(actionLayer, fill)
            kick()
        } else {
            fill()
        }
        // Sized to what's in it: a short link is a short card.
        val h = contentHeight(actionLayer, w)
        sizeLayer(actionLayer, w, h)
        setTint(tint)
        setGlow(tint)
        setShown(true)
        morph(w, h, dp(36).toFloat(), actionLayer)
    }

    /** Something is being dragged: the island invites the drop, and grows when it's over it. */
    fun showDropTarget(hovering: Boolean) {
        val was = mode == Mode.DROP
        mode = Mode.DROP
        compactBars.playing = false
        mediaBars.playing = false
        dropTitle.text = if (hovering) "Let go to share" else "Drop here"
        val clear = cameraClearance() / 2
        val w = if (hovering) dp(250) else dp(196)
        val h = (if (hovering) dp(64) else dp(52)) + clear
        dropLayer.setPadding(dp(14), clear, dp(18), 0)
        sizeLayer(dropLayer, w, h)
        setGlow(blue, pulsing = !hovering)
        setShown(true)
        if (was && hovering) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        morph(w, h, h / 2f, dropLayer)
    }

    fun showMedia(m: MediaState) {
        mode = Mode.MEDIA
        mediaProgress.onSeek = { callbacks?.onSeek(it) }
        val clear = cameraClearance()
        val w = expandedWidth()
        val h = dp(196) + clear
        mediaLayer.setPadding(dp(22), clear + dp(16), dp(22), dp(12))
        sizeLayer(mediaLayer, w, h)
        updateMediaContent(m)
        compactBars.playing = false
        setTint(m.accent)
        setGlow(m.accent, pulsing = m.playing)
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

    // The island only rests on screen when the user asks it to (Show idle);
    // otherwise it hides until an event re-opens it.
    private fun idleVisible() = cfg.showIdle

    // Base size comes from the detected camera; it is never user-configured.
    private fun compactH() = geometry.compactH.coerceAtLeast(dp(32))

    /**
     * Space reserved at the top of expanded pills so content sits under the
     * camera cut-out instead of drawing through it.
     */
    private fun cameraClearance(): Int {
        if (geometry.holeW <= 0) return dp(14)
        // The card's top edge is the compact pill's top edge and the camera sits
        // in the middle of that pill: start the content just under the lens
        // (with a little room for phones whose cut-out is reported a bit small).
        return compactH() / 2 + geometry.holeH / 2 + dp(10)
    }

    /** Rounded corners follow the user's Look slider (50–150 %). */
    private fun cornerRadius() =
        min(sr.value * sPress.value * (cfg.cornerPercent / 100f), rect.height() / 2f)

    private fun fillNotification(n: NotifEvent, expanded: Boolean, actions: List<Action>) {
        val photo = n.icon
        val app = n.appIcon ?: context.getDrawable(R.mipmap.ic_launcher)
        if (photo != null) {
            // Sender's photo with the app's icon on its corner.
            notifIcon.setImageDrawable(photo)
            notifBadge.setImageDrawable(app)
            notifBadge.visibility = VISIBLE
        } else {
            notifIcon.setImageDrawable(app)
            notifBadge.visibility = GONE
        }
        notifTitle.text = n.title
        notifApp.text = n.appName.takeIf { it.isNotBlank() && it != n.title }.orEmpty()
        notifApp.visibility = if (notifApp.text.isNullOrEmpty()) GONE else VISIBLE
        notifApp.maxWidth = dp(110)
        notifText.text = n.text
        notifText.maxLines = if (expanded) 8 else 2
        notifText.visibility = if (n.text.isEmpty()) GONE else VISIBLE
        notifActions.removeAllViews()
        if (expanded && actions.isNotEmpty()) {
            for ((i, a) in actions.take(4).withIndex()) {
                val primary = i == actions.lastIndex && actions.size > 0 && a.label == "Open"
                notifActions.addView(
                    chipButton(a, primary),
                    LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (i > 0) marginStart = dp(8) },
                )
            }
            notifActions.visibility = VISIBLE
        } else {
            notifActions.visibility = GONE
        }
    }

    /** Height of [layer] laid out at width [w] with its current content. */
    private fun contentHeight(layer: View, w: Int): Int {
        layer.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return max(layer.measuredHeight, compactH() + dp(24))
    }

    /** Swap in new content: it blurs in from nothing so old and new never mix. */
    private fun refill(layer: View, update: () -> Unit) {
        layerAnims.remove(layer)?.cancel()
        fadeLevel[layer] = 0f
        update()
        currentLayer = layer
        fade(layer, show = true, delay = 40)
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
            mediaArt.setBackgroundColor(chip)
            mediaArt.setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        mediaTitle.text = m.title.ifBlank { "Now playing" }
        mediaArtist.text = m.artist
        mediaArtist.visibility = if (m.artist.isBlank()) GONE else VISIBLE
        // Next-track preview: show the upcoming track when the session exposes its queue.
        val nextLine = buildString {
            m.nextTrackTitle?.let {
                append("Next: ")
                append(it)
                m.nextTrackArtist?.let {
                    append(" · ")
                    append(it)
                }
            }
        }
        mediaNextTrack.text = nextLine
        mediaNextTrack.visibility = if (nextLine.isNotBlank()) VISIBLE else GONE
        mediaBars.color = m.accent
        mediaBars.playing = m.playing
        mediaProgress.color = m.accent
        btnPlay.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateMediaProgress(m.currentPosition(), m.durationMs)
    }

    private fun prepareCompact() {
        videoPill = false
        compactPlay.visibility = GONE
        compactText.maxWidth = Int.MAX_VALUE
        compactText.textSize = 14f
        val h = compactH()
        sizeLayer(compactLayer, compactWidth(), h)
        val icon = (h - dp(10)).coerceAtLeast(dp(12))
        compactIcon.layoutParams = (compactIcon.layoutParams as LinearLayout.LayoutParams).apply {
            width = icon; height = icon
        }
        compactLayer.setPadding(dp(5), 0, dp(14), 0)
    }

    /** Horizontal span reserved for the detected camera and its safe margin. */
    private fun centerSpan(): Int =
        if (geometry.holeW > 0) max(geometry.holeW, compactH()) else 0

    private fun compactWidth(): Int = centerSpan() + 2 * compactH() + dp(28)

    private fun statusGapWidth(): Int = centerSpan().coerceAtLeast(dp(16))

    private fun statusWidth() = min(expandedWidth(), statusGapWidth() + dp(2 * 98 + 28))

    private fun expandedWidth() = min(resources.displayMetrics.widthPixels - dp(28), dp(400))

    private fun sizeLayer(layer: View, w: Int, h: Int) {
        val lp = layer.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            layer.layoutParams = lp
        }
    }

    private fun morph(w: Int, h: Int, r: Float, layer: View?) {
        if (layer !== notifLayer && layer !== actionLayer && layer !== mediaLayer) setTint(null)
        if (layer !== currentLayer) swapContent(layer, null)

        val first = sw.value == 0f
        if (!first && !running && sw.value == w.toFloat() && sh.value == h.toFloat() && sr.value == r) {
            return // already there (e.g. the same state rendered again)
        }
        val growing = w.toFloat() * h > sw.target * sh.target
        if (growing) {
            // Opening: it stretches sideways first, then drops down, with a
            // lively bounce at the end.
            sw.tune(response = 0.42f, damping = 0.66f)
            sh.tune(response = 0.50f, damping = 0.72f)
            sr.tune(response = 0.50f, damping = 0.80f)
        } else {
            // Closing: it lifts up first, then narrows back into the camera.
            sh.tune(response = 0.34f, damping = 0.90f)
            sw.tune(response = 0.44f, damping = 0.84f)
            sr.tune(response = 0.40f, damping = 0.90f)
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
        // Take touches where the island is going, straight away: waiting for the
        // spring to settle made taps in the first second fall through to the app below.
        callbacks?.onTouchArea(targetArea())
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

    private fun targetArea(): Rect? {
        if (idleHidden || !shown) return null
        val w = sw.target
        val h = sh.target
        val left = geometry.anchorX - w / 2f
        val top = topFor(h, compactH().toFloat()) + glowPad
        return withBubble(Rect(left.toInt(), top.toInt(), ceil(left + w).toInt(), ceil(top + h).toInt()))
    }

    /** The touch window must also cover the companion bubble, or taps on it miss. */
    private fun withBubble(base: Rect): Rect {
        if (secondaryKind == SecondaryKind.NONE || secondaryPresence < 0.5f) return base
        layoutSecondaryBubble()
        if (secondaryRect.width() <= 0f) return base
        val out = Rect(base)
        out.union(
            secondaryRect.left.toInt() - dp(2),
            secondaryRect.top.toInt() - dp(2),
            ceil(secondaryRect.right).toInt() + dp(2),
            ceil(secondaryRect.bottom).toInt() + dp(2),
        )
        return out
    }

    private fun onSettled() {
        if (!shown) alpha = 0f
        val area = if (idleHidden || !shown) null else withBubble(Rect(
            rect.left.toInt(), rect.top.toInt(), ceil(rect.right).toInt(), ceil(rect.bottom).toInt(),
        ))
        callbacks?.onTouchArea(area)
    }

    /** Room above the pill so its glow isn't cut off by the top of the window. */
    val glowPad: Int get() = dp(16)

    private fun topFor(h: Float, ch: Float): Float {
        // Always centre the pill on the camera anchor so clicking never shifts it
        // up or down — it just grows in place. Content inside expanded pills is
        // padded below the lens by cameraClearance().
        return max(0f, (ch - h) / 2f)
    }

    private fun redraw() {
        val ps = sPress.value
        val maxW = (if (geometry.screenW > 0) geometry.screenW else resources.displayMetrics.widthPixels) - dp(12).toFloat()
        val w = min(sw.value * ps, maxW)
        val h = sh.value * ps
        val ch = compactH().toFloat()
        // the window spans the screen width, so window x == screen x
        val left = geometry.anchorX - w / 2f
        val top = topFor(h, ch) + glowPad
        rect.set(left, top, left + w, top + h)
        for (l in layers) if (l.visibility == VISIBLE) applyLayer(l)
        pillView.invalidateOutline()
        pillView.invalidate()
        glowView.invalidate()
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
        // No RenderEffect blur on the cross-fading content: on some GPUs a blurred
        // view inside a translucent overlay renders as colour garbage mid-animation.
    }

    // ── Glow ────────────────────────────────────────────────────────────────

    /** Light the island in `color` (null = off). Music makes it breathe. */
    private fun setGlow(color: Int?, pulsing: Boolean = false) {
        if (!cfg.glow) {
            fadeGlowTo(0f)
            pulse.cancel()
            return
        }
        val c = color?.takeIf { it != Color.WHITE }
        // A user-chosen accent overrides the auto-derived colour (album art, app…).
        val effective = if (c != null && accentOverride != 0) accentOverride else c
        if (effective != null) glowColor = effective
        fadeGlowTo(if (effective != null) 1f else 0f)
        if (pulsing && color != null) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
            pulsePhase = 0f
        }
    }

    private fun fadeGlowTo(target: Float) {
        glowAnim?.cancel()
        val from = glowLevel
        if (from == target) return
        glowAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target > from) 420 else 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glowLevel = it.animatedValue as Float
                glowView.invalidate()
            }
            start()
        }
    }

    private fun glowStrength(): Float {
        val breathe = if (pulse.isRunning) 0.7f + 0.3f * sin(pulsePhase * 2f * Math.PI.toFloat()) else 1f
        // a glow on a pill that's still tiny looks like a smudge: let it grow in with the island
        val size = (rect.height() / max(1f, compactH().toFloat())).coerceIn(0f, 1f)
        return glowLevel * breathe * size * (cfg.glowPercent / 100f)
    }

    // ── Content cross-fades ─────────────────────────────────────────────────

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
     * the outgoing content is invisible (used to swap text without the new
     * words flashing up in the old layout).
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
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
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

    // ── Builders ────────────────────────────────────────────────────────────

    private fun actionButton(a: Action) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val iconView = ImageView(context).apply {
            if (a.icon != null) {
                // the real app's icon, like OriginOS's app suggestions
                setImageDrawable(a.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                setImageResource(a.glyph)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(11)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chip) }
            }
        }
        addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(label(11.5f, Color.WHITE).apply {
            text = a.label
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(5) })
        background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(Color.WHITE)
        })
        setPadding(0, dp(4), 0, dp(4))
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            a.run()
        }
    }

    /** A rounded text button for an opened notification ("Mark as read", "Pause", "Open"). */
    private fun chipButton(a: Action, primary: Boolean) = TextView(context).apply {
        text = a.label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        typeface = IslandFonts.medium
        setPadding(dp(10), 0, dp(10), 0)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(19).toFloat()
            setColor(if (primary) blue else 0x33FFFFFF)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            a.run()
        }
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
            typeface = if (bold) IslandFonts.medium else IslandFonts.regular
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
 * The island uses the phone's own font, so it changes with the font picked in
 * the phone's theme settings. Typeface.DEFAULT is what theme fonts replace;
 * named families like "sans-serif-medium" often aren't, which made the
 * island's titles look different from the rest of the phone.
 */
private object IslandFonts {
    val regular: Typeface get() = Typeface.DEFAULT
    val medium: Typeface get() = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
