package com.logan.spellmini.signals

import com.logan.spellmini.data.Settings
import com.logan.spellmini.net.Web
import com.logan.spellmini.net.arr
import com.logan.spellmini.net.obj
import com.logan.spellmini.net.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/** A city found by name, with the coordinates the forecast needs. */
data class City(val name: String, val lat: Double, val lon: Double)

/**
 * Tomorrow's weather from Open-Meteo: free, no key, answers from China. The numbers are turned into a few sentences
 * here; whether any of it matters to the user's plans is for JEV and the chat agent to say.
 */
object Weather {
    private fun number(o: JsonObject?, key: String, index: Int): Double? = (o?.arr(key)?.getOrNull(index) as? JsonPrimitive)?.doubleOrNull

    suspend fun findCity(name: String): City {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&name=" + URLEncoder.encode(name.trim(), "UTF-8")
        val first = (Json.parseToJsonElement(Web.readText(url).first) as? JsonObject)?.arr("results")?.firstOrNull() as? JsonObject
            ?: throw IOException("没找到「$name」这个城市，换个写法试试（如「北京」「Shanghai」）")
        val lat = (first["latitude"] as? JsonPrimitive)?.doubleOrNull ?: throw IOException("城市数据不完整")
        val lon = (first["longitude"] as? JsonPrimitive)?.doubleOrNull ?: throw IOException("城市数据不完整")
        return City(listOfNotNull(first.str("name"), first.str("admin1")).distinct().joinToString(" · "), lat, lon)
    }

    fun tomorrowStart(): Long = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun tomorrow(settings: Settings): String {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${settings.cityLat}&longitude=${settings.cityLon}" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,weather_code" +
            "&hourly=precipitation_probability&forecast_days=2&timezone=auto"
        val root = Json.parseToJsonElement(Web.readText(url).first) as? JsonObject ?: throw IOException("天气接口返回的不是 JSON")
        val daily = root.obj("daily")
        val high = number(daily, "temperature_2m_max", 1); val low = number(daily, "temperature_2m_min", 1)
        val rain = number(daily, "precipitation_probability_max", 1); val amount = number(daily, "precipitation_sum", 1)
        val wind = number(daily, "wind_speed_10m_max", 1)
        // Hours 24..47 are tomorrow; name the stretches where rain is likely, because "70%" alone says nothing about his commute.
        val hourly = root.obj("hourly")?.arr("precipitation_probability").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        val wetHours = (24 until minOf(48, hourly.size)).filter { hourly[it] >= 50 }.map { it - 24 }
        val wet = if (wetHours.isEmpty()) "全天降水概率都不到 50%" else "降水概率过半的时段：" + ranges(wetHours)
        return listOfNotNull(
            if (high != null && low != null) "${low.toInt()}–${high.toInt()}℃" else null,
            rain?.let { "最高降水概率 ${it.toInt()}%" }, amount?.takeIf { it >= 0.5 }?.let { "预计降水 ${"%.1f".format(it)} mm" },
            wet, wind?.takeIf { it >= 30 }?.let { "最大风速 ${it.toInt()} km/h" },
        ).joinToString("；")
    }

    private fun ranges(hours: List<Int>): String {
        val parts = mutableListOf<String>()
        var start = hours.first(); var prev = start
        for (h in hours.drop(1) + listOf(-1)) {
            if (h != prev + 1) { parts += if (start == prev) "${start} 点" else "${start}–${prev + 1} 点"; start = h }
            prev = h
        }
        return parts.joinToString("、")
    }
}
