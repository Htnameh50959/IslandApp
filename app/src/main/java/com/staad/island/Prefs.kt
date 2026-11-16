package com.staad.island

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ENABLED = "enabled"
    const val SHOW_IDLE = "show_idle"
    const val OFFSET_Y = "offset_y"
    const val OFFSET_X = "offset_x"
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
    /** Calls, navigation, timers and stopwatches stay as compact live pills. */
    const val LIVE_ACTIVITIES = "live_activities"
    /** Hotspot on / connected-devices pill. */
    const val HOTSPOT = "hotspot_devices"
    /** Packages where the island stays completely out of the way. */
    const val HIDDEN_APPS = "hidden_apps"
    /** Extra packages the user treats as video apps (on top of the built-in list). */
    const val VIDEO_APPS = "video_apps"
    /** Extra packages treated as calls/navigation/timers on top of built-in detection. */
    const val CALL_APPS = "call_apps"
    const val NAV_APPS = "nav_apps"
    const val TIMER_APPS = "timer_apps"
    /** Island look: 50–150 % corner rounding and 0–100 % glow. */
    const val CORNER_SCALE = "corner_scale"
    const val GLOW_STRENGTH = "glow_strength"
    /** Fold the island away over games and other fullscreen apps. */
    const val HIDE_IMMERSIVE = "hide_immersive"
    /** Opt-in, on-device clipboard history (last 20 items). */
    const val CLIP_HISTORY = "clip_history"
    /** Opt-in canned replies to chat notifications. */
    const val QUICK_REPLIES = "quick_replies"
    const val CANNED_REPLIES = "canned_replies"
    /** Stored clipboard history; only written when the user opts in. */
    const val CLIP_ITEMS = "clip_items"
    /** Volume/brightness pills. */
    const val VOLUME_PILL = "volume_pill"
    const val BRIGHTNESS_PILL = "brightness_pill"
    /** Accent color override (0 = auto from album/app). */
    const val ACCENT_COLOR = "accent_color"
    /** Height scale percent for island size. */
    const val HEIGHT_SCALE = "height_scale"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
}

data class IslandConfig(
    val enabled: Boolean = true,
    val showIdle: Boolean = false,
    val offsetYDp: Int = 0,
    val offsetXDp: Int = 0,
    val media: Boolean = true,
    val charging: Boolean = true,
    val hideLandscape: Boolean = true,
    /** Find the camera cut-out and grow the island out of it. */
    val fitCamera: Boolean = true,
    /** Soft coloured light around the island (album art colour, green when charging…). */
    val glow: Boolean = true,
    /** Copy a link / number / email and the island offers to open it. */
    // Off by default: the clipboard path briefly takes window focus, so Android
    // shows its "Island pasted from your clipboard" notice every time. Surprise
    // system UI on the first copy is not a good first impression.
    val copySuggestions: Boolean = false,
    /** Earbuds connected, silent / vibrate, Do Not Disturb. */
    val statusPopups: Boolean = true,
    /** Drag text or a link to the top of the screen to share it. */
    val dragDrop: Boolean = true,
    /** Face ID pill only while the lock screen is actively checking your face. */
    val faceId: Boolean = true,
    /** Show YouTube, browsers and other video apps in the island. Off = sound only. */
    val videoPill: Boolean = false,
    /** Keep ongoing calls, navigation and timers on the Island until they finish. */
    val liveActivities: Boolean = true,
    /** Show personal hotspot on / connected device count. */
    val hotspotDevices: Boolean = true,
    /** Never draw the island over these packages (games, banking, camera…). */
    val hiddenApps: Set<String> = emptySet(),
    val videoApps: Set<String> = emptySet(),
    val callApps: Set<String> = emptySet(),
    val navApps: Set<String> = emptySet(),
    val timerApps: Set<String> = emptySet(),
    val cornerPercent: Int = 100,
    val glowPercent: Int = 100,
    val hideImmersive: Boolean = false,
    val clipHistory: Boolean = false,
    val quickReplies: Boolean = false,
    val cannedReplies: Set<String> = DEFAULT_REPLIES,
    val volumePill: Boolean = true,
    val brightnessPill: Boolean = true,
    val accentColor: Int = 0,
    val heightPercent: Int = 100,
) {
    companion object {
        val DEFAULT_REPLIES: Set<String> = setOf("On my way", "Can't talk now", "OK")

        val DEFAULT = IslandConfig(cannedReplies = DEFAULT_REPLIES)

        fun load(ctx: Context): IslandConfig {
            val p = Prefs.get(ctx)
            val d = DEFAULT
            return IslandConfig(
                enabled = p.getBoolean(Prefs.ENABLED, d.enabled),
                showIdle = p.getBoolean(Prefs.SHOW_IDLE, d.showIdle),
                offsetYDp = p.getInt(Prefs.OFFSET_Y, d.offsetYDp),
                offsetXDp = p.getInt(Prefs.OFFSET_X, d.offsetXDp),
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
                liveActivities = p.getBoolean(Prefs.LIVE_ACTIVITIES, d.liveActivities),
                hotspotDevices = p.getBoolean(Prefs.HOTSPOT, d.hotspotDevices),
                hiddenApps = p.getStringSet(Prefs.HIDDEN_APPS, d.hiddenApps)?.toSet()
                    ?: d.hiddenApps,
                videoApps = p.getStringSet(Prefs.VIDEO_APPS, d.videoApps)?.toSet()
                    ?: d.videoApps,
                callApps = p.getStringSet(Prefs.CALL_APPS, d.callApps)?.toSet() ?: d.callApps,
                navApps = p.getStringSet(Prefs.NAV_APPS, d.navApps)?.toSet() ?: d.navApps,
                timerApps = p.getStringSet(Prefs.TIMER_APPS, d.timerApps)?.toSet() ?: d.timerApps,
                cornerPercent = p.getInt(Prefs.CORNER_SCALE, d.cornerPercent).coerceIn(50, 150),
                glowPercent = p.getInt(Prefs.GLOW_STRENGTH, d.glowPercent).coerceIn(0, 100),
                hideImmersive = p.getBoolean(Prefs.HIDE_IMMERSIVE, d.hideImmersive),
                clipHistory = p.getBoolean(Prefs.CLIP_HISTORY, d.clipHistory),
                quickReplies = p.getBoolean(Prefs.QUICK_REPLIES, d.quickReplies),
                cannedReplies = p.getStringSet(Prefs.CANNED_REPLIES, d.cannedReplies)?.toSet()
                    ?: d.cannedReplies,
                volumePill = p.getBoolean(Prefs.VOLUME_PILL, d.volumePill),
                brightnessPill = p.getBoolean(Prefs.BRIGHTNESS_PILL, d.brightnessPill),
                accentColor = p.getInt(Prefs.ACCENT_COLOR, d.accentColor),
                heightPercent = p.getInt(Prefs.HEIGHT_SCALE, d.heightPercent).coerceIn(70, 150),
            )
        }
    }
}
