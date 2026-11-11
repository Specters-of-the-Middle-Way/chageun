package kr.drivestep.tutor.workflow

import android.content.Context

/** Weather choices are separate from learning-record consent; coordinates are never persisted. */
class WeatherPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("weather_preferences_v1", Context.MODE_PRIVATE)
    var consent: Boolean
        get() = preferences.getBoolean("consent", true)
        set(value) { preferences.edit().putBoolean("consent", value).apply() }
    var startup: Boolean
        get() = preferences.getBoolean("startup", true)
        set(value) { preferences.edit().putBoolean("startup", value).apply() }
    var autoRead: Boolean
        get() = preferences.getBoolean("auto_read", true)
        set(value) { preferences.edit().putBoolean("auto_read", value).apply() }
}
