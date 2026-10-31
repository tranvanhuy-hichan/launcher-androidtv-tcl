package io.github.qbh21.vitrine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.graphics.Canvas as AndroidCanvas

// Vitrine: live tiles in frosted glass over a bloom wallpaper.
// Built from concepts/windows-rt-2026.html; sizes are that mockup's pixels / 2 (the TV is 1080p at 2x).

internal val Text = Color(0xFFF4F6FB)
internal val Muted = Color(0xADE8EEFA)
internal val Glass = Color(0x16FFFFFF)
internal val GlassEdge = Color(0x29FFFFFF)
internal val Sheen = Color(0x1AFFFFFF)
internal val Halo = Color(0x24FFFFFF)
internal val Band = Color(0xEB0E1430)

/** Brand colour that glows softly behind each app's icon. */
private val GlowColors = mapOf(
    "com.google.android.youtube.tv" to Color(0x8CFF2828),
    "org.moonfin.androidtv" to Color(0x8C7C5CFF),
    "com.android.vending" to Color(0x7328C88C),
    "com.mediatek.wwtv.tvcenter" to Color(0x66FF5AAA),
    "com.mediatek.dmp.mediaplayer" to Color(0x6BFF8C3C),
    "com.android.documentsui" to Color(0x5C4FA8FF),
    "com.netflix.ninja" to Color(0x80E50914),
)
private val SpareGlows = listOf(Color(0x664F8CFF), Color(0x668B5CF6), Color(0x5C19B8FF), Color(0x5CFF5AAA), Color(0x5C28C88C))
private fun glowFor(pkg: String) = GlowColors[pkg] ?: SpareGlows[Math.floorMod(pkg.hashCode(), SpareGlows.size)]

// Selawik: Microsoft's open-source stand-in for Segoe UI.
private val Selawik = FontFamily(
    Font(R.font.selawik_light, FontWeight.Light),
    Font(R.font.selawik, FontWeight.Normal),
    Font(R.font.selawik_semibold, FontWeight.SemiBold),
)

internal fun text(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Text) =
    TextStyle(fontFamily = Selawik, fontWeight = weight, fontSize = size.sp, color = color)

// ---- Wallpaper -------------------------------------------------------------------------------------

/** Wallpaper choices (the Personalise tile): the same bloom in four colourways. */
internal enum class Backdrop(val label: String, val base: IntArray, val petals: IntArray, val swatch: Color) {
    Sonoma("Sonoma", intArrayOf(0xFF1B1F4D.toInt(), 0xFF3A2A6E.toInt(), 0xFF7A3A78.toInt()),
        intArrayOf(0x3B3FD6, 0xFF7A59, 0xFF5C8A, 0x6A38C9), Color(0xFFB45CFF)),
    Sunset("Hoàng hôn", intArrayOf(0xFF2B1030.toInt(), 0xFF4A1A3A.toInt(), 0xFF7A2A3C.toInt()),
        intArrayOf(0xFF7A45, 0xFF4D6E, 0xFFB347, 0xB43C8C), Color(0xFFFF7A45)),
    Ocean("Đại dương", intArrayOf(0xFF04202E.toInt(), 0xFF062F45.toInt(), 0xFF0A3C5A.toInt()),
        intArrayOf(0x19B8FF, 0x3D7DFF, 0x35F0D0, 0x0E6E8C), Color(0xFF19B8FF)),
    Forest("Rừng", intArrayOf(0xFF08241C.toInt(), 0xFF0C3326.toInt(), 0xFF123D2E.toInt()),
        intArrayOf(0x30D158, 0x19D3A0, 0xB4E34D, 0x0E6E4C), Color(0xFF30D158)),
    Aurora("Cực quang", intArrayOf(0xFF0A1030.toInt(), 0xFF101A48.toInt(), 0xFF1B1058.toInt()),
        intArrayOf(0x35F0A0, 0x8A5CFF, 0x19B8FF, 0x2846B4), Color(0xFF35F0A0)),
    Bloom("Bloom", intArrayOf(0xFF0B1636.toInt(), 0xFF081028.toInt(), 0xFF0D0C2E.toInt()),
        intArrayOf(0x3D7DFF, 0x8A5CFF, 0x19B8FF, 0x2846B4), Color(0xFF4F7BFF)),
    Cyan("Cyan", intArrayOf(0xFF062A33.toInt(), 0xFF04202A.toInt(), 0xFF062634.toInt()),
        intArrayOf(0x19D3E6, 0x1A8CFF, 0x35F0D0, 0x0E6E8C), Color(0xFF3CCDE6)),
    Red("Red", intArrayOf(0xFF2A0A12.toInt(), 0xFF1E070D.toInt(), 0xFF2A0B1C.toInt()),
        intArrayOf(0xFF4D5E, 0xFF7A45, 0xE5486D, 0x8C1E3C), Color(0xFFE5484D)),
    Grey("Grey", intArrayOf(0xFF1A1C22.toInt(), 0xFF121418.toInt(), 0xFF1B1C24.toInt()),
        intArrayOf(0x8A93A6, 0x5E6A82, 0xB4BCCB, 0x3A4252), Color(0xFF9AA3B2)),
}

