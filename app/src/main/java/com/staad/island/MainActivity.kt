package com.staad.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        applyWindowInsets()
        val d = IslandConfig.load(this)

        bindSeek(R.id.seekY, R.id.valY, Prefs.OFFSET_Y, -40, 120, d.offsetYDp)
        bindSeek(R.id.seekX, R.id.valX, Prefs.OFFSET_X, -150, 150, d.offsetXDp)
        bindSeek(R.id.seekCorner, R.id.valCorner, Prefs.CORNER_SCALE, 50, 150, d.cornerPercent, "%")
        bindSeek(R.id.seekGlow, R.id.valGlow, Prefs.GLOW_STRENGTH, 0, 100, d.glowPercent, "%")

        bindSwitch(R.id.swEnabled, Prefs.ENABLED, d.enabled)
        bindSwitch(R.id.swIdle, Prefs.SHOW_IDLE, d.showIdle)
        bindSwitch(R.id.swMedia, Prefs.MEDIA, d.media)
        bindSwitch(R.id.swCharging, Prefs.CHARGING, d.charging)
        bindSwitch(R.id.swLandscape, Prefs.HIDE_LANDSCAPE, d.hideLandscape)
        bindSwitch(R.id.swFit, Prefs.FIT_CAMERA, d.fitCamera)
        bindSwitch(R.id.swStatus, Prefs.STATUS, d.statusPopups)
        bindSwitch(R.id.swHotspot, Prefs.HOTSPOT, d.hotspotDevices)
        bindSwitch(R.id.swDrop, Prefs.DROP, d.dragDrop)
        bindSwitch(R.id.swVideo, Prefs.VIDEO, d.videoPill)
        bindSwitch(R.id.swNotifications, Prefs.NOTIFICATIONS_ENABLED, d.notificationsEnabled)
        bindSwitch(R.id.swLiveActivities, Prefs.LIVE_TIMERS, d.liveTimers)
        bindSwitch(R.id.swNavigation, Prefs.LIVE_NAVIGATION, d.liveNavigation)
        bindSwitch(R.id.swCall, Prefs.LIVE_CALLS, d.liveCalls)
        bindSwitch(R.id.swOtherLive, Prefs.LIVE_OTHER, d.liveOther)
        // Smart suggestions ride with Copy & Go.
        bindLinkedSwitches(
            intArrayOf(R.id.swCopy, R.id.swSuggestions),
            Prefs.COPY,
            d.copySuggestions,
        )
        bindSwitch(R.id.swFaceId, Prefs.FACE_ID, d.faceId)
        bindSwitch(R.id.swGlow, Prefs.GLOW, d.glow)
        bindSwitch(R.id.swImmersive, Prefs.HIDE_IMMERSIVE, d.hideImmersive)
        bindSwitch(R.id.swVolumePill, Prefs.VOLUME_PILL, d.volumePill)
        bindSwitch(R.id.swBrightnessPill, Prefs.BRIGHTNESS_PILL, d.brightnessPill)
        findViewById<View>(R.id.btnAccentColor).setOnClickListener { pickAccentColor() }
        bindSwitch(R.id.swClipHistory, Prefs.CLIP_HISTORY, d.clipHistory)
        bindSwitch(R.id.swQuickReplies, Prefs.QUICK_REPLIES, d.quickReplies)
        findViewById<TextView>(R.id.fitInfo).text = cameraInfo()

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBt).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31 && !hasBluetooth()) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            } else {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
        findViewById<View>(R.id.btnFaceId).setOnClickListener {
            if (!IslandBus.isOverlayRunning) {
                Toast.makeText(this, "Turn on step 1 first", Toast.LENGTH_SHORT).show()
            } else IslandBus.postFaceIdPreview()
        }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            prefs.edit().clear().apply()
            recreate()
        }
        findViewById<View>(R.id.btnPreview).setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
        findViewById<View>(R.id.btnHiddenApps).setOnClickListener{
            pickApps("Hide the island over", Prefs.HIDDEN_APPS) { updateHiddenInfo() }
        }
        findViewById<View>(R.id.btnVideoApps).setOnClickListener {
            pickApps("Treat as video apps", Prefs.VIDEO_APPS) { updateVideoInfo() }
        }
        findViewById<View>(R.id.btnNotificationApps).setOnClickListener {
            pickApps("Show notifications from", Prefs.NOTIFICATION_RULES) { updateNotificationAppsInfo() }
        }
        findViewById<View?>(R.id.btnCallApps)?.setOnClickListener {
            pickApps("Treat as call apps", Prefs.CALL_APPS) { updateCallInfo() }
        }
        findViewById<View?>(R.id.btnNavApps)?.setOnClickListener {
            pickApps("Treat as navigation apps", Prefs.NAV_APPS) { updateNavInfo() }
        }
        findViewById<View?>(R.id.btnTimerApps)?.setOnClickListener {
            pickApps("Treat as timer apps", Prefs.TIMER_APPS) { updateTimerInfo() }
        }
        findViewById<View>(R.id.btnClipHistory).setOnClickListener { showClipHistory() }
        findViewById<View>(R.id.btnCannedReplies).setOnClickListener { editCannedReplies() }
        findViewById<View>(R.id.btnExport).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "island-settings.json")
            startActivityForResult(intent, REQ_EXPORT)
        }
        findViewById<View>(R.id.btnImport).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
            startActivityForResult(intent, REQ_IMPORT)
        }
    }

    // ── Hidden apps ─────────────────────────────────────────────────────────

    private fun pickApps(title: String, key: String, onSaved: () -> Unit) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val selectionConfigured = prefs.contains(key)
        val chosen = if (key == Prefs.NOTIFICATION_RULES && !selectionConfigured) {
            apps.mapTo(mutableSetOf()) { it.packageName }
        } else {
            prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        }
        val checked = BooleanArray(apps.size) {
            (key == Prefs.NOTIFICATION_RULES && !selectionConfigured) ||
                apps[it].packageName in chosen
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) chosen += apps[which].packageName else chosen -= apps[which].packageName
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet(key, chosen).apply()
                IslandBus.reloadPrefs()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadCopies(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(Prefs.CLIP_ITEMS, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun showClipHistory() {
        val items = loadCopies()
        if (items.isEmpty()) {
            Toast.makeText(this, "Nothing copied yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { it.replace("\n", " ").take(70) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Clipboard history")
            .setItems(labels) { _, which ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Island", items[which]))
                Toast.makeText(this, "Copied again", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit().putString(Prefs.CLIP_ITEMS, "[]").apply()
                updateClipInfo()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editCannedReplies() {
        val current = prefs.getStringSet(Prefs.CANNED_REPLIES, IslandConfig.DEFAULT_REPLIES)
            ?.joinToString(", ") ?: ""
        val field = EditText(this).apply {
            setText(current)
            setHint("On my way, Can't talk now, OK")
            setSingleLine(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Canned replies")
            .setMessage("Comma separated. Shown as chips on chat notifications when Quick replies is on.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val set = field.text.toString().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.take(5).toSet()
                prefs.edit()
                    .putStringSet(
                        Prefs.CANNED_REPLIES,
                        set.ifEmpty { IslandConfig.DEFAULT_REPLIES },
                    ).apply()
                updateCannedInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateClipInfo() {
        val n = loadCopies().size
        findViewById<TextView>(R.id.clipInfo).text =
            if (n == 0) "Empty" else "$n item${if (n == 1) "" else "s"}"
    }

    private fun updateCannedInfo() {
        val n = prefs.getStringSet(Prefs.CANNED_REPLIES, IslandConfig.DEFAULT_REPLIES)?.size ?: 0
        findViewById<TextView>(R.id.cannedInfo).text = "$n repl${if (n == 1) "y" else "ies"}"
    }

    /** Preset accent colours the user can force on every pill (0 = auto from album / app). */
    private val ACCENT_PRESETS: List<Pair<String, Int>> = listOf(
        Pair("Auto", 0),
        Pair("Blue", 0xFF0A84FF.toInt()),
        Pair("Green", 0xFF30D158.toInt()),
        Pair("Red", 0xFFFF3B30.toInt()),
        Pair("Orange", 0xFFFF9F0A.toInt()),
        Pair("Purple", 0xFF5E5CE6.toInt()),
        Pair("Pink", 0xFFFF2D55.toInt()),
    )

    private fun pickAccentColor() {
        val current = prefs.getInt(Prefs.ACCENT_COLOR, 0)
        val names = ACCENT_PRESETS.map { it.first }.toTypedArray()
        val selected = ACCENT_PRESETS.indexOfFirst { it.second == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Accent color")
            .setSingleChoiceItems(names, selected) { _, which ->
                prefs.edit().putInt(Prefs.ACCENT_COLOR, ACCENT_PRESETS[which].second).apply()
                updateAccentInfo()
                IslandBus.reloadPrefs()
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAccentInfo() {
        val current = prefs.getInt(Prefs.ACCENT_COLOR, 0)
        val name = ACCENT_PRESETS.firstOrNull { it.second == current }?.first ?: "Custom"
        findViewById<TextView>(R.id.accentInfo).text = name
    }

    private fun updateVideoInfo() {
        val n = prefs.getStringSet(Prefs.VIDEO_APPS, emptySet())?.size ?: 0
        findViewById<TextView>(R.id.videoInfo).text = if (n == 0) {
            "Built-in list"
        } else {
            "Built-in + $n app${if (n == 1) "" else "s"}"
        }
    }

    private fun updateNotificationAppsInfo() {
        if (!prefs.contains(Prefs.NOTIFICATION_RULES)) {
            findViewById<TextView>(R.id.notificationAppsInfo).text = "All apps selected"
            return
        }
        val n = prefs.getStringSet(Prefs.NOTIFICATION_RULES, emptySet())?.size ?: 0
        findViewById<TextView>(R.id.notificationAppsInfo).text = if (n == 0) {
            "No apps selected"
        } else {
            "$n app${if (n == 1) "" else "s"} selected"
        }
    }

    private fun updateHiddenInfo() {
        val n = prefs.getStringSet(Prefs.HIDDEN_APPS, emptySet())?.size ?: 0
        findViewById<TextView>(R.id.hiddenInfo).text = if (n == 0) {
            "Island shows everywhere"
        } else {
            "Hidden over $n app${if (n == 1) "" else "s"}"
        }
    }

    private fun updateCallInfo() {
        val n = prefs.getStringSet(Prefs.CALL_APPS, emptySet())?.size ?: 0
        findViewById<TextView?>(R.id.callAppsInfo)?.text =
            if (n == 0) "Using built-in call apps" else "$n custom call app${if (n == 1) "" else "s"}"
    }

    private fun updateNavInfo() {
        val n = prefs.getStringSet(Prefs.NAV_APPS, emptySet())?.size ?: 0
        findViewById<TextView?>(R.id.navAppsInfo)?.text =
            if (n == 0) "Using built-in navigation apps" else "$n custom nav app${if (n == 1) "" else "s"}"
    }

    private fun updateTimerInfo() {
        val n = prefs.getStringSet(Prefs.TIMER_APPS, emptySet())?.size ?: 0
        findViewById<TextView?>(R.id.timerAppsInfo)?.text =
            if (n == 0) "Using built-in timer apps" else "$n custom timer app${if (n == 1) "" else "s"}"
    }

    // ── Settings import / export ─────────────────────────────────────────────

    private fun exportSettings() {
        val root = JSONObject()
        root.put("app", "Island")
        root.put("version", 1)
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean -> root.put(key, value)
                is Int -> root.put(key, value)
                is Long -> root.put(key, value)
                is String -> root.put(key, value)
                is Set<*> -> root.put(key, JSONArray(value.toList()))
                else -> Unit
            }
        }
        val uri = exportUri ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
        }.onFailure {
            Toast.makeText(this, "Could not export settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSettings(uri: android.net.Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val root = JSONObject(text)
            if (root.optString("app") != "Island") error("not an Island settings file")
            val editor = prefs.edit()
            for (key in root.keys()) {
                if (key == "app" || key == "version") continue
                when (val value = root.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
                }
            }
            editor.apply()
            Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
            recreate()
        }.onFailure {
            Toast.makeText(this, "That is not an Island settings file", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Uses the platform file picker; there is no AndroidX in this app.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> {
                exportUri = uri
                exportSettings()
            }
            REQ_IMPORT -> importSettings(uri)
        }
    }

    private var exportUri: android.net.Uri? = null

    override fun onResume() {
        super.onResume()
        updateHiddenInfo()
        updateVideoInfo()
        updateNotificationAppsInfo()
        updateCallInfo()
        updateNavInfo()
        updateTimerInfo()
        updateClipInfo()
        updateCannedInfo()
        val a11y = AccessibilityStatus.isAccessibilityEnabled(this)
        if (!a11y) AccessibilityStatus.showEnableNotification(this)
        val listener = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, IslandNotificationListener::class.java))
        setStatus(R.id.statusA11y, R.id.btnA11y, a11y, "On", "Needs Accessibility")
        setStatus(R.id.statusNotif, R.id.btnNotif, listener, "On", "Needs notification access")
        setStatus(R.id.statusBt, R.id.btnBt, hasBluetooth(), "On", "Needs nearby devices")
    }

    private fun hasBluetooth() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(statusId: Int, buttonId: Int, on: Boolean, onText: String, offText: String) {
        findViewById<TextView>(statusId).apply {
            text = if (on) onText else offText
            setTextColor(getColor(if (on) R.color.text2 else R.color.warn))
        }
        // One tap: opens Accessibility settings so the user can flip the toggle.
        // Android does not let an ordinary app switch its own AccessibilityService
        // on or off silently — the user must do it in Settings.
        findViewById<Button>(buttonId).apply {
            text = if (on) getString(R.string.a11y_open) else getString(R.string.a11y_enable)
            setBackgroundResource(if (on) R.drawable.btn_secondary else R.drawable.btn_primary)
            setTextColor(if (on) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
        }
    }

    /** Tell the user what we found, so they know whether to nudge the sliders. */
    @Suppress("DEPRECATION")
    private fun cameraInfo(): String {
        val cut = if (Build.VERSION.SDK_INT >= 29) windowManager.defaultDisplay.cutout?.boundingRectTop else null
        if (cut == null || cut.isEmpty) {
            return "No camera cut-out here, so the island comes from the top of the screen."
        }
        val d = resources.displayMetrics.density
        val kind = if (cut.width() <= cut.height() * 1.3f) "Punch-hole camera" else "Notch"
        return "$kind found, ${(cut.width() / d).toInt()} × ${(cut.height() / d).toInt()} dp. " +
            "Nudge the island below if it doesn’t sit right."
    }

    private fun bindSeek(
        seekId: Int,
        valueId: Int,
        key: String,
        min: Int,
        max: Int,
        def: Int,
        suffix: String = "dp",
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val value = findViewById<TextView>(valueId)
        val current = prefs.getInt(key, def).coerceIn(min, max)
        seek.max = max - min
        seek.progress = current - min
        value.text = "$current $suffix"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // Only update the label while dragging. Writing a preference on every
            // tick meant dozens of synchronous disk writes per second, which showed
            // up as stutter in the list. Persist once, when the finger lifts.
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = "${progress + min} $suffix"
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) {
                prefs.edit().putInt(key, s.progress + min).apply()
                IslandBus.reloadPrefs()
            }
        })
    }

    /**
     * targetSdk 36 enforces edge-to-edge, so a fixed top padding lands the title
     * under the status bar on tall cutouts. Pad by the real status-bar inset.
     */
    private fun applyWindowInsets() {
        val content = findViewById<View>(R.id.content)
        val baseTop = content.paddingTop
        content.setPaddingRelative(
            content.paddingStart, baseTop, content.paddingEnd, content.paddingBottom,
        )
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetTop
            }
            content.setPaddingRelative(
                content.paddingStart, baseTop + top, content.paddingEnd, content.paddingBottom,
            )
            insets
        }
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        findViewById<Switch>(id).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                IslandBus.reloadPrefs()
            }
        }
    }

    /** Several feature rows that share one preference stay visually in sync. */
    private fun bindLinkedSwitches(ids: IntArray, key: String, def: Boolean) {
        val switches = ids.map { findViewById<Switch>(it) }
        fun apply(checked: Boolean, source: Switch?) {
            prefs.edit().putBoolean(key, checked).apply()
            IslandBus.reloadPrefs()
            for (sw in switches) {
                if (sw === source || sw.isChecked == checked) continue
                sw.tag = true
                sw.isChecked = checked
                sw.tag = null
            }
        }
        val current = prefs.getBoolean(key, def)
        for (sw in switches) {
            sw.isChecked = current
            sw.setOnCheckedChangeListener { button, checked ->
                if (button.tag == true) return@setOnCheckedChangeListener
                apply(checked, button as Switch)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) onResume()
    }

    private companion object {
        const val REQ_BT = 8
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
    }
}
