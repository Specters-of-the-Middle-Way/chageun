package kr.drivestep.tutor.workflow

/** Session-only opt-in. Called on a fresh response, never on render or foreground return. */
class WeatherAutoAnnouncement {
    var enabled = false
    private val requested = linkedSetOf<String>()

    fun request(reading: LiveWeatherReading?, now: Long, active: Boolean, consent: Boolean,
        speak: (String) -> Boolean): Boolean {
        if (!enabled || !active || !consent || reading == null || !reading.validAt(now)) return false
        val place = reading.place.approximate()
        val key = "${place.latitude}:${place.longitude}:${reading.modelTime}"
        if (key in requested) return false
        if (!speak("${reading.place.name}, ${reading.condition}, ${reading.temperatureC}도. ${reading.advice}")) return false
        requested += key
        if (requested.size > 32) requested.remove(requested.first())
        return true
    }
}
