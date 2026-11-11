package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test

class StartupWeatherStateTest {
    @Test fun startupWaitsForActiveConsentAndEnabledSetting() {
        val state = StartupWeatherState()
        assertNull(state.begin(true, true)); assertFalse(state.attempted)
        state.activate(true)
        assertNull(state.begin(false, true)); assertNull(state.begin(true, false))
        assertFalse(state.attempted)
        assertNotNull(state.begin(true, true)); assertTrue(state.attempted)
    }
    @Test fun leavingInvalidatesResponseAndDoesNotRepeatOnReturn() {
        val state = StartupWeatherState(); state.activate(true)
        val token = state.begin(true, true)!!
        assertTrue(state.weather.accepts(token))
        state.activate(false); assertFalse(state.weather.accepts(token))
        state.activate(true); assertNull(state.begin(true, true))
    }
    @Test fun activityRecreationDoesNotStartAnotherRequest() {
        val state = StartupWeatherState(true); state.activate(true)
        assertNull(state.begin(true, true))
    }
    @Test fun detailsOnlyReuseFreshConsentedWeatherAndInvalidateOlderRequests() {
        val now = 1_000_000L
        val reading = LiveWeatherReading(WeatherPlaces.DEFAULT, 5.0, 0, now)
        val state = LiveWeatherState()
        state.restore(reading, now); assertNull(state.reading)
        state.consent(true)
        state.restore(reading, now + LiveWeatherReading.TTL + 1); assertNull(state.reading)
        state.activate(true)
        val oldRequest = state.begin()!!
        state.restore(reading, now)
        assertEquals(reading, state.fresh(now))
        assertFalse(state.accepts(oldRequest)); assertFalse(state.loading)
    }
}
