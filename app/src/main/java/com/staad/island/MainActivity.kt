package com.staad.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var testCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        val d = IslandConfig.DEFAULT

        bindSeek(R.id.seekWidth, R.id.valWidth, Prefs.WIDTH, 60, 300, d.widthDp)
        bindSeek(R.id.seekHeight, R.id.valHeight, Prefs.HEIGHT, 20, 64, d.heightDp)
        bindSeek(R.id.seekY, R.id.valY, Prefs.OFFSET_Y, 0, 120, d.offsetYDp)
        bindSeek(R.id.seekX, R.id.valX, Prefs.OFFSET_X, -150, 150, d.offsetXDp)

        bindSwitch(R.id.swEnabled, Prefs.ENABLED, d.enabled)
        bindSwitch(R.id.swIdle, Prefs.SHOW_IDLE, d.showIdle)
        bindSwitch(R.id.swNotif, Prefs.NOTIFICATIONS, d.notifications)
        bindSwitch(R.id.swMedia, Prefs.MEDIA, d.media)
        bindSwitch(R.id.swCharging, Prefs.CHARGING, d.charging)
        bindSwitch(R.id.swLandscape, Prefs.HIDE_LANDSCAPE, d.hideLandscape)

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { preview() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTestNotification() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            prefs.edit().clear().apply()
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        val a11y = isAccessibilityEnabled()
        val listener = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, IslandNotificationListener::class.java))
        setStatus(R.id.statusA11y, R.id.btnA11y, a11y, "Island is on screen", "Turn on “Island” in Accessibility")
        setStatus(R.id.statusNotif, R.id.btnNotif, listener, "Pop-outs & music active", "Allow notification access")
    }

    private fun setStatus(statusId: Int, buttonId: Int, on: Boolean, onText: String, offText: String) {
        findViewById<TextView>(statusId).apply {
            text = if (on) "● $onText" else "○ $offText"
            setTextColor(if (on) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt())
        }
        findViewById<Button>(buttonId).apply {
            text = if (on) "Manage" else "Enable"
            setBackgroundResource(if (on) R.drawable.btn_secondary else R.drawable.btn_primary)
        }
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
                if (fromUser) prefs.edit().putInt(key, v).apply()
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        findViewById<Switch>(id).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
    }

    private fun preview() {
        if (!IslandBus.isOverlayRunning) {
            Toast.makeText(this, "Turn on step 1 first", Toast.LENGTH_SHORT).show()
            return
        }
        IslandBus.postNotification(
            NotifEvent(
                key = "island-preview",
                packageName = packageName,
                title = "Hello from the Island ✨",
                text = "Tap to open · long-press or swipe up to dismiss",
                icon = getDrawable(R.mipmap.ic_launcher),
                contentIntent = null,
                autoCancel = false,
            )
        )
    }

    private fun sendTestNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Test notifications", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        testCount++
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Test notification #$testCount")
            .setContentText("This popped out of the island. Tap it to come back here.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        Toast.makeText(this, "Arriving in 2 seconds — go to your home screen", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ nm.notify(TEST_ID, n) }, 2000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            sendTestNotification()
        }
    }

    private companion object {
        const val CHANNEL = "test"
        const val TEST_ID = 42
        const val REQ_NOTIF = 7
    }
}
