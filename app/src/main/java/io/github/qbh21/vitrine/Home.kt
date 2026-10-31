package io.github.qbh21.vitrine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.tv.TvInputManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.cos
import kotlin.math.sin

// Home screen in the style of a desktop: menu bar, widgets, Dock. Sizes are dp on a 960x540dp canvas,
// which is exactly this TV (1280x720 at 213 dpi). Kept light: no live blur, no continuous animation.

/** Remembers which element had focus, so coming back from a screen or another app lands on the same one. */
internal class FocusMemory {
    var last = "dock0"
    val requesters = HashMap<String, FocusRequester>()
}

private val LocalMemory = androidx.compose.runtime.compositionLocalOf { FocusMemory() }

/** How opaque the widget cards are (0..1); set from the phone. */
private val LocalPanelAlpha = androidx.compose.runtime.compositionLocalOf { 0.46f }

/** Tags a focusable with [id]: it records itself in the memory when focused and can be focused again by id. */
@Composable
private fun Modifier.remembered(id: String?): Modifier {
    if (id == null) return this
    val mem = LocalMemory.current
    val requester = mem.requesters.getOrPut(id) { FocusRequester() }
    return this.focusRequester(requester).onFocusChanged { if (it.isFocused) mem.last = id }
}

/**
 * Keys sent from the phone. They go to the window that is on top (the home screen, or a dialog such as the
 * control centre), as if they came from the TV's own remote. Only works while the launcher is in front.
 */
internal object Remote {
    private val stack = java.util.concurrent.CopyOnWriteArrayList<android.view.View>()

    /** True while the launcher is on screen: phone keys go to it only then. */
    @Volatile var front = false
    fun push(v: android.view.View) { stack.add(v) }
    fun pop(v: android.view.View) { stack.remove(v) }
    fun send(code: Int): Boolean {
        if (!front) return false
        val v = stack.lastOrNull() ?: return false
        val t = android.os.SystemClock.uptimeMillis()
        v.post {
            v.rootView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            v.rootView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
        return true
    }
}

/** Registers the surrounding window as the target for phone keys while it is on screen. */
@Composable
private fun RemoteTarget() {
    val v = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(v) { Remote.push(v); onDispose { Remote.pop(v) } }
}

private val Panel = Color(0x751E2230)
private val PanelLine = Color(0x24FFFFFF)
private val Dark = Color(0xFF10131A)

internal enum class Screen { Start, Apps, Search, Calendar, Weather, Phone }

// ---- Helpers ---------------------------------------------------------------------------------------

internal fun open(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) {
    false   // app removed or can't be opened; the list refreshes next time we're shown
}

internal fun packageIntent(action: String, pkg: String) = Intent(action, Uri.fromParts("package", pkg, null))

/** Ticks on the minute; re-syncs whenever the screen comes back. */
@Composable
private fun rememberNow(refresh: Int): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(refresh) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    return now
}

private fun format(pattern: String, now: Long) = SimpleDateFormat(pattern, Locale("vi", "VN")).format(Date(now))

// ---- Root ------------------------------------------------------------------------------------------

@Composable
fun LauncherScreen(refresh: Int, reset: Int, asHome: Boolean, resumed: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val apps = remember(refresh) { Apps.load(context) }
    var dock by remember { mutableStateOf(Apps.pinned(context)) }
    var screen by remember { mutableStateOf(Screen.Start) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var control by remember { mutableStateOf(false) }
    var inputs by remember { mutableStateOf(false) }
    var powerAsk by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    var cityTick by remember { mutableIntStateOf(0) }
    var wallTick by remember { mutableIntStateOf(0) }
    var widgets by remember { mutableStateOf(Apps.widgets(context)) }
    var weather by remember { mutableStateOf(Weather.cached(context)) }
    var showQr by remember { mutableStateOf(Apps.widgetOn(context, "qr")) }
    var events by remember { mutableStateOf(Events.load(context)) }
    var panelAlpha by remember { mutableStateOf(Apps.panelAlpha(context)) }
    // The phone server runs while the home screen is showing, and stops when another app takes over.
    // One server shared with the background service, so the phone stays connected while other apps are open.
    val phoneServer = remember { PhoneHub.get(context) }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var wallSeen by remember { mutableStateOf("") }
    LaunchedEffect(resumed) {
        // The address can change (Wi-Fi reconnects), so look it up again now and then while we are showing.
        while (resumed) { phoneUrl = withContext(Dispatchers.IO) { phoneServer.start() }; delay(5_000) }
    }
    // Things the phone changed while another app was in front.
    LaunchedEffect(refresh) {
        events = Events.load(context); dock = Apps.pinned(context); widgets = Apps.widgets(context); showQr = Apps.widgetOn(context, "qr"); panelAlpha = Apps.panelAlpha(context)
        val key = "${Apps.backdrop(context)}|${Apps.dim(context)}|${File(context.filesDir, "wallpaper.jpg").lastModified()}"
        if (key != wallSeen) { if (wallSeen.isNotEmpty()) wallTick++; wallSeen = key }
    }
    phoneServer.onChange = { what ->
        // Called from the server's thread: hop to the main thread, then refresh what changed.
        Handler(Looper.getMainLooper()).post {
            when (what) {
                "city" -> { weather = null; cityTick++ }
                "wall" -> wallTick++
                "dock" -> dock = Apps.pinned(context)
                "events" -> events = Events.load(context)
                "widgets" -> { widgets = Apps.widgets(context); showQr = Apps.widgetOn(context, "qr"); panelAlpha = Apps.panelAlpha(context) }
                "key" -> phoneUrl = phoneServer.url()
                "home" -> { screen = Screen.Start; idle = false; lastInput = SystemClock.elapsedRealtime() }
            }
        }
    }
    val sys = rememberSysState(resumed)
    // Idle mode: after a few minutes without a key on the home screen, show a dim clock until any key is pressed.
    LaunchedEffect(resumed) { lastInput = SystemClock.elapsedRealtime(); idle = false }
    LaunchedEffect(resumed, screen, control, inputs, powerAsk, menuFor) {
        while (resumed) {
            delay(5_000)
            val busy = screen != Screen.Start || control || inputs || powerAsk || menuFor != null
            if (!busy && SystemClock.elapsedRealtime() - lastInput > IDLE_MS) idle = true
        }
    }
    remember { Sfx.init(context) }
    val memory = remember { FocusMemory() }

    LaunchedEffect(wallTick) { wallpaper = withContext(Dispatchers.Default) { loadWallpaper(context) } }
    LaunchedEffect(reset) { screen = Screen.Start; menuFor = null; control = false; inputs = false; memory.last = "dock0" }
    LaunchedEffect(refresh, cityTick) { Weather.load(context)?.let { weather = it } }
    // Back: any other screen -> Start. On Start it closes the app when opened as a normal app; as the home
    // screen there's nowhere further back to go (Home and the stock launcher still work).
    BackHandler { if (screen != Screen.Start) screen = Screen.Start else if (!asHome) onExit() }

    val openApp: (AppEntry) -> Unit = { app -> Apps.recordLaunch(context, app.pkg); open(context, app.launch) }

    CompositionLocalProvider(LocalMemory provides memory, LocalPanelAlpha provides panelAlpha / 100f) {
    RemoteTarget()
    androidx.compose.runtime.SideEffect { Remote.front = resumed }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF1B1F4D)).onPreviewKeyEvent {
            lastInput = SystemClock.elapsedRealtime()
            if (idle) {
                if (it.type == KeyEventType.KeyDown) idle = false   // the key that wakes the screen does nothing else
                return@onPreviewKeyEvent true
            }
            if (it.type == KeyEventType.KeyDown) Sfx.key(it.nativeKeyEvent.keyCode)
            false
        }.onKeyEvent {
            // The Menu key (when nothing on screen claims it) opens the control centre.
            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && it.nativeKeyEvent.action == KeyEvent.ACTION_UP && screen == Screen.Start) {
                control = true; true
            } else false
        },
    ) {
        Crossfade(wallpaper, animationSpec = tween(500), label = "wallpaper") { w ->
            if (w != null) androidx.compose.foundation.Image(w, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        AnimatedContent(
            screen, label = "screen",
            transitionSpec = { (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.96f)) togetherWith fadeOut(tween(120)) },
        ) { target ->
        when (target) {
            Screen.Start -> MacHome(
                apps, dock, weather, widgets, if (showQr) phoneUrl else null, events, sys, refresh, reset,
                onOpen = openApp, onMenu = { menuFor = it },
                onAll = { screen = Screen.Apps }, onCalendar = { screen = Screen.Calendar },
                onWeather = { screen = Screen.Weather }, onControl = { control = true },
                onPower = { powerAsk = true }, onInputs = { inputs = true },
            )
            Screen.Apps -> AppsScreen(apps, onOpen = openApp, onMenu = { menuFor = it })
            Screen.Search -> SearchScreen(apps, onOpen = openApp, onMenu = { menuFor = it })
            Screen.Calendar -> CalendarScreen(events)
            Screen.Weather -> WeatherScreen(weather)
            Screen.Phone -> PhoneScreen(phoneUrl)
        }
        }
        AnimatedVisibility(idle, enter = fadeIn(tween(900)), exit = fadeOut(tween(250))) { IdleScreen(weather) }
    }

    menuFor?.let { app ->
        fun setDock(list: List<String>) { dock = list; Apps.savePinned(context, list) }
        val options = buildList<Pair<String, () -> Unit>> {
            add("Mở" to { openApp(app) })
            if (app.pkg in dock) add("Bỏ khỏi Dock" to { setDock(dock - app.pkg) })
            else add("Thêm vào Dock" to { setDock(dock + app.pkg) })
            add("Thông tin ứng dụng" to { open(context, packageIntent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.pkg)) })
            if (!app.isSystem) add("Gỡ cài đặt" to { open(context, packageIntent(Intent.ACTION_DELETE, app.pkg)) })
        }
        OptionsMenu(app.label, options, onDismiss = { menuFor = null })
    }

    if (control) {
        ControlCenter(
            sys, Weather.current(context).name,
            onCity = { Weather.nextCity(context); weather = null; cityTick++ },
            onInputs = { control = false; inputs = true },
            onPhone = { control = false; screen = Screen.Phone },
            onDismiss = { control = false },
        )
    }

    if (powerAsk) {
        OptionsMenu("Tắt màn hình TV?", listOf(
            "Tắt màn hình" to { if (!TvControl.power()) Toast.makeText(context, "Cần bật dịch vụ trợ năng của launcher (tools/install.sh)", Toast.LENGTH_LONG).show() },
            "Hủy" to {},
        ), onDismiss = { powerAsk = false })
    }

    if (inputs) {
        val list = remember { TvControl.inputs(context) }
        val options = buildList<Pair<String, () -> Unit>> {
            list.forEach { (label, intent) -> add(label to { open(context, intent) }) }
            add("Truyền hình" to { open(context, Intent().setClassName("com.tcl.tv", "com.tcl.tv.TVActivity")) })
        }
        OptionsMenu("Nguồn vào", options, onDismiss = { inputs = false })
    }
    }
}

