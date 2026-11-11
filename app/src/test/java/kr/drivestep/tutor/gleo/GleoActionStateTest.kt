package kr.drivestep.tutor.gleo

import kr.drivestep.tutor.connected.*
import kr.drivestep.tutor.workflow.*
import org.junit.Assert.*
import org.junit.Test

class GleoActionStateTest {
    @Test fun acceptsGleoPackageQualifiedActionsAndGuideExamplesOnly() {
        for (action in GleoAction.entries) {
            assertEquals(action, GleoAction.from(action.intentAction))
            assertEquals(action, GleoAction.from(action.name))
            assertNull(GleoAction.from("another.app.${action.name}"))
        }
        assertNull(GleoAction.from(null))
    }
    @Test fun requestNeedsForegroundAndParkedAndOnlyDispatchesOnce() {
        val state = GleoRequestState()
        val token = state.begin(GleoRequest(GleoAction.CHECK_DEPARTURE_READINESS), 100)
        assertFalse(state.dispatch(token, 101, false, true))
        assertFalse(state.dispatch(token, 101, true, false))
        assertTrue(state.dispatch(token, 101, true, true))
        assertFalse(state.dispatch(token, 102, true, true))
    }
    @Test fun timeoutAndClockReversalDoNotAllowLateAction() {
        val state = GleoRequestState()
        val token = state.begin(GleoRequest(GleoAction.GET_WEATHER_DRIVING_ADVICE), 100)
        assertFalse(state.current(token, 99))
        assertTrue(state.current(token, 6099))
        assertFalse(state.current(token, 6100))
    }
    @Test fun replacementAndEndRejectOldCallbacks() {
        val state = GleoRequestState()
        val old = state.begin(GleoRequest(GleoAction.CHECK_DEPARTURE_READINESS), 100)
        val next = state.begin(GleoRequest(GleoAction.GET_WEATHER_DRIVING_ADVICE), 101)
        assertFalse(state.current(old, 102)); assertTrue(state.current(next, 102))
        state.end(); assertFalse(state.current(next, 103))
    }
    private fun vehicle() = ReadinessSnapshot(emptyMap(), Observed(false, InputValidity.VALID, 1000),
        Observed(emptyList(), InputValidity.VALID, 1000), false)
    @Test fun incompletePreparationNeverAuthorizesDeparture() {
        val reply = GleoActionPolicy.preparation(3, 0, false, true, vehicle(), 1200)
        assertEquals(false, reply.data["departureAuthorized"])
        assertEquals(false, reply.data["practiceUnlocked"])
        assertEquals("미체결", reply.data["belt"])
    }
    @Test fun evenCompletedChecklistIsNotDeparturePermission() {
        val reply = GleoActionPolicy.preparation(9, 7, true, true, vehicle(), 1200)
        assertEquals(false, reply.data["departureAuthorized"])
        assertEquals(true, reply.data["practiceUnlocked"])
    }
    @Test fun absentConsentAndStaleObservationsAreUnknown() {
        assertEquals("미확인", GleoActionPolicy.preparation(0, 0, false, false, vehicle(), 1200).data["belt"])
        assertEquals("미확인", GleoActionPolicy.preparation(0, 0, false, true, vehicle(), 5000).data["belt"])
    }
    @Test fun parkingAsksForManualPreparation() {
        assertEquals("asking", GleoActionPolicy.parkingReady(false)?.status)
        assertNull(GleoActionPolicy.parkingReady(true))
    }
    @Test fun weatherDoesNotInventMissingOrOldConditions() {
        assertEquals("fail", GleoActionPolicy.weather(null, 100_000).status)
        val old = LiveWeatherReading(WeatherPlaces.DEFAULT, 10.0, 61, 100_000)
        assertEquals("fail", GleoActionPolicy.weather(old, 100_000 + LiveWeatherReading.TTL + 1).status)
    }
    @Test fun weatherNamesFallbackAndOmitsCoordinates() {
        val rain = LiveWeatherReading(WeatherPlaces.DEFAULT, 10.0, 61, 100_000)
        val reply = GleoActionPolicy.weather(rain, 100_100)
        assertEquals("success", reply.status); assertEquals(true, reply.data["fallbackArea"])
        assertTrue(reply.display.contains("복정동")); assertTrue(reply.display.contains("와이퍼"))
        assertFalse(reply.data.keys.any { it.contains("latitude") || it.contains("longitude") })
    }
}
