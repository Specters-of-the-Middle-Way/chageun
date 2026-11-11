package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test

class WorkflowContractsTest {
    private val now = 100_000L
    @Test fun unavailableWeatherDoesNotBlockPreparationOrInventConditions() {
        val session = WeatherSession("app")
        session.accept(UnavailableWeatherProvider().read("서울", "app", now), now)
        assertEquals(InputValidity.UNSUPPORTED, session.result!!.validity)
        assertNull(session.result!!.value)
        assertNull(session.advice(now))
    }
    @Test fun oldFutureAndDifferentSessionWeatherCannotBeSpoken() {
        val session = WeatherSession("app", WeatherPolicy(ttlMillis = 100))
        val sample = SimulatedWeatherProvider(WeatherCondition.RAIN).read("가상", "app", now)
        for (value in listOf(sample.copy(observedAt = now - 101), sample.copy(observedAt = now + 1),
            sample.copy(observedAt = null), sample.copy(contextId = "previous"))) {
            session.accept(value, now); assertNull(session.advice(now)); assertNull(session.result!!.value)
        }
    }
    @Test fun weatherIsOncePerSessionOnlyAfterActualSpeechAcknowledgement() {
        val session = WeatherSession("app")
        session.accept(SimulatedWeatherProvider(WeatherCondition.SNOW).read("가상", "app", now), now)
        assertNotNull(session.advice(now))
        assertNotNull(session.advice(now)) // An output that never started is retryable.
        session.markSpoken()
        assertNull(session.advice(now))
        session.accept(SimulatedWeatherProvider(WeatherCondition.SNOW).read("가상", "app", now + 1), now + 1)
        assertNull(session.advice(now + 1))
    }
    @Test fun warningMuteRouteChangeAndExpiredAdviceNeverQueueOldSpeech() {
        val arbiter = AdviceArbiter()
        val cue = PendingAdvice("event", "준비", now, now + 100, 2)
        assertNull(arbiter.select(cue, now, 2, true, true, false))
        assertNull(arbiter.select(cue, now, 2, true, false, true))
        assertNull(arbiter.select(cue, now, 2, false, false, false))
        assertNull(arbiter.select(cue, now, 3, true, false, false))
        assertNull(arbiter.select(cue, now + 100, 2, true, false, false))
        assertNull(arbiter.select(cue, now, 2, true, false, false, true))
        arbiter.mute(); assertNull(arbiter.select(cue, now, 2, true, false, false))
        arbiter.resume(); assertEquals("준비", arbiter.select(cue, now, 2, true, false, false))
        arbiter.acknowledge(cue.id); assertNull(arbiter.select(cue, now, 2, true, false, false))
    }
    @Test fun realVehicleHasNoInventedManualOrUnsupportedTrimFeature() {
        val repository = ManualRepository()
        assertNull(repository.find(ManualVehicle("IONIQ 3", "2026", "unknown"), ManualFeature.SEAT_HEAT))
        assertNull(repository.find(ManualRepository.DEMO_VEHICLE, ManualFeature.OTHER))
        val lesson = repository.find(ManualRepository.DEMO_VEHICLE, ManualFeature.DEFOG)!!
        assertEquals(InputSource.SIMULATION, lesson.source)
        assertEquals("SIM-MANUAL-1", lesson.revision)
        val state = ManualLearningSession(lesson)
        state.confirm(); assertFalse(state.learned)
        state.next(); state.next(); state.next(); state.confirm(); assertFalse(state.learned)
        state.previous(); state.operate(); state.next(); state.confirm(); assertTrue(state.learned)
    }
    @Test fun providedRoutesOnlyAndUntrustedGeneratedNumbersAreRejected() {
        val selection = CourseSelection()
        selection.load(RouteRequest(RouteGoal.WEAKNESS, "D10", "가상 연습장"), SimulatedRouteProvider(), now)
        assertEquals("sim-target", selection.candidates.first().id)
        val original = selection.candidates.toList()
        assertFalse(selection.applyProposal(RouteProposal(listOf("invented"), setOf("invented")), now))
        assertFalse(selection.applyProposal(RouteProposal(listOf("sim-target"), emptySet()), now))
        assertFalse(selection.applyProposal(RouteProposal(listOf("sim-target"), setOf("sim-target"), mapOf("sim-target" to 999.0)), now))
        assertEquals(original, selection.candidates)
        assertTrue(selection.applyProposal(RouteProposal(listOf("sim-target"), setOf("sim-target"), mapOf("sim-target" to 1.8)), now))
    }
    @Test fun missingStartAndExclusionsHaveExplicitEmptyResults() {
        val selection = CourseSelection()
        selection.load(RouteRequest(RouteGoal.BEGINNER, "D01", null), SimulatedRouteProvider(), now)
        assertTrue(selection.candidates.isEmpty())
        selection.load(RouteRequest(RouteGoal.BEGINNER, "D01", "가상", setOf("기초", "선택 과제", "왕복")), SimulatedRouteProvider(), now)
        assertTrue(selection.candidates.isEmpty())
        assertFalse(selection.acknowledged)
    }
    @Test fun navigationRejectionWrongVersionAndWrongSourceDoNotStartCourse() {
        val selection = CourseSelection()
        selection.load(RouteRequest(RouteGoal.BEGINNER, "D01", "가상"), SimulatedRouteProvider(), now)
        for (mode in 0..2) {
            val navigator = object : RouteNavigator {
                override fun submit(route: TrainingRoute) = NavigationReceipt(route.id,
                    if (mode == 1) route.version + 1 else route.version,
                    if (mode == 2) InputSource.NAVIGATION else route.source, mode != 0)
            }
            assertFalse(selection.choose("sim-basic", navigator, now)); assertNull(selection.selected)
        }
        assertTrue(selection.choose("sim-basic", SimulationNavigator(), now))
        assertEquals(InputSource.SIMULATION, selection.selected!!.source)
    }
    @Test fun missingDistanceIsNotZeroAndStaleSensorHelpStops() {
        val state = DistanceAssistance("attempt", 100)
        assertNull(state.distanceM)
        val sample = InputEnvelope(4.0, InputSource.VEHICLE_SDK, now, now, InputValidity.VALID, "m", "attempt")
        state.accept(sample, now); assertEquals(4.0, state.distanceM!!, 0.0)
        state.accept(sample, now + 101); assertNull(state.distanceM)
        state.accept(sample.copy(value = null, validity = InputValidity.DENIED), now)
        assertNull(state.distanceM); assertEquals(InputValidity.DENIED, state.validity)
        state.accept(sample.copy(unit = "cm"), now); assertNull(state.distanceM)
    }
    @Test fun invalidAndStaleRouteProviderValuesAreFiltered() {
        val selection = CourseSelection()
        val provider = object : RouteProvider {
            override fun candidates(request: RouteRequest, now: Long) = listOf(
                TrainingRoute("bad",1,InputSource.NAVIGATION,"bad",Double.NaN,1,listOf("D01"),emptySet(),now),
                TrainingRoute("old",1,InputSource.NAVIGATION,"old",1.0,1,listOf("D01"),emptySet(),now-1_000_000),
                TrainingRoute("bad-task",1,InputSource.SIMULATION,"bad",1.0,1,listOf("D99"),emptySet(),now))
        }
        selection.load(RouteRequest(RouteGoal.BEGINNER,"D01","수동 출발지"),provider,now)
        assertTrue(selection.candidates.isEmpty())
    }

