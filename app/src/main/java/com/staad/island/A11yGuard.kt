package com.staad.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * vivo's i Manager (and other phone cleaners) keep switching the island's
 * Accessibility service off. If this app was given WRITE_SECURE_SETTINGS once
 * over USB:
 *     adb shell pm grant com.staad.island android.permission.WRITE_SECURE_SETTINGS
 * it puts itself back: when the notification listener connects, every 30 s
 * while it runs, after a restart or update, and when the app is opened.
 * Turning the island off in the app (the main switch) stops this.
 */
object A11yGuard {

    private const val TAG = "IslandA11yGuard"

    fun canRepair(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun isOn(ctx: Context): Boolean {
        val me = ComponentName(ctx, IslandService::class.java)
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Switch the service back on if something turned it off. True if it's on afterwards. */
    fun repair(ctx: Context): Boolean {
        if (isOn(ctx)) return true
        if (!canRepair(ctx) || !Prefs.get(ctx).getBoolean(Prefs.ENABLED, IslandConfig.DEFAULT.enabled)) return false
        return try {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, IslandService::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = cur.split(':').filter { it.isNotBlank() } + me
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Accessibility was turned off; switched it back on")
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** After the phone restarts or the app is updated. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            repair(ctx)
        }
    }
}
