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

/** A button from the notification itself ("Pause", "Mark as read", "Hang up"...). */
class NotifAction(val title: String, val intent: PendingIntent)

data class NotifEvent(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    /** The sender's photo / large picture, if the notification has one. */
    val icon: Drawable?,
    val contentIntent: PendingIntent?,
    val autoCancel: Boolean,
    /** An ongoing call, navigation session, timer or stopwatch. */
    val isLiveActivity: Boolean = false,
    val liveKind: LiveKind = LiveKind.NONE,
    /** The posting app's launcher icon, shown as a badge (or alone when there is no photo). */
    val appIcon: Drawable? = null,
    val appName: String = "",
    val actions: List<NotifAction> = emptyList(),
    /** The app icon's main colour, for the glow. */
    val accent: Int = 0xFFFFFFFF.toInt(),
) {
    /** What the user would see; two events with the same signature are the same pop-up. */
    val signature: String get() = "$title\u0000$text\u0000$liveKind"
}

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
) {
    /** True for video apps (YouTube, Netflix, ...): shown as a title + play/pause pill. */
    val isVideo: Boolean get() = packageName in VIDEO_PACKAGES

    fun currentPosition(): Long {
        if (positionMs < 0) return 0
        if (!playing) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - positionUpdatedAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p
    }
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

    /** Set by the notification listener so the overlay can dismiss notifications. */
    var canceller: ((String) -> Unit)? = null

    val isOverlayRunning: Boolean get() = listener != null

    fun postNotification(e: NotifEvent) {
        main.post { listener?.onNotification(e) }
    }

    fun postFaceIdPreview() {
        main.post { listener?.onFaceIdPreview() }
    }

    fun reloadPrefs() {
        main.post { listener?.onPrefsChanged() }
    }

    fun postRemoved(key: String) {
        main.post { listener?.onNotificationRemoved(key) }
    }

    fun postMedia(s: MediaState?) {
        main.post {
            media = s
            listener?.onMedia(s)
        }
    }

    fun cancel(key: String) {
        canceller?.invoke(key)
    }
}

val VIDEO_PACKAGES = setOf(
    "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube",
    "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "in.startv.hotstar",
    "com.google.android.videos", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
    "org.videolan.vlc", "tv.twitch.android.app", "com.vivo.video", "com.sonyliv",
    "com.jio.media.ondemand", "com.graymatrix.did", "com.vivo.gallery", "com.android.gallery3d",
    "com.google.android.apps.photos",
)