// ---- Home ------------------------------------------------------------------------------------------

@Composable
private fun MacHome(
    apps: List<AppEntry>, dock: List<String>, weather: WeatherData?, widgets: Set<String>, qrUrl: String?, events: List<Event>, sys: SysState, refresh: Int, reset: Int,
    onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit,
    onAll: () -> Unit, onCalendar: () -> Unit, onWeather: () -> Unit, onControl: () -> Unit,
    onPower: () -> Unit, onInputs: () -> Unit,
) {
    val context = LocalContext.current
    val now = rememberNow(refresh)
    val mem = LocalMemory.current
    val pinned = remember(apps, dock) { dock.mapNotNull { p -> apps.firstOrNull { it.pkg == p } } }
    val recent = remember(refresh, apps) { Apps.recents(context).mapNotNull { (p, t) -> apps.firstOrNull { it.pkg == p }?.let { it to t } } }

    val moved = remember { mutableStateOf(false) }   // set once the user presses a key, so we stop claiming focus
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { moved.value = true; false }) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(now, sys, onAll, onControl)
            Row(Modifier.padding(start = 38.dp, end = 38.dp).fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Header("Đã ghim", Modifier.padding(start = 4.dp), color = Text)
                BarPill("dock-all", onAll) { c -> BasicText("Tất cả ứng dụng  ›", style = text(11f, color = c)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(start = 38.dp, end = 38.dp, bottom = 46.dp).fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // Left: pinned apps (one large, four small), then date and storage.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (r in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (c in 0..1) {
                                val i = r * 2 + c
                                val app = pinned.getOrNull(i)
                                if (app != null) AppTile(app, Modifier.weight(1f).fillMaxHeight().appear(i), "dock$i", onOpen, onMenu)
                                else if (i == pinned.size) AddTile(Modifier.weight(1f).fillMaxHeight().appear(i), onAll)
                                else Box(Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if ("time" in widgets) DateTile(now, Modifier.weight(1.6f).fillMaxHeight().appear(5), onCalendar)
                        StorageTile(sys, Modifier.weight(1f).fillMaxHeight().appear(6), onControl)
                    }
                }
                // Right: weather, system and the next occasion, then recent apps and the phone QR code.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if ("weather" in widgets) {
                        WeatherWidget(weather, Modifier.fillMaxWidth().weight(1f).appear(2), onWeather)
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if ("system" in widgets) SystemWidget(sys, Modifier.weight(1.5f).fillMaxHeight().appear(4), onControl)
                        EventTile(now, events, Modifier.weight(1f).fillMaxHeight().appear(5), onCalendar)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RecentStrip(recent, now, Modifier.weight(1f).fillMaxHeight().appear(6), onOpen, onMenu)
                        if (qrUrl != null) QrCorner(qrUrl, Modifier.fillMaxHeight().appear(7))
                    }
                }
            }
        }
        KeyHints(Modifier.align(Alignment.BottomStart).padding(start = 38.dp, bottom = 14.dp))
        TvButtons(onPower, onInputs, Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 9.dp))
    }
    // The Dock's first icon takes focus once it's laid out. Retried briefly: on this slow TV the first attempt can be early.
    // Every time we come back (from a screen or another app) focus returns to the element that was open; the Home button restarts at the Dock.
    LaunchedEffect(refresh, reset) {
        moved.value = false
        withFrameNanos { }
        repeat(12) {
            if (!moved.value) {
                // The remembered element may be gone (a hidden widget): fall back to the first Dock icon.
                val ok = runCatching { mem.requesters[mem.last]!!.requestFocus(); true }.getOrDefault(false)
                if (!ok) runCatching { mem.requesters["dock0"]!!.requestFocus() }
            }
            delay(250)
        }
    }
}

// ---- Menu bar --------------------------------------------------------------------------------------

@Composable
private fun MenuBar(now: Long, s: SysState, onSearch: () -> Unit, onControl: () -> Unit) {
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..10 -> "Chào buổi sáng"; in 11..12 -> "Chào buổi trưa"; in 13..17 -> "Chào buổi chiều"; else -> "Chào buổi tối" }
    Row(Modifier.fillMaxWidth().height(86.dp).padding(start = 38.dp, end = 38.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            BasicText(greeting, style = text(11f, color = Muted))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(format("HH:mm", now), style = text(40f, FontWeight.Light))
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    BasicText(format("EEEE", now), style = text(15f, FontWeight.SemiBold))
                    BasicText(format("d MMMM", now), style = text(15f, color = Muted))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarPill("mb-search", onSearch, 200.dp) { c ->
                Image(painterResource(R.drawable.pk_search), null, Modifier.size(13.dp), colorFilter = ColorFilter.tint(c))
                BasicText("Tìm ứng dụng", style = text(11f, color = c))
            }
            BarPill("mb-wifi", onControl, 30.dp, true) { c ->
                Image(painterResource(R.drawable.pk_wifi), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(c))
            }
            BarPill("mb-cc", onControl) { c ->
                Image(painterResource(R.drawable.pk_gear), null, Modifier.size(13.dp), colorFilter = ColorFilter.tint(c))
                BasicText("Điều khiển", style = text(11f, color = c))
            }
            Column(Modifier.padding(start = 6.dp), horizontalAlignment = Alignment.End) {
                BasicText("Launcher TCL", style = text(11f, FontWeight.SemiBold))
                BasicText("Android TV", style = text(9f, color = Muted))
            }
            Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7A6BFF), Color(0xFF5A8CFF)))), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.pk_tv), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color.White))
            }
        }
    }
}

