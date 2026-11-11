package kr.drivestep.tutor.workflow

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** No credentials, profile, training history or exact GPS coordinates are sent. */
class OpenMeteoClient {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; connection?.disconnect() }
    fun read(place: WeatherPlace): LiveWeatherReading {
        require(place.valid)
        check(!cancelled)
        val point = place.approximate()
        val query = String.format(Locale.US, "latitude=%.2f&longitude=%.2f", point.latitude, point.longitude)
        val request = URL("https://api.open-meteo.com/v1/forecast?$query&current=temperature_2m,weather_code&timeformat=unixtime&timezone=UTC")
            .openConnection() as HttpURLConnection
        connection = request
        try {
            check(!cancelled)
            request.connectTimeout = 8_000; request.readTimeout = 8_000
            request.instanceFollowRedirects = false
            check(request.responseCode == 200) { "Weather request failed" }
            val body = request.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(32_769)
                var count = 0
                while (count < buffer.size) {
                    val n = reader.read(buffer, count, buffer.size - count)
                    if (n == -1) break
                    count += n
                }
                check(count <= 32_768) { "Weather response too large" }
                String(buffer, 0, count)
            }
            check(!cancelled)
            return parse(body, point)
        } finally { request.disconnect(); connection = null }
    }
    companion object {
        fun parse(body: String, place: WeatherPlace): LiveWeatherReading {
            val json = JSONObject(body)
            val units = json.getJSONObject("current_units")
            require(units.getString("temperature_2m") == "°C" && units.getString("weather_code") == "wmo code" && units.getString("time") == "unixtime")
            val current = json.getJSONObject("current")
            val time = current.getDouble("time")
            val code = current.getDouble("weather_code")
            require(time.isFinite() && time > 0 && time < Long.MAX_VALUE / 1000 && time % 1.0 == 0.0)
            require(code.isFinite() && code % 1.0 == 0.0 && code in 0.0..99.0)
            return LiveWeatherReading(place, current.getDouble("temperature_2m"), code.toInt(), time.toLong() * 1000)
        }
    }
}
