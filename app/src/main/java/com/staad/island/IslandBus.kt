package com.staad.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Origin Island-style live activities that stay on the pill until they finish. */
enum class LiveKind { NONE, CALL, TIMER, NAVIGATION, ACTIVITY }

/** A button from the notification itself ("Pause", "Hang up"...). */
class NotifAction(val title: String, val intent: PendingIntent)

/** An ongoing call, navigation session, timer or stopwatch, taken from its notification. */
data class NotifEvent(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    /** The sender's photo / large picture, if the notification has one. */
    val icon: Drawable?,
    val contentIntent: PendingIntent?,
    val liveKind: LiveKind,
    /** The posting app's launcher icon, shown as a badge (or alone when there is no photo). */
    val appIcon: Drawable? = null,
    val appName: String = "",
    val actions: List<NotifAction> = emptyList(),
    /** Reply action of a chat notification, when the app allows replying. */
    val replyIntent: android.app.PendingIntent? = null,
    val replyInput: android.app.RemoteInput? = null,
    /** The app icon's main colour, for the glow. */
    val accent: Int = 0xFFFFFFFF.toInt(),
    /** Chonometer base (elapsedRealtime) when the notification shows a running clock. */
    val chronometerBase: Long = 0L,
    /** True when that clock counts down (a timer) instead of up (a stopwatch). */
    val chronometerCountDown: Boolean = false,
    /** The base above is a wall-clock time (System.currentTimeMillis), not elapsedRealtime. */
    val wallClockBase: Boolean = false,
)

data class MediaState(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val accent: Int,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val controller: MediaController?,
    val packageName: String = "",
    /** Title of the next track in the queue, when the session provides it. */
    val nextTrackTitle: String? = null,
    /** Artist of the next track in the queue, when the session provides it. */
    val nextTrackArtist: String? = null,
) {
    /** True for video apps (YouTube, Netflix, ...): shown as a title + play/pause pill. */
    val isVideo: Boolean get() = packageName in VIDEO_PACKAGES || packageName in IslandBus.videoApps

    fun currentPosition(): Long = positionAt(
        positionMs = positionMs,
        playing = playing,
        elapsedSinceUpdateMs = SystemClock.elapsedRealtime() - positionUpdatedAt,
        speed = speed,
        durationMs = durationMs,
    )
}

/**
 * Pure position maths, split out of [MediaState.currentPosition] so it can be
 * unit-tested without an Android runtime.
 */
internal fun positionAt(
    positionMs: Long,
    playing: Boolean,
    elapsedSinceUpdateMs: Long,
    speed: Float,
    durationMs: Long,
): Long {
    if (positionMs < 0) return 0
    if (!playing) return positionMs
    val p = positionMs + (elapsedSinceUpdateMs * speed).toLong()
    return if (durationMs > 0) p.coerceIn(0, durationMs) else p
}

/**
 * In-process bridge between the notification listener (producer) and the
 * accessibility service that owns the overlay (consumer). Everything is
 * delivered on the main thread.
 */
object IslandBus {
    interface Listener {
        fun onNotification(event: NotifEvent)
        fun onNotificationRemoved(key: String)
        fun onMedia(state: MediaState?)
        fun onFaceIdPreview()
        fun onPrefsChanged()
        /** Show a fake pill so every mode can be checked on-device. */
        fun onPreview(mode: String)
        /** Open the timer / stopwatch card (Quick Settings tile, shortcut). */
        fun onTimers() {}
    }

    private val main = Handler(Looper.getMainLooper())

    /** Last known media state, replayed to a listener when it registers. */
    var media: MediaState? = null
        private set

    /** True while Android has the notification listener bound. */
    @Volatile var notificationsConnected = false

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onMedia(media)
        }

    val isOverlayRunning: Boolean get() = listener != null

    /** User-added video packages, refreshed from settings by the overlay service. */
    @Volatile var videoApps: Set<String> = emptySet()

    /** Set by the notification listener: move to the next active media session. */
    @Volatile var mediaSwitcher: (() -> Unit)? = null

    fun switchMediaSource() {
        main.post { mediaSwitcher?.invoke() }
    }

    fun postNotification(e: NotifEvent) {
        main.post { listener?.onNotification(e) }
    }

    fun postFaceIdPreview() {
        main.post { listener?.onFaceIdPreview() }
    }

    fun reloadPrefs() {
        main.post { listener?.onPrefsChanged() }
    }

    fun postPreview(mode: String) {
        main.post { listener?.onPreview(mode) }
    }

    /** Ask the island to show its timer / stopwatch card. */
    fun postTimers() {
        main.post { listener?.onTimers() }
    }

    fun postRemoved(key: String) {
        main.post { listener?.onNotificationRemoved(key) }
    }

    /** Connected devices shown in the system's hotspot notification (null = no such notification / no count). */
    var hotspotNotifClients: Int? = null
        private set

    /** Set by [HotspotMonitor] while the island runs; called on the main thread. */
    var hotspotObserver: (() -> Unit)? = null

    fun postHotspotClients(n: Int?) {
        main.post {
            if (hotspotNotifClients == n) return@post
            hotspotNotifClients = n
            hotspotObserver?.invoke()
        }
    }

    fun postMedia(s: MediaState?) {
        main.post {
            media = s
            listener?.onMedia(s)
        }
    }
}

val VIDEO_PACKAGES = setOf(
    "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube",
    "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "in.startv.hotstar",
    "com.google.android.videos", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
    "org.videolan.vlc", "tv.twitch.android.app", "com.vivo.video", "com.sonyliv",
    "com.jio.media.ondemand", "com.graymatrix.did", "com.vivo.gallery", "com.android.gallery3d",
    "com.google.android.apps.photos",
    // browsers: a media session there is almost always a video (YouTube in a tab etc.)
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
    "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
    "com.opera.browser", "com.opera.mini.native", "com.vivaldi.browser",
    "com.kiwibrowser.browser", "com.duckduckgo.mobile.android", "com.mi.globalbrowser",
    "com.vivo.browser", "com.heytap.browser", "com.UCMobile.intl",
)
