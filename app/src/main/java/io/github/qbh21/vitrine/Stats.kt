package io.github.qbh21.vitrine

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live readings shown in the menu bar and the Wi-Fi / System widgets. Sampled only while the launcher is on screen. */
@Stable
class SysState {
    var down by mutableFloatStateOf(0f)          // Mbps being downloaded right now
    var up by mutableFloatStateOf(0f)            // Mbps being uploaded right now
    var history by mutableStateOf(List(30) { 0f })
    var linkMbps by mutableFloatStateOf(-1f)     // Wi-Fi link speed, -1 when not on Wi-Fi
    var rssi by mutableFloatStateOf(0f)
    var ssid by mutableStateOf<String?>(null)    // needs the location permission; null if unavailable
    var ramUsedPct by mutableFloatStateOf(0f)
    var ramFreeMb by mutableFloatStateOf(0f)
    var storageUsedPct by mutableFloatStateOf(0f)
    var storageFreeGb by mutableFloatStateOf(0f)
}

object SysStats {
    /** Available and total RAM in bytes. */
    fun ram(context: Context): Pair<Long, Long> {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem to info.totalMem
    }

    class Wifi(val link: Int, val rssi: Int, val ssid: String?)

    /** Wi-Fi link speed (Mbps), signal (dBm) and network name; link -1 when not connected. */
    @Suppress("DEPRECATION")
    fun wifi(context: Context): Wifi {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        val name = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return Wifi(info?.linkSpeed ?: -1, info?.rssi ?: 0, name)
    }
}

@Composable
fun rememberSysState(resumed: Boolean): SysState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = remember { SysState() }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastT = SystemClock.elapsedRealtime()
        var tick = 0
        while (true) {
            val (avail, total) = SysStats.ram(context)
            s.ramUsedPct = (1f - avail.toFloat() / total).coerceIn(0f, 1f) * 100
            s.ramFreeMb = avail / 1e6f
            val (free, totalDisk) = Storage.gb()
            s.storageUsedPct = (1 - free / totalDisk).toFloat().coerceIn(0f, 1f) * 100
            s.storageFreeGb = free.toFloat()
            val wifi = SysStats.wifi(context)
            s.linkMbps = wifi.link.toFloat()
            s.rssi = wifi.rssi.toFloat()
            s.ssid = wifi.ssid
            if (tick++ % 2 == 0) {   // network every 2 s, the rest every 4 s
                delay(2_000)
                val t = SystemClock.elapsedRealtime()
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val dt = (t - lastT) / 1000f
                if (lastRx >= 0 && rx >= 0 && dt > 0) {
                    s.down = (rx - lastRx) * 8f / 1e6f / dt
                    s.up = (tx - lastTx).coerceAtLeast(0) * 8f / 1e6f / dt
                    s.history = (s.history + s.down).takeLast(30)
                }
                lastRx = rx; lastTx = tx; lastT = t
            } else {
                delay(2_000)
            }
        }
    }
    return s
}

/** Asks Android to end the background processes of the apps the launcher lists. Returns the RAM freed, in MB. */
suspend fun cleanRam(context: Context): Long {
    val before = SysStats.ram(context).first
    withContext(Dispatchers.IO) {
        val am = context.getSystemService(ActivityManager::class.java)
        Apps.load(context).forEach { if (it.pkg != context.packageName) runCatching { am.killBackgroundProcesses(it.pkg) } }
    }
    delay(1_200)
    return ((SysStats.ram(context).first - before) / 1_000_000).coerceAtLeast(0)
}
