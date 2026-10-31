package io.github.qbh21.vitrine

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * An app's icon. [fullBleed] icons fill a square edge to edge. Older icons carry their own shape;
 * [background] is the colour of that shape (if it has a solid one), so a square of the same colour
 * behind it makes the two read as one.
 */
class AppIcon(val bitmap: ImageBitmap, val fullBleed: Boolean, val background: Color? = null)

/** An app the launcher can open. Its icon loads only while its tile is on screen, to keep memory low. */
class AppEntry(val label: String, val pkg: String, val launch: Intent, private val info: ActivityInfo) {
    /** System apps can't be uninstalled from the launcher (only disabled from App info). */
    val isSystem: Boolean get() = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    /** The wide banner an Android TV app ships for the home screen (usually 16:9), drawn at [w] x [h], or null if it has none. */
    fun banner(context: Context, w: Int, h: Int): ImageBitmap? {
        val pm = context.packageManager
        val drawable = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm) ?: return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }

    /** The app's own icon (the same one it has on phones), rendered as a [px]-sized square. */
    fun icon(context: Context, px: Int): AppIcon {
        val drawable = info.loadIcon(context.packageManager)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            // Draw both layers unmasked (their outer quarter is normally cut off), so the tile's rounded corners shape it.
            val bleed = px / 4
            for (layer in listOf(drawable.background, drawable.foreground)) {
                layer?.setBounds(-bleed, -bleed, px + bleed, px + bleed)
                layer?.draw(canvas)
            }
            return AppIcon(bitmap.asImageBitmap(), fullBleed = true)
        }
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        // An older icon that's an opaque square (e.g. YouTube's) can fill the tile too; a shaped one gets padding.
        val opaqueSquare = android.graphics.Color.alpha(bitmap.getPixel(px / 20, px / 20)) == 255
        val shapeEdge = bitmap.getPixel(px / 2, px / 12)   // just inside the top of a rounded or round icon
        val background = if (android.graphics.Color.alpha(shapeEdge) == 255) Color(shapeEdge) else null
        return AppIcon(bitmap.asImageBitmap(), fullBleed = opaqueSquare, background = background)
    }
}

/** Apps whose own icon or name doesn't suit the dashboard get a cleaner one here. */
object Overrides {
    val labels = mapOf("com.mediatek.dmp.mediaplayer" to "Media Player")
    val glyphs = mapOf(
        "com.mediatek.dmp.mediaplayer" to R.drawable.pk_media,
        "com.mediatek.wwtv.tvcenter" to R.drawable.pk_tv,
        "com.android.documentsui" to R.drawable.pk_folder,
        "com.android.tv.settings" to R.drawable.pk_gear,
        "com.android.settings" to R.drawable.pk_gear,
        "com.cvte.tv.cvte_help" to R.drawable.pk_help,
    )
}

object Apps {
    /**
     * Apps pinned to Start, in order. The first six fill the layout's app slots (the first gets the big
     * tile); any more go in an extra column on the right. Long-press options pin and unpin.
     */
    private val DEFAULT_PINNED = listOf("com.google.android.youtube.tv", "vn.vtv.vtvgotv", "com.android.vending", "com.android.tv.settings")

    fun pinned(context: Context): List<String> =
        prefs(context).getString("dock2", null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_PINNED

    fun savePinned(context: Context, pinned: List<String>) =
        prefs(context).edit().putString("dock2", pinned.joinToString(",")).apply()

    /** The last few apps opened from the launcher, newest first, as (package, time). Kept on the TV only. */
    fun recents(context: Context): List<Pair<String, Long>> =
        prefs(context).getString("recents", "")!!.split(',').mapNotNull { entry ->
            val (pkg, time) = entry.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
            time.toLongOrNull()?.let { pkg to it }
        }

    fun recordLaunch(context: Context, pkg: String) {
        val list = listOf(pkg to System.currentTimeMillis()) + recents(context).filter { it.first != pkg }
        prefs(context).edit().putString("recents", list.take(5).joinToString(",") { "${it.first}|${it.second}" }).apply()
    }

    /** The Notes tile's text, kept on the TV only. */
    fun note(context: Context): String = prefs(context).getString("note", "") ?: ""

    fun saveNote(context: Context, note: String) = prefs(context).edit().putString("note", note).apply()

    /** The chosen wallpaper colourway (a Backdrop name). The Xbox-era choice under "backdrop" isn't carried over. */
    fun backdrop(context: Context): String? = prefs(context).getString("backdrop2", null)

    fun saveBackdrop(context: Context, name: String) = prefs(context).edit().putString("backdrop2", name).apply()

    /** Which home-screen widgets are shown (time, weather, system). All on by default. */
    fun widgetOn(context: Context, id: String) = prefs(context).getBoolean("w_$id", true)
    fun setWidget(context: Context, id: String, on: Boolean) = prefs(context).edit().putBoolean("w_$id", on).apply()
    fun widgets(context: Context): Set<String> = listOf("time", "weather", "system").filter { widgetOn(context, it) }.toSet()

    /** How opaque the widget cards are, in percent (100 = solid, 10 = nearly see-through). */
    fun panelAlpha(context: Context): Int = prefs(context).getInt("panel_alpha", 46)
    fun savePanelAlpha(context: Context, value: Int) = prefs(context).edit().putInt("panel_alpha", value).apply()

    /** How much a photo wallpaper is darkened, in percent. */
    fun dim(context: Context): Int = prefs(context).getInt("dim", 40)
    fun saveDim(context: Context, value: Int) = prefs(context).edit().putInt("dim", value).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Every launchable app: TV apps (LEANBACK_LAUNCHER), phone-style apps (LAUNCHER), plus Settings. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val apps = LinkedHashMap<String, AppEntry>()
        fun add(query: Intent, label: String? = null) {
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName || ai.packageName in apps) continue
                val launch = Intent(query).setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val name = label ?: Overrides.labels[ai.packageName] ?: ri.loadLabel(pm).toString()
                apps[ai.packageName] = AppEntry(name, ai.packageName, launch, ai)
            }
        }
        add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        add(Intent(Settings.ACTION_SETTINGS), label = "Cài đặt")
        return apps.values.sortedBy { it.label.lowercase() }
    }
}
