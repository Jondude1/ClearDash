package com.jondude1.cleardash.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Is the sun down (civil dusk to dawn) at this place and time? NOAA's simplified solar position. */
object Sun {
    fun isDark(lat: Double?, lon: Double?, now: Long = System.currentTimeMillis()): Boolean {
        if (lat == null || lon == null) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return h < 7 || h >= 18
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val hourUtc = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val g = 2 * Math.PI / 365.0 * (day - 1 + (hourUtc - 12) / 24.0)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val solarMin = hourUtc * 60 + eqTime + 4 * lon
        val ha = Math.toRadians(solarMin / 4 - 180)
        val latR = Math.toRadians(lat)
        val cosZen = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(ha)
        val elevation = 90 - Math.toDegrees(acos(cosZen.coerceIn(-1.0, 1.0)))
        return elevation < -2.0 // a little past sunset: headlights territory
    }

}
