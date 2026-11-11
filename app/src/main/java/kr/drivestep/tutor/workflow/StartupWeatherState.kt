package kr.drivestep.tutor.workflow

/** A fresh app launch gets one foreground attempt; leaving the screen invalidates its callbacks. */
class StartupWeatherState(restoredAttempt: Boolean = false) {
    val weather = LiveWeatherState()
    var attempted = restoredAttempt; private set
    fun activate(active: Boolean) = weather.activate(active)
    fun begin(enabled: Boolean, consent: Boolean): Long? {
        weather.consent(consent)
        if (!enabled || attempted) return null
        val token = weather.begin() ?: return null
        attempted = true
        return token
    }
}
