package com.staad.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
        val d = IslandConfig.DEFAULT

        bindSeek(R.id.seekY, R.id.valY, Prefs.OFFSET_Y, -40, 120, d.offsetYDp)
        bindSeek(R.id.seekX, R.id.valX, Prefs.OFFSET_X, -150, 150, d.offsetXDp)

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
        // Timer, navigation and call status share one live-activities preference.
        bindLinkedSwitches(
            intArrayOf(R.id.swLiveActivities, R.id.swNavigation, R.id.swCall),
            Prefs.LIVE_ACTIVITIES,
            d.liveActivities,
        )
        // Smart suggestions ride with Copy & Go.
        bindLinkedSwitches(
            intArrayOf(R.id.swCopy, R.id.swSuggestions),
            Prefs.COPY,
            d.copySuggestions,
        )
        bindSwitch(R.id.swFaceId, Prefs.FACE_ID, d.faceId)
        bindSwitch(R.id.swGlow, Prefs.GLOW, d.glow)
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
    }

    override fun onResume() {
        super.onResume()
        A11yGuard.repair(this)
        val a11y = isAccessibilityEnabled()
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
        // Done: a quiet "Settings" link. To do: the one filled button on the row.
        findViewById<Button>(buttonId).apply {
            text = if (on) "Settings" else "Turn on"
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

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, IslandService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun bindSeek(seekId: Int, valueId: Int, key: String, min: Int, max: Int, def: Int) {
        val seek = findViewById<SeekBar>(seekId)
        val value = findViewById<TextView>(valueId)
        val current = prefs.getInt(key, def).coerceIn(min, max)
        seek.max = max - min
        seek.progress = current - min
        value.text = "$current dp"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + min
                value.text = "$v dp"
                if (fromUser) {
                    prefs.edit().putInt(key, v).commit()
                    IslandBus.reloadPrefs()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        findViewById<Switch>(id).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).commit()
                IslandBus.reloadPrefs()
            }
        }
    }

    /** Several feature rows that share one preference stay visually in sync. */
    private fun bindLinkedSwitches(ids: IntArray, key: String, def: Boolean) {
        val switches = ids.map { findViewById<Switch>(it) }
        fun apply(checked: Boolean, source: Switch?) {
            prefs.edit().putBoolean(key, checked).commit()
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
    }
}