    @Test fun roundTripExclusionCannotSilentlyBecomeOneWayAndNavigatorFailureIsRecoverable() {
        val selection=CourseSelection()
        selection.load(RouteRequest(RouteGoal.RETURN_TRIP,"D15","가상",setOf("왕복")),SimulatedRouteProvider(),now)
        assertTrue(selection.candidates.isEmpty())
        selection.load(RouteRequest(RouteGoal.BEGINNER,"D01","가상"),SimulatedRouteProvider(),now)
        val broken=object:RouteNavigator{override fun submit(route:TrainingRoute):NavigationReceipt=error("offline")}
        assertFalse(selection.choose("sim-basic",broken,now));assertFalse(selection.acknowledged)
        assertTrue(selection.choose("sim-basic",SimulationNavigator(),now))
        assertFalse(selection.choose("invented",SimulationNavigator(),now));assertFalse(selection.acknowledged);assertNull(selection.selected)
    }

    @Test fun staleCandidateAndLateRecommendationCannotBeSelected() {
        val selection=CourseSelection()
        selection.load(RouteRequest(RouteGoal.BEGINNER,"D01","가상"),SimulatedRouteProvider(),now)
        val later=now+CourseSelection.ROUTE_TTL_MS+1
        assertFalse(selection.choose("sim-basic",SimulationNavigator(),later))
        assertNull(selection.selected);assertFalse(selection.acknowledged)
        assertFalse(selection.applyProposal(RouteProposal(listOf("sim-basic"),setOf("sim-basic")),later))
    }
    @Test fun acknowledgedRouteMustBeRecheckedAtActualStartTime() {
        val selection=CourseSelection()
        selection.load(RouteRequest(RouteGoal.BEGINNER,"D01","가상"),SimulatedRouteProvider(),now)
        assertTrue(selection.choose("sim-basic",SimulationNavigator(),now))
        assertNotNull(selection.confirmedRoute(now))
        assertNull(selection.confirmedRoute(now+CourseSelection.ROUTE_TTL_MS+1))
        assertFalse(selection.acknowledged)
    }
    @Test fun missingTimestampAndInvalidUnitAreNotReportedAsStaleObservations() {
        val sample=InputEnvelope(4.0,InputSource.VEHICLE_SDK,now,now,InputValidity.VALID,"m","attempt")
        assertEquals(InputValidity.UNKNOWN,sample.copy(observedAt=null).effectiveValidity(now,100,"attempt"))
        assertEquals(InputValidity.ERROR,sample.copy(observedAt=now+1).effectiveValidity(now,100,"attempt"))
        assertEquals(InputValidity.STALE,sample.effectiveValidity(now+101,100,"attempt"))
        val distance=DistanceAssistance("attempt")
        distance.accept(sample.copy(unit="cm"),now)
        assertEquals(InputValidity.ERROR,distance.validity);assertNull(distance.distanceM)
    }
}
