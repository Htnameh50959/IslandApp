package com.staad.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

/**
 * Checks whether Island's own AccessibilityService is enabled, using Android's
 * normal API (never WRITE_SECURE_SETTINGS or a watchdog).
 *
 * If disabled, the app shows a notification / in-app message and lets the user
 * turn it on manually — it never force-re-enables the service.
 */
object AccessibilityStatus {

    /** True when Island's AccessibilityService is turned on. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val pkgName = context.packageName

        // Primary: check via the standard framework API.
        val found = services.any { info ->
            info.resolveInfo?.serviceInfo?.packageName == pkgName
        }
        if (found) return true

        // Fallback: some OEM skins (vivo / Xiaomi / OnePlus) report a stale
        // framework list while Settings.Secure already reflects the change.
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (enabled != null) {
            val target = "$pkgName/${pkgName}.IslandService"
            return enabled.split(":").map { it.trim() }.any { it == target }
        }

        return false
    }

    /** Open the system Accessibility settings so the user can turn Island on. */
    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Show a one-shot notification asking the user to enable Accessibility manually. */
    fun showEnableNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "island_a11y",
                    "Island accessibility",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Turn on Island's Accessibility service so the island can appear at the top of the screen."
                },
            )
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, channelId())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Island needs Accessibility")
            .setContentText("Turn on the Island service in Accessibility settings so the island can appear at the top of the screen.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, n)
    }

    private fun channelId(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "island_a11y" else ""
}
