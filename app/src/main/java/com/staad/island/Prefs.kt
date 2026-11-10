package com.staad.island

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ENABLED = "enabled"
    const val SHOW_IDLE = "show_idle"
    const val OFFSET_Y = "offset_y"
    const val OFFSET_X = "offset_x"
    const val NOTIFICATIONS = "notifications"
    const val MEDIA = "media"
    const val CHARGING = "charging"
    const val HIDE_LANDSCAPE = "hide_landscape"
    const val FIT_CAMERA = "fit_camera"
    const val GLOW = "glow"
    const val COPY = "copy_suggestions"
    const val STATUS = "status_popups"
    const val DROP = "drag_drop"
    const val FACE_ID = "face_id"
    const val VIDEO = "video_pill"
    /** Stop dismissible shade banners and show those notifications only in the Island. */
    const val ROUTE_NOTIFICATIONS = "route_notifications"
    /** Calls, navigation, timers and stopwatches stay as compact live pills. */
    const val LIVE_ACTIVITIES = "live_activities"
    /** Hotspot on / connected-devices pill. */
    const val HOTSPOT = "hotspot_devices"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
}

data class IslandConfig(
    val enabled: Boolean = true,
    val showIdle: Boolean = false,
    val offsetYDp: Int = 0,
    val offsetXDp: Int = 0,
    val notifications: Boolean = true,
    val media: Boolean = true,
    val charging: Boolean = true,
    val hideLandscape: Boolean = true,
    /** Find the camera cut-out and grow the island out of it. */
    val fitCamera: Boolean = true,
    /** Soft coloured light around the island (album art colour, green when charging…). */
    val glow: Boolean = true,
    /** Copy a link / number / email and the island offers to open it. */
    val copySuggestions: Boolean = true,
    /** Earbuds connected, silent / vibrate, Do Not Disturb. */
    val statusPopups: Boolean = true,
    /** Drag text or a link to the top of the screen to share it. */
    val dragDrop: Boolean = true,
    /** Face ID pill only while the lock screen is actively checking your face. */
    val faceId: Boolean = true,
    /** Show YouTube, browsers and other video apps in the island. Off = sound only. */
    val videoPill: Boolean = false,
    /** Route dismissible app notifications to the Island instead of the system shade/banner. */
    val routeNotifications: Boolean = false,
    /** Keep ongoing calls, navigation and timers on the Island until they finish. */
    val liveActivities: Boolean = true,
    /** Show personal hotspot on / connected device count. */
    val hotspotDevices: Boolean = true,
) {
    companion object {
        val DEFAULT = IslandConfig()

        fun load(ctx: Context): IslandConfig {
            val p = Prefs.get(ctx)
            val d = DEFAULT
            return IslandConfig(
                enabled = p.getBoolean(Prefs.ENABLED, d.enabled),
                showIdle = p.getBoolean(Prefs.SHOW_IDLE, d.showIdle),
                offsetYDp = p.getInt(Prefs.OFFSET_Y, d.offsetYDp),
                offsetXDp = p.getInt(Prefs.OFFSET_X, d.offsetXDp),
                notifications = p.getBoolean(Prefs.NOTIFICATIONS, d.notifications),
                media = p.getBoolean(Prefs.MEDIA, d.media),
                charging = p.getBoolean(Prefs.CHARGING, d.charging),
                hideLandscape = p.getBoolean(Prefs.HIDE_LANDSCAPE, d.hideLandscape),
                fitCamera = p.getBoolean(Prefs.FIT_CAMERA, d.fitCamera),
                glow = p.getBoolean(Prefs.GLOW, d.glow),
                copySuggestions = p.getBoolean(Prefs.COPY, d.copySuggestions),
                statusPopups = p.getBoolean(Prefs.STATUS, d.statusPopups),
                dragDrop = p.getBoolean(Prefs.DROP, d.dragDrop),
                faceId = p.getBoolean(Prefs.FACE_ID, d.faceId),
                videoPill = p.getBoolean(Prefs.VIDEO, d.videoPill),
                routeNotifications = p.getBoolean(Prefs.ROUTE_NOTIFICATIONS, d.routeNotifications),
                liveActivities = p.getBoolean(Prefs.LIVE_ACTIVITIES, d.liveActivities),
                hotspotDevices = p.getBoolean(Prefs.HOTSPOT, d.hotspotDevices),
            )
        }
    }
}
