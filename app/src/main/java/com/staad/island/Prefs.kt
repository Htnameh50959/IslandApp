package com.staad.island

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ENABLED = "enabled"
    const val SHOW_IDLE = "show_idle"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val OFFSET_Y = "offset_y"
    const val OFFSET_X = "offset_x"
    const val NOTIFICATIONS = "notifications"
    const val MEDIA = "media"
    const val CHARGING = "charging"
    const val HIDE_LANDSCAPE = "hide_landscape"
    const val FIT_CAMERA = "fit_camera"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
}

data class IslandConfig(
    val enabled: Boolean = true,
    val showIdle: Boolean = true,
    val widthDp: Int = 110,
    val heightDp: Int = 32,
    val offsetYDp: Int = 0,
    val offsetXDp: Int = 0,
    val notifications: Boolean = true,
    val media: Boolean = true,
    val charging: Boolean = true,
    val hideLandscape: Boolean = true,
    /** Find the camera cut-out and grow the island out of it. */
    val fitCamera: Boolean = true,
) {
    companion object {
        val DEFAULT = IslandConfig()

        fun load(ctx: Context): IslandConfig {
            val p = Prefs.get(ctx)
            val d = DEFAULT
            return IslandConfig(
                enabled = p.getBoolean(Prefs.ENABLED, d.enabled),
                showIdle = p.getBoolean(Prefs.SHOW_IDLE, d.showIdle),
                widthDp = p.getInt(Prefs.WIDTH, d.widthDp),
                heightDp = p.getInt(Prefs.HEIGHT, d.heightDp),
                offsetYDp = p.getInt(Prefs.OFFSET_Y, d.offsetYDp),
                offsetXDp = p.getInt(Prefs.OFFSET_X, d.offsetXDp),
                notifications = p.getBoolean(Prefs.NOTIFICATIONS, d.notifications),
                media = p.getBoolean(Prefs.MEDIA, d.media),
                charging = p.getBoolean(Prefs.CHARGING, d.charging),
                hideLandscape = p.getBoolean(Prefs.HIDE_LANDSCAPE, d.hideLandscape),
                fitCamera = p.getBoolean(Prefs.FIT_CAMERA, d.fitCamera),
            )
        }
    }
}
