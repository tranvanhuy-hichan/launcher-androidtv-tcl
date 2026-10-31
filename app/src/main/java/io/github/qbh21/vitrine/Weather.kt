package io.github.qbh21.vitrine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class City(val name: String, val lat: Double, val lon: Double)

class DayForecast(val code: Int, val lo: Int, val hi: Int, val rain: Int)

class WeatherData(
    val city: String, val temp: Int, val feel: Int, val code: Int, val humidity: Int, val wind: Int, val uv: Int,
    val hour: Int, val hours: List<Pair<Int, Int>>, val days: List<DayForecast>, val fetchedAt: Long,
)

/**
 * Weather from Open-Meteo (free, no account or key). The launcher's only network call: one small request
 * at most every 30 minutes while it's on screen, cached on the TV otherwise.
 */
object Weather {
    val CITIES = listOf(
        City("Hà Nội", 21.0285, 105.8542), City("TP. Hồ Chí Minh", 10.8231, 106.6297), City("Đà Nẵng", 16.0544, 108.2022),
        City("Huế", 16.4637, 107.5909), City("Cần Thơ", 10.0452, 105.7469), City("Hải Phòng", 20.8449, 106.6881),
        City("Nha Trang", 12.2388, 109.1967),
    )
    private const val STALE_MS = 30 * 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun cityIndex(c: Context) = prefs(c).getInt("city", 0).coerceIn(0, CITIES.lastIndex)

    /** Cycling through the built-in list clears a city chosen from the phone. */
    fun nextCity(c: Context): Int = ((cityIndex(c) + 1) % CITIES.size).also { prefs(c).edit().putInt("city", it).remove("city_name").apply() }

    /** The city in use: one chosen from the phone, else the built-in one. */
    fun current(c: Context): City {
        val p = prefs(c)
        val name = p.getString("city_name", null)
        return if (name != null) City(name, p.getFloat("city_lat", 0f).toDouble(), p.getFloat("city_lon", 0f).toDouble()) else CITIES[cityIndex(c)]
    }

    fun setCustom(c: Context, name: String, lat: Double, lon: Double) {
        prefs(c).edit().putString("city_name", name).putFloat("city_lat", lat.toFloat()).putFloat("city_lon", lon.toFloat()).apply()
    }

    private fun key(c: Context) = current(c).let { "${it.name}|${it.lat}|${it.lon}" }

    fun cached(c: Context): WeatherData? {
        val p = prefs(c)
        val json = p.getString("wx_json", null) ?: return null
        if (p.getString("wx_key", null) != key(c)) return null
        return runCatching { parse(json, current(c).name, p.getLong("wx_time", 0)) }.getOrNull()
    }

    /** Fresh data if the cache is older than 30 minutes (or [force]); otherwise the cache. Null when there's nothing at all. */
    suspend fun load(c: Context, force: Boolean = false): WeatherData? {
        val old = cached(c)
        if (old != null && !force && System.currentTimeMillis() - old.fetchedAt < STALE_MS) return old
        val city = current(c)
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&hourly=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max" +
                    "&timezone=Asia%2FBangkok&forecast_days=5"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val now = System.currentTimeMillis()
                val data = parse(body, city.name, now)
                prefs(c).edit().putString("wx_json", body).putString("wx_key", key(c)).putLong("wx_time", now).apply()
                data
            }.getOrNull() ?: old
        }
    }

    private fun parse(json: String, city: String, time: Long): WeatherData {
        val j = JSONObject(json)
        val cur = j.getJSONObject("current")
        val hour = cur.getString("time").substring(11, 13).toInt()
        val hCodes = j.getJSONObject("hourly").getJSONArray("weather_code")
        val hTemps = j.getJSONObject("hourly").getJSONArray("temperature_2m")
        val hours = (0 until 5).map { i -> hCodes.getInt(hour + i) to Math.round(hTemps.getDouble(hour + i)).toInt() }
        val d = j.getJSONObject("daily")
        val days = (0 until d.getJSONArray("time").length()).map { i ->
            DayForecast(
                d.getJSONArray("weather_code").getInt(i), Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0),
            )
        }
        return WeatherData(
            city, Math.round(cur.getDouble("temperature_2m")).toInt(), Math.round(cur.getDouble("apparent_temperature")).toInt(),
            cur.getInt("weather_code"), cur.getInt("relative_humidity_2m"), Math.round(cur.getDouble("wind_speed_10m")).toInt(),
            Math.round(d.getJSONArray("uv_index_max").getDouble(0)).toInt(), hour, hours, days, time,
        )
    }

    fun label(code: Int) = when (code) {
        0 -> "Trời quang"; 1 -> "Ít mây"; 2 -> "Có mây"; 3 -> "Nhiều mây"; 45, 48 -> "Sương mù"
        51, 53, 55, 56, 57 -> "Mưa phùn"; 61, 63, 65, 66, 67 -> "Mưa"; 71, 73, 75, 77, 85, 86 -> "Tuyết"
        80, 81, 82 -> "Mưa rào"; 95, 96, 99 -> "Dông"; else -> "Có mây"
    }

    /** 0 sun, 1 partly cloudy, 2 cloud, 3 rain. */
    fun kind(code: Int) = when (code) {
        0 -> 0; 1, 2 -> 1; 3, 45, 48, 71, 73, 75, 77, 85, 86 -> 2; else -> 3
    }
}
