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
    private var sessions: List<MediaController> = emptyList()
    private var sessionIndex = 0
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
        IslandBus.mediaSwitcher = { cycleSession() }
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
        IslandBus.mediaSwitcher = null
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
            else all.filter { it.packageName !in VIDEO_PACKAGES && it.packageName !in IslandBus.videoApps }
        this.sessions = sessions
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(mediaCallback)
            controller = best
            sessionIndex = sessions.indexOfFirst { it.sessionToken == best?.sessionToken }
                .coerceAtLeast(0)
            best?.registerCallback(mediaCallback, mainHandler)
        }
        publishMedia()
    }

    /**
     * Two sessions at once (music + a podcast, or music behind a video): move to
     * the next active one. Long-press on the open player calls this.
     */
    private fun cycleSession() {
        if (sessions.size < 2) return
        sessionIndex = (sessionIndex + 1) % sessions.size
        controller?.unregisterCallback(mediaCallback)
        controller = sessions[sessionIndex]
        controller?.registerCallback(mediaCallback, mainHandler)
        lastArt = null
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
        // Next-track preview: read the first entry from the session's queue, when provided.
        var nextTitle: String? = null
        var nextArtist: String? = null
        try {
            val queue = c.queue.orEmpty()
            if (queue.isNotEmpty()) {
                val nextItem = queue.firstOrNull { it.description != null }
                nextTitle = nextItem?.description?.title?.toString()
                nextArtist = nextItem?.description?.subtitle?.toString()
            }
        } catch (_: Exception) {
            // Some sessions don't expose a queue — next-track preview simply stays hidden.
        }
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
                nextTrackTitle = nextTitle,
                nextTrackArtist = nextArtist,
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
        // in the system shade — unless canned replies are on and this is a
        // repliable chat message, which is exactly what that opt-in is for.
        val prefs = Prefs.get(this)
        val reply = replyOf(n)
        var kind = classifyLive(n, sbn.packageName, sbn.isOngoing)
        if (kind == LiveKind.NONE) {
            if (reply == null || !prefs.getBoolean(Prefs.QUICK_REPLIES, false)) return
            kind = LiveKind.ACTIVITY
        }
        if (!prefs.getBoolean(Prefs.LIVE_ACTIVITIES, true)) return

        val appName = appLabel(sbn.packageName)
        val (rawTitle, text) = titleAndText(n)
        val title = rawTitle.ifEmpty { appName }
        if (title.isBlank() && text.isBlank()) return

        // Countdown: use the chronometer extras when present; a desk-clock timer
        // (vivo / AOSP Clock) only puts its end time in Notification.when.
        var chronoBase = chronometerBase(n)
        var chronoCountDown = n.extras.getBoolean(EXTRA_CHRONOMETER_COUNT_DOWN, false)
        var wallBase = chronoBase > 1_000_000_000_000L
        if (kind == LiveKind.TIMER && chronoBase <= 0L) {
            // vivo / AOSP Clock keep the countdown only in their RemoteViews
            // chronometer widget, so read it from the inflated layout.
            val fromViews = chronometerFromViews(n)
            if (fromViews != null) {
                chronoBase = fromViews.first
                chronoCountDown = fromViews.second
                wallBase = chronoBase > 1_000_000_000_000L
            }
        }

        // Live activities re-post often (elapsed time); refresh only when the text changes.
        // The clock base is part of the signature so a brand-new timer still arrives.
        val signature = "$title\u0000$text\u0000$kind\u0000$chronoBase"
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
                replyIntent = reply?.first,
                replyInput = reply?.second,
                accent = appAccent(sbn.packageName),
                // A timer / stopwatch carries its clock in the extras: the island
                // ticks it locally instead of waiting for the next update.
                chronometerBase = chronoBase,
                chronometerCountDown = chronoCountDown,
                wallClockBase = wallBase,
            )
        )
    }

    /**
     * Base time of a running timer / stopwatch. Some apps put it in the extras,
     * others only set Notification.when (in elapsedRealtime, which is far below
     * any wall-clock timestamp).
     */
    private fun chronometerBase(n: Notification): Long {
        val explicit = n.extras.getLong(EXTRA_CHRONOMETER_BASE, 0L)
        if (explicit > 0L) return explicit
        val chrono = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) ||
            n.extras.getBoolean(EXTRA_CHRONOMETER_COUNT_DOWN, false)
        if (!chrono) return 0L
        // elapsedRealtime is in the millions: anything below 10^12 is not a date.
        return n.`when`.takeIf { it in 1..999_999_999_999L } ?: 0L
    }

    /** Base + countdown direction of the first Chronometer inside a notification's layout. */
    private fun chronometerFromViews(n: Notification): Pair<Long, Boolean>? = runCatching {
        val rv = n.bigContentView ?: n.contentView ?: return@runCatching null
        val host = android.widget.FrameLayout(this)
        val root = rv.apply(this, host) ?: return@runCatching null
        val chrono = findChronometer(root) ?: return@runCatching null
        chrono.base to chrono.isCountDown
    }.getOrNull()

    private fun findChronometer(v: android.view.View): android.widget.Chronometer? {
        if (v is android.widget.Chronometer) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findChronometer(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun classifyLive(n: Notification, pkg: String, ongoing: Boolean): LiveKind {
        val chronometer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val blob = "$title $text".lowercase()
        val p = pkg.lowercase()
        val cfg = IslandConfig.load(applicationContext)
        val callPkg = cfg.callApps.any { p.contains(it.lowercase()) } || CALL_PACKAGES.any { p.contains(it) }

        // A live call is not always flagged ongoing: once the call connects, the
        // dialer posts its card as an ordinary notification, so keying on the flag
        // made the pill vanish mid-call. Only missed / declined calls are skipped.
        if (!MISSED_RE.containsMatchIn(blob) &&
            (n.category == Notification.CATEGORY_CALL ||
                (callPkg && CALL_RE.containsMatchIn(blob)))
        ) {
            return LiveKind.CALL
        }

        // Other live activities only when genuinely ongoing — a one-shot alert
        // should not stick as a live pill.
        when (n.category) {
            Notification.CATEGORY_CALL -> if (ongoing) return LiveKind.CALL
            Notification.CATEGORY_NAVIGATION -> if (ongoing) return LiveKind.NAVIGATION
            Notification.CATEGORY_ALARM -> if (ongoing || chronometer) return LiveKind.TIMER
            Notification.CATEGORY_PROGRESS -> if (ongoing || chronometer) return LiveKind.ACTIVITY
        }
        // Package / text heuristics only for ongoing or chronometer cards — missed-call
        // toasts and one-shot alerts should not stick forever on the island.
        if (!ongoing && !chronometer) return LiveKind.NONE

        if (callPkg) return LiveKind.CALL
        if (cfg.navApps.any { p.contains(it.lowercase()) } || NAV_PACKAGES.any { p.contains(it) }) return LiveKind.NAVIGATION
        if (cfg.timerApps.any { p.contains(it.lowercase()) } || TIMER_PACKAGES.any { p.contains(it) }) return LiveKind.TIMER

        // Recording / live-activity apps (voice recorder, screen recorder, fitness, …)
        if (RECORDING_PACKAGES.any { p.contains(it) } || RECORDING_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (FITNESS_PACKAGES.any { p.contains(it) } || FITNESS_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (DELIVERY_PACKAGES.any { p.contains(it) } || DELIVERY_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (RIDE_PACKAGES.any { p.contains(it) } || RIDE_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (PAYMENT_PACKAGES.any { p.contains(it) } || PAYMENT_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (FILE_TRANSFER_PACKAGES.any { p.contains(it) } || FILE_TRANSFER_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (APP_INSTALL_PACKAGES.any { p.contains(it) } || APP_INSTALL_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY
        if (WIFI_PACKAGES.any { p.contains(it) } || WIFI_RE.containsMatchIn(blob)) return LiveKind.ACTIVITY

        if (blob.contains(TIMER_RE)) return LiveKind.TIMER
        if (blob.contains(NAV_RE)) return LiveKind.NAVIGATION
        if (blob.contains(CALL_RE)) return LiveKind.CALL
        if (chronometer) return LiveKind.ACTIVITY
        return LiveKind.NONE
    }

    private companion object {
        /** Notification extras keys SystemUI uses for a running timer / stopwatch. */
        const val EXTRA_CHRONOMETER_BASE = "android.chronometerBase"
        const val EXTRA_CHRONOMETER_COUNT_DOWN = "android.chronometerCountDown"
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
        /** A call that is over or was never answered must not stay on the island. */
        val MISSED_RE = Regex(
            "\\b(missed|declined|unanswered|rejected|call ended|blocked)\\b",
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
        val RECORDING_PACKAGES = listOf(
            "recorder", "voice", "audiorecorder", "screenrecorder", "screen recorder",
            "com.android.server.telecom", "callrecording",
        )
        val FITNESS_PACKAGES = listOf(
            "fitness", "workout", "exercise", "steps", "health", "samsunghealth",
            "com.google.android.apps.fitness", "com.xiaomi.wearable",
        )
        val DELIVERY_PACKAGES = listOf(
            "delivery", "food", "zomato", "swiggy", "ubereats", "doordash",
            "amazon", "flipkart", "meesho",
        )
        val RIDE_PACKAGES = listOf(
            "uber", "ola", "lyft", "cab", "taxi", "ride", "didi", "grab",
        )
        val PAYMENT_PACKAGES = listOf(
            "payment", "pay", "wallet", "upi", "phonepe", "gpay", "paytm",
            "bhim", "amazonpay",
        )
        val FILE_TRANSFER_PACKAGES = listOf(
            "files", "transfer", "shareit", "xender", "send anywhere",
            "com.android.documentsui",
        )
        val APP_INSTALL_PACKAGES = listOf(
            "package", "install", "update", "play store", "galaxy store",
            "com.android.packageinstaller",
        )
        val WIFI_PACKAGES = listOf(
            "wifi", "wlan", "com.android.settings", "com.android.systemui",
        )
        val RECORDING_RE = Regex(
            "\\b(recording|record|voice memo|screen record|call record|dictaphone)\\b",
            RegexOption.IGNORE_CASE,
        )
        val FITNESS_RE = Regex(
            "\\b(workout|fitness|exercise|steps|running|walking|cycling|activity)\\b",
            RegexOption.IGNORE_CASE,
        )
        val DELIVERY_RE = Regex(
            "\\b(delivery|order|food|delivered|out for delivery|restaurant)\\b",
            RegexOption.IGNORE_CASE,
        )
        val RIDE_RE = Regex(
            "\\b(ride|trip|driver|arriving|pickup|cab|taxi|uber|ola|lyft)\\b",
            RegexOption.IGNORE_CASE,
        )
        val PAYMENT_RE = Regex(
            "\\b(payment|paid|transaction|wallet|upi|paid successfully|payment received)\\b",
            RegexOption.IGNORE_CASE,
        )
        val FILE_TRANSFER_RE = Regex(
            "\\b(file transfer|file sent|file received|download complete|upload complete)\\b",
            RegexOption.IGNORE_CASE,
        )
        val APP_INSTALL_RE = Regex(
            "\\b(app install|installation|installed|app update|download complete)\\b",
            RegexOption.IGNORE_CASE,
        )
        val WIFI_RE = Regex(
            "\\b(wifi|wireless|network|connected to|hotspot|tether)\\b",
            RegexOption.IGNORE_CASE,
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

    /** The first reply action (canned replies use it without opening a keyboard). */
    private fun replyOf(n: Notification): Pair<android.app.PendingIntent, android.app.RemoteInput>? {
        for (a in n.actions.orEmpty()) {
            val input = a.remoteInputs?.firstOrNull() ?: continue
            val intent = a.actionIntent ?: continue
            return intent to input
        }
        return null
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