/** One soft light in the bloom, in 1920x1080 mockup coordinates. [fade] is where it reaches transparent. */
private class Glow(val x: Float, val y: Float, val rx: Float, val ry: Float, val rgb: Int, val alpha: Float, val rotation: Float = 0f, val fade: Float = 0.7f)

private fun Backdrop.glows() = if (this == Backdrop.Sonoma) listOf(
    Glow(960f, 40f, 1100f, 640f, petals[0], .55f),
    Glow(1800f, 330f, 800f, 560f, petals[3], .50f),
    Glow(300f, 1100f, 1100f, 700f, petals[1], .60f),
    Glow(1560f, 1060f, 1000f, 640f, petals[2], .55f),
) else listOf(
    Glow(1382f, 497f, 900f, 520f, petals[0], .55f),
    Glow(1651f, 842f, 700f, 600f, petals[1], .42f),
    Glow(1114f, 194f, 620f, 420f, petals[2], .30f),
    Glow(230f, 1080f, 1100f, 700f, petals[3], .35f),
    Glow(1490f, 460f, 370f, 190f, petals[0], .50f, -28f, 1f),   // the three "petals"
    Glow(1590f, 670f, 320f, 170f, petals[1], .50f, 22f, 1f),
    Glow(1230f, 610f, 270f, 150f, petals[2], .32f, 8f, 1f),
)

/** Paints the bloom once into a half-resolution bitmap, so each frame only has to draw one image. */
internal fun renderBloom(b: Backdrop, width: Int, height: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    paint.shader = LinearGradient(width * 0.4f, 0f, width * 0.6f, height.toFloat(), b.base, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    val s = width / 1920f
    for (g in b.glows()) {
        val color = ((g.alpha * 255).toInt() shl 24) or g.rgb
        canvas.save()
        canvas.translate(g.x * s, g.y * s)
        canvas.rotate(g.rotation)
        canvas.scale(g.rx / g.ry, 1f)   // a circle stretched into the ellipse
        val r = g.ry * s
        paint.shader = RadialGradient(0f, 0f, r, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, g.fade), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, r, paint)
        canvas.restore()
    }
    return bmp.asImageBitmap()
}

// ---- Other screens ---------------------------------------------------------------------------------

/** All apps by name: icon squares with names, scrolling sideways. */
@Composable
internal fun AppList(
    title: String, subtitle: String?, apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit,
    focusList: Boolean = true, header: (@Composable () -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(title, style = text(28f, FontWeight.Light))
            if (subtitle != null) BasicText(subtitle, Modifier.padding(start = 10.dp, bottom = 4.dp), style = text(11f, color = Muted))
        }
        header?.invoke()
        Spacer(Modifier.height(14.dp))
        LazyHorizontalGrid(
            rows = GridCells.Fixed(6),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 44.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.pkg }) { i, app ->
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {   // keep rows their own height
                    GlassBox(
                        150.dp, 36.dp, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier, { onOpen(app) }, { onMenu(app) },
                        corner = 8.dp, background = SolidColor(Color.Transparent), focusBackground = SolidColor(Glass), sheen = false, focusScale = 1.03f,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app, 26.dp, Modifier)
                            BasicText(app.label, Modifier.padding(start = 9.dp), style = text(11f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (focusList && apps.isNotEmpty()) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } } }
}

