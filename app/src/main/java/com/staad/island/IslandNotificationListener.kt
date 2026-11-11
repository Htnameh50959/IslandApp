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
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Feeds the island with live activities (calls, navigation, timers) and the
 * active media session. Ordinary notifications are not shown in the island.
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

    // The Videos switch changes which session is picked. Kept in a field:
    // SharedPreferences only holds listeners weakly.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.VIDEO) refreshSessions(null)
        }

    override fun onListenerConnected() {
        IslandBus.notificationsConnected = true
        // Earlier versions posted panel copies and test notifications: drop their channels.
        getSystemService(NotificationManager::class.java)?.let { nm ->
            runCatching { nm.deleteNotificationChannel("shade_copies") }
            runCatching { nm.deleteNotificationChannel("test") }
        }
        // The hotspot may already be on (and its notification posted) before we connect.
        runCatching { activeNotifications }.getOrNull().orEmpty().forEach { checkHotspot(it) }
        sessionManager = getSystemService(MediaSessionManager::class.java)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            refreshSessions(null)
        } catch (_: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        IslandBus.notificationsConnected = false
        // Some phones (vivo, Xiaomi…) unbind the listener in the background, and
        // then notifications silently stop reaching the island. Ask to come back.
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { requestRebind(component) }
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(mediaCallback)
        controller = null
        IslandBus.postMedia(null)
    }

    // ── Media ───────────────────────────────────────────────────────────────


    private fun refreshSessions(list: List<MediaController>?) {
        val all = (list
            ?: (try { sessionManager?.getActiveSessions(component) } catch (_: SecurityException) { null })
            ?: emptyList())
        // Sound only (Videos switch off): skip video apps so music playing
        // behind YouTube etc. is still picked.
        val sessions = if (IslandConfig.load(this).videoPill) all
            else all.filter { it.packageName !in VIDEO_PACKAGES }
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

    // ── Live activities ─────────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val n = sbn.notification ?: return
        if (checkHotspot(sbn)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music is shown by the player

        // Only calls, navigation and timers reach the island; other notifications stay
        // in the system shade.
        val kind = classifyLive(n, sbn.packageName, sbn.isOngoing)
        if (kind == LiveKind.NONE) return
        if (!Prefs.get(this).getBoolean(Prefs.LIVE_ACTIVITIES, true)) return

        val appName = appLabel(sbn.packageName)
        val (rawTitle, text) = titleAndText(n)
        val title = rawTitle.ifEmpty { appName }
        if (title.isBlank() && text.isBlank()) return

        // Live activities re-post often (elapsed time); refresh only when the text changes.
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
                icon = largeIcon(sbn),
                contentIntent = n.contentIntent,
                liveKind = kind,
                appIcon = appIcon(sbn.packageName),
                appName = appName,
                actions = actionsOf(n),
                accent = appAccent(sbn.packageName),
            )
        )
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
        val WHITESPACE = Regex("\\s*\\n\\s*")
        const val HOTSPOT_TAG = "IslandHotspot"
        val HOTSPOT_TEXT_KEYS = arrayOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
        )
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
        if (sbn.key == hotspotKey) {
            hotspotKey = null
            IslandBus.postHotspotClients(null)
        }
        IslandBus.postRemoved(sbn.key)
    }

    // ── Hotspot device count ────────────────────────────────────────────────

    private var hotspotKey: String? = null
    private val systemPkgCache = HashMap<String, Boolean>()

    /**
     * Android gives the hotspot's client list only to system apps, so read the
     * device count from the system's own hotspot notification instead.
     * Returns true when [sbn] is that notification.
     */
    private fun checkHotspot(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (!isSystemPackage(sbn.packageName)) return false
        val x = n.extras
        val text = buildList {
            for (k in HOTSPOT_TEXT_KEYS) x.getCharSequence(k)?.let { add(it) }
            x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { addAll(it) }
        }.joinToString(" ").replace(WHITESPACE, " ")
        if (!HotspotNotification.isHotspot(text, n.channelId.orEmpty())) return false
        val count = HotspotNotification.clientCount(text)
        Log.d(HOTSPOT_TAG, "${sbn.packageName} [${n.channelId}] \"$text\" -> $count")
        hotspotKey = sbn.key
        IslandBus.postHotspotClients(count)
        return true
    }

    private fun isSystemPackage(pkg: String): Boolean = systemPkgCache.getOrPut(pkg) {
        pkg == "android" || runCatching {
            val f = packageManager.getApplicationInfo(pkg, 0).flags
            f and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }

    /**
     * Title and text as the shade shows them. Chat apps (MessagingStyle) keep the
     * newest message in EXTRA_MESSAGES, and EXTRA_TEXT is often a summary such as
     * "3 new messages" there, so read the last message instead.
     */
    private fun titleAndText(n: Notification): Pair<String, String> {
        val x = n.extras
        var title = (x.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        var text = (x.getCharSequence(Notification.EXTRA_TEXT)
            ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()
            ?: x.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()

        @Suppress("DEPRECATION")
        val last = runCatching { x.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull()
            ?.lastOrNull() as? android.os.Bundle
        if (last != null) {
            val msg = last.getCharSequence("text")?.toString()?.trim().orEmpty()
            @Suppress("DEPRECATION")
            val sender = (last.getCharSequence("sender")
                ?: runCatching { last.getParcelable<android.app.Person>("sender_person")?.name }.getOrNull())
                ?.toString()?.trim().orEmpty()
            val conversation = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
            val group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversation.isNotEmpty()
            if (msg.isNotEmpty()) {
                if (group && sender.isNotEmpty()) {
                    title = conversation.ifEmpty { title }
                    text = "$sender: $msg"
                } else {
                    if (sender.isNotEmpty() && title.isEmpty()) title = sender
                    text = msg
                }
            }
        }
        return title to text.replace(WHITESPACE, " ")
    }

    /** The notification's own buttons, except reply fields (those need typing, so open the app). */
    private fun actionsOf(n: Notification): List<NotifAction> =
        n.actions.orEmpty().filter { a ->
            a.actionIntent != null && !a.title.isNullOrBlank() && a.remoteInputs.isNullOrEmpty()
        }.take(3).map { NotifAction(it.title.toString().trim(), it.actionIntent) }

    /** The sender's photo or picture (null when the notification has none). */
    private fun largeIcon(sbn: StatusBarNotification): Drawable? =
        sbn.notification.getLargeIcon()?.let { icon -> runCatching { icon.loadDrawable(this) }.getOrNull() }

    private val iconCache = HashMap<String, Drawable?>()
    private val accentCache = HashMap<String, Int>()

    private fun appAccent(pkg: String): Int = accentCache.getOrPut(pkg) {
        val d = appIcon(pkg) ?: return@getOrPut Color.WHITE
        runCatching {
            val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val old = d.copyBounds()
            d.setBounds(0, 0, 24, 24)
            d.draw(c)
            d.bounds = old
            accentOf(bmp)
        }.getOrDefault(Color.WHITE)
    }

    private fun appIcon(pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
