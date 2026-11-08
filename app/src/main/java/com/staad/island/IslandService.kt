package com.staad.island

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import com.staad.island.IslandView.Mode
import kotlin.math.roundToInt

/**
 * Owns the overlay window and decides what the island shows.
 *
 * Priority (highest first):
 *   notification pop-out › charging › expanded player › compact "now playing" › idle pill
 */
class IslandService : AccessibilityService(),
    IslandBus.Listener,
    IslandView.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var wm: WindowManager? = null
    private var island: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cfg = IslandConfig.DEFAULT

    private var media: MediaState? = null
    private var notif: NotifEvent? = null
    private var notifShownAt = 0L
    private var charge: Pair<Int, Boolean>? = null
    private var mediaExpanded = false
    private var receiverRegistered = false

    private val clearNotif = Runnable { notif = null; render() }
    private val clearCharge = Runnable { charge = null; render() }
    private val collapseMedia = Runnable { mediaExpanded = false; render() }
    private val progressTick = object : Runnable {
        override fun run() {
            val m = media ?: return
            island?.updateMediaProgress(m.currentPosition(), m.durationMs)
            handler.postDelayed(this, 500)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!cfg.charging) return
            val plugged = intent.action == Intent.ACTION_POWER_CONNECTED
            val level = getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return
            charge = level to plugged
            handler.removeCallbacks(clearCharge)
            handler.postDelayed(clearCharge, 3500)
            render()
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        cfg = IslandConfig.load(this)
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val view = IslandView(this).also {
            it.cfg = cfg
            it.callbacks = this
        }
        val lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or
                FLAG_WATCH_OUTSIDE_TOUCH or FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "Island"
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        wm = windowManager
        island = view
        params = lp
        applyPosition(update = false)
        windowManager.addView(view, lp)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }
        receiverRegistered = true
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        IslandBus.listener = this // replays current media state → render()
        render()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (IslandBus.listener === this) IslandBus.listener = null
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(powerReceiver) }
            receiverRegistered = false
        }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        island?.let { v -> runCatching { wm?.removeView(v) } }
        island = null
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        cfg = IslandConfig.load(this)
        island?.cfg = cfg
        applyPosition()
        render()
    }

    // ── Events from the listener ────────────────────────────────────────────

    override fun onNotification(event: NotifEvent) {
        if (!cfg.notifications) return
        notif = event
        notifShownAt = SystemClock.uptimeMillis()
        mediaExpanded = false
        handler.removeCallbacks(clearNotif)
        handler.postDelayed(clearNotif, 4500)
        render()
    }

    override fun onNotificationRemoved(key: String) {
        if (notif?.key == key) {
            handler.removeCallbacks(clearNotif)
            notif = null
            render()
        }
    }

    override fun onMedia(state: MediaState?) {
        media = state
        render()
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        val v = island ?: return
        val m = media?.takeIf { cfg.media }
        if (m == null) mediaExpanded = false
        val n = notif
        val c = charge
        when {
            n != null -> v.showNotification(n.icon, n.title, n.text)
            c != null -> v.showCharging(c.first, c.second)
            mediaExpanded && m != null -> v.showMedia(m)
            m != null && m.playing -> v.showCompactMedia(m)
            else -> v.showIdle()
        }
        handler.removeCallbacks(progressTick)
        if (v.mode == Mode.MEDIA) handler.post(progressTick)
        updateVisibility()
    }

    private fun updateVisibility() {
        val v = island ?: return
        val lp = params ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val visible = cfg.enabled && !(landscape && cfg.hideLandscape)
        v.visibility = if (visible) View.VISIBLE else View.GONE
        // A hidden idle pill must not swallow taps on the status bar beneath it.
        val touchable = visible && !(v.mode == Mode.IDLE && !cfg.showIdle)
        val flags = if (touchable) lp.flags and FLAG_NOT_TOUCHABLE.inv() else lp.flags or FLAG_NOT_TOUCHABLE
        if (flags != lp.flags) {
            lp.flags = flags
            updateWindow()
        }
    }

    private fun applyPosition(update: Boolean = true) {
        val lp = params ?: return
        val d = resources.displayMetrics.density
        lp.x = (cfg.offsetXDp * d).roundToInt()
        lp.y = (cfg.offsetYDp * d).roundToInt()
        if (update) updateWindow()
    }

    private fun updateWindow() {
        val v = island ?: return
        val lp = params ?: return
        if (v.isAttachedToWindow) runCatching { wm?.updateViewLayout(v, lp) }
    }

    // ── Gestures ────────────────────────────────────────────────────────────

    override fun onTap() {
        val v = island ?: return
        when (v.mode) {
            Mode.NOTIFICATION -> notif?.let { openNotification(it) }
            Mode.CHARGING -> {
                handler.removeCallbacks(clearCharge)
                charge = null
                render()
            }
            Mode.IDLE, Mode.COMPACT_MEDIA ->
                if (media != null && cfg.media) expandMedia() else v.bounce()
            Mode.MEDIA -> {
                media?.controller?.sessionActivity?.let { send(it) }
                handler.removeCallbacks(collapseMedia)
                mediaExpanded = false
                render()
            }
        }
    }

    override fun onLongPress() {
        val v = island ?: return
        when {
            v.mode == Mode.NOTIFICATION -> dismissAll()
            media != null && cfg.media && v.mode != Mode.MEDIA -> expandMedia()
            else -> v.bounce()
        }
    }

    override fun onSwipeUp() = dismissAll()

    override fun onOutsideTouch() {
        var changed = false
        if (mediaExpanded) {
            handler.removeCallbacks(collapseMedia)
            mediaExpanded = false
            changed = true
        }
        if (notif != null && SystemClock.uptimeMillis() - notifShownAt > 700) {
            handler.removeCallbacks(clearNotif)
            notif = null
            changed = true
        }
        if (changed) render()
    }

    override fun onPrev() {
        media?.controller?.transportControls?.skipToPrevious()
        rearmMediaCollapse()
    }

    override fun onPlayPause() {
        val m = media ?: return
        val tc = m.controller?.transportControls ?: return
        if (m.playing) tc.pause() else tc.play()
        rearmMediaCollapse()
    }

    override fun onNext() {
        media?.controller?.transportControls?.skipToNext()
        rearmMediaCollapse()
    }

    private fun expandMedia() {
        island?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        mediaExpanded = true
        rearmMediaCollapse()
        render()
    }

    private fun rearmMediaCollapse() {
        handler.removeCallbacks(collapseMedia)
        handler.postDelayed(collapseMedia, 8000)
    }

    private fun dismissAll() {
        handler.removeCallbacks(clearNotif)
        handler.removeCallbacks(clearCharge)
        handler.removeCallbacks(collapseMedia)
        notif = null
        charge = null
        mediaExpanded = false
        render()
    }

    private fun openNotification(n: NotifEvent) {
        n.contentIntent?.let { send(it) }
        if (n.autoCancel) IslandBus.cancel(n.key)
        handler.removeCallbacks(clearNotif)
        notif = null
        render()
    }

    @Suppress("DEPRECATION")
    private fun send(pi: PendingIntent) {
        try {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(this, 0, null, null, null, null, opts)
        } catch (_: PendingIntent.CanceledException) {
        }
    }
}
