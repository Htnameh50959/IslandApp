package com.staad.island

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
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
 *   drop target › suggestion card (copied link…) › notification › status
 *   (earbuds, silent…) › charging › expanded player › compact "now playing" › idle
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
    private var notif: NotifEvent? = null
    private var notifShownAt = 0L
    private var charge: Pair<Int, Boolean>? = null
    private var mediaExpanded = false
    private var receiverRegistered = false

    private class Status(val icon: Int, val tint: Int, val title: String, val value: String, val valueColor: Int)
    private var status: Status? = null
    private var card: Card? = null
    private var dropActive = false
    private var dropHover = false
    private val suggestions by lazy { Suggestions(this) { card = null; render() } }

    // copy detection (see onAccessibilityEvent)
    private var lastSelection: String? = null
    private var lastSelectionAt = 0L
    private var lastCopied: String? = null
    private var lastCopiedAt = 0L
    private val copyLabels by lazy {
        setOf("copy", runCatching { Resources.getSystem().getString(android.R.string.copy) }.getOrDefault("Copy"))
            .map { it.lowercase() }.toSet()
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Android only lets background apps read the clipboard in a few cases;
        // when it does, this is the most reliable signal.
        val text = runCatching {
            getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        if (!text.isNullOrBlank()) onCopied(text)
    }
    private var btConnectedAt = 0L
    private var btName: String? = null

    private val clearNotif = Runnable { notif = null; render() }
    private val clearCharge = Runnable { charge = null; render() }
    private val clearStatus = Runnable { status = null; render() }
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
            if (!cfg.statusPopups || isInitialStickyBroadcast) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBluetooth(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onBluetooth(intent, connected = false)
                ACTION_BT_BATTERY -> onBluetoothBattery(intent)
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
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, statusFilter)
        }
        receiverRegistered = true
        runCatching { getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener) }
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        IslandBus.listener = this // replays current media state → render()
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
                // Toasts queue up: an app's own "Message copied" can show up
                // seconds after Android's "Copied" for the same copy.
                if (SystemClock.uptimeMillis() - lastGrabAt < 10_000) return
                handler.postDelayed({ grabClipboard(null) }, 100)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (e.packageName == "com.android.systemui") readClipboardPreview()
        }
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
            if (!text.isNullOrBlank()) onCopied(text)
            return
        }
    }

    private var grabView: View? = null
    private var lastGrabAt = 0L
    private var grabFallback: String? = null
    private val endGrab = Runnable { releaseClipboardFocus() }

    /** Take focus for a moment so Android lets us read the clipboard. */
    private fun grabClipboard(fallback: String?) {
        // one copy can announce itself twice (WhatsApp: Android's "Copied"
        // and then its own "Message copied"): read the clipboard only once
        val now = SystemClock.uptimeMillis()
        if (grabView != null || now - lastGrabAt < 3000) return
        lastGrabAt = now
        val w = wm ?: return
        grabFallback = fallback
        val v = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) return
                val text = runCatching {
                    getSystemService(ClipboardManager::class.java)?.primaryClip
                        ?.getItemAt(0)?.coerceToText(this@IslandService)?.toString()
                }.getOrNull()
                if (DEBUG_COPY) android.util.Log.i("IslandCopy", "focus grab read: ${text?.length ?: "null"}")
                if (!text.isNullOrBlank()) {
                    grabFallback = null
                    onCopied(text)
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

    private fun onCopied(text: String) {
        val now = SystemClock.uptimeMillis()
        if (DEBUG_COPY) android.util.Log.i(
            "IslandCopy", "onCopied len=${text.length} same=${text == lastCopied} dt=${now - lastCopiedAt} from=" +
                Throwable().stackTrace.getOrNull(1)?.methodName,
        )
        // the clipboard still holds what we last showed: it's the same copy, seen again
        if (text == lastCopied && now - lastCopiedAt < 30_000) return
        lastCopied = text
        lastCopiedAt = now
        val c = suggestions.forCopied(text) ?: return
        showCard(c)
    }

    private fun showCard(c: Card) {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "showCard ${c.kind}")
        card = c
        mediaExpanded = false
        handler.removeCallbacks(clearCard)
        handler.postDelayed(clearCard, 6500)
        render()
    }

    private fun showStatus(st: Status) {
        status = st
        handler.removeCallbacks(clearStatus)
        handler.postDelayed(clearStatus, 2400)
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
        val color = if (level <= 20) RED else GREEN
        showStatus(Status(R.drawable.ic_headphones, BLUE, name, "$level%", color))
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
            receiverRegistered = false
        }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        runCatching { getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener) }
        island?.let { v -> runCatching { wm?.removeView(v) } }
        touchPad?.let { v -> runCatching { wm?.removeView(v) } }
        grabView?.let { v -> runCatching { wm?.removeView(v) } }
        grabView = null
        island = null
        touchPad = null
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        cfg = IslandConfig.load(this)
        island?.cfg = cfg
        applyPosition()
        render()
    }

    // ── Events from the listener ────────────────────────────────────────────

    override fun onNotification(event: NotifEvent) {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "notification from ${event.packageName}")
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
        if (DEBUG_COPY) android.util.Log.i(
            "IslandCopy",
            "render drop=$dropActive card=${card != null} notif=${notif != null} status=${status != null} " +
                "charge=${charge != null} media=${media != null} exp=$mediaExpanded cfgNotif=${cfg.notifications} enabled=${cfg.enabled}",
        )
        val m = media?.takeIf { cfg.media }
        if (m == null) mediaExpanded = false
        val n = notif
        val c = charge
        val k = card
        val st = status
        when {
            dropActive -> v.showDropTarget(dropHover)
            k != null -> v.showActions(k.badge, k.tint, k.kind, k.text, k.actions)
            n != null -> v.showNotification(n.icon, n.title, n.text)
            st != null -> v.showStatus(st.icon, st.tint, st.title, st.value, st.valueColor)
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
        val compactH = (cfg.heightDp * d).roundToInt()

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
        lp.y = maxOf(0, anchorY - pillH / 2) + (cfg.offsetYDp * d).roundToInt()
        lp.width = screen.x
        lp.height = (260 * d).roundToInt()
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
            Mode.NOTIFICATION -> notif?.let { openNotification(it) }
            // tapping the card itself (not one of its buttons) closes it
            Mode.ACTIONS -> {
                handler.removeCallbacks(clearCard)
                card = null
                render()
            }
            Mode.STATUS -> {
                handler.removeCallbacks(clearStatus)
                status = null
                render()
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
        when {
            v.mode == Mode.NOTIFICATION -> dismissAll()
            media != null && cfg.media && v.mode != Mode.MEDIA -> expandMedia()
            else -> v.bounce()
        }
    }

    override fun onSwipeUp() {
        if (DEBUG_COPY) android.util.Log.i("IslandCopy", "swipe up")
        dismissAll()
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
        handler.removeCallbacks(clearStatus)
        handler.removeCallbacks(clearCard)
        notif = null
        charge = null
        status = null
        card = null
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

    private companion object {
        // not in the public SDK, but sent by Android when a headset reports its battery
        const val DEBUG_COPY = false
        /** Off-screen spot for the touch window when the island shouldn't take touches. */
        const val PARKED = -200
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
