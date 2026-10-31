package io.github.qbh21.vitrine

import java.util.Calendar

/**
 * Vietnamese public holidays and well-known occasions: fixed solar dates, fixed lunar dates, and Giao thừa
 * (the last night of the lunar year). Easter-style movable dates are left out on purpose.
 */
object Holidays {
    private val SOLAR = mapOf(
        "1/1" to "Tết Dương lịch", "14/2" to "Valentine", "8/3" to "Quốc tế Phụ nữ", "30/4" to "Giải phóng miền Nam",
        "1/5" to "Quốc tế Lao động", "1/6" to "Quốc tế Thiếu nhi", "27/7" to "Thương binh Liệt sĩ", "2/9" to "Quốc khánh",
        "10/10" to "Giải phóng Thủ đô", "20/10" to "Phụ nữ VN", "20/11" to "Nhà giáo VN", "22/12" to "Quân đội ND",
        "24/12" to "Đêm Giáng sinh", "25/12" to "Giáng sinh",
    )
    private val LUNAR = mapOf(
        "1/1" to "Tết Nguyên Đán", "2/1" to "Mùng 2 Tết", "3/1" to "Mùng 3 Tết", "15/1" to "Rằm tháng Giêng", "10/3" to "Giỗ Tổ Hùng Vương",
        "15/4" to "Lễ Phật Đản", "5/5" to "Tết Đoan Ngọ", "15/7" to "Lễ Vu Lan", "15/8" to "Tết Trung Thu", "23/12" to "Ông Công Ông Táo",
    )

    /** The occasion on this solar date (lunar date [l] is passed in to save a conversion), or null. */
    fun of(d: Int, m: Int, y: Int, l: Lunar.Date): String? {
        val names = mutableListOf<String>()
        SOLAR["$d/$m"]?.let { names += it }
        if (!l.leap) LUNAR["${l.day}/${l.month}"]?.let { names += it }
        if (l.month == 12 && l.day >= 29) {   // the day before lunar new year is Giao thừa
            val next = Calendar.getInstance().apply { clear(); set(y, m - 1, d); add(Calendar.DAY_OF_MONTH, 1) }
            val n = Lunar.convert(next.get(Calendar.DAY_OF_MONTH), next.get(Calendar.MONTH) + 1, next.get(Calendar.YEAR))
            if (n.day == 1 && n.month == 1) names += "Giao thừa"
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Today's occasion or the next one within 60 days: (days from today, text, true if it is one of the user's own
     * events). The user's events come first in the text.
     */
    fun upcoming(now: Long, events: List<Event> = emptyList()): Triple<Int, String, Boolean>? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        for (ahead in 0..60) {
            val d = cal.get(Calendar.DAY_OF_MONTH); val m = cal.get(Calendar.MONTH) + 1; val y = cal.get(Calendar.YEAR)
            val l = Lunar.convert(d, m, y)
            val mine = Events.on(events, d, m, l)
            val public = of(d, m, y, l)
            if (mine.isNotEmpty() || public != null) return Triple(ahead, (mine + listOfNotNull(public)).joinToString(" · "), mine.isNotEmpty())
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }
}
