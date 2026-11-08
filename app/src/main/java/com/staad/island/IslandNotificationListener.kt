package com.staad.island

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Feeds the island with incoming notifications and the active media session.
 * (Notification access is also what grants permission to read media sessions.)
 */
class IslandNotificationListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastArt: Bitmap? = null
    private var lastAccent = Color.WHITE
    private val lastContent = HashMap<String, String>()
    private val component by lazy { ComponentName(this, IslandNotificationListener::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
        override fun onSessionDestroyed() = refreshSessions(null)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions(it) }

    override fun onListenerConnected() {
        IslandBus.canceller = { key -> runCatching { cancelNotification(key) } }
        sessionManager = getSystemService(MediaSessionManager::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshSessions(null)
        } catch (_: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        IslandBus.canceller = null
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(mediaCallback)
        controller = null
        IslandBus.postMedia(null)
    }

    // ── Media ───────────────────────────────────────────────────────────────

    private fun refreshSessions(list: List<MediaController>?) {
        val sessions = list
            ?: (try { sessionManager?.getActiveSessions(component) } catch (_: SecurityException) { null })
            ?: emptyList()
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(mediaCallback)
            controller = best
            best?.registerCallback(mediaCallback, mainHandler)
        }
        publishMedia()
    }

    private fun publishMedia() {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        val state = st?.state ?: PlaybackState.STATE_NONE
        if (c == null || md == null ||
            state == PlaybackState.STATE_NONE ||
            state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_ERROR
        ) {
            IslandBus.postMedia(null)
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art !== lastArt) {
            lastArt = art
            lastAccent = accentOf(art)
        }
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        IslandBus.postMedia(
            MediaState(
                title = title,
                artist = artist,
                art = art,
                accent = lastAccent,
                playing = playing,
                durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = st?.position ?: 0L,
                positionUpdatedAt = st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = st?.playbackSpeed ?: 1f,
                controller = c,
            )
        )
    }

    // ── Notifications ───────────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val n = sbn.notification ?: return
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music is shown by the player
        when (n.category) {
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM -> return
        }
        // Respect silent channels and Do Not Disturb.
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (!ranking.matchesInterruptionFilter()) return
        }

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            .orEmpty().ifEmpty { appLabel(sbn.packageName) }
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()

        // Skip silent re-posts of the same content (progress updates, re-sorts, …).
        val signature = "$title\u0000$text"
        if (lastContent[sbn.key] == signature) return
        if (lastContent.size > 300) lastContent.clear()
        lastContent[sbn.key] = signature

        IslandBus.postNotification(
            NotifEvent(
                key = sbn.key,
                packageName = sbn.packageName,
                title = title,
                text = text,
                icon = loadIcon(sbn),
                contentIntent = n.contentIntent,
                autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        lastContent.remove(sbn.key)
        IslandBus.postRemoved(sbn.key)
    }

    private fun loadIcon(sbn: StatusBarNotification): Drawable? {
        sbn.notification.getLargeIcon()?.let { icon ->
            runCatching { icon.loadDrawable(this) }.getOrNull()?.let { return it }
        }
        return runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
