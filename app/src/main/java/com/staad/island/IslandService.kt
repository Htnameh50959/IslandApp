package com.staad.island

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.staad.island.IslandView.Mode
import com.staad.island.IslandView.SecondaryKind
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the overlay window and decides what the island shows.
 *
 * Priority (highest first):
 *   drop target › suggestion card (copied link…) › live activity › hotspot
 *   (connected devices) › status (earbuds, silent…) › charging ›
 *   expanded player › compact "now playing" › idle
 */
class IslandService : AccessibilityService(),
    IslandBus.Listener,
    IslandView.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var wm: WindowManager? = null
    private var island: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    // Small invisible window over the settled island that takes its touches.
    // The drawing window never moves or resizes (that's what keeps the
    // animation smooth) and lets every touch through.
    private var touchPad: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var touchArea: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cfg = IslandConfig.DEFAULT

    private var media: MediaState? = null
    /** The live activity (call, navigation, timer) on the island. */
    private var notif: NotifEvent? = null
    private var charge: Pair<Int, Boolean>? = null
    private var mediaExpanded = false
    private var receiverRegistered = false

    private class Status(val icon: Int, val tint: Int, val title: String, val value: String, val valueColor: Int)
    private var status: Status? = null
    private var statusExpanded = false
    private var liveExpanded = false
    /** Long-pressed: the open card also shows the app's own buttons. */
    private var notifControls = false
    private var card: Card? = null
    private var dropActive = false
    private var dropHover = false
    private val suggestions by lazy { Suggestions(this) { card = null; render() } }

    // copy detection (see onAccessibilityEvent)
    private var lastSelection: String? = null
    private var lastSelectionAt = 0L
    private var lastCopied: String? = null
    private var lastCopiedAt = 0L
    /** When the clip we last showed was put on the clipboard (ClipDescription timestamp). */
    private var lastClipStamp = -1L
    /** Text in System UI's "copied" preview, so one preview counts as one copy. */
    private var lastPreviewText: String? = null
    private var brightnessObserver: ContentObserver? = null
    private val copyLabels by lazy {
        setOf("copy", runCatching { Resources.getSystem().getString(android.R.string.copy) }.getOrDefault("Copy"))
            .map { it.lowercase() }.toSet()
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Android only lets background apps read the clipboard in a few cases;
        // when it does, this is the most reliable signal.
        readClip()?.let { (text, stamp) -> onCopied(text, stamp) }
    }

    /** The clipboard's text and when it was copied, if Android lets us read it now. */
    private fun readClip(): Pair<String, Long>? = runCatching {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) null else text to (clip.description?.timestamp ?: -1L)
    }.getOrNull()
    private var btConnectedAt = 0L
    private var btName: String? = null
    // Last Bluetooth pop-up ("connected|Buds Air3") and when, to show each change once.
    private var btLastEvent: String? = null
    private var btLastAt = 0L

    private var hotspot: HotspotMonitor? = null

    private val clearNotif = Runnable { nextNotification() }
    private val clearCharge = Runnable { charge = null; render() }
    private val clearStatus = Runnable {
        if (status?.title == "Face ID") faceScanActive = false
        status = null
        render()
    }
    private val clearCard = Runnable { card = null; render() }
    private val collapseMedia = Runnable { mediaExpanded = false; render() }
    /** A timer / stopwatch notification only carries a base time: tick it here. */
    private val liveTick = object : Runnable {
        override fun run() {
            val n = notif ?: return
            if (n.chronometerBase <= 0L) return
            render()
        }
    }

    // ── Island timers (in-app countdown + stopwatch) ────────────────────────
    // The clock app's timer only carries a base time, so the island can show it
    // but not control it. These are started here and run here, which is what
    // lets the card offer Start / Pause · Reset · Stop.
    private enum class TimerKind { COUNTDOWN, STOPWATCH }

    private class IslandTimer(
        val kind: TimerKind,
        /** Countdown length in ms; unused by the stopwatch. */
        val lengthMs: Long,
        var startedAt: Long = 0L,
        var accumulatedMs: Long = 0L,
        var running: Boolean = false,
    ) {
        fun elapsed(now: Long): Long {
            val total = accumulatedMs + if (running) (now - startedAt).coerceAtLeast(0L) else 0L
            return if (kind == TimerKind.COUNTDOWN) total.coerceAtMost(lengthMs) else total
        }
    }

    private var timerState: IslandTimer? = null
    private var timerExpanded = false

    private fun nowMs() = SystemClock.elapsedRealtime()

    /** What the pill shows: time left for a countdown, time elapsed for a stopwatch. */
    private fun timerRemaining(t: IslandTimer): Long = when (t.kind) {
        TimerKind.COUNTDOWN -> t.lengthMs - t.elapsed(nowMs())
        TimerKind.STOPWATCH -> t.elapsed(nowMs())
    }

    private fun timerText(t: IslandTimer): String = clock(timerRemaining(t))

    private fun timerTitle(t: IslandTimer): String =
        if (t.kind == TimerKind.STOPWATCH) "Stopwatch" else "Timer"

    /** "04:59" / "1:02:59". Counts up, so partial seconds round up. */
    private fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun startTimerClock(minutes: Int) {
        timerState = IslandTimer(
            TimerKind.COUNTDOWN, minutes * 60_000L, startedAt = nowMs(), running = true,
        )
        timerExpanded = true
        card = null
        island?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        render()
    }

    private fun startStopwatch() {
        timerState = IslandTimer(TimerKind.STOPWATCH, 0L, startedAt = nowMs(), running = true)
        timerExpanded = true
        card = null
        island?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        render()
    }

    private fun pauseTimer() {
        val t = timerState ?: return
        t.accumulatedMs = t.elapsed(nowMs())
        t.startedAt = 0L
        t.running = false
        render()
    }

    private fun resumeTimer() {
        val t = timerState ?: return
        t.startedAt = nowMs()
        t.running = true
        render()
    }

    /** Back to the start of the countdown / 00:00, paused. */
    private fun resetTimer() {
        val t = timerState ?: return
        t.accumulatedMs = 0L
        t.startedAt = 0L
        t.running = false
        render()
    }

    private fun stopTimer() {
        timerState = null
        timerExpanded = false
        handler.removeCallbacks(timerTick)
        render()
    }

    /** A countdown reached zero: ring it once, then fold the island away. */
    private fun finishTimer() {
        val t = timerState ?: return
        timerState = null
        timerExpanded = false
        handler.removeCallbacks(timerTick)
        island?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        showStatus(Status(R.drawable.ic_timer, ORANGE, timerTitle(t), "Done", ORANGE), 4000)
    }

    private fun timerActions(t: IslandTimer): List<IslandView.Action> = buildList {
        if (t.running) {
            add(IslandView.Action("Pause", null, R.drawable.ic_pause) { pauseTimer() })
        } else {
            add(IslandView.Action("Start", null, R.drawable.ic_play) { resumeTimer() })
        }
        add(IslandView.Action("Reset", null, R.drawable.ic_rotate) { resetTimer() })
        add(IslandView.Action("Stop", null, R.drawable.ic_stop) { stopTimer() })
    }

    /** Keeps the pill's clock moving while the timer runs. */
    private val timerTick = object : Runnable {
        override fun run() {
            val t = timerState ?: return
            if (!t.running) return
            if (t.kind == TimerKind.COUNTDOWN && timerRemaining(t) <= 0L) {
                finishTimer()
                return
            }
            render()
        }
    }

    /** "03:58" / "1:02:59" for a running timer or stopwatch. */
    private fun clockText(n: NotifEvent): String {
        val base = n.chronometerBase
        if (base <= 0L) return n.text
        // A base in the 10^12 range is a date (wall clock), anything smaller is elapsedRealtime.
        val now = if (base > 1_000_000_000_000L) System.currentTimeMillis() else SystemClock.elapsedRealtime()
        val ms = if (n.chronometerCountDown) base - now else now - base
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

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

    /** Earbuds, silent / vibrate, Do Not Disturb. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Each wake is a good moment to check notifications still reach the island.
            if (intent.action == Intent.ACTION_SCREEN_ON) ensureNotificationListener()
            // ACTION_SCREEN_ON is a sticky broadcast; its initial delivery has
            // isInitialStickyBroadcast=true and must not be skipped, or Face ID
            // scanning never starts on the first wake after the receiver binds.
            if (!cfg.statusPopups && intent.action != Intent.ACTION_SCREEN_ON) return
            if (isInitialStickyBroadcast && intent.action != Intent.ACTION_SCREEN_ON) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBluetooth(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onBluetooth(intent, connected = false)
                ACTION_BT_BATTERY -> onBluetoothBattery(intent)
                Intent.ACTION_SCREEN_ON -> if (cfg.faceId) {
                    android.util.Log.d("IslandFaceID", "ACTION_SCREEN_ON received, calling maybeStartFaceScan")
                    screenOnAt = SystemClock.uptimeMillis()
                    unlockShown = false
                    maybeStartFaceScan()
                    watchForUnlock()
                }
                Intent.ACTION_USER_PRESENT -> finishFaceScan(unlocked = true)
                Intent.ACTION_SCREEN_OFF -> {
                    faceScanActive = false
                    unlockShown = false
                    handler.removeCallbacks(unlockPoll)
                    if (status?.title == "Face ID") {
                        handler.removeCallbacks(clearStatus); status = null; render()
                    }
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                    AudioManager.RINGER_MODE_SILENT -> showStatus(Status(R.drawable.ic_bell_off, RED, "Silent", "On", RED))
                    AudioManager.RINGER_MODE_VIBRATE -> showStatus(Status(R.drawable.ic_vibrate, ORANGE, "Vibrate", "On", ORANGE))
                    AudioManager.RINGER_MODE_NORMAL -> showStatus(Status(R.drawable.ic_bell, WHITE, "Ring", "On", WHITE))
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val on = getSystemService(NotificationManager::class.java)
                        ?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL } ?: return
                    showStatus(
                        Status(R.drawable.ic_moon, PURPLE, "Do Not Disturb", if (on) "On" else "Off", if (on) PURPLE else GREY)
                    )
                }
            }
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
            WRAP_CONTENT, WRAP_CONTENT, // applyPosition() sets the real size
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or
                FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Island"
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        val pad = View(this).apply {
            // An empty window never draws, and on Android 12+ a window's new
            // size and touchability only reach the input system with its next
            // drawn frame. A practically invisible background makes it draw.
            setBackgroundColor(0x01000000)
            setOnTouchListener { _, ev ->
                val area = touchArea
                val tp = touchParams
                val dp = params
                if (DEBUG_COPY) android.util.Log.i("IslandCopy", "pad touch ${ev.actionMasked} at ${ev.x},${ev.y} area=$area")
                if (area == null || tp == null || dp == null) return@setOnTouchListener false
                val r = island?.forwardTouch(ev, (tp.x - dp.x).toFloat(), (tp.y - dp.y).toFloat()) ?: false
                if (DEBUG_COPY) android.util.Log.i("IslandCopy", "forwarded -> $r")
                r
            }
            setOnDragListener { _, e -> onDrag(e) }
        }
        // Always touchable: on some phones (this vivo, Android 15) switching
        // FLAG_NOT_TOUCHABLE on a live window never reaches the input system,
        // so instead the window is parked off-screen when it shouldn't take touches.
        val tlp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or
                FLAG_LAYOUT_NO_LIMITS or FLAG_WATCH_OUTSIDE_TOUCH or FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Island touch"
            x = PARKED
            y = PARKED
            layoutInDisplayCutoutMode = lp.layoutInDisplayCutoutMode
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        wm = windowManager
        island = view
        params = lp
        touchPad = pad
        touchParams = tlp
        applyPosition(update = false)
        windowManager.addView(view, lp)
        windowManager.addView(pad, tlp)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }
        val statusFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BT_BATTERY)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, statusFilter)
        }
        receiverRegistered = true
        // The moment the lock screen opens (face matched), not when the system gets round to
        // the later USER_PRESENT broadcast: that's what keeps the check in sync with the unlock.
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                getSystemService(KeyguardManager::class.java)?.addKeyguardLockedStateListener(mainExecutor, keyguardListener)
            }
        }
        runCatching { getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener) }
        registerBrightnessObserver()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        // Already plugged in when the island came up (after a reboot, or while the
        // service was off): without this the charging event never existed.
        runCatching {
            val bm = getSystemService(BatteryManager::class.java)
            if (bm?.isCharging == true) {
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                charge = level to true
                handler.postDelayed(clearCharge, 3500)
            }
        }
        hotspot = HotspotMonitor(this) { render() }.also { it.start() }
        IslandBus.listener = this // replays current media state → render()
        ensureNotificationListener()
        render()
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "service connected")
    }

    /**
     * Copy suggestions. Android doesn't let a background app read the
     * clipboard, so first notice that something was copied:
     *  - you tapped a "Copy…" button or menu item (any app), or
     *  - the app showed a "…copied" toast, or
     *  - System UI showed its "copied" preview (Android 13+),
     * then briefly take focus, which is when Android allows reading the
     * clipboard (Android shows a small "pasted from your clipboard" note).
     * If that fails, fall back to the text you had selected.
     * Nothing is stored: the text only lives in memory to show the card.
     */
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        if (DEBUG_COPY) debugEvent(e)
        trackForeground(e)
        if (cfg.faceId) trackBouncer(e)
        if (!cfg.copySuggestions) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = e.text?.firstOrNull()?.toString() ?: return
                val a = minOf(e.fromIndex, e.toIndex)
                val b = maxOf(e.fromIndex, e.toIndex)
                if (a >= 0 && b > a && b <= text.length) {
                    lastSelection = text.substring(a, b)
                    lastSelectionAt = SystemClock.uptimeMillis()
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = ((e.text?.joinToString(" ") ?: "") + " " + (e.contentDescription ?: "")).lowercase()
                if (!COPY_WORD.containsMatchIn(label) && copyLabels.none { label.trim() == it }) return
                val sel = lastSelection?.takeIf { SystemClock.uptimeMillis() - lastSelectionAt < 60_000 }
                // give the app a moment to actually put it on the clipboard
                handler.postDelayed({ grabClipboard(sel) }, 150)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // a toast like "Message copied" / "Copied to clipboard"
                if (e.parcelableData != null) return // a real notification, not a toast
                val text = e.text?.joinToString(" ")?.lowercase() ?: return
                if (!(COPY_WORD.containsMatchIn(text) || "copied" in text)) return
                // An app's own "Message copied" toast can come seconds after Android's
                // "Copied" for the same copy; the clip's timestamp tells them apart
                // in onCopied, so every new copy still gets through.
                handler.postDelayed({ grabClipboard(null) }, 100)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (e.packageName == "com.android.systemui") readClipboardPreview()
        }
    }

    /** The app on screen, so a video's pill only shows once you've left its app. */
    private var foregroundPkg: String? = null

    private var screenOnAt = 0L
    /** True only while a lock-screen face check is in progress. */
    private var faceScanActive = false

    /** Face unlock is set up for the lock screen. With it off (PIN / fingerprint only) the Face ID pill never shows. */
    private fun faceUnlockOn(): Boolean = runCatching {
        android.provider.Settings.Secure.getInt(contentResolver, "face_unlock_keyguard_enabled", 0) == 1
    }.getOrDefault(false)

    private fun maybeStartFaceScan() {
        android.util.Log.d("IslandFaceID", "maybeStartFaceScan called cfg.faceId=$cfg.faceId faceScanActive=$faceScanActive")
        if (!cfg.faceId) return
        faceScanActive = true
        startFaceScan()
        watchForUnlock()
    }

    private fun startFaceScan() {
        android.util.Log.d("IslandFaceID", "startFaceScan showing scanning pill")
        showStatus(Status(R.drawable.ic_face_scan, WHITE, "Face ID", "Scanning", GREY), 5000)
    }

    private fun finishFaceScan(unlocked: Boolean) {
        if (!unlocked || !cfg.faceId) {
            faceScanActive = false
            if (status?.title == "Face ID") {
                handler.removeCallbacks(clearStatus); status = null; render()
            }
            return
        }
        onDeviceUnlocked(keyguardShowing = false)
    }

    // ── How the phone was unlocked ──────────────────────────────────────────
    // Android doesn't say whether face, fingerprint or PIN unlocked the phone,
    // so the island works it out:
    //  • unlocked while the lock screen is still showing → face (with "stay on
    //    lock screen" the phone is unlocked but waits for a swipe; a fingerprint
    //    always goes straight in)
    //  • the PIN / pattern / password pad was used on the lock screen → PIN
    //  • straight in the moment the screen woke → fingerprint (it wakes and
    //    unlocks in one touch); straight in later → fingerprint if this phone's
    //    face unlock is known to stay on the lock screen, otherwise face if a
    //    face check was running.

    private enum class UnlockMethod(val icon: Int, val label: String) {
        FACE(R.drawable.ic_face_ok, "Face"),
        FINGERPRINT(R.drawable.ic_fingerprint, "Fingerprint"),
        PIN(R.drawable.ic_lock_open, "PIN"),
    }

    /** The unlock pill already showed for this wake (the swipe that follows isn't a second unlock). */
    private var unlockShown = false
    /** Last time the lock screen's PIN / pattern / password pad was used. */
    private var bouncerUsedAt = 0L
    private var unlockPollUntil = 0L

    /** With "stay on lock screen" there's no broadcast when face unlock succeeds: watch for it. */
    private val unlockPoll = object : Runnable {
        override fun run() {
            val km = getSystemService(KeyguardManager::class.java) ?: return
            if (!km.isDeviceLocked) {
                onDeviceUnlocked(keyguardShowing = km.isKeyguardLocked)
                return
            }
            if (SystemClock.uptimeMillis() < unlockPollUntil) handler.postDelayed(this, 150)
        }
    }

    private fun watchForUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (!km.isDeviceSecure || !km.isDeviceLocked) return
        unlockPollUntil = SystemClock.uptimeMillis() + 15_000
        handler.removeCallbacks(unlockPoll)
        handler.postDelayed(unlockPoll, 150)
    }

    private fun onDeviceUnlocked(keyguardShowing: Boolean) {
        handler.removeCallbacks(unlockPoll)
        if (!cfg.faceId || unlockShown) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) return // swipe-only lock: nothing to show
        unlockShown = true
        val now = SystemClock.uptimeMillis()
        val faceStays = Prefs.get(this).getBoolean(PREF_FACE_STAYS, false)
        val method = when {
            now - bouncerUsedAt < 20_000 -> UnlockMethod.PIN
            keyguardShowing -> UnlockMethod.FACE
            now - screenOnAt < 700 -> UnlockMethod.FINGERPRINT
            faceStays -> UnlockMethod.FINGERPRINT
            faceScanActive -> UnlockMethod.FACE
            else -> UnlockMethod.FINGERPRINT
        }
        // Learn that this phone's face unlock waits on the lock screen.
        if (method == UnlockMethod.FACE && keyguardShowing && !faceStays) {
            Prefs.get(this).edit().putBoolean(PREF_FACE_STAYS, true).apply()
        }
        faceScanActive = false
        showStatus(Status(method.icon, GREEN, "Face ID", "Unlocked", GREEN), 1600)
    }

    /** Lock-screen PIN pad, pattern or password in use (System UI keyguard views). */
    private fun trackBouncer(e: AccessibilityEvent) {
        if (e.packageName != "com.android.systemui") return
        val cls = e.className?.toString() ?: ""
        val bouncer = BOUNCER_CLASS.containsMatchIn(cls) ||
            (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true &&
                (e.text?.joinToString("")?.trim()?.let { it.length == 1 && it[0].isDigit() } == true))
        if (bouncer) bouncerUsedAt = SystemClock.uptimeMillis()
    }

    private val keyguardListener = KeyguardManager.KeyguardLockedStateListener { locked ->
        if (!cfg.faceId) return@KeyguardLockedStateListener
        android.util.Log.d("IslandFaceID", "keyguardListener locked=$locked faceScanActive=$faceScanActive")
        if (locked) {
            unlockShown = false
            screenOnAt = SystemClock.uptimeMillis()
            maybeStartFaceScan()
        } else {
            finishFaceScan(unlocked = true)
        }
    }

    /**
     * True when the active window fills the screen (a game, a fullscreen
     * video). Uses the accessibility window bounds, so it needs no extra
     * permission and does not guess from the package name.
     */
    private fun isImmersive(): Boolean = runCatching {
        // Only an app window can be fullscreen: the shade, the lock screen and
        // the status bar are TYPE_SYSTEM and cover the screen too, but pulling
        // down the control centre must not hide the island.
        val win = windows.firstOrNull {
            it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: return@runCatching false
        val b = Rect()
        win.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        val fills = b.top <= 0 && b.left <= 0 &&
            b.width() >= dm.widthPixels - 2 && b.height() >= dm.heightPixels - 2
        if (!fills) return@runCatching false
        // Filling the screen is not enough: modern launchers and edge-to-edge
        // apps do too. Only count it when the status bar itself is gone.
        val sb = statusBarHeight()
        val barVisible = windows.any { w ->
            if (w === win) return@any false
            runCatching {
                val r = Rect()
                w.getBoundsInScreen(r)
                r.top <= 0 && r.height() in 1..(sb + 6) && r.width() >= dm.widthPixels - 4
            }.getOrDefault(false)
        }
        !barVisible
    }.getOrDefault(false)

    private fun trackForeground(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == packageName || "inputmethod" in pkg || "keyboard" in pkg) return
        if (pkg == foregroundPkg) return
        val wasHidden = foregroundPkg in cfg.hiddenApps
        foregroundPkg = pkg
        // A hidden app becoming visible (or vice versa) must redraw even when no
        // media is playing, otherwise the island stays hidden or stays out.
        if (wasHidden != (pkg in cfg.hiddenApps) || cfg.hideImmersive || media?.isVideo == true) render()
    }

    // TEMP: what the phone reports around copying (labels and lengths only, never the text)
    private fun debugEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && e.packageName != "com.android.systemui") return
        val label = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "len=${e.text?.firstOrNull()?.length} sel=${e.fromIndex}..${e.toIndex}"
            else -> ((e.text?.joinToString("|") ?: "") + " cd=" + (e.contentDescription ?: "")).take(60)
        }
        android.util.Log.i("IslandCopy", "${AccessibilityEvent.eventTypeToString(e.eventType)} pkg=${e.packageName} cls=${e.className} $label")
    }

    private fun readClipboardPreview() {
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        for (root in roots) {
            if (root.packageName != "com.android.systemui") continue
            val node: AccessibilityNodeInfo = root.findAccessibilityNodeInfosByViewId(
                "com.android.systemui:id/text_preview"
            ).firstOrNull() ?: continue
            val text = node.text?.toString()
            // The preview fires many updates while it's up: count it once.
            if (!text.isNullOrBlank() && text != lastPreviewText) {
                lastPreviewText = text
                onCopied(text)
            }
            return
        }
        lastPreviewText = null // preview closed: the next one is a new copy
    }

    private var grabView: View? = null
    private var lastGrabAt = 0L
    private var grabFallback: String? = null
    private val endGrab = Runnable { releaseClipboardFocus() }

    /** Take focus for a moment so Android lets us read the clipboard. */
    private fun grabClipboard(fallback: String?) {
        // One copy can announce itself twice (tap on Copy, then the toast):
        // a read already under way covers both.
        val now = SystemClock.uptimeMillis()
        if (grabView != null || now - lastGrabAt < 600) return
        lastGrabAt = now
        val w = wm ?: return
        grabFallback = fallback
        val v = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) return
                val clip = readClip()
                if (DEBUG_COPY) android.util.Log.i("IslandCopy", "focus grab read: ${clip?.first?.length ?: "null"}")
                if (clip != null) {
                    grabFallback = null
                    onCopied(clip.first, clip.second)
                }
                handler.post(endGrab)
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // focusable, but not for the keyboard, so typing in the app isn't disturbed
            FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Island clipboard"
        }
        if (runCatching { w.addView(v, lp) }.isFailure) {
            fallback?.let { onCopied(it) }
            return
        }
        grabView = v
        handler.postDelayed(endGrab, 900)
    }

    private fun releaseClipboardFocus() {
        handler.removeCallbacks(endGrab)
        grabView?.let { v -> runCatching { wm?.removeView(v) } }
        grabView = null
        grabFallback?.let { onCopied(it) }
        grabFallback = null
    }

    /**
     * Something was copied. [stamp] is when it went on the clipboard (-1 when we
     * only saw the text, e.g. in System UI's preview).
     *
     * One copy often reaches us several ways (the tap on Copy, Android's toast,
     * the app's own toast, the preview), so: the same clip timestamp is the same
     * copy; without a timestamp, the same text within a moment is the same copy.
     * Copying the same text again later is a new copy and shows the island again.
     */
    private fun onCopied(text: String, stamp: Long = -1L) {
        val now = SystemClock.uptimeMillis()
        if (DEBUG_COPY) android.util.Log.i(
            "IslandCopy", "onCopied len=${text.length} same=${text == lastCopied} dt=${now - lastCopiedAt} stamp=$stamp from=" +
                Throwable().stackTrace.getOrNull(1)?.methodName,
        )
        if (stamp > 0 && stamp == lastClipStamp) return
        if (text == lastCopied && now - lastCopiedAt < 1500) {
            if (stamp > 0) lastClipStamp = stamp
            return
        }
        if (stamp > 0) lastClipStamp = stamp
        lastCopied = text
        lastCopiedAt = now
        rememberCopy(text)
        shareClipboardWithComputer(text)
        val c = suggestions.forCopied(text) ?: return
        showCard(c)
    }

    /** Clipboard sync: Notify Bridge sends what you copy to your computer (when it's installed). */
    private fun shareClipboardWithComputer(text: String) {
        runCatching {
            sendBroadcast(
                Intent("com.notifybridge.action.CLIPBOARD")
                    .setPackage("com.notifybridge")
                    .putExtra("text", text.take(100_000)),
                "com.notifybridge.permission.CLIPBOARD",
            )
        }
    }

    private fun showCard(c: Card) {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "showCard ${c.kind}")
        // Copied the same thing again while its card is still up: nudge it so you see it registered.
        val again = card?.text == c.text && card?.kind == c.kind
        card = c
        if (again) island?.bounce()
        mediaExpanded = false
        handler.removeCallbacks(clearCard)
        handler.postDelayed(clearCard, 6500)
        render()
    }

    private fun showStatus(st: Status, holdMs: Long = 2400) {
        status = st
        statusExpanded = false
        handler.removeCallbacks(clearStatus)
        handler.postDelayed(clearStatus, holdMs)
        render()
    }

    // Earbuds, headphones, speakers, watches: not phones or computers
    // (Notify Bridge keeps a link to the PC, and that's not news).
    private fun onBluetooth(intent: Intent, connected: Boolean) {
        if (!hasBluetoothPermission()) return
        val device = deviceFrom(intent) ?: return
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return
        if (major != BluetoothClass.Device.Major.AUDIO_VIDEO && major != BluetoothClass.Device.Major.WEARABLE &&
            major != BluetoothClass.Device.Major.PERIPHERAL
        ) return
        val name = runCatching { device.alias ?: device.name }.getOrNull() ?: "Bluetooth device"
        val icon = if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) R.drawable.ic_headphones else R.drawable.ic_bluetooth
        // Earbuds connect each bud (and each audio profile) as its own link, so one
        // connection arrives as two or more events: show it once. Keyed by name,
        // since the left and right buds have different addresses but one name.
        val now = SystemClock.uptimeMillis()
        val event = "$connected|$name"
        if (event == btLastEvent && now - btLastAt < 15_000) return
        btLastEvent = event
        btLastAt = now
        if (connected) {
            btConnectedAt = SystemClock.uptimeMillis()
            btName = name
            showStatus(Status(icon, BLUE, name, "Connected", BLUE))
        } else {
            showStatus(Status(icon, GREY, name, "Disconnected", GREY))
        }
    }

    // The battery level usually arrives a moment after connecting: show it in the same pop-up.
    private fun onBluetoothBattery(intent: Intent) {
        val level = intent.getIntExtra(EXTRA_BT_BATTERY, -1)
        if (level < 0 || SystemClock.uptimeMillis() - btConnectedAt > 8000) return
        val name = btName ?: return
        // Only fill in the pop-up that's still on screen; never open a second one
        // (each bud reports its battery separately, often after the pop-up closed).
        val st = status ?: return
        if (st.title != name || st.value == "$level%") return
        val color = if (level <= 20) RED else GREEN
        status = Status(st.icon, BLUE, name, "$level%", color)
        handler.removeCallbacks(clearStatus)
        handler.postDelayed(clearStatus, 2000) // a moment to read the battery level
        render()
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ── Drag & drop ─────────────────────────────────────────────────────────
    // Apps that allow dragging between apps (Chrome, Files, Photos in split
    // screen…) can drop text, links and photos on the island to share them.

    private fun onDrag(e: DragEvent): Boolean {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "drag ${e.action}")
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (!cfg.dragDrop || island?.visibility != View.VISIBLE) return false
                dropActive = true
                dropHover = false
                openDropZone()
                render()
                return true
            }
            DragEvent.ACTION_DRAG_ENTERED -> { dropHover = true; render() }
            DragEvent.ACTION_DRAG_EXITED -> { dropHover = false; render() }
            DragEvent.ACTION_DROP -> {
                val c = suggestions.forDropped(e.clipData)
                dropActive = false
                dropHover = false
                if (c != null) showCard(c) else render()
                return c != null
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                if (dropActive) {
                    dropActive = false
                    dropHover = false
                    render()
                }
            }
        }
        return true
    }

    /** While something is being dragged, the whole top of the screen is the drop zone. */
    private fun openDropZone() {
        val pad = touchPad ?: return
        val tp = touchParams ?: return
        val screen = screenSize()
        tp.x = 0
        tp.y = 0
        tp.width = screen.x
        tp.height = (150 * resources.displayMetrics.density).roundToInt()
        runCatching { wm?.updateViewLayout(pad, tp) }
        pad.invalidate()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPosition() // rotation moves the camera
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
        handler.removeCallbacks(liveTick)
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(powerReceiver) }
            runCatching { unregisterReceiver(statusReceiver) }
            if (Build.VERSION.SDK_INT >= 33) runCatching {
                getSystemService(KeyguardManager::class.java)?.removeKeyguardLockedStateListener(keyguardListener)
            }
            receiverRegistered = false
        }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        runCatching { getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener) }
        unregisterBrightnessObserver()
        hotspot?.stop()
        hotspot = null
        island?.let { v -> runCatching { wm?.removeView(v) } }
        touchPad?.let { v -> runCatching { wm?.removeView(v) } }
        grabView?.let { v -> runCatching { wm?.removeView(v) } }
        grabView = null
        island = null
        touchPad = null
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        reloadFromPrefs()
    }

    override fun onPrefsChanged() {
        reloadFromPrefs()
    }

    private fun reloadFromPrefs() {
        handler.post {
            cfg = IslandConfig.load(this)
            IslandBus.videoApps = cfg.videoApps
            island?.cfg = cfg
            applyPosition()
            render()
        }
    }

    // ── Events from the listener ────────────────────────────────────────────

    /**
     * Some phones unbind the notification listener in the background (after an
     * update, a reboot or battery cleaning) and Android doesn't bring it back on
     * its own. Without it no notification reaches the island, so ask for it.
     */
    private fun ensureNotificationListener() {
        if (IslandBus.notificationsConnected) return
        val granted = runCatching {
            getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(
                android.content.ComponentName(this, IslandNotificationListener::class.java),
            )
        }.getOrNull() ?: false
        if (!granted) return
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, IslandNotificationListener::class.java),
            )
        }
    }

    override fun onNotification(event: NotifEvent) {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "live activity from ${event.packageName}")
        if (notif?.key == event.key) {
            // Same activity updated (timer ticking, next turn): refresh in place.
            notif = event
            if (!liveExpanded) armNotifTimeout(event)
            render()
        } else {
            present(event)
        }
    }

    private fun present(event: NotifEvent) {
        notif = event
        liveExpanded = false
        notifControls = false
        mediaExpanded = false
        armNotifTimeout(event)
        render()
    }

    /** An activity that is still running (timer, call, navigation, stopwatch). */
    private fun isOngoingLive(event: NotifEvent): Boolean =
        event.chronometerBase > 0L || event.liveKind == LiveKind.CALL ||
            event.liveKind == LiveKind.TIMER || event.liveKind == LiveKind.NAVIGATION

    /** The pill folds back on its own; one with more to read stays a little longer. */
    private fun armNotifTimeout(event: NotifEvent) {
        handler.removeCallbacks(clearNotif)
        // An activity that is still running stays on the island until its
        // notification goes away: folding it back after a few seconds would hide
        // the countdown (or the call) that is the whole point.
        if (isOngoingLive(event)) return
        handler.postDelayed(clearNotif, if (event.text.length > 60) 6000L else 4500L)
    }

    /** The live activity is done or put away: go back to idle. */
    private fun nextNotification() {
        handler.removeCallbacks(clearNotif)
        liveExpanded = false
        notifControls = false
        notif = null
        render()
    }

    override fun onNotificationRemoved(key: String) {
        val current = notif ?: return
        if (current.key != key) return
        if (!isOngoingLive(current)) {
            nextNotification()
            return
        }
        // Dialers cancel and re-post their call card while the call is still up
        // (ringing › connected, putting the screen to sleep). Clearing straight
        // away made the pill blink in and out mid-call, so give it a moment to
        // come back; a re-post cancels this by re-arming the timeout.
        handler.removeCallbacks(clearNotif)
        handler.postDelayed(clearNotif, REMOVE_GRACE_MS)
    }

    /** "Preview Face ID" button: scanning, then the green check. */
    override fun onFaceIdPreview() {
        faceScanActive = true
        showStatus(Status(R.drawable.ic_face_scan, WHITE, "Face ID", "Scanning", GREY), 1600)
        handler.postDelayed({
            faceScanActive = false
            showStatus(Status(R.drawable.ic_face_ok, GREEN, "Face ID", "Unlocked", GREEN), 1400)
        }, 1600)
    }

    override fun onMedia(state: MediaState?) {
        media = state
        render()
    }

    // ── Preview every pill (settings → Try it) ──────────────────────────────

    private var previewMode: String? = null
    private var previewUntil = 0L

    /** Quick Settings tile / shortcut: the timer and stopwatch card. */
    override fun onTimers() {
        showTimerPresets()
    }

    override fun onPreview(mode: String) {
        previewMode = mode
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS
        render()
        handler.postDelayed(previewDone, PREVIEW_MS + 100)
    }

    private val previewDone = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() < previewUntil) return
            previewMode = null
            render()
        }
    }

    private fun renderPreview(v: IslandView, mode: String) {
        when (mode) {
            "idle" -> v.showIdle()
            "charging" -> v.showCharging(72, true)
            "status" -> v.showStatus(R.drawable.ic_bluetooth, BLUE, "Earbuds", "Connected", BLUE)
            "live" -> v.showLiveActivity(LiveKind.TIMER, "Timer", "24:59")
            "actions" -> v.showActions(
                R.drawable.ic_copy, GREY, "Copy & Go", "example.com/article", emptyList(),
            )
            "drop" -> v.showDropTarget(false)
            "face" -> v.showFaceId(true)
            "media" -> v.showCompactMedia(previewMedia())
            "video" -> v.showCompactVideo(previewMedia())
            else -> v.showIdle()
        }
    }

    private fun previewMedia() = MediaState(
        title = "Preview track",
        artist = "Island",
        art = null,
        accent = 0xFFFF9F0A.toInt(),
        playing = true,
        durationMs = 180_000,
        positionMs = 42_000,
        positionUpdatedAt = SystemClock.elapsedRealtime(),
        speed = 1f,
        controller = null,
    )

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        val v = island ?: return
        // Apply the user-chosen accent override (0 = auto) so every pill drawn this
        // pass uses the configured colour instead of the album / app colour.
        v.accentOverride = cfg.accentColor
        val preview = previewMode
        if (preview != null && SystemClock.uptimeMillis() < previewUntil) {
            v.setSecondaryBubble(IslandView.SecondaryKind.NONE, false)
            renderPreview(v, preview)
            updateVisibility()
            return
        }
        if (DEBUG_COPY) android.util.Log.i(
            "IslandCopy",
            "render drop=$dropActive card=${card != null} live=${notif != null} status=${status != null} " +
                "charge=${charge != null} media=${media != null} exp=$mediaExpanded enabled=${cfg.enabled}",
        )
        val m = media?.takeIf { cfg.media && (!it.isVideo || cfg.videoPill) }
        if (m == null) mediaExpanded = false
        val n = notif
        val c = charge
        if (c == null) chargeExpanded = false
        val k = card
        val st = status
        val p = hotspot
        val hotspotOn = cfg.hotspotDevices && p?.hotspotOn == true
        if (!hotspotOn) hotspotExpanded = false
        val focus = swipeFocus?.takeIf { SystemClock.uptimeMillis() < swipeFocusUntil }
        if (focus != null) {
            v.setStrip(false, 0, c)
            if (renderFocus(v, focus, m, n, c, st, hotspotOn)) {
                updateVisibility()
                return
            }
            swipeFocus = null
        }
        // Dual layout: primary + secondary bubble when two compatible activities are active (compact only).
        // Priority (per spec): CALL P0 > NAV / media / recording P1 > timer P2 > charging / hotspot P3.
        // The primary pill is whatever the render block below decides to show; the bubble is the
        // highest-priority *other* active event beside it. A call always takes over as the expanded
        // primary card, so it never appears as a bubble beside another pill.
        var secondary = SecondaryKind.NONE
        var secondaryPlaying = false
        if (focus == null && k == null && !dropActive && !mediaExpanded && !liveExpanded &&
            !statusExpanded && !hotspotExpanded && !chargeExpanded && !timerExpanded
        ) {
            // Mirror the render `when` order so the bubble never duplicates the primary pill.
            val primaryKind = when {
                n != null && !(n.liveKind == LiveKind.CALL) -> when (n.liveKind) {
                    LiveKind.TIMER -> SecondaryKind.TIMER
                    LiveKind.NAVIGATION -> SecondaryKind.NAV
                    LiveKind.ACTIVITY, LiveKind.NONE -> SecondaryKind.STATUS
                    LiveKind.CALL -> SecondaryKind.CALL // unreachable due to the guard above
                }
                timerState != null -> SecondaryKind.TIMER                                    // island timer
                hotspotOn -> SecondaryKind.HOTSPOT                                          // hotspot pill
                st != null -> SecondaryKind.STATUS                                          // status pill
                c != null && !chargeExpanded -> SecondaryKind.CHARGING                      // charging pill
                m != null && m.playing -> SecondaryKind.MEDIA                               // media pill
                else -> SecondaryKind.NONE
            }

            val hasTimer = n != null && n.liveKind == LiveKind.TIMER
            val hasNav = n != null && n.liveKind == LiveKind.NAVIGATION
            val hasOtherLive = n != null && n.liveKind == LiveKind.ACTIVITY // voice / screen recording
            val hasMedia = m != null && m.playing
            val hasCharge = c != null && !chargeExpanded
            val hasHotspot = hotspotOn && (n == null || !liveExpanded)

            /** Priority rank: lower = more important / shown as primary first. */
            fun prio(kind: SecondaryKind): Int = when (kind) {
                SecondaryKind.CALL -> 0
                SecondaryKind.MEDIA, SecondaryKind.NAV, SecondaryKind.STATUS -> 1
                SecondaryKind.TIMER -> 2
                SecondaryKind.CHARGING, SecondaryKind.HOTSPOT -> 3
                SecondaryKind.NONE -> 9
            }

            // Candidates: any active event whose kind differs from the primary pill this pass.
            val candidates = mutableListOf<SecondaryKind>()
            if (hasMedia && primaryKind != SecondaryKind.MEDIA) candidates += SecondaryKind.MEDIA
            if (hasNav && primaryKind != SecondaryKind.NAV) candidates += SecondaryKind.NAV
            if (hasOtherLive && primaryKind != SecondaryKind.STATUS) candidates += SecondaryKind.STATUS
            if ((hasTimer || timerState != null) && primaryKind != SecondaryKind.TIMER) {
                candidates += SecondaryKind.TIMER
            }
            if (hasCharge && primaryKind != SecondaryKind.CHARGING) candidates += SecondaryKind.CHARGING
            if (hasHotspot && primaryKind != SecondaryKind.HOTSPOT) candidates += SecondaryKind.HOTSPOT

            // Highest-priority candidate becomes the bubble beside the primary pill.
            secondary = candidates.minByOrNull { prio(it) } ?: SecondaryKind.NONE
            secondaryPlaying = (secondary == SecondaryKind.MEDIA && m?.playing == true)
        }
        // Don't show a bubble alongside an expanded card.
        if (mediaExpanded || liveExpanded || statusExpanded) secondary = SecondaryKind.NONE
        lastSecondary = secondary
        // A charging / hotspot indicator that the bubble already shows must not be
        // repeated inside the main pill's strip.
        v.setStrip(
            hotspot = hotspotOn && secondary != IslandView.SecondaryKind.HOTSPOT,
            clients = p?.hotspotClients ?: 0,
            charge = if (secondary == IslandView.SecondaryKind.CHARGING) null else c,
        )
        v.setSecondaryBubble(secondary, secondaryPlaying)
        when {
            dropActive -> v.showDropTarget(dropHover)
            k != null -> v.showActions(k.badge, k.tint, k.kind, k.text, k.actions)
            n != null && liveExpanded ->
                v.showNotification(n, expanded = true, actions = notificationActions(n, notifControls), controls = notifControls)
            st != null && statusExpanded ->
                v.showActions(st.icon, st.tint, st.title, st.value, statusActions(st))
            // A ringing call opens straight into its card so Answer / Decline
            // are one tap away instead of hiding behind a long-press.
            // A timer / stopwatch card the user opened stays on top of the other
            // pills: it is the one thing they asked to look at.
            timerState != null && timerExpanded -> {
                val t = timerState!!
                v.showActions(
                    R.drawable.ic_timer,
                    if (t.kind == TimerKind.STOPWATCH) BLUE else ORANGE,
                    timerTitle(t), timerText(t), timerActions(t),
                )
            }
            n != null && n.liveKind == LiveKind.CALL ->
                v.showNotification(n, expanded = true, actions = notificationActions(n, true), controls = true)
            // A message or recording card shows its app's icon in the pill: the generic
            // glyph made every chat message look like the charging pill.
            n != null -> v.showLiveActivity(
                n.liveKind, n.title, clockText(n),
                badge = n.appIcon.takeIf { n.liveKind == LiveKind.ACTIVITY || n.liveKind == LiveKind.NONE },
            )
            timerState != null -> {
                val t = timerState!!
                v.showLiveActivity(LiveKind.TIMER, timerTitle(t), timerText(t))
            }
            st != null && st.title == "Face ID" -> v.showFaceId(st.value == "Unlocked", st.icon)
            hotspotOn && hotspotExpanded -> v.showActions(
                R.drawable.ic_hotspot, GREEN, "Hotspot",
                (p?.hotspotClients ?: 0).let { if (it > 0) "$it connected" else "On" },
                hotspotActions(),
            )
            hotspotOn -> v.showHotspot(p?.hotspotClients ?: 0)
            st != null -> v.showStatus(st.icon, st.tint, st.title, st.value, st.valueColor)
            c != null -> if (chargeExpanded) showBatteryCard(v, c.first, c.second) else v.showCharging(c.first, c.second)
            mediaExpanded && m != null -> v.showMedia(m)
            m != null && m.isVideo ->
                if (m.packageName != foregroundPkg) v.showCompactVideo(m) else v.showIdle()
            m != null && m.playing -> v.showCompactMedia(m)
            else -> v.showIdle()
        }
        handler.removeCallbacks(progressTick)
        if (v.mode == Mode.MEDIA) handler.post(progressTick)
        handler.removeCallbacks(liveTick)
        if (n != null && n.chronometerBase > 0L) handler.postDelayed(liveTick, 1000)
        // The island's own clock ticks itself; a countdown needs the finer step
        // so it never skips the second it actually runs out.
        handler.removeCallbacks(timerTick)
        timerState?.takeIf { it.running }?.let { t ->
            handler.postDelayed(timerTick, if (t.kind == TimerKind.STOPWATCH) 1000L else 500L)
        }
        updateVisibility()
    }

    private var chargeExpanded = false
    private var hotspotExpanded = false

    /** Charging pill, expanded: current draw, temperature and time-to-full. */
    /** Send a canned RemoteInput reply through the notification's own action. */
    private fun sendReply(n: NotifEvent, text: String) {
        val input = n.replyInput ?: return
        val intent = Intent()
        val results = android.os.Bundle().apply { putCharSequence(input.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
        runCatching { n.replyIntent?.send(this, 0, intent) }
            .onFailure { showStatus(Status(R.drawable.ic_message, ORANGE, "Reply", "Could not send", GREY)) }
        handler.removeCallbacks(clearNotif)
        notif = null
        render()
    }

    // ── Clipboard history (opt-in) ──────────────────────────────────────────

    private fun rememberCopy(text: String) {
        if (!cfg.clipHistory || text.isBlank()) return
        val items = loadCopies().toMutableList()
        items.remove(text)
        items.add(0, text.take(500))
        prefs().edit().putString(Prefs.CLIP_ITEMS, encodeCopies(items.take(20))).apply()
    }

    private fun loadCopies(): List<String> = runCatching {
        val raw = prefs().getString(Prefs.CLIP_ITEMS, null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun encodeCopies(items: List<String>): String {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun prefs() = Prefs.get(this)

    private fun showBatteryCard(v: IslandView, level: Int, plugged: Boolean) {
        val bm = getSystemService(BatteryManager::class.java)
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val chargePlug = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val currentAvg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: Int.MIN_VALUE
        val chargeCounter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        val energyCounter = if (Build.VERSION.SDK_INT >= 28) {
            try { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) ?: Long.MIN_VALUE } catch (_: Exception) { Long.MIN_VALUE }
        } else Long.MIN_VALUE

        // CURRENT_NOW units vary by device (often µA, sometimes mA). Normalize to mA-ish for display.
        fun normMa(v: Int): Float {
            val a = abs(v).toFloat()
            return if (a > 10000f) a / 1000f else a
        }
        val curMa = when {
            currentNow != Int.MIN_VALUE && currentNow != 0 -> normMa(currentNow)
            currentAvg != Int.MIN_VALUE && currentAvg != 0 -> normMa(currentAvg)
            else -> Float.NaN
        }
        val watts = if (!curMa.isNaN() && voltageMv > 0) (curMa / 1000f) * (voltageMv / 1000f) else Float.NaN

        val isCharging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val fast = isCharging && ((watts.isFinite() && watts >= 15f) || (!curMa.isNaN() && curMa >= 3000f))

        // Time to full estimate (very rough): charge counter is usually µAh remaining; if unavailable, skip.
        val hoursLeft = if (isCharging && curMa.isFinite() && curMa > 50f && chargeCounter > 0) {
            // If chargeCounter looks like µAh, convert to mAh; some devices report remaining differently.
            val cc = abs(chargeCounter).toFloat()
            val remainingMah = if (cc > 100000f) cc / 1000f else cc
            (remainingMah / curMa).takeIf { it in 0f..48f }
        } else null

        val plugLabel = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> null
        }

        val parts = buildList {
            val head = buildString {
                append("$level%")
                if (isCharging) append(if (fast) " · fast charging" else " · charging")
                if (plugLabel != null && isCharging) append(" ($plugLabel)")
            }
            add(head)
            if (curMa.isFinite()) {
                if (watts.isFinite()) add("%.0f mA · %.1f W".format(curMa, watts)) else add("%.0f mA".format(curMa))
            }
            if (tempTenths != Int.MIN_VALUE) add("%.1f °C".format(tempTenths / 10f))
            hoursLeft?.let { h -> add("~%dh %02dm to full".format(h.toInt(), ((h % 1f) * 60).toInt())) }
            if (energyCounter != Long.MIN_VALUE && energyCounter > 0) add("energy %d nWh".format(energyCounter))
        }
        v.showActions(
            R.drawable.ic_battery, if (isCharging) GREEN else WHITE, "Battery", parts.joinToString(" · "),
            batteryActions(),
        )
    }

    /** Buttons for an expanded status pill: its settings screen, then Hide. */
    private fun statusActions(st: Status): List<IslandView.Action> {
        val bluetooth = st.icon == R.drawable.ic_headphones || st.icon == R.drawable.ic_bluetooth
        val (label, action) = when {
            st.title == "Brightness" -> "Display" to android.provider.Settings.ACTION_DISPLAY_SETTINGS
            st.title == "Volume" -> "Sound" to android.provider.Settings.ACTION_SOUND_SETTINGS
            bluetooth -> "Bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            st.title == "Face ID" -> return listOf(statusHide)
            else -> "Settings" to android.provider.Settings.ACTION_SETTINGS
        }
        return listOf(
            IslandView.Action(label, null, R.drawable.ic_open) { openSettings(Intent(action)) },
            statusHide,
        )
    }

    private val statusHide = IslandView.Action("Hide", null, 0) {
        handler.removeCallbacks(clearStatus)
        statusExpanded = false
        clearStatus.run()
    }

    /** Buttons for the expanded charging card. */
    private fun batteryActions(): List<IslandView.Action> = listOf(
        IslandView.Action("Usage", null, R.drawable.ic_battery) {
            openSettings(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        },
        IslandView.Action("Hide", null, 0) {
            handler.removeCallbacks(clearCharge)
            chargeExpanded = false
            clearCharge.run()
        },
    )

    /** Buttons for the expanded hotspot card. */
    private fun hotspotActions(): List<IslandView.Action> = listOf(
        IslandView.Action("Settings", null, R.drawable.ic_open) {
            openSettings(Intent("android.settings.TETHER_SETTINGS"))
        },
        IslandView.Action("Hide", null, 0) {
            hotspotExpanded = false
            render()
        },
    )

    /** Open a settings screen, falling back to the main Settings app. */
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    private fun updateVisibility() {
        val v = island ?: return
        val lp = params ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val blocked = foregroundPkg in cfg.hiddenApps || (cfg.hideImmersive && isImmersive() && !faceScanActive)
        val visible = cfg.enabled && !blocked && !(landscape && cfg.hideLandscape)
        // Never set the view GONE: that destroys the window's surface, and the
        // next appearance (a face scan, a volume press) then shows up late and
        // its first frames can be garbage. Park the window off-screen instead.
        v.visibility = View.VISIBLE
        if (visible) {
            if (windowParked) {
                windowParked = false
                applyPosition()
            }
        } else {
            if (!windowParked) {
                windowParked = true
                lp.y = -lp.height - 200
                updateWindow()
            }
            onTouchArea(null)
        }
    }

    private var windowParked = false
    /** What the companion bubble currently stands for (set by render). */
    private var lastSecondary = IslandView.SecondaryKind.NONE

    /** Tapping the bubble opens that event's card as the primary pill. */
    override fun onSecondaryTap() {
        when (lastSecondary) {
            IslandView.SecondaryKind.MEDIA -> {
                if (media == null) return
                mediaExpanded = true
            }
            IslandView.SecondaryKind.CHARGING -> {
                if (charge == null) return
                chargeExpanded = true
            }
            // The bubble opens the same thing its pill would: the hotspot card with
            // its buttons, not a dead tap.
            IslandView.SecondaryKind.HOTSPOT -> {
                if (hotspot?.hotspotOn != true) return
                hotspotExpanded = true
            }
            IslandView.SecondaryKind.TIMER,
            IslandView.SecondaryKind.STATUS,
            -> when {
                // A timer the island runs itself is opened as its card with the
                // Start / Pause · Reset · Stop buttons.
                timerState != null -> timerExpanded = true
                notif != null -> liveExpanded = true
                status != null -> statusExpanded = true
                else -> return
            }
            IslandView.SecondaryKind.NAV,
            IslandView.SecondaryKind.CALL,
            -> if (notif != null) liveExpanded = true else return
            IslandView.SecondaryKind.NONE -> return
        }
        island?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        render()
    }

    // The island settled somewhere (or is moving / hidden: null). Put the
    // touch window exactly over it, or make it let everything through.
    override fun onTouchArea(area: Rect?) {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "touchArea $area drop=$dropActive vis=${island?.visibility}")
        if (dropActive) return // the drop zone owns the touch window until the drag ends
        val pad = touchPad ?: return
        val tp = touchParams ?: return
        val dp = params ?: return
        val usable = area?.takeIf { !windowParked && !it.isEmpty }
        touchArea = usable
        if (usable == null) {
            if (tp.x != PARKED || tp.width != 1) {
                tp.x = PARKED
                tp.y = PARKED
                tp.width = 1
                tp.height = 1
                runCatching { wm?.updateViewLayout(pad, tp) }
            }
            return
        }
        tp.x = dp.x + usable.left
        tp.y = dp.y + usable.top
        tp.width = usable.width()
        tp.height = usable.height()
        runCatching { wm?.updateViewLayout(pad, tp) }
        pad.invalidate()
    }

    /**
     * Put the island on the camera. Phones differ:
     *  - punch-hole camera near the top centre → the island hugs the hole
     *    and grows out of it
     *  - notch (wide cut-out) at the top centre → the island covers the notch
     *  - no cut-out, or a camera in a corner → the island sits at the top
     *    centre of the status bar and disappears when idle
     * The X/Y sliders then nudge it from there.
     */
    private fun applyPosition(update: Boolean = true) {
        val lp = params ?: return
        val v = island ?: return
        val d = resources.displayMetrics.density
        val screen = screenSize()
        val statusBar = statusBarHeight()
        // The camera cut-out determines the compact pill; events grow from it.
        val compactH = (32 * d).roundToInt()

        var anchorX = screen.x / 2
        var anchorY = statusBar / 2
        var holeW = 0
        var holeH = 0
        val cut = if (cfg.fitCamera) topCutout(screen.x) else null
        if (cut != null) {
            anchorX = cut.centerX()
            if (cut.width() <= cut.height() * 1.3f) {
                // punch hole: the cut-out band is often taller than the hole
                // itself, so use a circle as wide as the band
                holeW = cut.width()
                holeH = cut.width()
                anchorY = cut.centerY()
            } else {
                holeW = cut.width()
                holeH = cut.height()
                anchorY = cut.centerY()
            }
        }
        // the compact pill must be a bit taller than the camera it wraps
        val pillH = maxOf(compactH, holeH + (8 * d).roundToInt())
        val centreX = anchorX + (cfg.offsetXDp * d).roundToInt()
        v.geometry = IslandView.Geometry(holeW, holeH, pillH, centreX, screen.x)
        // One fixed window across the top, tall enough for the biggest state
        // (the media player) plus its bounce. It is never resized while the
        // island animates.
        lp.x = 0
        lp.y = maxOf(0, anchorY - pillH / 2) + (cfg.offsetYDp * d).roundToInt() - v.glowPad
        lp.width = screen.x
        // Extra room so expanded media / notifications can clear the camera hole.
        lp.height = (300 * d).roundToInt() + v.glowPad + holeH
        if (update) updateWindow()
    }

    private fun display(): Display? =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    @Suppress("DEPRECATION")
    private fun screenSize(): android.graphics.Point {
        val dm = DisplayMetrics()
        display()?.getRealMetrics(dm) ?: return android.graphics.Point(
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
        )
        return android.graphics.Point(dm.widthPixels, dm.heightPixels)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).roundToInt()
    }

    /** The camera cut-out at the top of the screen, if it's roughly centred. */
    private fun topCutout(screenW: Int): Rect? {
        if (Build.VERSION.SDK_INT < 29) return null
        val r = display()?.cutout?.boundingRectTop ?: return null
        if (r.isEmpty) return null
        val centred = abs(r.centerX() - screenW / 2) < screenW * 0.15f
        val notTooWide = r.width() < screenW * 0.6f
        return if (centred && notTooWide) r else null
    }

    private fun updateWindow() {
        val v = island ?: return
        val lp = params ?: return
        if (v.isAttachedToWindow) runCatching { wm?.updateViewLayout(v, lp) }
    }

    // ── Gestures ────────────────────────────────────────────────────────────

    override fun onTap() {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "tap")
        val v = island ?: return
        when (v.mode) {
            // A tap on the open live card goes to the app.
            // One tap opens the card with every button the app offers; there is
            // nothing left for a long-press to reveal.
            Mode.NOTIFICATION -> notif?.let { n ->
                if (liveExpanded) openNotification(n) else expandNotification(n, controls = true)
            }
            // A live pill (timer, call, navigation) opens into its card.
            Mode.LIVE -> when {
                timerState != null -> {
                    timerExpanded = !timerExpanded
                    render()
                }
                else -> notif?.let { expandNotification(it, controls = true) }
            }
            Mode.ACTIONS -> when {
                statusExpanded -> {
                    statusExpanded = false
                    render()
                }
                hotspotExpanded -> {
                    hotspotExpanded = false
                    render()
                }
                chargeExpanded -> {
                    chargeExpanded = false
                    render()
                }
                card == null && timerExpanded -> {
                    timerExpanded = false
                    render()
                }
                else -> {
                    handler.removeCallbacks(clearCard)
                    card = null
                    render()
                }
            }
            Mode.STATUS -> {
                statusExpanded = !statusExpanded
                render()
            }
            Mode.CHARGING -> {
                // The charging pill opens into the numbers the status bar can't show.
                chargeExpanded = !chargeExpanded
                render()
            }
            Mode.HOTSPOT -> {
                // Open the hotspot card: device count plus its own buttons.
                hotspotExpanded = true
                render()
            }
            Mode.DROP -> Unit
            Mode.CHARGING -> {
                handler.removeCallbacks(clearCharge)
                charge = null
                render()
            }
            Mode.IDLE, Mode.COMPACT_MEDIA ->
                // A tap is the way in: an empty island offers timers and the
                // stopwatch, a music pill opens the player.
                if (media != null && cfg.media) expandMedia() else showTimerPresets()
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
        val n = notif
        when {
            // The tap already opened every button, so a long-press adds nothing
            // here (and must not open the timer card over a notification).
            n != null && (v.mode == Mode.NOTIFICATION || v.mode == Mode.LIVE) -> Unit
            // A timer or stopwatch is already running: long-press opens its own
            // card (Start/Pause · Reset · Stop), not the card for starting a new one.
            timerState != null && v.mode != Mode.MEDIA -> {
                timerExpanded = true
                render()
            }
            // Tapping the music pill already opens the player, so the long-press
            // there is free: it opens the timer / stopwatch card.
            media != null && cfg.media && v.mode != Mode.MEDIA -> showTimerPresets()
            // Already on the player: switch to the phone's other active session
            // (podcast vs music) instead of doing nothing.
            v.mode == Mode.MEDIA -> IslandBus.switchMediaSource()
            else -> showTimerPresets()
        }
    }

    /** Long-press with nothing else to do: timers and a stopwatch to start. */
    private fun showTimerPresets() {
        val presets = listOf("5 min" to 5, "10 min" to 10, "25 min" to 25)
        card = Card(
            badge = R.drawable.ic_timer,
            tint = 0xFFFF9F0A.toInt(),
            kind = "Timer",
            text = "Start a timer",
            actions = presets.map { (label, minutes) ->
                IslandView.Action(label, null, 0) { startTimer(minutes) }
            } + IslandView.Action("Stopwatch", null, 0) { startStopwatch() },
        )
        render()
        handler.removeCallbacks(clearCard)
        handler.postDelayed(clearCard, 8000)
    }

    private fun startTimer(minutes: Int) = startTimerClock(minutes)

    override fun onSwipeUp() {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "swipe up")
        // An open card first folds back; a live pill is put away.
        val v = island
        if (notif != null && v != null && (v.mode == Mode.NOTIFICATION || v.mode == Mode.LIVE)) {
            if (liveExpanded) {
                liveExpanded = false
                notifControls = false
                notif?.let { armNotifTimeout(it) }
                render()
            } else {
                nextNotification()
            }
            return
        }
        dismissAll()
    }

    /**
     * Grow the live pill into its full card (all of the text, Open / Hide);
     * with [controls] also the app's own buttons. It stays while you read it.
     */
    private fun expandNotification(n: NotifEvent, controls: Boolean) {
        liveExpanded = true
        notifControls = controls
        handler.removeCallbacks(clearNotif)
        handler.postDelayed(clearNotif, EXPANDED_HOLD_MS)
        render()
    }

    /** Open card: Hide · Open. With controls: the app's own buttons first. */
    private fun notificationActions(n: NotifEvent, controls: Boolean): List<IslandView.Action> {
        // A call or timer is only hidden here, never removed from the shade.
        val hide = IslandView.Action("Hide", null, 0) { nextNotification() }
        val open = IslandView.Action("Open", null, R.drawable.ic_open) { openNotification(n) }
        // Opt-in canned replies: answer without a keyboard and without leaving
        // the island. Only shown when the notification actually has a reply action.
        if (n.replyIntent != null && n.replyInput != null && cfg.quickReplies) {
            val canned = cfg.cannedReplies.take(3).map { text ->
                IslandView.Action(text, null, R.drawable.ic_message) { sendReply(n, text) }
            }
            return canned + hide
        }
        if (!controls || n.actions.isEmpty()) return listOf(hide, open)
        val own = n.actions.take(3).map { a ->
            IslandView.Action(a.title, null, 0) {
                send(a.intent)
                // Live activities update themselves (Pause → Resume): keep the card open.
                handler.removeCallbacks(clearNotif)
                handler.postDelayed(clearNotif, EXPANDED_HOLD_MS)
            }
        }
        // Tapping the open card already opens the app, so Open gives way to the
        // app's own buttons: the card fits four, and the timer's Stop / Reset /
        // +1 min must not be cut off.
        return own + hide
    }

    /**
     * Flick sideways: on the open player it changes song. Everywhere else it
     * cycles through the activities that are on screen right now, iOS-style,
     * instead of throwing the current one away.
     */
    override fun onSwipeSideways(toLeft: Boolean) {
        val v = island ?: return
        if (v.mode == Mode.MEDIA) {
            if (toLeft) onNext() else onPrev()
            return
        }
        val order = buildList {
            if (media != null && cfg.media) add(Mode.COMPACT_MEDIA)
            if (notif != null) add(Mode.NOTIFICATION)
            if (cfg.hotspotDevices && hotspot?.hotspotOn == true) add(Mode.HOTSPOT)
            if (charge != null) add(Mode.CHARGING)
            if (status != null) add(Mode.STATUS)
        }
        if (order.isEmpty()) {
            onSwipeUp()
            return
        }
        val current = order.indexOf(v.mode).takeIf { it >= 0 } ?: -1
        val step = if (toLeft) 1 else -1
        val next = order[Math.floorMod(current + step, order.size)]
        swipeFocus = next
        swipeFocusUntil = SystemClock.uptimeMillis() + 6000
        render()
    }

    private var swipeFocus: Mode? = null
    private var swipeFocusUntil = 0L

    /** Draw the item the user flicked to, if it is still active. */
    private fun renderFocus(
        v: IslandView,
        focus: Mode,
        media: MediaState?,
        notif: NotifEvent?,
        charge: Pair<Int, Boolean>?,
        status: Status?,
        hotspotOn: Boolean,
    ): Boolean {
        when (focus) {
            Mode.COMPACT_MEDIA -> {
                val m = media ?: return false
                v.showCompactMedia(m)
            }
            Mode.NOTIFICATION -> {
                val n = notif ?: return false
                v.showLiveActivity(
                    n.liveKind, n.title, n.text,
                    badge = n.appIcon.takeIf { n.liveKind == LiveKind.ACTIVITY || n.liveKind == LiveKind.NONE },
                )
            }
            Mode.HOTSPOT -> {
                if (!hotspotOn) return false
                v.showHotspot(hotspot?.hotspotClients ?: 0)
            }
            Mode.CHARGING -> {
                val c = charge ?: return false
                v.showCharging(c.first, c.second)
            }
            Mode.STATUS -> {
                val st = status ?: return false
                v.showStatus(st.icon, st.tint, st.title, st.value, st.valueColor)
            }
            else -> return false
        }
        return true
    }


    override fun onOutsideTouch() {
        var changed = false
        if (mediaExpanded) {
            handler.removeCallbacks(collapseMedia)
            mediaExpanded = false
            changed = true
        }
        if (card != null && SystemClock.uptimeMillis() - lastCopiedAt > 700) {
            handler.removeCallbacks(clearCard)
            card = null
            changed = true
        }
        if (hotspotExpanded) {
            hotspotExpanded = false
            changed = true
        }
        if (chargeExpanded) {
            chargeExpanded = false
            changed = true
        }
        if (timerExpanded) {
            timerExpanded = false
            changed = true
        }
        // Touching the app below folds an open live card back.
        val n = notif
        if (n != null && liveExpanded) {
            liveExpanded = false
            notifControls = false
            armNotifTimeout(n)
            changed = true
        }
        if (changed) render()
    }

    override fun onPrev() {
        media?.controller?.transportControls?.skipToPrevious()
        rearmMediaCollapse()
    }

    private fun registerBrightnessObserver() {
        if (brightnessObserver != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { showBrightness() }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, obs
            )
            brightnessObserver = obs
        } catch (_: Exception) { }
    }

    private fun unregisterBrightnessObserver() {
        val obs = brightnessObserver ?: return
        try { contentResolver.unregisterContentObserver(obs) } catch (_: Exception) { }
        brightnessObserver = null
    }

    private fun showBrightness() {
        if (!IslandConfig.load(this).brightnessPill) return
        try {
            val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val pct = (cur * 100 / 255).coerceIn(0, 100)
            showStatus(
                Status(
                    R.drawable.ic_bell,
                    0xFFFFCC00.toInt(),
                    "Brightness",
                    "$pct%",
                    0xFFFFFFFF.toInt(),
                ),
                1500,
            )
        } catch (_: Exception) { }
    }

    /**
     * Volume keys: draw the level as a pill.
     *
     * The key is never swallowed — the phone's own volume change still happens;
     * the island just reflects it. Needs canRequestFilterKeyEvents (config xml).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN &&
            (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            handler.postDelayed({ showVolume() }, 60)
        }
        return super.onKeyEvent(event)
    }

    private fun showVolume() {
        if (!IslandConfig.load(this).volumePill) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) (level * 100 / max) else 0
        showStatus(
            Status(
                if (level == 0) R.drawable.ic_bell_off else R.drawable.ic_bell,
                if (level == 0) GREY else BLUE,
                "Volume",
                "$pct%",
                if (level == 0) GREY else WHITE,
            ),
            1500,
        )
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

    override fun onSeek(fraction: Float) {
        val m = media ?: return
        val dur = m.durationMs
        if (dur <= 0) return
        val tc = m.controller?.transportControls ?: return
        tc.seekTo((dur * fraction).toLong())
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
        liveExpanded = false
        notifControls = false
        handler.removeCallbacks(clearNotif)
        handler.removeCallbacks(clearCharge)
        handler.removeCallbacks(collapseMedia)
        handler.removeCallbacks(clearStatus)
        handler.removeCallbacks(clearCard)
        notif = null
        charge = null
        status = null
        card = null
        mediaExpanded = false
        hotspotExpanded = false
        chargeExpanded = false
        timerState = null
        timerExpanded = false
        handler.removeCallbacks(timerTick)
        faceScanActive = false
        render()
    }

    /**
     * Open the app the live activity came from, then fold the island straight back
     * into the camera. If the app's own link no longer works (it was cancelled or
     * the app was updated), open the app itself instead.
     */
    private fun openNotification(n: NotifEvent) {
        island?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val opened = n.contentIntent?.let { send(it) } ?: false
        if (!opened) {
            runCatching {
                packageManager.getLaunchIntentForPackage(n.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    ?.let { startActivity(it) }
            }
        }
        handler.removeCallbacks(clearNotif)
        liveExpanded = false
        notifControls = false
        notif = null
        render()
    }

    /** Returns false when the PendingIntent is gone. */
    @Suppress("DEPRECATION")
    private fun send(pi: PendingIntent): Boolean {
        return try {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(this, 0, null, null, null, null, opts)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    private companion object {
        // not in the public SDK, but sent by Android when a headset reports its battery
        const val DEBUG_COPY = false
        /** Off-screen spot for the touch window when the island shouldn't take touches. */
        const val PARKED = -200
        const val PREF_FACE_STAYS = "face_unlock_stays_on_lockscreen"
        val BOUNCER_CLASS = Regex("(?i)(pin|password|pattern|bouncer|numpad|passcode)")
        /** An opened card stays this long while you read it. */
        const val EXPANDED_HOLD_MS = 9000L
        /** How long a call / timer pill survives its notification being cancelled. */
        const val REMOVE_GRACE_MS = 3000L
        val COPY_WORD = Regex("\\bcopy\\b")
        const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        /** How long a preview pill from settings stays on screen. */
        const val PREVIEW_MS = 5000L
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val RED = 0xFFFF453A.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val PURPLE = 0xFF5E5CE6.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
