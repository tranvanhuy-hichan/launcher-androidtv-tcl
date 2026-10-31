package io.github.qbh21.vitrine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A small web app for managing the launcher from a phone: city, wallpaper, Dock, widgets, sounds, volume, RAM.
 * It runs only while the "Điện thoại" screen is open, answers only devices on the home network, and every
 * request must carry the random key that is inside the QR code. [onChange] is told what changed, so the
 * home screen can refresh.
 */
class PhoneServer(private val context: Context) {
    /** Told what changed ("city", "wall", "dock", "widgets", "key", "home"). Set by the home screen. */
    var onChange: (String) -> Unit = {}

    // The key is kept between runs, so the QR code on the home screen stays the same. "Đổi mã" on the phone replaces it.
    private fun prefs() = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun newKey() = (100_000 + java.security.SecureRandom().nextInt(900_000)).toString()
    private var key: String = prefs().getString("phone_key", null) ?: newKey().also { prefs().edit().putString("phone_key", it).apply() }
    private var port = 0
    private var server: ServerSocket? = null
    private var ip = ""
    private var apps: List<AppEntry> = emptyList()
    private val thumbs = HashMap<String, ByteArray>()

    /** Activities started from the background are only allowed for the accessibility service, so use it when it is there. */
    private val launcher: Context get() = HomeRedirectService.instance ?: context

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** Starts listening. Returns the address for the QR code, or null when the TV has no local network. */
    fun start(): String? {
        if (server != null) return url()
        // Bind first: at boot the Wi-Fi may not be up yet. The address is looked up when it is asked for.
        for (p in 8765..8775) {
            try {
                server = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(p)) }
                port = p
                break
            } catch (e: IOException) { /* port taken, try the next */ }
        }
        val s = server ?: return null
        thread(isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                } catch (e: IOException) { break }
            }
        }
        return url()
    }

    /** The address to show in the QR code while the server is running. */
    fun url(): String? {
        if (server == null) return null
        localIp()?.let { ip = it }
        return if (ip.isEmpty()) null else "http://$ip:$port/?k=$key"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(c: Socket) {
        c.use {
            c.soTimeout = 15_000
            if (!(c.inetAddress.isSiteLocalAddress || c.inetAddress.isLoopbackAddress)) return@use
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val head = readHead(input) ?: return@use
            val lines = head.split("\r\n")
            val first = lines[0].split(" ")
            if (first.size < 2) return@use
            val method = first[0]
            val path = first[1].substringBefore('?')
            val query = first[1].substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (query["k"] != key) { text(out, 403, "Sai mã"); return@use }
            if (length > 6_000_000) { text(out, 413, "Quá lớn"); return@use }
            val body = ByteArray(length)
            var off = 0
            while (off < length) { val n = input.read(body, off, length - off); if (n < 0) break; off += n }
            if (off != length) return@use
            try { route(method, path, query, body, out) } catch (e: Exception) { text(out, 500, "Lỗi") }
        }
    }

    private fun route(method: String, path: String, q: Map<String, String>, body: ByteArray, out: OutputStream) {
        if (method == "GET") {
            when (path) {
                "/" -> bytes(out, 200, "text/html; charset=utf-8", context.assets.open("phone/index.html").use { it.readBytes() })
                "/api/typestate" -> {
                    val svc = HomeRedirectService.instance
                    val (focused, current) = svc?.focusedField() ?: (false to "")
                    bytes(out, 200, "application/json; charset=utf-8", JSONObject().put("svc", svc != null).put("focused", focused).put("text", current).toString().toByteArray())
                }
                "/api/state" -> bytes(out, 200, "application/json; charset=utf-8", state().toByteArray())
                "/icon" -> iconPng(q["pkg"])?.let { bytes(out, 200, "image/png", it, "max-age=3600") } ?: text(out, 404, "none")
                "/thumb" -> thumb(q["name"])?.let { bytes(out, 200, "image/jpeg", it, "max-age=60") } ?: text(out, 404, "none")
                else -> text(out, 404, "not found")
            }
            return
        }
        if (method != "POST") { text(out, 405, "method"); return }
        when (path) {
            "/api/city" -> {
                val name = q["name"]?.take(60)
                val lat = q["lat"]?.toDoubleOrNull()
                val lon = q["lon"]?.toDoubleOrNull()
                if (name.isNullOrBlank() || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return text(out, 400, "bad city")
                Weather.setCustom(context, name, lat, lon); onChange("city"); text(out, 200, "ok")
            }
            "/api/wall" -> {
                val name = q["name"]
                val ok = Backdrop.entries.any { it.name == name } || (name == "Photo" && File(context.filesDir, "wallpaper.jpg").exists())
                if (!ok) return text(out, 400, "bad wallpaper")
                Apps.saveBackdrop(context, name!!); onChange("wall"); text(out, 200, "ok")
            }
            "/api/upload" -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
                if (body.isEmpty() || bounds.outWidth <= 0) return text(out, 400, "bad image")
                File(context.filesDir, "wallpaper.jpg").writeBytes(body)
                Apps.saveBackdrop(context, "Photo"); onChange("wall"); text(out, 200, "ok")
            }
            "/api/panel" -> { Apps.savePanelAlpha(context, (q["v"]?.toIntOrNull() ?: 46).coerceIn(10, 100)); onChange("widgets"); text(out, 200, "ok") }
            "/api/dim" -> { Apps.saveDim(context, (q["v"]?.toIntOrNull() ?: 40).coerceIn(0, 80)); onChange("wall"); text(out, 200, "ok") }
            "/api/dock" -> {
                val installed = Apps.load(context).map { it.pkg }.toSet()
                val arr = JSONArray(String(body))
                val list = (0 until arr.length()).map { arr.getString(it) }.filter { it in installed }.distinct()
                Apps.savePinned(context, list); onChange("dock"); text(out, 200, "ok")
            }
            "/api/widget" -> {
                val id = q["id"]
                if (id !in listOf("time", "weather", "system", "qr")) return text(out, 400, "bad widget")
                Apps.setWidget(context, id!!, q["on"] == "1"); onChange("widgets"); text(out, 200, "ok")
            }
            "/api/sfx" -> { Sfx.set(context, q["on"] == "1"); text(out, 200, "ok") }
            "/api/volume" -> {
                val audio = context.getSystemService(AudioManager::class.java)
                val v = (q["v"]?.toIntOrNull() ?: 0).coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                text(out, 200, "ok")
            }
            "/api/launch" -> {
                val app = Apps.load(context).firstOrNull { it.pkg == q["pkg"] } ?: return text(out, 404, "none")
                Apps.recordLaunch(context, app.pkg)
                launcher.startActivity(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                text(out, 200, "ok")
            }
            "/api/type" -> {
                val svc = HomeRedirectService.instance ?: return text(out, 409, "no-service")
                if (!svc.setText(String(body, Charsets.UTF_8))) return text(out, 409, "no-field")
                text(out, 200, "ok")
            }
            "/api/enter" -> {
                val svc = HomeRedirectService.instance ?: return text(out, 409, "no-service")
                if (!svc.pressEnter()) return text(out, 409, "no-field")
                text(out, 200, "ok")
            }
            "/api/key" -> {
                val c = q["c"]
                val audio = context.getSystemService(AudioManager::class.java)
                val code = when (c) {
                    "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP; "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT; "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    "ok" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER; "back" -> android.view.KeyEvent.KEYCODE_BACK
                    "menu" -> android.view.KeyEvent.KEYCODE_MENU; else -> -1
                }
                when {
                    c == "home" -> {
                        onChange("home")
                        // From inside another app the system has to take us home.
                        if (!Remote.front) (HomeRedirectService.instance?.goHome() ?: runCatching {
                            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    }
                    c == "back" && !Remote.front -> if (HomeRedirectService.instance?.goBack() != true) return text(out, 409, "no-service")
                    c == "volup" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                    c == "voldown" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                    c == "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                    code > 0 -> if (!Remote.send(code)) return text(out, 409, "not-in-launcher")
                    else -> return text(out, 400, "bad key")
                }
                text(out, 200, "ok")
            }
            "/api/power" -> if (TvControl.power()) text(out, 200, "ok") else text(out, 409, "no-service")
            "/api/input" -> if (TvControl.switchInput(context, q["i"]?.toIntOrNull() ?: -1)) text(out, 200, "ok") else text(out, 404, "none")
            "/api/ytsearch" -> {
                val query = q["q"]?.trim()?.take(120)
                if (query.isNullOrEmpty()) return text(out, 400, "empty")
                // YouTube for TV opens the results for this search link directly.
                launcher.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)))
                        .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                text(out, 200, "ok")
            }
            "/api/event" -> {
                val ok = Events.add(context, q["name"], q["cal"] == "lunar", q["day"]?.toIntOrNull(), q["month"]?.toIntOrNull())
                if (!ok) return text(out, 400, "bad event")
                onChange("events"); text(out, 200, "ok")
            }
            "/api/eventdel" -> { Events.remove(context, q["id"] ?: ""); onChange("events"); text(out, 200, "ok") }
            "/api/rotate" -> { key = newKey(); prefs().edit().putString("phone_key", key).apply(); onChange("key"); text(out, 200, "ok") }
            "/api/home" -> { onChange("home"); text(out, 200, "ok") }
            "/api/clean" -> text(out, 200, runBlocking { cleanRam(context) }.toString())
            else -> text(out, 404, "not found")
        }
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private fun state(): String {
        apps = Apps.load(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val (avail, total) = SysStats.ram(context)
        val (free, disk) = Storage.gb()
        val wifi = SysStats.wifi(context)
        val photo = File(context.filesDir, "wallpaper.jpg").exists()
        val walls = JSONArray()
        Backdrop.entries.forEach { walls.put(JSONObject().put("name", it.name).put("label", it.label)) }
        if (photo) walls.put(JSONObject().put("name", "Photo").put("label", "Ảnh của bạn"))
        return JSONObject()
            .put("tv", Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: "Android TV")
            .put("ip", ip)
            .put("cities", JSONArray().also { a -> Weather.CITIES.forEach { a.put(JSONArray().put(it.name).put(it.lat).put(it.lon)) } })
            .put("city", Weather.current(context).name)
            .put("walls", walls)
            .put("wall", Apps.backdrop(context) ?: "Sonoma")
            .put("dim", Apps.dim(context))
            .put("panelAlpha", Apps.panelAlpha(context))
            .put("widgets", JSONObject().also { w -> listOf("time", "weather", "system", "qr").forEach { w.put(it, Apps.widgetOn(context, it)) } })
            .put("sfx", Sfx.enabled)
            .put("events", JSONArray().also { a -> Events.load(context).forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lunar", it.lunar).put("day", it.day).put("month", it.month)) } })
            .put("dock", JSONArray(Apps.pinned(context)))
            .put("apps", JSONArray().also { a -> apps.forEach { a.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("system", it.isSystem)) } })
            .put("inputs", JSONArray().also { a -> TvControl.inputs(context).forEach { a.put(it.first) } })
            .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
            .put("ram", JSONObject().put("freeMb", avail / 1e6).put("totalMb", total / 1e6))
            .put("storage", JSONObject().put("freeGb", free).put("totalGb", disk))
            .put("wifi", JSONObject().put("ssid", wifi.ssid ?: JSONObject.NULL).put("link", wifi.link).put("rssi", wifi.rssi))
            .toString()
    }

    private fun iconPng(pkg: String?): ByteArray? {
        val app = (apps.ifEmpty { Apps.load(context).also { apps = it } }).firstOrNull { it.pkg == pkg } ?: return null
        val icon = app.icon(context, 96)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor((icon.background ?: Color.White).toArgb())
        val inset = if (icon.fullBleed) 0 else 7
        canvas.drawBitmap(icon.bitmap.asAndroidBitmap(), null, Rect(inset, inset, 96 - inset, 96 - inset), null)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A 320x180 preview of a wallpaper: drawn from the same code the TV uses, so what you pick is what you get. */
    private fun thumb(name: String?): ByteArray? {
        val bmp: Bitmap = if (name == "Photo") {
            val file = File(context.filesDir, "wallpaper.jpg")
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 3 }) ?: return null
        } else {
            thumbs[name]?.let { return it }
            val backdrop = Backdrop.entries.firstOrNull { it.name == name } ?: return null
            renderBloom(backdrop, 320, 180).asAndroidBitmap()
        }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        if (name != "Photo" && name != null) thumbs[name] = bytes
        return bytes
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("UTF-8")
        }
        return null
    }

    private fun text(out: OutputStream, code: Int, body: String) = bytes(out, code, "text/plain; charset=utf-8", body.toByteArray())

    private fun bytes(out: OutputStream, code: Int, type: String, body: ByteArray, cache: String = "no-store") {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 409 -> "Conflict"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Error" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: $cache\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }
}
