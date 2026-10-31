package io.github.qbh21.vitrine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A yearly event the user adds from the phone: a birthday on a solar date, or a death anniversary (giỗ) on a
 * lunar date. Kept on the TV only.
 */
class Event(val id: String, val name: String, val lunar: Boolean, val day: Int, val month: Int)

object Events {
    private const val KEY = "events"
    private const val MAX = 60

    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun load(c: Context): List<Event> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Event(o.getString("id"), o.getString("name"), o.getBoolean("lunar"), o.getInt("day"), o.getInt("month"))
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Event>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lunar", it.lunar).put("day", it.day).put("month", it.month)) }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }

    /** Adds an event; false if the input isn't valid (empty name, impossible day or month, too many events). */
    fun add(c: Context, name: String?, lunar: Boolean, day: Int?, month: Int?): Boolean {
        val n = name?.trim().orEmpty().take(40)
        if (n.isEmpty() || day == null || month == null || month !in 1..12 || day !in 1..(if (lunar) 30 else 31)) return false
        val list = load(c)
        if (list.size >= MAX) return false
        save(c, list + Event(UUID.randomUUID().toString().take(8), n, lunar, day, month))
        return true
    }

    fun remove(c: Context, id: String) = save(c, load(c).filter { it.id != id })

    /** Names of the events that fall on solar date [d]/[m] (whose lunar date is [l]). */
    fun on(list: List<Event>, d: Int, m: Int, l: Lunar.Date): List<String> =
        list.filter { if (it.lunar) !l.leap && it.day == l.day && it.month == l.month else it.day == d && it.month == m }.map { it.name }
}
