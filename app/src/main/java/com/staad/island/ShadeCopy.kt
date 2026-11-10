package com.staad.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification

/**
 * "Only in the island" removes an app's notification so Android doesn't show its
 * banner. Android has no way to stop just the banner, so this puts a quiet copy
 * back in the notification panel: a low-importance channel (no pop-up, no sound).
 * Tapping it, its buttons and inline replies still go to the original app.
 */
object ShadeCopy {
    const val CHANNEL = "shade_copies"

    /** Original notification key → id of our copy. */
    private val ids = HashMap<String, Int>()

    fun post(ctx: Context, sbn: StatusBarNotification, appName: String, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(nm)
        val src = sbn.notification
        val bigText = src.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: text

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(asBitmapIcon(ctx, src.smallIcon) ?: Icon.createWithResource(ctx, R.drawable.ic_bell))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setSubText(appName)
            .setWhen(src.`when`)
            .setShowWhen(true)
            .setColor(src.color)
            .setCategory(src.category)
            .setGroup("shade:${sbn.packageName}")
            .setOnlyAlertOnce(true)
            .setAutoCancel(src.flags and Notification.FLAG_AUTO_CANCEL != 0)
        asBitmapIcon(ctx, src.getLargeIcon())?.let { b.setLargeIcon(it) }
        src.contentIntent?.let { b.setContentIntent(it) }
        src.deleteIntent?.let { b.setDeleteIntent(it) }
        src.actions?.forEach { runCatching { b.addAction(it) } }

        val id = sbn.key.hashCode()
        synchronized(ids) { ids[sbn.key] = id }
        runCatching { nm.notify(id, b.build()) }
    }

    /** The Island handled the notification (opened / dismissed): drop the copy too. */
    fun cancel(ctx: Context, originalKey: String) {
        val id = synchronized(ids) { ids.remove(originalKey) } ?: return
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    fun isCopy(ctx: Context, sbn: StatusBarNotification): Boolean =
        sbn.packageName == ctx.packageName && sbn.notification?.channelId == CHANNEL

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Notifications from other apps", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kept in the notification panel while the Island shows them. No pop-up or sound."
                setShowBadge(true)
            }
        )
    }

    // Icons from another app point at that app's resources, which the panel may
    // fail to load for our notification. Draw them into a bitmap instead.
    private fun asBitmapIcon(ctx: Context, icon: Icon?): Icon? {
        icon ?: return null
        return runCatching {
            val d = icon.loadDrawable(ctx) ?: return null
            val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(256) ?: 96
            val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(256) ?: 96
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            Icon.createWithBitmap(bmp)
        }.getOrNull()
    }
}