/** Search apps: type with the on-screen keyboard, results narrow as you go. */
@Composable
internal fun SearchScreen(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(apps, query) { if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    AppList("Tìm kiếm", if (query.isBlank()) "tất cả ứng dụng" else "${results.size} kết quả", results, onOpen, onMenu, focusList = false) {
        Box(
            Modifier.padding(start = 48.dp, top = 14.dp).width(330.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(Glass)
                .border(if (focused) 1.5.dp else 0.5.dp, if (focused) Color.White else GlassEdge, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.pk_search), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(Muted))
                Box(Modifier.padding(start = 7.dp)) {
                    if (query.isEmpty()) BasicText("Gõ tên ứng dụng", style = text(11f, color = Muted))
                    BasicTextField(
                        query, { query = it },
                        Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                        textStyle = text(11f), singleLine = true, cursorBrush = SolidColor(Text),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() }; keyboard?.show() }
}

// ---- Building blocks -------------------------------------------------------------------------------

/**
 * A focusable frosted-glass surface: translucent fill, a faint top sheen and edge. Focused, it lifts a
 * little with a white ring and a soft halo; pressing OK pushes it in. [content] is told
 * whether it's focused.
 */
@Composable
internal fun GlassBox(
    width: Dp, height: Dp, modifier: Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    corner: Dp = 8.dp, background: Brush = SolidColor(Glass), focusBackground: Brush? = null,
    sheen: Boolean = true, ring: Boolean = true, focusScale: Float = 1.045f,
    focusable: Boolean = true,   // false: purely informational, the remote skips over it
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(when { pressed -> 0.97f; focused -> focusScale; else -> 1f }, tween(150), label = "scale")
    val press = remember { Press() }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(width, height)
            .graphicsLayer { scaleX = scale; scaleY = scale }   // no shadow: it would show through the glass
            .drawBehind {   // soft halo just outside the ring
                if (focused && ring) {
                    val h = 4.dp.toPx()
                    drawRoundRect(Halo, Offset(-h / 2, -h / 2), Size(size.width + h, size.height + h), CornerRadius(corner.toPx() + h / 2), style = Stroke(h))
                }
            }
            .drawWithContent {
                drawContent()
                val w = if (focused && ring) 1.5.dp.toPx() else 0.5.dp.toPx()
                drawRoundRect(if (focused && ring) Color.White else GlassEdge, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(corner.toPx() - w / 2), style = Stroke(w))
            }
            .clip(shape)
            .background(if (focused && focusBackground != null) focusBackground else background)
            .drawBehind { if (sheen) drawRect(Brush.verticalGradient(0f to Sheen, 0.45f to Color.Transparent)) }
            .then(
                if (focusable) Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { press.handle(it.nativeKeyEvent, { p -> pressed = p }, onClick, onLongClick) }
                    .focusable()
                else Modifier
            ),
    ) { content(focused) }
}

/** OK/Enter on a tile: click on release; holding it (or the Menu key) opens the options instead. */
internal class Press {
    private var down = false
    private var longFired = false

    fun handle(k: KeyEvent, setPressed: (Boolean) -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)?): Boolean {
        if (k.keyCode == KeyEvent.KEYCODE_MENU && onLongClick != null) {
            if (k.action == KeyEvent.ACTION_UP) onLongClick()
            return true
        }
        if (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER && k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false
        if (k.action == KeyEvent.ACTION_DOWN) {
            if (k.repeatCount == 0) {
                down = true; longFired = false; setPressed(true)
            } else if (down && !longFired && onLongClick != null && (k.isLongPress || k.repeatCount >= 8)) {
                longFired = true; setPressed(false); onLongClick()
            }
        } else if (k.action == KeyEvent.ACTION_UP) {
            setPressed(false)
            if (down && !longFired) onClick()   // only if the press started here, not in the previous screen
            down = false
        }
        return true
    }
}

/** Options as a full-width glass band with pill buttons; the focused one turns white. Back closes it. */
@Composable
internal fun OptionsBand(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val firstFocus = remember { FocusRequester() }
        Column(Modifier.fillMaxWidth().background(Band).padding(horizontal = 48.dp, vertical = 20.dp)) {
            BasicText(title, style = text(20f, FontWeight.Light), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, (label, action) ->
                    GlassBox(
                        150.dp, 32.dp, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier, { onDismiss(); action() },
                        corner = 16.dp, focusBackground = SolidColor(Color.White), sheen = false, ring = false, focusScale = 1.04f,
                    ) { focused ->
                        BasicText(label, Modifier.align(Alignment.Center), style = text(11.5f, color = if (focused) Color(0xFF0B1636) else Text), maxLines = 1)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
}
