package kr.drivestep.tutor.workflow

import android.content.Context

/** Keep automatic startup I/O out of unrelated device tests, restoring every user choice. */
internal class WeatherTestSettings(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("weather_preferences_v1", Context.MODE_PRIVATE)
    private val original = preferences.all.toMap()
    init { preferences.edit().putBoolean("startup", false).putBoolean("consent", false).putBoolean("auto_read", false).commit() }
    override fun close() {
        preferences.edit().apply {
            clear()
            original.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
            } }
        }.commit()
    }
}