/** A pill-shaped, focusable chip in the top bar. */
@Composable
private fun BarPill(id: String, onClick: () -> Unit, minWidth: Dp = 0.dp, round: Boolean = false, content: @Composable RowScope.(Color) -> Unit) {
    Pressable(Modifier, onClick, focusId = id) { focused ->
        Row(
            Modifier.widthIn(min = minWidth).height(30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (focused) Color(0xF2FFFFFF) else Color(0x24FFFFFF))
                .border(0.7.dp, if (focused) Color.Transparent else Color(0x33FFFFFF), RoundedCornerShape(15.dp))
                .padding(horizontal = if (round) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) { content(if (focused) Dark else Text) }
    }
}

// ---- Widgets ---------------------------------------------------------------------------------------

/** Fades a card in and slides it up a little, [index] x 40 ms after the screen appears. Plays once per appearance. */
@Composable
private fun Modifier.appear(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index * 40L); a.animateTo(1f, tween(320)) }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 22.dp.toPx() }
}

/** A number that eases to [target] (from 0 the first time it is shown) instead of jumping. */
@Composable
private fun animatedValue(target: Float, ms: Int = 700): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target, tween(ms)) }
    return a.value
}

/** A focusable glass card. OK runs [onClick]. */
@Composable
private fun Widget(modifier: Modifier, onClick: () -> Unit, gradient: Pair<Color, Color>? = null, focusId: String? = null, onLong: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val a = LocalPanelAlpha.current
    val background = if (gradient == null) Brush.verticalGradient(listOf(Color(0xFF7F95E8).copy(alpha = a * 0.5f), Color(0xFF1B2030).copy(alpha = a * 0.95f)))
    else Brush.linearGradient(listOf(gradient.first.copy(alpha = (a * 1.35f).coerceAtMost(1f)), gradient.second.copy(alpha = (a * 1.3f).coerceAtMost(1f))))
    var focused by remember { mutableStateOf(false) }
    val press = remember { Press() }
    val shape = RoundedCornerShape(24.dp)
    val lift by animateFloatAsState(if (focused) 1.045f else 1f, tween(120), label = "lift")
    Box(
        modifier.remembered(focusId).graphicsLayer { scaleX = lift; scaleY = lift }.clip(shape).background(background)
            .border(if (focused) 3.dp else 0.7.dp, if (focused) Color.White else PanelLine, shape)
            .onFocusChanged { if (it.isFocused && !focused) Sfx.move(); focused = it.isFocused }
            .onKeyEvent { press.handle(it.nativeKeyEvent, {}, { Sfx.select(); onClick() }, onLong) }
            .focusable(),
        content = content,
    )
}

@Composable
private fun Header(title: String, modifier: Modifier = Modifier, color: Color = Muted) {
    BasicText(title, modifier, style = text(12f, FontWeight.SemiBold, color))
}

