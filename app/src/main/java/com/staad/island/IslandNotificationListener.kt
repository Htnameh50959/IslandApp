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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

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
    // Keys cancelled after delivery to the Island. Their removal must not hide the pop-out.
    private val routedKeys = HashSet<String>()
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
        val sessions = (list
            ?: (try { sessionManager?.getActiveSessions(component) } catch (_: SecurityException) { null })
            ?: emptyList())
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
                packageName = c.packageName,
            )
        )
    }

    // ── Notifications ───────────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music is shown by the player

        val kind = classifyLive(n, sbn.packageName, sbn.isOngoing)
        val live = kind != LiveKind.NONE
        // Ongoing cards normally are noisy background services; calls, navigation and
        // timers are useful live activities and stay in the Island until they finish.
        if (sbn.isOngoing && !live) return
        if (live && !Prefs.get(this).getBoolean(Prefs.LIVE_ACTIVITIES, true)) return
        when (n.category) {
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM -> if (!live) return
            Notification.CATEGORY_PROGRESS -> if (!live) return
        }

        // Respect silent channels and Do Not Disturb for ordinary notifications.
        val ranking = Ranking()
        if (!live && rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (!ranking.matchesInterruptionFilter()) return
        }

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            .orEmpty().ifEmpty { appLabel(sbn.packageName) }
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()

        // Skip silent re-posts of the same content (progress updates, re-sorts, …).
        // Live activities re-post often (elapsed time); refresh when the text changes.
        val signature = "$title\u0000$text\u0000$kind"
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
                isLiveActivity = live,
                liveKind = kind,
            )
        )

        // Let the user hear the notification even when the Island replaces
        // the system banner (live activities re-post constantly, so don't chime).
        if (!live) playNotificationSound()

        // A notification listener cannot suppress protected system UI (including calls),
        // but it can remove normal, clearable app notifications once the Island has them.
        if (!live && sbn.isClearable && Prefs.get(this).getBoolean(Prefs.ROUTE_NOTIFICATIONS, false)) {
            routedKeys += sbn.key
            mainHandler.post { runCatching { cancelNotification(sbn.key) } }
        }
    }

    private fun classifyLive(n: Notification, pkg: String, ongoing: Boolean): LiveKind {
        val chronometer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        // Only treat as a live activity when it's genuinely ongoing — a missed-call
        // or one-shot alert with CATEGORY_CALL should not stick as a live pill.
        when (n.category) {
            Notification.CATEGORY_CALL -> if (ongoing) return LiveKind.CALL
            Notification.CATEGORY_NAVIGATION -> if (ongoing) return LiveKind.NAVIGATION
            Notification.CATEGORY_ALARM -> if (ongoing || chronometer) return LiveKind.TIMER
        }
        // Package / text heuristics only for ongoing or chronometer cards — missed-call
        // toasts and one-shot alerts should not stick forever on the island.
        if (!ongoing && !chronometer) return LiveKind.NONE

        val p = pkg.lowercase()
        if (CALL_PACKAGES.any { p.contains(it) }) return LiveKind.CALL
        if (NAV_PACKAGES.any { p.contains(it) }) return LiveKind.NAVIGATION
        if (TIMER_PACKAGES.any { p.contains(it) }) return LiveKind.TIMER

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val blob = "$title $text"
        if (blob.contains(TIMER_RE)) return LiveKind.TIMER
        if (blob.contains(NAV_RE)) return LiveKind.NAVIGATION
        if (blob.contains(CALL_RE)) return LiveKind.CALL
        if (chronometer) return LiveKind.ACTIVITY
        return LiveKind.NONE
    }

    private companion object {
        val TIMER_RE = Regex("\\b(timer|stopwatch|countdown)\\b", RegexOption.IGNORE_CASE)
        val NAV_RE = Regex(
            "\\b(navigation|navigating|turn left|turn right|arriving|destination|rerouting)\\b",
            RegexOption.IGNORE_CASE,
        )
        val CALL_RE = Regex(
            "\\b(incoming call|ongoing call|in call|call in progress|calling|ringing)\\b",
            RegexOption.IGNORE_CASE,
        )
        val CALL_PACKAGES = listOf(
            "dialer", "incallui", "com.android.phone", "com.google.android.dialer",
            "com.samsung.android.incallui", "com.android.server.telecom",
        )
        val NAV_PACKAGES = listOf(
            "maps", "navigation", "com.google.android.apps.maps", "com.waze", "com.sygic",
            "com.mapmyindia", "com.here.app.maps", "com.autonavi", "com.baidu.BaiduMap",
        )
        val TIMER_PACKAGES = listOf(
            "deskclock", "clock", "timer", "stopwatch", "com.google.android.deskclock",
            "com.android.deskclock", "com.sec.android.app.clockpackage", "com.vivo.countdown",
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        lastContent.remove(sbn.key)
        if (routedKeys.remove(sbn.key)) return
        IslandBus.postRemoved(sbn.key)
    }

    /** Play the system default notification sound once. */
    private fun playNotificationSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        runCatching {
            player.setDataSource(this, uri)
            player.prepare()
            player.start()
        }
        player.setOnCompletionListener { player.release() }
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
