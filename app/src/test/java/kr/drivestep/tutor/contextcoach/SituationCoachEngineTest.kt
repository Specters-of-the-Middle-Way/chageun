package kr.drivestep.tutor.contextcoach

import org.junit.Assert.*
import org.junit.Test

class SituationCoachEngineTest {
    private fun frame(t: Long, speed: Double? = 45.0, signal: SituationSignal = SituationSignal.LEFT,
        warning: SituationWarning = SituationWarning.UNKNOWN, source: SituationSource = SituationSource.SIMULATION) =
        SituationInput(t, source, signal, speed, warning, SituationGear.D, roadContext = SituationRoad.STRAIGHT)

    private fun ramp(engine: SituationCoachEngine, start: Long = 0, from: Double = 45.0, to: Double = 30.0,
        change: (SituationInput) -> SituationInput = { it }): List<SituationEvent> = (0..15).mapNotNull { index ->
        engine.accept(change(frame(start + index * 200, from + (to - from) * index / 15))).event
    }

    @Test fun signalAloneNeverStartsAnUnneededTutor() {
        assertTrue(ramp(SituationCoachEngine(), from = 45.0, to = 45.0).isEmpty())
    }
    @Test fun signalAndSpeedDropCreateOneTraceableCase() {
        val event = ramp(SituationCoachEngine()).single()
        assertEquals(SituationKind.SPEED_DROP, event.kind)
        assertEquals(SituationSource.SIMULATION, event.source)
        assertTrue(event.observations.size >= 11)
        assertFalse(event.speechSuppressed)
        assertTrue(event.evidence.any { it.contains("미확인") })
    }
    @Test fun speedRiseHasItsOwnCoachInsteadOfInferringAnUnsafeGap() {
        assertEquals(SituationKind.SPEED_RISE, ramp(SituationCoachEngine(), from=30.0, to=45.0).single().kind)
    }
    @Test fun sameAttemptDoesNotRepeatedlySpeak() {
        val engine = SituationCoachEngine()
        assertEquals(1, ramp(engine).size)
        for (i in 16..100) assertNull(engine.accept(frame(i*200L, if (i % 20 < 10) 45.0 else 30.0)).event)
    }
    @Test fun parkingReverseNeutralAndUnknownGearDoNotBecomeLaneChanges() {
        for (gear in listOf(SituationGear.P, SituationGear.R, SituationGear.N, SituationGear.UNKNOWN)) {
            assertTrue(ramp(SituationCoachEngine()) { it.copy(gear=gear) }.isEmpty())
        }
    }
    @Test fun hazardOrUnknownIndicatorNeverBecomesLaneChangeIntent() {
        for (signal in listOf(SituationSignal.HAZARD, SituationSignal.UNKNOWN)) {
            assertTrue(ramp(SituationCoachEngine()) { it.copy(signal=signal) }.isEmpty())
        }
    }
    @Test fun intersectionAndParkingContextSuppressTheLaneTutor() {
        for (road in listOf(SituationRoad.INTERSECTION, SituationRoad.PARKING)) {
            assertTrue(ramp(SituationCoachEngine()) { it.copy(roadContext=road) }.isEmpty())
        }
    }
    @Test fun lowSpeedManeuverIsNotEnoughForLaneChangeInference() {
        assertTrue(ramp(SituationCoachEngine(),from=14.0,to=0.0).isEmpty())
    }
    @Test fun warningAloneCanActivateEvenWithoutAnIndicatorOrSpeed() {
        val event = SituationCoachEngine().accept(frame(0, null, SituationSignal.OFF, SituationWarning.LEFT)).event!!
        assertEquals(SituationKind.LANE_WARNING,event.kind)
    }
    @Test fun freshWarningEscalatesOverSpeedButDoesNotRepeat() {
        val engine = SituationCoachEngine()
        ramp(engine).single()
        val input = frame(3200, 30.0, warning=SituationWarning.LEFT)
        assertEquals(SituationKind.LANE_WARNING, engine.accept(input).event!!.kind)
        for (i in 17..30) assertNull(engine.accept(input.copy(atElapsedMillis=i*200L, signalAtMillis=i*200L,
            warningAtMillis=i*200L,speedAtMillis=i*200L)).event)
    }
    @Test fun staleOrFutureSpeedDoesNotStartTheTutor() {
        for (stamp in listOf(-1L,99999L)) {
            assertTrue(ramp(SituationCoachEngine()) { it.copy(speedAtMillis=stamp) }.isEmpty())
        }
    }
    @Test fun staleWarningDoesNotBecomeAPresentWarning() {
        val result = SituationCoachEngine().accept(frame(5000, signal=SituationSignal.OFF,
            warning=SituationWarning.LEFT).copy(warningAtMillis=0))
        assertNull(result.event)
    }
    @Test fun staleSignalDoesNotActivateFromARecordedTurnSignal() {
        assertTrue(ramp(SituationCoachEngine()) { it.copy(signalAtMillis=-1) }.isEmpty())
    }
    @Test fun mixedSourcesCannotMakeOneSpeedWindow() {
        val engine = SituationCoachEngine()
        for(i in 0..10) engine.accept(frame(i*200L,45.0))
        assertNull(engine.accept(frame(2200,30.0,source=SituationSource.SDK)).event)
        assertNull(engine.accept(frame(2400,30.0,source=SituationSource.SDK)).event)
    }
    @Test fun outOfOrderAndDuplicateFramesDoNotAdvanceTime() {
        val engine=SituationCoachEngine()
        engine.accept(frame(1000,45.0))
        assertNull(engine.accept(frame(1000,25.0)).event)
        assertNull(engine.accept(frame(900,20.0)).event)
        assertNull(engine.accept(frame(1200,30.0)).event)
    }
    @Test fun connectionGapDiscardsTheOldSpeedWindow() {
        val engine=SituationCoachEngine()
        engine.accept(frame(0,45.0));engine.accept(frame(200,45.0))
        assertNull(engine.accept(frame(2400,20.0)).event)
        assertNull(engine.accept(frame(2600,20.0)).event)
    }
    @Test fun oemAudioSuppressesSpeechAndIsNotQueuedForAfterward() {
        val engine=SituationCoachEngine()
        val event=engine.accept(frame(0,warning=SituationWarning.LEFT).copy(vehicleWarningActive=true)).event!!
        assertTrue(event.speechSuppressed)
        assertNull(engine.accept(frame(200,warning=SituationWarning.LEFT)).event)
    }
    @Test fun cooldownAppliesWhenDriverTogglesSignalToRepeatSpeedCase() {
        val engine=SituationCoachEngine()
        ramp(engine).single()
        engine.accept(frame(3200,30.0,signal=SituationSignal.OFF))
        val results=ramp(engine,start=3400,from=30.0,to=45.0) { it.copy(signal=SituationSignal.RIGHT) }
        assertTrue(results.isEmpty())
    }
    @Test fun warningIsUnknownByDefaultAndNeverMeansClear() {
        val event=ramp(SituationCoachEngine()).single()
        assertEquals(SituationWarning.UNKNOWN,event.observations.last().warning)
        assertTrue(event.evidence.any { it == "차로 변경 경고: 미확인" })
    }
    @Test(expected=IllegalArgumentException::class) fun invalidThresholdIsRejected() {
        SituationConfig(deltaThresholdKmh=Double.NaN)
    }
}