@Composable
private fun AnalogClock(now: Long, modifier: Modifier) {
    val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val minute = cal.get(Calendar.MINUTE)
    val hour = cal.get(Calendar.HOUR) + minute / 60f
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Color(0x590A0C18), r, c)
        drawCircle(Color(0x40FFFFFF), r, c, style = Stroke(1.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            drawLine(
                Color.White.copy(alpha = if (i % 3 == 0) 0.95f else 0.5f),
                Offset(c.x + sin(a).toFloat() * r * 0.8f, c.y - cos(a).toFloat() * r * 0.8f),
                Offset(c.x + sin(a).toFloat() * r * 0.93f, c.y - cos(a).toFloat() * r * 0.93f),
                strokeWidth = (if (i % 3 == 0) 1.6f else 1f).dp.toPx(),
            )
        }
        fun hand(deg: Float, len: Float, w: Float) {
            val a = Math.toRadians(deg.toDouble())
            drawLine(
                Color.White, Offset(c.x - sin(a).toFloat() * r * 0.1f, c.y + cos(a).toFloat() * r * 0.1f),
                Offset(c.x + sin(a).toFloat() * r * len, c.y - cos(a).toFloat() * r * len), strokeWidth = w.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        hand(hour * 30f, 0.5f, 3f)
        hand(minute * 6f, 0.75f, 2.2f)
        drawCircle(Color(0xFFFF9F0A), 3.dp.toPx(), c)
    }
}

/** Clock, date and lunar date in one card. OK opens the month view. */
@Composable
private fun TimeWidget(now: Long, events: List<Event>, modifier: Modifier, onClick: () -> Unit) {
    val dayKey = format("yyyyMMdd", now)
    val today = remember(dayKey) { Calendar.getInstance().apply { timeInMillis = now } }
    val lunar = remember(dayKey) { Lunar.convert(today.get(Calendar.DAY_OF_MONTH), today.get(Calendar.MONTH) + 1, today.get(Calendar.YEAR)) }
    val holiday = remember(dayKey, events) { Holidays.upcoming(now, events) }
    Widget(modifier, onClick, focusId = "w-time") {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AnalogClock(now, Modifier.size(78.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Header(format("EEEE", now).uppercase(), color = Color(0xFFFF6259))
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText("${today.get(Calendar.DAY_OF_MONTH)}", style = text(36f, FontWeight.Light))
                    BasicText("Tháng ${today.get(Calendar.MONTH) + 1}, ${today.get(Calendar.YEAR)}", Modifier.padding(start = 6.dp, bottom = 6.dp), style = text(12f, color = Muted), maxLines = 1)
                }
                BasicText("Âm lịch ${lunar.day}/${lunar.month}${if (lunar.leap) " (nhuận)" else ""} · ${Lunar.yearName(lunar.year)}", style = text(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                holiday?.let { (ahead, name, mine) ->
                    BasicText(
                        if (ahead == 0) "Hôm nay · $name" else if (ahead == 1) "Ngày mai · $name" else "$name · còn $ahead ngày",
                        style = text(11.5f, FontWeight.SemiBold, if (mine) Color(0xFF6FD6FF) else Color(0xFFFFB340)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherWidget(w: WeatherData?, modifier: Modifier, onClick: () -> Unit) {
    Widget(modifier, onClick, Color(0xFF4096F0) to Color(0xFF2C3482), "w-weather") {
        if (w == null) {
            BasicText("Đang tải thời tiết…", Modifier.align(Alignment.Center), style = text(11f, color = Muted))
            return@Widget
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(100.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Header(w.city.uppercase(), color = Color(0xE6FFFFFF))
                BasicText("${animatedValue(w.temp.toFloat(), 600).toInt()}°", style = text(42f, FontWeight.Light))
                BasicText(Weather.label(w.code), style = text(12f, color = Color(0xE6FFFFFF)), maxLines = 1)
            }
            Box(Modifier.padding(horizontal = 10.dp).width(1.dp).fillMaxHeight().background(Color(0x33FFFFFF)))
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                w.hours.take(4).forEachIndexed { i, (code, temp) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BasicText(if (i == 0) "Bây giờ" else "${(w.hour + i) % 24}h", style = text(11f, color = Color(0xCCFFFFFF)))
                        WeatherIcon(Weather.kind(code), 26.dp)
                        BasicText("$temp°", style = text(13.5f, FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

/** Wi-Fi speed and name, RAM and storage in one card. OK opens the control centre. */
@Composable
private fun SystemWidget(s: SysState, modifier: Modifier, onClick: () -> Unit) {
    Widget(modifier, onClick, focusId = "w-sys") {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.pk_wifi), null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(Muted))
                    Header("WI‑FI", Modifier.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(if (s.linkMbps >= 0) "${animatedValue(s.linkMbps).toInt()}" else "–", style = text(34f, FontWeight.Light))
                    BasicText("Mbps", Modifier.padding(start = 3.dp, bottom = 6.dp), style = text(12f, color = Muted))
                }
                BasicText(s.ssid ?: "${s.rssi.toInt()} dBm", style = text(12f, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ring(s.ramUsedPct, if (s.ramUsedPct > 85) Color(0xFFFF9F0A) else Color(0xFF0A84FF), "RAM")
                Ring(s.storageUsedPct, Color(0xFF30D158), "Bộ nhớ")
            }
        }
    }
}

@Composable
private fun Ring(target: Float, color: Color, label: String) {
    val pct = animatedValue(target, 900)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 5.dp.toPx()
                val d = size.minDimension - st
                drawArc(Color(0x24FFFFFF), -90f, 360f, false, Offset(st / 2, st / 2), Size(d, d), style = Stroke(st))
                drawArc(color, -90f, 360f * pct.coerceIn(0f, 100f) / 100f, false, Offset(st / 2, st / 2), Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
            }
            BasicText("${pct.toInt()}", style = text(13f, FontWeight.SemiBold))
        }
        BasicText(label, Modifier.padding(top = 3.dp), style = text(11f, color = Muted))
    }
}

@Composable
private fun WeatherIcon(kind: Int, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        fun cloud(dy: Float, color: Color) {
            drawCircle(color, w * 0.19f, Offset(w * 0.33f, w * (0.58f + dy)))
            drawCircle(color, w * 0.25f, Offset(w * 0.53f, w * (0.46f + dy)))
            drawCircle(color, w * 0.17f, Offset(w * 0.73f, w * (0.6f + dy)))
            drawRoundRect(color, Offset(w * 0.2f, w * (0.58f + dy)), Size(w * 0.6f, w * 0.2f), CornerRadius(w * 0.1f))
        }
        fun sun(cx: Float, cy: Float, r: Float) {
            drawCircle(Color(0xFFFFD60A), r, Offset(cx, cy))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(
                    Color(0xFFFFD60A), Offset(cx + cos(a).toFloat() * r * 1.45f, cy + sin(a).toFloat() * r * 1.45f),
                    Offset(cx + cos(a).toFloat() * r * 1.85f, cy + sin(a).toFloat() * r * 1.85f), strokeWidth = w * 0.05f, cap = StrokeCap.Round,
                )
            }
        }
        when (kind) {
            0 -> sun(w / 2, w / 2, w * 0.2f)
            1 -> { sun(w * 0.34f, w * 0.36f, w * 0.15f); cloud(0.08f, Color(0xF2FFFFFF)) }
            2 -> cloud(0f, Color(0xE6FFFFFF))
            else -> {
                cloud(-0.1f, Color(0xE6FFFFFF))
                for (i in 0..2) {
                    val x = w * (0.35f + 0.15f * i)
                    drawLine(Color(0xFF7FD0FF), Offset(x, w * 0.72f), Offset(x - w * 0.05f, w * 0.86f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

// ---- Dock ------------------------------------------------------------------------------------------

/** A pinned app as a wide tile: the app's own TV banner when it has one, else its icon on a glow in the icon's colour. */
@Composable
private fun AppTile(app: AppEntry, modifier: Modifier, id: String, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val banner = remember(app.pkg) { app.banner(context, 400, 225) }
    val glow = glowFor(app.pkg)
    Widget(modifier, { onOpen(app) }, if (banner == null) glow.copy(alpha = 0.55f) to Color(0xFF141828) else null, id, { onMenu(app) }) {
        if (banner != null) {
            Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(rememberAppDockBitmap(app), app.label, Modifier.size(64.dp))
                BasicText(app.label, style = text(13f, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The first free pinned slot: opens the full app list, where holding OK on an app pins it. */
@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) {
    Widget(modifier, onClick, focusId = "dock-add") {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(rememberMoreBitmap(), null, Modifier.size(56.dp))
            BasicText("Thêm ứng dụng", Modifier.padding(top = 7.dp), style = text(11f, color = Muted), maxLines = 1)
        }
    }
}

/** Day of the month, weekday and lunar date. OK opens the month view. */
@Composable
private fun DateTile(now: Long, modifier: Modifier, onClick: () -> Unit) {
    val dayKey = format("yyyyMMdd", now)
    val today = remember(dayKey) { Calendar.getInstance().apply { timeInMillis = now } }
    val lunar = remember(dayKey) { Lunar.convert(today.get(Calendar.DAY_OF_MONTH), today.get(Calendar.MONTH) + 1, today.get(Calendar.YEAR)) }
    Widget(modifier, onClick, focusId = "w-time") {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("${today.get(Calendar.DAY_OF_MONTH)}", style = text(44f, FontWeight.Light))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Header(format("EEEE", now).uppercase(), color = Color(0xFFFF6259))
                BasicText("Tháng ${today.get(Calendar.MONTH) + 1}, ${today.get(Calendar.YEAR)}", style = text(11f, color = Muted), maxLines = 1)
                BasicText("Âm lịch ${lunar.day}/${lunar.month}${if (lunar.leap) " (nhuận)" else ""} · ${Lunar.yearName(lunar.year)}", style = text(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Free storage with a thin bar. OK opens the control centre. */
@Composable
private fun StorageTile(s: SysState, modifier: Modifier, onClick: () -> Unit) {
    Widget(modifier, onClick, focusId = "w-rom") {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Header("BỘ NHỚ")
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText("%.1f".format(animatedValue(s.storageFreeGb)), style = text(28f, FontWeight.Light))
                    BasicText("GB trống", Modifier.padding(start = 4.dp, bottom = 5.dp), style = text(11f, color = Muted), maxLines = 1)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x24FFFFFF))) {
                    Box(Modifier.fillMaxWidth(animatedValue(s.storageUsedPct, 900).coerceIn(0f, 100f) / 100f).fillMaxHeight().background(Color(0xFF30D158)))
                }
            }
        }
    }
}

/** The next occasion: today's, a holiday or one of the user's own events. OK opens the month view. */
@Composable
private fun EventTile(now: Long, events: List<Event>, modifier: Modifier, onClick: () -> Unit) {
    val dayKey = format("yyyyMMdd", now)
    val next = remember(dayKey, events) { Holidays.upcoming(now, events) }
    Widget(modifier, onClick, focusId = "w-event") {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Header("SẮP TỚI")
            if (next == null) BasicText("Không có sự kiện", style = text(11.5f, color = Muted))
            else {
                val (ahead, name, mine) = next
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(name, style = text(12.5f, FontWeight.SemiBold, if (mine) Color(0xFF6FD6FF) else Color(0xFFFFB340)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText(if (ahead == 0) "Hôm nay" else if (ahead == 1) "Ngày mai" else "Còn $ahead ngày", style = text(11f, color = Muted), maxLines = 1)
                }
            }
        }
    }
}

/** "Dùng gần đây": the last apps opened from the launcher. */
@Composable
private fun RecentStrip(recent: List<Pair<AppEntry, Long>>, now: Long, modifier: Modifier, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit) {
    val a = LocalPanelAlpha.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF7F95E8).copy(alpha = a * 0.5f), Color(0xFF1B2030).copy(alpha = a * 0.95f))))
            .border(0.7.dp, PanelLine, shape).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Header("DÙNG GẦN ĐÂY")
        if (recent.isEmpty()) BasicText("Chưa có ứng dụng nào", style = text(11f, color = Muted))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.take(3).forEachIndexed { i, (app, at) ->
                Pressable(Modifier.weight(1f).fillMaxHeight(), { onOpen(app) }, { onMenu(app) }, focusId = "recent$i") { f ->
                    Row(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(if (f) Color(0x40FFFFFF) else Color(0x1AFFFFFF))
                            .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Image(rememberAppDockBitmap(app), app.label, Modifier.size(28.dp))
                        Column {
                            BasicText(app.label, style = text(10f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(ago(now, at), style = text(9f, color = Muted), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun ago(now: Long, at: Long): String {
    val m = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "Vừa xong"
        m < 60 -> "$m phút trước"
        m < 24 * 60 -> "${m / 60} giờ trước"
        else -> "${m / (24 * 60)} ngày trước"
    }
}

/** Power, mute and input shortcuts at the right end of the bottom row. */
@Composable
private fun TvButtons(onPower: () -> Unit, onInputs: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var muted by remember { mutableStateOf(TvControl.isMuted(context)) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((id, label, act) in listOf(
            Triple("tv-mute", if (muted) "Bật tiếng" else "Tắt tiếng", { muted = TvControl.toggleMute(context) }),
            Triple("tv-input", "Nguồn vào", onInputs),
            Triple("tv-power", "Nguồn", onPower),
        )) {
            Pressable(Modifier, act, focusId = id) { f ->
                BasicText(
                    label,
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (f) Color(0xF2FFFFFF) else Color(0x33FFFFFF))
                        .border(0.7.dp, if (f) Color.Transparent else Color(0x33FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
                    style = text(10.5f, FontWeight.SemiBold, if (f) Dark else Text),
                )
            }
        }
    }
}

/** A dim, slowly drifting clock for when nobody has pressed a key for a while. The drift spares the screen from burn-in. */
@Composable
private fun IdleScreen(weather: WeatherData?) {
    val now = rememberNow(0)
    val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val lunar = remember(format("yyyyMMdd", now)) { Lunar.convert(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR)) }
    val step = (now / 60_000).toInt()
    val dx by animateDpAsState(28.dp * ((step % 5) - 2), tween(2000), label = "dx")
    val dy by animateDpAsState(14.dp * (((step / 5) % 5) - 2), tween(2000), label = "dy")
    val dim = Color(0xFFB9C0D0)
    Box(Modifier.fillMaxSize().background(Color(0xFF04060C)), contentAlignment = Alignment.Center) {
        Column(Modifier.offset(dx, dy), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(format("HH:mm", now), style = text(120f, FontWeight.Light, Color(0xFFE3E7F2)))
            BasicText(format("EEEE, d MMMM yyyy", now), style = text(20f, color = dim))
            BasicText("Âm lịch ${lunar.day}/${lunar.month}${if (lunar.leap) " (nhuận)" else ""} · ${Lunar.yearName(lunar.year)}", style = text(16f, color = dim.copy(alpha = 0.8f)))
            weather?.let {
                BasicText("${it.temp}° · ${it.city} · ${Weather.label(it.code)}", Modifier.padding(top = 14.dp), style = text(18f, color = dim))
            }
        }
    }
}

private const val IDLE_MS = 3 * 60_000L

/** Key hints along the bottom edge. */
@Composable
private fun KeyHints(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((key, what) in listOf("OK" to "Chọn", "Giữ OK" to "Tùy chọn", "Menu" to "Điều khiển")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(key, Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp), style = text(9f, FontWeight.SemiBold))
                BasicText(what, style = text(9.5f, color = Muted))
            }
        }
    }
}

private val DOCK_ICON = 92.dp

/** The focus ring: a vivid cyan that none of the icons use (many are white, red or multicolour). */
private val DockRing = Color(0xFF2CE6FF)

/** A Dock icon with a thin cyan ring while it has focus, so the selection is easy to see from the sofa. */
@Composable
private fun DockIcon(bitmap: ImageBitmap, description: String, focused: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Image(bitmap, description, Modifier.fillMaxSize())
        if (focused) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(DOCK_ICON * 0.23f)))
    }
}

/**
 * Dock icons are drawn into rounded-corner bitmaps once. Scaling a ready bitmap is nearly free, whereas clipping
 * every icon to a rounded shape on every frame of the magnify animation is what made the Dock stutter.
 */
private fun roundedBitmap(px: Int, draw: (android.graphics.Canvas, Int) -> Unit): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val r = px * 0.23f
    c.clipPath(android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, android.graphics.Path.Direction.CW) })
    draw(c, px)
    return bmp.asImageBitmap()
}

@Composable
private fun dockPx() = with(LocalDensity.current) { (DOCK_ICON * 1.4f).roundToPx() }   // sharp at the largest magnification

@Composable
private fun rememberAppDockBitmap(app: AppEntry): ImageBitmap {
    val context = LocalContext.current
    val px = dockPx()
    return remember(app.pkg, px) {
        roundedBitmap(px) { c, s ->
            val f = s.toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val glyph = Overrides.glyphs[app.pkg]
            if (glyph != null) {
                paint.shader = LinearGradient(0f, 0f, f, f, 0xFF6B7385.toInt(), 0xFF3A4252.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, f, f, paint)
                context.getDrawable(glyph)?.mutate()?.let { d ->
                    d.setTint(0xFFF4F6FB.toInt())
                    val m = (s * 0.22f).toInt()
                    d.setBounds(m, m, s - m, s - m)
                    d.draw(c)
                }
            } else {
                val icon = app.icon(context, s)
                paint.color = (icon.background ?: Color.White).toArgb()
                c.drawRect(0f, 0f, f, f, paint)
                val inset = if (icon.fullBleed) 0 else (s * 0.07f).toInt()
                c.drawBitmap(icon.bitmap.asAndroidBitmap(), null, Rect(inset, inset, s - inset, s - inset), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }
}

@Composable
private fun rememberMoreBitmap(): ImageBitmap {
    val px = dockPx()
    return remember(px) {
        roundedBitmap(px) { c, s ->
            val f = s.toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x40FFFFFF; c.drawRect(0f, 0f, f, f, paint)
            paint.color = 0xFFF4F6FB.toInt(); paint.strokeWidth = f * 0.06f; paint.strokeCap = Paint.Cap.ROUND
            c.drawLine(f * 0.3f, f * 0.5f, f * 0.7f, f * 0.5f, paint); c.drawLine(f * 0.5f, f * 0.3f, f * 0.5f, f * 0.7f, paint)
        }
    }
}

/** The app's own icon in a rounded square, or the cleaner glyph from [Overrides]. Used by the app list. */
@Composable
internal fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier) {
    val glyph = Overrides.glyphs[app.pkg]
    if (glyph != null) {
        Image(painterResource(glyph), app.label, modifier.size(if (size > 40.dp) 60.dp else 30.dp), colorFilter = ColorFilter.tint(Text))
        return
    }
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(app.pkg, px) { app.icon(context, px) }
    val shape = RoundedCornerShape(size * 0.23f)
    Box(modifier.size(size).clip(shape).background(icon.background ?: Color.White), contentAlignment = Alignment.Center) {
        Image(icon.bitmap, app.label, if (icon.fullBleed) Modifier.fillMaxSize() else Modifier.size(size * 0.86f))
    }
}

/** A bare focusable area: OK clicks, holding OK (or Menu) long-clicks. [content] is told whether it has focus. */
@Composable
private fun Pressable(
    modifier: Modifier, onClick: () -> Unit, onLong: (() -> Unit)? = null, onFocus: (Boolean) -> Unit = {},
    focusId: String? = null, content: @Composable BoxScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val press = remember { Press() }
    Box(
        modifier.remembered(focusId).onFocusChanged { if (it.isFocused && !focused) Sfx.move(); focused = it.isFocused; onFocus(it.isFocused) }
            .onKeyEvent { press.handle(it.nativeKeyEvent, {}, { Sfx.select(); onClick() }, onLong) }
            .focusable(),
    ) { content(focused) }
}

// ---- Calendar and weather screens ------------------------------------------------------------------

@Composable
private fun PanelScreen(onKey: (Int) -> Boolean, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().focusRequester(focus).onKeyEvent {
            it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && onKey(it.nativeKeyEvent.keyCode)
        }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xCC1E2230)).border(0.7.dp, PanelLine, RoundedCornerShape(24.dp)).padding(horizontal = 26.dp, vertical = 22.dp),
        ) { content() }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
}

private class Cell(val d: Int, val l: Lunar.Date, val holiday: String?, val events: List<String>)

@Composable
private fun CalendarScreen(events: List<Event>) {
    val start = remember { Calendar.getInstance() }
    var year by remember { mutableIntStateOf(start.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(start.get(Calendar.MONTH)) }   // 0-based
    val cells = remember(year, month, events) {
        val cal = Calendar.getInstance().apply { clear(); set(year, month, 1) }
        val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        List<Cell?>(offset) { null } + (1..days).map { d -> Lunar.convert(d, month + 1, year).let { l -> Cell(d, l, Holidays.of(d, month + 1, year, l), Events.on(events, d, month + 1, l)) } }
    }
    val mid = remember(year, month) { Lunar.convert(15, month + 1, year) }
    val today = Calendar.getInstance()
    PanelScreen(onKey = { key ->
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (month == 0) { month = 11; year-- } else month--; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (month == 11) { month = 0; year++ } else month++; true }
            else -> false
        }
    }) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText("Tháng ${month + 1} năm $year", style = text(26f, FontWeight.Light))
            BasicText("Năm ${Lunar.yearName(mid.year)}", Modifier.padding(start = 12.dp, bottom = 4.dp), style = text(12f, color = Muted))
            Spacer(Modifier.weight(1f))
            BasicText("◀ ▶ đổi tháng", Modifier.padding(bottom = 4.dp), style = text(10f, color = Muted))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            for (n in listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")) {
                BasicText(n, Modifier.weight(1f), style = text(11f, color = Muted).copy(textAlign = TextAlign.Center))
            }
        }
        Spacer(Modifier.height(4.dp))
        for (row in cells.chunked(7)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (i in 0 until 7) {
                    val cell = row.getOrNull(i)
                    Column(
                        Modifier.weight(1f).height(44.dp).padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).drawBehind {
                            if (cell?.holiday != null) drawCircle(Color(0xFFFF6B6B), 2.8.dp.toPx(), Offset(size.width - 9.dp.toPx(), 9.dp.toPx()))
                            if (cell?.events?.isNotEmpty() == true) drawCircle(Color(0xFF5BD6FF), 2.8.dp.toPx(), Offset(9.dp.toPx(), 9.dp.toPx()))
                        }.background(
                            if (cell != null && cell.d == today.get(Calendar.DAY_OF_MONTH) && month == today.get(Calendar.MONTH) && year == today.get(Calendar.YEAR)) Color(0xFFFF453A)
                            else if (cell != null) Color(0x10FFFFFF) else Color.Transparent,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        if (cell != null) {
                            val d = cell.d
                            val l = cell.l
                            val special = l.day == 1 || l.day == 15
                            val isToday = d == today.get(Calendar.DAY_OF_MONTH) && month == today.get(Calendar.MONTH) && year == today.get(Calendar.YEAR)
                            BasicText("$d", style = text(16f, color = if (isToday) Color.White else if (i == 6) Color(0xFFFF8D85) else Text))
                            BasicText(Lunar.label(l), style = text(9.5f, color = if (isToday) Color.White else if (special) Color(0xFFFFB340) else Muted))
                        }
                    }
                }
            }
        }
        val monthHolidays = cells.filterNotNull().filter { it.holiday != null }.joinToString("   ·   ") { "${it.d}/${month + 1} ${it.holiday}" }
        val mine = cells.filterNotNull().filter { it.events.isNotEmpty() }.joinToString("   ·   ") { "${it.d}/${month + 1} ${it.events.joinToString(", ")}" }
        if (mine.isNotEmpty()) BasicText(mine, Modifier.padding(top = 8.dp), style = text(11f, FontWeight.SemiBold, Color(0xFF6FD6FF)), maxLines = 3)
        if (monthHolidays.isNotEmpty()) BasicText(monthHolidays, Modifier.padding(top = 8.dp), style = text(11f, FontWeight.SemiBold, Color(0xFFFFB340)), maxLines = 3)
        BasicText("Chấm đỏ: ngày lễ · Chấm xanh: sự kiện của bạn · Chữ cam: mùng 1, rằm", Modifier.padding(top = 4.dp), style = text(10f, color = Muted))
    }
}

@Composable
private fun WeatherScreen(w: WeatherData?) {
    PanelScreen(onKey = { false }) {
        if (w == null) {
            BasicText("Chưa có dữ liệu thời tiết. Kiểm tra kết nối mạng.", style = text(14f))
            return@PanelScreen
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                BasicText(w.city, style = text(16f, FontWeight.SemiBold))
                BasicText("${w.temp}°", style = text(62f, FontWeight.Light))
                BasicText("${Weather.label(w.code)} · cảm giác như ${w.feel}°", style = text(12f, color = Muted))
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pair in listOf("Độ ẩm" to "${w.humidity}%", "Gió" to "${w.wind} km/h", "Chỉ số UV" to "${w.uv}", "Khả năng mưa" to "${w.days.firstOrNull()?.rain ?: 0}%")) {
                    Row(Modifier.width(190.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        BasicText(pair.first, style = text(11f, color = Muted))
                        Spacer(Modifier.weight(1f))
                        BasicText(pair.second, style = text(12f, FontWeight.SemiBold))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val names = listOf("Chủ nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy")
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        w.days.forEachIndexed { i, d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (i == 0) "Hôm nay" else names[(dow + i) % 7], Modifier.width(90.dp), style = text(13f))
                WeatherIcon(Weather.kind(d.code), 24.dp)
                BasicText("${d.rain}%", Modifier.width(52.dp).padding(start = 8.dp), style = text(11f, color = Color(0xFF7FD0FF)))
                BasicText("${d.lo}°", Modifier.width(34.dp), style = text(12f, color = Muted))
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color(0x2EFFFFFF))) {
                    val lo = ((d.lo - 18) / 18f).coerceIn(0f, 1f)
                    val hi = ((d.hi - 18) / 18f).coerceIn(lo, 1f)
                    Box(Modifier.fillMaxWidth(hi - lo).fillMaxHeight().offset(x = 0.dp).graphicsLayer { translationX = size.width * lo / maxOf(hi - lo, 0.01f) }
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A))), CircleShape))
                }
                BasicText("${d.hi}°", Modifier.width(34.dp).padding(start = 8.dp), style = text(12f))
            }
        }
    }
}

// ---- Control centre --------------------------------------------------------------------------------

private enum class Glyph { Gear, Wifi, Sound, Picture, Input, Bluetooth, Apps, Storage, Clock, Language, Update, Info, Phone }

/** [intent] null means the input picker. All of these were checked to exist on this TV's Settings. */
private class Shortcut(val label: String, val glyph: Glyph, val intent: Intent?)

private fun component(pkg: String, cls: String) = Intent().setClassName(pkg, cls)

private val SHORTCUTS = listOf(
    Shortcut("Cài đặt", Glyph.Gear, Intent(Settings.ACTION_SETTINGS)),
    Shortcut("Wi‑Fi", Glyph.Wifi, Intent(Settings.ACTION_WIFI_SETTINGS)),
    // Android's own sound screen crashes on this TV's firmware; TCL's panel (Picture / Sound / System) has the sound settings.
    Shortcut("Âm thanh", Glyph.Sound, component("com.tcl.settings", "com.tcl.settings.MainActivity")),
    Shortcut("Hình ảnh", Glyph.Picture, component("com.tcl.settings", "com.tcl.settings.MainActivity")),
    Shortcut("Nguồn vào", Glyph.Input, null),
    Shortcut("Bluetooth", Glyph.Bluetooth, component("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity")),
    Shortcut("Ứng dụng", Glyph.Apps, component("com.android.tv.settings", "com.android.tv.settings.device.apps.AppsActivity")),
    Shortcut("Bộ nhớ", Glyph.Storage, component("com.android.tv.settings", "com.android.tv.settings.device.StorageSummaryActivity")),
    Shortcut("Ngày giờ", Glyph.Clock, Intent(Settings.ACTION_DATE_SETTINGS)),
    Shortcut("Ngôn ngữ", Glyph.Language, Intent(Settings.ACTION_LOCALE_SETTINGS)),
    Shortcut("Cập nhật", Glyph.Update, Intent("android.settings.SYSTEM_UPDATE_SETTINGS")),
    Shortcut("Giới thiệu", Glyph.Info, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
)

@Composable
private fun ControlCenter(sys: SysState, city: String, onCity: () -> Unit, onInputs: () -> Unit, onPhone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var cleanLabel by remember { mutableStateOf("Dọn RAM") }
    var sfxOn by remember { mutableStateOf(Sfx.enabled) }
    val shape = RoundedCornerShape(21.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RemoteTarget()
        Box(Modifier.fillMaxSize().padding(top = 40.dp, end = 24.dp), contentAlignment = Alignment.TopEnd) {
            Row(
                Modifier.clip(shape).background(Color(0xEB24283A)).border(0.7.dp, PanelLine, shape).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Left: shortcuts to the TV's own settings screens.
                Column(Modifier.width(272.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SHORTCUTS.chunked(4).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { c, sc ->
                                Tile(sc, Modifier.weight(1f).height(66.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier)) {
                                    onDismiss()
                                    if (sc.intent == null) onInputs()
                                    else if (!open(context, sc.intent)) Toast.makeText(context, "Không mở được ${sc.label}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        // A last row of one: the interface sounds on/off.
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile(Shortcut(if (sfxOn) "Tiếng: bật" else "Tiếng: tắt", Glyph.Sound, Intent()), Modifier.weight(1f).height(66.dp)) { sfxOn = Sfx.toggle(context) }
                        Tile(Shortcut("Điện thoại", Glyph.Phone, Intent()), Modifier.weight(1f).height(66.dp)) { onDismiss(); onPhone() }
                        Spacer(Modifier.weight(2f))
                    }
                }
                // Right: live Wi-Fi, city, sliders, RAM clean-up.
                Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CcRow(
                        R.drawable.pk_wifi, "Wi‑Fi: ${sys.ssid ?: if (sys.linkMbps >= 0) "đang bật" else "chưa kết nối"}",
                        if (sys.linkMbps >= 0) "${sys.linkMbps.toInt()} Mbps · ${sys.rssi.toInt()} dBm" else "Mở cài đặt mạng",
                    ) { onDismiss(); open(context, Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    CcRow(R.drawable.pk_search, "Thành phố thời tiết", "$city · OK để đổi") { onCity() }
                    CcSlider("Âm lượng", vol, maxVol, 1) { v ->
                        vol = v
                        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                    }
                    Pressable(Modifier.fillMaxWidth(), {
                        scope.launch {
                            val freed = cleanRam(context)
                            cleanLabel = if (freed > 0) "Đã giải phóng $freed MB" else "Không còn gì để dọn"
                        }
                    }) { f ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (f) Color.White else Color(0xFFFF5B4A)).padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { BasicText(cleanLabel, style = text(13f, FontWeight.SemiBold, if (f) Dark else Color.White)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        BasicText("RAM trống ${"%.0f".format(sys.ramFreeMb)} MB", style = text(10f, color = Muted))
                        BasicText("Bộ nhớ trống ${"%.1f".format(sys.storageFreeGb)} GB", style = text(10f, color = Muted))
                    }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}

@Composable
private fun Tile(sc: Shortcut, modifier: Modifier, onClick: () -> Unit) {
    Pressable(modifier, onClick) { f ->
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(if (f) Color.White else Color(0x26FFFFFF)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            SysIcon(sc.glyph, if (f) Dark else Text)
            BasicText(sc.label, Modifier.padding(top = 5.dp), style = text(9.5f, FontWeight.SemiBold, if (f) Dark else Text), maxLines = 1)
        }
    }
}

@Composable
private fun CcSlider(label: String, value: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x26FFFFFF))
            .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(14.dp))
            .onFocusChanged { if (it.isFocused && !focused) Sfx.move(); focused = it.isFocused }
            .onKeyEvent {
                val k = it.nativeKeyEvent
                if (k.action == KeyEvent.ACTION_DOWN && (k.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    Sfx.move()
                    onChange((value + if (k.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step).coerceIn(0, max))
                    true
                } else false
            }
            .focusable().padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText("$label  ◀ ▶", style = text(12f, FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            BasicText("${value * 100 / max}", style = text(12f, FontWeight.SemiBold))
        }
        Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value / max.toFloat()).background(Color.White))
        }
    }
}

@Composable
private fun CcRow(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    Pressable(Modifier.fillMaxWidth(), onClick) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (f) Color.White else Color(0x26FFFFFF)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (f) Color(0x1F000000) else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                Image(painterResource(icon), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(if (f) Dark else Text))
            }
            Column(Modifier.padding(start = 10.dp)) {
                BasicText(title, style = text(12.5f, FontWeight.SemiBold, if (f) Dark else Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(sub, style = text(10f, color = if (f) Color(0x99000000) else Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Simple line icons, drawn rather than shipped as images. */
@Composable
private fun SysIcon(g: Glyph, color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val sw = w * 0.085f
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, w * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), sw, StrokeCap.Round)
        when (g) {
            Glyph.Gear -> {
                drawCircle(color, w * 0.3f, center, style = st)
                drawCircle(color, w * 0.12f, center, style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    drawLine(color, Offset(center.x + cos(a).toFloat() * w * 0.34f, center.y + sin(a).toFloat() * w * 0.34f),
                        Offset(center.x + cos(a).toFloat() * w * 0.45f, center.y + sin(a).toFloat() * w * 0.45f), sw, StrokeCap.Round)
                }
            }
            Glyph.Wifi -> {
                for (r in listOf(0.5f, 0.33f, 0.16f)) drawArc(color, -135f, 90f, false, p(0.5f - r, 0.78f - r), Size(w * 2 * r, w * 2 * r), style = st)
                drawCircle(color, sw, p(0.5f, 0.78f))
            }
            Glyph.Sound -> {
                val spk = Path().apply { moveTo(w * .12f, w * .4f); lineTo(w * .3f, w * .4f); lineTo(w * .5f, w * .2f); lineTo(w * .5f, w * .8f); lineTo(w * .3f, w * .6f); lineTo(w * .12f, w * .6f); close() }
                drawPath(spk, color, style = st)
                for (r in listOf(0.2f, 0.34f)) drawArc(color, -40f, 80f, false, p(0.52f - r, 0.5f - r), Size(w * 2 * r, w * 2 * r), style = st)
            }
            Glyph.Picture -> {
                drawRoundRect(color, p(.1f, .18f), Size(w * .8f, w * .64f), CornerRadius(w * .08f), style = st)
                val m = Path().apply { moveTo(w * .14f, w * .72f); lineTo(w * .38f, w * .46f); lineTo(w * .55f, w * .62f); lineTo(w * .68f, w * .5f); lineTo(w * .86f, w * .72f) }
                drawPath(m, color, style = st)
                drawCircle(color, w * .05f, p(.7f, .34f))
            }
            Glyph.Input -> {
                drawRoundRect(color, p(.4f, .2f), Size(w * .5f, w * .6f), CornerRadius(w * .06f), style = st)
                line(.08f, .5f, .6f, .5f); line(.44f, .34f, .6f, .5f); line(.44f, .66f, .6f, .5f)
            }
            Glyph.Bluetooth -> {
                val b = Path().apply { moveTo(w * .3f, w * .3f); lineTo(w * .7f, w * .7f); lineTo(w * .5f, w * .88f); lineTo(w * .5f, w * .12f); lineTo(w * .7f, w * .3f); lineTo(w * .3f, w * .7f) }
                drawPath(b, color, style = st)
            }
            Glyph.Apps -> for ((x, y) in listOf(.14f to .14f, .54f to .14f, .14f to .54f, .54f to .54f))
                drawRoundRect(color, p(x, y), Size(w * .32f, w * .32f), CornerRadius(w * .06f), style = st)
            Glyph.Storage -> {
                drawOval(color, p(.18f, .14f), Size(w * .64f, w * .24f), style = st)
                line(.18f, .26f, .18f, .72f); line(.82f, .26f, .82f, .72f)
                drawArc(color, 0f, 180f, false, p(.18f, .6f), Size(w * .64f, w * .24f), style = st)
            }
            Glyph.Clock -> {
                drawCircle(color, w * .38f, center, style = st)
                line(.5f, .5f, .5f, .28f); line(.5f, .5f, .66f, .6f)
            }
            Glyph.Language -> {
                drawCircle(color, w * .38f, center, style = st)
                drawOval(color, p(.33f, .12f), Size(w * .34f, w * .76f), style = st)
                line(.12f, .5f, .88f, .5f)
            }
            Glyph.Update -> {
                drawArc(color, -50f, 270f, false, p(.2f, .2f), Size(w * .6f, w * .6f), style = st)
                line(.72f, .14f, .74f, .34f); line(.72f, .14f, .54f, .2f)
            }
            Glyph.Phone -> {
                drawRoundRect(color, p(.3f, .1f), Size(w * .4f, w * .8f), CornerRadius(w * .08f), style = st)
                line(.44f, .78f, .56f, .78f)
            }
            Glyph.Info -> {
                drawCircle(color, w * .38f, center, style = st)
                drawCircle(color, sw * .8f, p(.5f, .33f)); line(.5f, .46f, .5f, .7f)
            }
        }
    }
}

// ---- Wallpaper and phone settings ------------------------------------------------------------------

/** The chosen colourway, or the photo sent from the phone (darkened a little so the widgets stay readable). */
private fun loadWallpaper(context: Context): ImageBitmap {
    val name = Apps.backdrop(context)
    val file = File(context.filesDir, "wallpaper.jpg")
    if (name == "Photo" && file.exists()) {
        runCatching {
            val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) ?: error("decode")
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(src, 0f, 0f, null)
            canvas.drawColor((Apps.dim(context) * 255 / 100) shl 24)
            src.recycle()
            return out.asImageBitmap()
        }
    }
    return renderBloom(Backdrop.entries.firstOrNull { it.name == name } ?: Backdrop.Sonoma, 640, 360)
}

@Composable
private fun PhoneScreen(url: String?) {
    PanelScreen(onKey = { false }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(200.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                if (url != null) QrCode(url, Modifier.fillMaxSize()) else BasicText("Chưa có mạng", style = text(12f, color = Dark))
            }
            Column(Modifier.padding(start = 24.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("Điều khiển bằng điện thoại", style = text(22f, FontWeight.Light))
                BasicText("1. Điện thoại và TV dùng cùng một Wi‑Fi.", style = text(12f))
                BasicText("2. Mở camera, quét mã QR bên trái.", style = text(12f))
                BasicText("3. Chọn thành phố, hình nền, Dock, widget… TV đổi ngay.", style = text(12f))
                if (url != null) BasicText(url, Modifier.padding(top = 4.dp), style = text(9.5f, color = Muted), maxLines = 2)
                BasicText("Mã QR cũng luôn hiện ở góc màn hình chính. Bấm Back để thoát.", style = text(10f, color = Muted))
            }
        }
    }
}

/** A small QR code that stays in the corner of the home screen. */
@Composable
private fun QrCorner(url: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x66141828)).border(0.7.dp, PanelLine, RoundedCornerShape(16.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(62.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(3.dp)) { QrCode(url, Modifier.fillMaxSize()) }
        BasicText("Quét để điều khiển", Modifier.padding(top = 4.dp).width(66.dp), style = text(9f, color = Muted).copy(textAlign = TextAlign.Center), maxLines = 2)
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, mapOf(EncodeHintType.MARGIN to 0)) }
    Canvas(modifier) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

// ---- All apps (launchpad) and the options menu -----------------------------------------------------

/** All apps as a grid of large icons, with a search field. Hold OK on an icon for its menu (add to Dock...). */
@Composable
private fun AppsScreen(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    val firstApp = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxSize().background(Color(0xB30B0E1C))) {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("Ứng dụng", style = text(26f, FontWeight.Light))
                    BasicText("${shown.size} ứng dụng · giữ OK để thêm vào Dock", Modifier.padding(top = 2.dp), style = text(11f, color = Muted))
                }
                var typing by remember { mutableStateOf(false) }
                var hadFocus by remember { mutableStateOf(false) }
                val fieldFocus = remember { FocusRequester() }
                val pill = RoundedCornerShape(19.dp)
                Pressable(Modifier.width(250.dp), { typing = true }) { f ->
                    Row(
                        Modifier.fillMaxWidth().height(38.dp).clip(pill).background(Color(0x26FFFFFF))
                            .border(if (f || typing) 2.dp else 0.7.dp, if (f || typing) DockRing else PanelLine, pill).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(painterResource(R.drawable.pk_search), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Muted))
                        Box(Modifier.padding(start = 8.dp).weight(1f)) {
                            if (query.isEmpty()) BasicText(if (typing) "Gõ để tìm…" else "Tìm ứng dụng · bấm OK", style = text(12f, color = Muted))
                            if (typing) {
                                BasicTextField(
                                    query, { query = it },
                                    Modifier.fillMaxWidth().focusRequester(fieldFocus).onFocusChanged {
                                        if (it.isFocused) hadFocus = true else if (hadFocus) { hadFocus = false; typing = false; keyboard?.hide() }
                                    },
                                    textStyle = text(12f), singleLine = true, cursorBrush = SolidColor(Text),
                                )
                            } else BasicText(query, style = text(12f))
                        }
                    }
                }
                LaunchedEffect(typing) { if (typing) { withFrameNanos { }; runCatching { fieldFocus.requestFocus() }; keyboard?.show() } }
            }
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(7), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(shown, key = { _, a -> a.pkg }) { i, app -> AppCell(app, if (i == 0) firstApp else null, onOpen, onMenu) }
            }
        }
    }
    // Focus starts on the first app; the keyboard only opens when you move to the search field yourself.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstApp.requestFocus() }; delay(120); keyboard?.hide() }
}

@Composable
private fun AppCell(app: AppEntry, requester: FocusRequester?, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit) {
    val bitmap = rememberAppDockBitmap(app)
    Pressable(Modifier.fillMaxWidth().height(94.dp).then(if (requester != null) Modifier.focusRequester(requester) else Modifier), { onOpen(app) }, { onMenu(app) }) { f ->
        val s by animateFloatAsState(if (f) 1.12f else 1f, tween(100), label = "cell")
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(58.dp).graphicsLayer { scaleX = s; scaleY = s }) { DockIcon(bitmap, app.label, f) }
            BasicText(
                app.label, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                style = text(11f, if (f) FontWeight.SemiBold else FontWeight.Normal, if (f) Color.White else Muted).copy(textAlign = TextAlign.Center),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A context menu in the middle of the screen; the focused row turns white. Back closes it. */
@Composable
internal fun OptionsMenu(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RemoteTarget()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(290.dp).clip(shape).background(Color(0xF0222638)).border(0.7.dp, PanelLine, shape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(title, Modifier.padding(start = 6.dp, top = 2.dp, bottom = 6.dp), style = text(13f, FontWeight.SemiBold, Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                options.forEachIndexed { i, (label, action) ->
                    Pressable(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), { onDismiss(); action() }) { f ->
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (f) Color.White else Color(0x1AFFFFFF)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                            BasicText(label, style = text(13f, if (f) FontWeight.SemiBold else FontWeight.Normal, if (f) Dark else Text), maxLines = 1)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
