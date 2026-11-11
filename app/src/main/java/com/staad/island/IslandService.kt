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
import android.util.DisplayMetrics
import android.view.Display
import android.view.DragEvent
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
import android.view.accessibility.AccessibilityNodeInfo
import com.staad.island.IslandView.Mode
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
            if (!cfg.statusPopups || isInitialStickyBroadcast) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBluetooth(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onBluetooth(intent, connected = false)
                ACTION_BT_BATTERY -> onBluetoothBattery(intent)
                Intent.ACTION_SCREEN_ON -> if (cfg.faceId) {
                    screenOnAt = SystemClock.uptimeMillis()
                    unlockShown = false
                    // Only while the lock screen is up and face unlock is enabled —
                    // never on a normal wake of an already-unlocked phone.
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
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
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
        if (!cfg.faceId || !faceUnlockOn()) return
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (km.isKeyguardLocked != true) return
        faceScanActive = true
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
        if (locked) {
            unlockShown = false
            // Lock screen came up (timeout / power) — start scanning if face unlock is on.
            if (SystemClock.uptimeMillis() - screenOnAt < 2500 || getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
                maybeStartFaceScan()
            }
        } else {
            finishFaceScan(unlocked = true)
        }
    }

    private fun trackForeground(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == packageName || "inputmethod" in pkg || "keyboard" in pkg) return
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        if (media?.isVideo == true) render()
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

    /** The pill folds back on its own; one with more to read stays a little longer. */
    private fun armNotifTimeout(event: NotifEvent) {
        handler.removeCallbacks(clearNotif)
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
        if (notif?.key == key) nextNotification()
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

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        val v = island ?: return
        if (DEBUG_COPY) android.util.Log.i(
            "IslandCopy",
            "render drop=$dropActive card=${card != null} live=${notif != null} status=${status != null} " +
                "charge=${charge != null} media=${media != null} exp=$mediaExpanded enabled=${cfg.enabled}",
        )
        val m = media?.takeIf { cfg.media && (!it.isVideo || cfg.videoPill) }
        if (m == null) mediaExpanded = false
        val n = notif
        val c = charge
        val k = card
        val st = status
        val p = hotspot
        val hotspotOn = cfg.hotspotDevices && p?.hotspotOn == true
        v.setStrip(hotspotOn, p?.hotspotClients ?: 0, c)
        when {
            dropActive -> v.showDropTarget(dropHover)
            k != null -> v.showActions(k.badge, k.tint, k.kind, k.text, k.actions)
            n != null && liveExpanded ->
                v.showNotification(n, expanded = true, actions = notificationActions(n, notifControls), controls = notifControls)
            st != null && statusExpanded -> v.showActions(st.icon, st.tint, st.title, st.value, emptyList())
            n != null -> v.showLiveActivity(n.liveKind, n.title, n.text)
            st != null && st.title == "Face ID" -> v.showFaceId(st.value == "Unlocked", st.icon)
            hotspotOn -> v.showHotspot(p?.hotspotClients ?: 0)
            st != null -> v.showStatus(st.icon, st.tint, st.title, st.value, st.valueColor)
            c != null -> v.showCharging(c.first, c.second)
            mediaExpanded && m != null -> v.showMedia(m)
            m != null && m.isVideo ->
                if (m.packageName != foregroundPkg) v.showCompactVideo(m) else v.showIdle()
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
        if (!visible) onTouchArea(null)
    }

    // The island settled somewhere (or is moving / hidden: null). Put the
    // touch window exactly over it, or make it let everything through.
    override fun onTouchArea(area: Rect?) {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "touchArea $area drop=$dropActive vis=${island?.visibility}")
        if (dropActive) return // the drop zone owns the touch window until the drag ends
        val pad = touchPad ?: return
        val tp = touchParams ?: return
        val dp = params ?: return
        val usable = area?.takeIf { island?.visibility == View.VISIBLE && !it.isEmpty }
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
            Mode.NOTIFICATION -> notif?.let { n ->
                if (liveExpanded) openNotification(n) else expandNotification(n, controls = false)
            }
            // A live pill (timer, call, navigation) opens into its card.
            Mode.LIVE -> notif?.let { expandNotification(it, controls = false) }
            Mode.ACTIONS -> {
                if (statusExpanded) {
                    statusExpanded = false
                    render()
                } else {
                    handler.removeCallbacks(clearCard)
                    card = null
                    render()
                }
            }
            Mode.STATUS -> {
                statusExpanded = !statusExpanded
                render()
            }
            Mode.HOTSPOT -> {
                // Open the system hotspot / tethering settings when possible.
                runCatching {
                    startActivity(
                        Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.recoverCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            Mode.DROP -> Unit
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
        val n = notif
        when {
            // Long-press: the card with additional controls (the app's own buttons).
            n != null && (v.mode == Mode.NOTIFICATION || v.mode == Mode.LIVE) ->
                expandNotification(n, controls = true)
            media != null && cfg.media && v.mode != Mode.MEDIA -> expandMedia()
            else -> v.bounce()
        }
    }

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
        if (!controls || n.actions.isEmpty()) return listOf(hide, open)
        val own = n.actions.take(2).map { a ->
            IslandView.Action(a.title, null, 0) {
                send(a.intent)
                // Live activities update themselves (Pause → Resume): keep the card open.
                handler.removeCallbacks(clearNotif)
                handler.postDelayed(clearNotif, EXPANDED_HOLD_MS)
            }
        }
        return own + hide + open
    }

    /** Flick sideways: on the open player it changes song; otherwise it dismisses. */
    override fun onSwipeSideways(toLeft: Boolean) {
        val v = island ?: return
        when {
            v.mode == Mode.MEDIA -> if (toLeft) onNext() else onPrev()
            else -> onSwipeUp()
        }
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
        val COPY_WORD = Regex("\\bcopy\\b")
        const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val RED = 0xFFFF453A.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val PURPLE = 0xFF5E5CE6.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
