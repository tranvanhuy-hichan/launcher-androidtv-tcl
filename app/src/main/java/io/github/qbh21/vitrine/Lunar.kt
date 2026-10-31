package io.github.qbh21.vitrine

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Vietnamese lunar calendar (UTC+7), after Hồ Ngọc Đức's astronomical algorithm.
 * Checked against Tết 2024, 2025 and 2026, Trung thu 2026 and the leap year 2023.
 */
object Lunar {
    class Date(val day: Int, val month: Int, val year: Int, val leap: Boolean)

    private const val TZ = 7.0
    private const val SYNODIC = 29.530588853
    private const val EPOCH = 2415021.076998695

    private fun jd(dd: Int, mm: Int, yy: Int): Int {
        val a = (14 - mm) / 12
        val y = yy + 4800 - a
        val m = mm + 12 * a - 3
        return dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }

    private fun newMoon(k: Int): Double {
        val kk = k.toDouble()
        val t = kk / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val r = PI / 180
        var j = 2415020.75933 + 29.53058868 * kk + 0.0001178 * t2 - 0.000000155 * t3
        j += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * r)
        val m = 359.2242 + 29.10535608 * kk - 0.0000333 * t2 - 0.00000347 * t3
        val p = 306.0253 + 385.81691806 * kk + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * kk - 0.0016528 * t2 - 0.00000239 * t3
        var c = (0.1734 - 0.000393 * t) * sin(m * r) + 0.0021 * sin(2 * r * m)
        c -= 0.4068 * sin(p * r); c += 0.0161 * sin(r * 2 * p); c -= 0.0004 * sin(r * 3 * p)
        c += 0.0104 * sin(r * 2 * f); c -= 0.0051 * sin(r * (m + p)); c -= 0.0074 * sin(r * (m - p))
        c += 0.0004 * sin(r * (2 * f + m)); c -= 0.0004 * sin(r * (2 * f - m)); c -= 0.0006 * sin(r * (2 * f + p))
        c += 0.0010 * sin(r * (2 * f - p)); c += 0.0005 * sin(r * (2 * p + m))
        val dt = if (t < -11) 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        else -0.000278 + 0.000265 * t + 0.000262 * t2
        return j + c - dt
    }

    private fun sunLongitude(j: Double): Double {
        val t = (j - 2451545.0) / 36525
        val t2 = t * t
        val r = PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var d = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(r * m)
        d += (0.019993 - 0.000101 * t) * sin(r * 2 * m) + 0.000290 * sin(r * 3 * m)
        var l = (l0 + d) * r
        l -= PI * 2 * floor(l / (PI * 2))
        return l
    }

    private fun sunSector(day: Int) = floor(sunLongitude(day - 0.5 - TZ / 24) / PI * 6).toInt()
    private fun newMoonDay(k: Int) = floor(newMoon(k) + 0.5 + TZ / 24).toInt()

    private fun month11(y: Int): Int {
        val off = jd(31, 12, y) - 2415021
        val k = floor(off / SYNODIC).toInt()
        var nm = newMoonDay(k)
        if (sunSector(nm) >= 9) nm = newMoonDay(k - 1)
        return nm
    }

    private fun leapOffset(a11: Int): Int {
        val k = floor((a11 - EPOCH) / SYNODIC + 0.5).toInt()
        var last: Int
        var i = 1
        var arc = sunSector(newMoonDay(k + i))
        do {
            last = arc
            i++
            arc = sunSector(newMoonDay(k + i))
        } while (arc != last && i < 14)
        return i - 1
    }

    fun convert(dd: Int, mm: Int, yy: Int): Date {
        val d = jd(dd, mm, yy)
        val k = floor((d - EPOCH) / SYNODIC).toInt()
        var monthStart = newMoonDay(k + 1)
        if (monthStart > d) monthStart = newMoonDay(k)
        var a11 = month11(yy)
        var b11 = a11
        var ly: Int
        if (a11 >= monthStart) { ly = yy; a11 = month11(yy - 1) } else { ly = yy + 1; b11 = month11(yy + 1) }
        val ld = d - monthStart + 1
        val diff = (monthStart - a11) / 29
        var leap = false
        var lm = diff + 11
        if (b11 - a11 > 365) {
            val lo = leapOffset(a11)
            if (diff >= lo) { lm = diff + 10; if (diff == lo) leap = true }
        }
        if (lm > 12) lm -= 12
        if (lm >= 11 && diff < 4) ly -= 1
        return Date(ld, lm, ly, leap)
    }

    private val CAN = listOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")
    private val CHI = listOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")

    fun yearName(y: Int) = "${CAN[(y + 6) % 10]} ${CHI[(y + 8) % 12]}"

    /** The small label under a solar day: the lunar day, or day/month on the 1st. */
    fun label(d: Date) = if (d.day == 1) "1/${d.month}" else d.day.toString()
}
