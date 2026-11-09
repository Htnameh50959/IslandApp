package com.staad.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches Wi-Fi hotspot state and connected-client count so the island
 * can show the hotspot pill (on / N devices).
 *
 * WIFI_AP_STATE_CHANGED + best-effort Soft AP / tether client counts;
 * several OEM broadcasts are covered for vivo / Oppo / Xiaomi / MediaTek.
 */
class HotspotMonitor(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    var hotspotOn: Boolean = false
        private set
    var hotspotClients: Int = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private val started = AtomicBoolean(false)

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WIFI_AP_STATE_CHANGED, ACTION_TETHER_STATE -> refreshHotspotState(intent)
                WIFI_AP_STA_JOIN, WIFI_AP_STA_LEAVE, WIFI_AP_CLIENTS_CHANGED -> {
                    val count = intent.getIntExtra(EXTRA_CLIENT_COUNT, -1)
                        .takeIf { it >= 0 }
                        ?: intent.getIntExtra(EXTRA_STA_COUNT, -1).takeIf { it >= 0 }
                        ?: intent.getIntExtra("clientNum", -1).takeIf { it >= 0 }
                    if (count != null) {
                        hotspotOn = true
                        if (count != hotspotClients) {
                            hotspotClients = count
                            notifyChanged()
                        }
                    } else {
                        refreshHotspotState(intent)
                    }
                }
            }
        }
    }

    private var softApCallback: Any? = null
    private var tetherCallback: Any? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(WIFI_AP_STATE_CHANGED)
            addAction(ACTION_TETHER_STATE)
            addAction(WIFI_AP_STA_JOIN)
            addAction(WIFI_AP_STA_LEAVE)
            addAction(WIFI_AP_CLIENTS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(hotspotReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(hotspotReceiver, filter)
        }
        refreshHotspotState(null)
        registerSoftApCallback()
        registerTetherCallback()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(hotspotReceiver) }
        unregisterSoftApCallback()
        unregisterTetherCallback()
        hotspotOn = false
        hotspotClients = 0
    }

    private fun refreshHotspotState(intent: Intent?) {
        val state = intent?.getIntExtra(EXTRA_WIFI_AP_STATE, -1) ?: -1
        val enabled = when {
            state == WIFI_AP_STATE_ENABLED -> true
            state == WIFI_AP_STATE_DISABLED || state == WIFI_AP_STATE_FAILED -> false
            else -> readWifiApEnabled()
        }
        var clients = hotspotClients
        if (!enabled) clients = 0
        else if (intent != null) {
            val fromExtra = intent.getIntExtra(EXTRA_CLIENT_COUNT, -1)
                .takeIf { it >= 0 }
                ?: intent.getIntExtra(EXTRA_STA_COUNT, -1).takeIf { it >= 0 }
            if (fromExtra != null) clients = fromExtra
            else clients = readClientCount() ?: clients
        } else {
            clients = readClientCount() ?: clients
        }
        if (enabled == hotspotOn && clients == hotspotClients) return
        hotspotOn = enabled
        hotspotClients = if (enabled) clients else 0
        notifyChanged()
    }

    private fun readWifiApEnabled(): Boolean {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return false
        return runCatching {
            val m = wifi.javaClass.getMethod("isWifiApEnabled")
            m.invoke(wifi) as Boolean
        }.getOrElse {
            runCatching {
                val m = wifi.javaClass.getMethod("getWifiApState")
                (m.invoke(wifi) as Int) == WIFI_AP_STATE_ENABLED
            }.getOrDefault(false)
        }
    }

    private fun readClientCount(): Int? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        // OEM helpers used on some MediaTek / vivo / Oppo builds.
        for (name in listOf("getHotspotClients", "getWifiApConnectedStations", "getConnectedStations")) {
            val n = runCatching {
                val m = wifi.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                } ?: return@runCatching null
                when (val r = m.invoke(wifi)) {
                    is Collection<*> -> r.size
                    is Array<*> -> r.size
                    is Int -> r
                    else -> null
                }
            }.getOrNull()
            if (n != null && n >= 0) return n
        }
        return null
    }

    private fun registerSoftApCallback() {
        // SoftApCallback is @SystemApi on many SDKs — register via reflection.
        if (Build.VERSION.SDK_INT < 30) return
        val wifi = context.getSystemService(WifiManager::class.java) ?: return
        val cbClass = runCatching {
            Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
        }.getOrNull() ?: return
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            cbClass.classLoader,
            arrayOf(cbClass),
        ) { _, method, args ->
            when (method.name) {
                "onStateChanged" -> {
                    val state = args?.getOrNull(0) as? Int ?: return@newProxyInstance null
                    main.post {
                        val on = state == WIFI_AP_STATE_ENABLED
                        if (on == hotspotOn) {
                            if (!on && hotspotClients != 0) {
                                hotspotClients = 0
                                notifyChanged()
                            }
                            return@post
                        }
                        hotspotOn = on
                        if (!on) hotspotClients = 0
                        notifyChanged()
                    }
                }
                "onConnectedClientsChanged" -> {
                    val clients = args?.getOrNull(0) as? Collection<*>
                    val n = clients?.size ?: 0
                    main.post {
                        val changed = !hotspotOn || n != hotspotClients
                        hotspotOn = true
                        hotspotClients = n
                        if (changed) notifyChanged()
                    }
                }
            }
            null
        }
        softApCallback = proxy
        val ok = runCatching {
            wifi.javaClass.getMethod(
                "registerSoftApCallback",
                Executor::class.java,
                cbClass,
            ).invoke(wifi, executor, proxy)
            true
        }.getOrDefault(false)
        if (!ok) softApCallback = null
    }

    private fun unregisterSoftApCallback() {
        val proxy = softApCallback ?: return
        softApCallback = null
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return
            val cbClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            wifi.javaClass.getMethod("unregisterSoftApCallback", cbClass).invoke(wifi, proxy)
        }
    }

    private fun registerTetherCallback() {
        if (Build.VERSION.SDK_INT < 30) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // TetheringManager is obtained via ConnectivityManager on API 30+.
        val tmClass = runCatching { Class.forName("android.net.TetheringManager") }.getOrNull() ?: return
        val tm = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(tmClass)
            } else {
                ConnectivityManager::class.java.getMethod("getTetheringManager").invoke(cm)
            }
        }.getOrNull() ?: return

        val cbClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            cbClass.classLoader,
            arrayOf(cbClass),
        ) { _, method, args ->
            if (method.name == "onClientsChanged" && args != null && args.isNotEmpty()) {
                val clients = args[0] as? Collection<*>
                val n = clients?.size ?: 0
                main.post {
                    if (!hotspotOn && n == 0) return@post
                    if (n > 0) hotspotOn = true
                    if (n != hotspotClients) {
                        hotspotClients = n
                        notifyChanged()
                    }
                }
            }
            null
        }
        tetherCallback = proxy
        runCatching {
            tm.javaClass.getMethod(
                "registerTetheringEventCallback",
                Executor::class.java,
                cbClass,
            ).invoke(tm, executor, proxy)
        }.onFailure { tetherCallback = null }
    }

    private fun unregisterTetherCallback() {
        val proxy = tetherCallback ?: return
        tetherCallback = null
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val tmClass = Class.forName("android.net.TetheringManager")
            val tm = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(tmClass)
            } else {
                context.getSystemService(ConnectivityManager::class.java)?.let {
                    ConnectivityManager::class.java.getMethod("getTetheringManager").invoke(it)
                }
            } ?: return
            val cbClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            tm.javaClass.getMethod("unregisterTetheringEventCallback", cbClass).invoke(tm, proxy)
        }
    }

    private fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) onChanged()
        else main.post { onChanged() }
    }

    private companion object {
        const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val ACTION_TETHER_STATE = "android.net.conn.TETHER_STATE_CHANGED"
        // Common OEM client-change broadcasts (vivo / Oppo / Xiaomi / MediaTek).
        const val WIFI_AP_STA_JOIN = "android.net.wifi.WIFI_AP_STA_JOIN"
        const val WIFI_AP_STA_LEAVE = "android.net.wifi.WIFI_AP_STA_LEAVE"
        const val WIFI_AP_CLIENTS_CHANGED = "android.net.wifi.WIFI_HOTSPOT_CLIENTS_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val EXTRA_CLIENT_COUNT = "android.net.wifi.extra.CLIENT_COUNT"
        const val EXTRA_STA_COUNT = "staCount"
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_ENABLED = 13
        const val WIFI_AP_STATE_FAILED = 14
    }
}
