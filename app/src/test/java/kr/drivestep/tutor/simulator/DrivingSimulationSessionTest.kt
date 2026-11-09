package kr.drivestep.tutor.simulator

import org.junit.Assert.*
import org.junit.Test

class DrivingSimulationSessionTest {
    private fun newSession(scenario: SimulationScenario = SimulationScenario.LEFT_LANE) = DrivingSimulationSession(scenario)
    private fun advance(session: DrivingSimulationSession, seconds: Double) {
        repeat((seconds * 100).toInt()) { session.tick(0.01) }
    }
    private fun prepare(session: DrivingSimulationSession) {
        val left = session.snapshot().targetLane < 0
        session.setSignal(if (left) SimulationSignal.LEFT else SimulationSignal.RIGHT)
        session.lookMirror(if (left) SimulationMirror.LEFT else SimulationMirror.RIGHT)
    }
    private fun complete(session: DrivingSimulationSession) {
        prepare(session)
        if (session.snapshot().phase == SimulationPhase.WAIT_FOR_GAP) advance(session, 10.0)
        session.setSteering(session.snapshot().targetLane * 0.6)
        repeat(60) { if (session.snapshot().phase != SimulationPhase.CENTER) session.tick(0.1) }
        assertEquals(SimulationPhase.CENTER, session.snapshot().phase)
        session.setSteering(0.0)
        session.setSignal(SimulationSignal.OFF)
        advance(session, 0.7)
        assertTrue(session.snapshot().completed)
    }

    @Test fun startsAtFortyInMiddleLaneWithCourseSpecificTarget() {
        for (scenario in SimulationScenario.entries) {
            val state = newSession(scenario).snapshot()
            assertEquals(40.0, state.speedKmh, 0.0)
            assertEquals(0.0, state.lanePosition, 0.0)
            assertEquals(if (scenario == SimulationScenario.RIGHT_LANE) 1.0 else -1.0, state.targetLane, 0.0)
            assertEquals(SimulationPhase.SIGNAL, state.phase)
            assertFalse(state.isTerminal)
            assertEquals(scenario == SimulationScenario.BLIND_SPOT, state.blindSpotLeft)
        }
    }

    @Test fun signalAndMirrorNeverPerformAnAutomaticLaneChange() {
        val session = newSession()
        prepare(session)
        advance(session, 5.0)
        assertEquals(0.0, session.snapshot().lanePosition, 0.0)
        assertEquals(40.0, session.snapshot().speedKmh, 0.0)
        assertEquals(SimulationPhase.STEER, session.snapshot().phase)
        assertTrue(session.snapshot().distanceM > 50.0)
    }

    @Test fun steeringMovesContinuouslyInsteadOfJumpingBetweenLanes() {
        val session = newSession()
        session.setSteering(-0.5)
        val state = session.tick(0.1)
        assertTrue(state.lanePosition < 0.0)
        assertTrue(state.lanePosition > -0.1)
        val previous = state.lanePosition
        session.setSteering(0.0)
        advance(session, 0.5)
        assertEquals(previous, session.snapshot().lanePosition, 0.0)
    }

    @Test fun pedalsChangeSpeedAndBrakeOverridesSimultaneousGas() {
        val session = newSession()
        session.setPedals(0.5, 0.0)
        advance(session, 0.5)
        val accelerated = session.snapshot().speedKmh
        assertTrue(accelerated > 40.0)
        session.setPedals(1.0, 1.0)
        advance(session, 0.5)
        assertTrue(session.snapshot().speedKmh < accelerated)
        session.releaseInputs()
        val held = session.snapshot().speedKmh
        advance(session, 1.0)
        assertEquals(held, session.snapshot().speedKmh, 0.0)
    }

    @Test fun zeroSpeedCannotSlideTheCarSideways() {
        val session = newSession()
        session.setPedals(0.0, 1.0)
        advance(session, 3.0)
        session.setSteering(1.0)
        advance(session, 1.0)
        assertEquals(0.0, session.snapshot().speedKmh, 0.0)
        assertEquals(0.0, session.snapshot().lanePosition, 0.0)
    }

    @Test fun oversizedTickCannotCatchUpAcrossAnAppBackgroundGap() {
        val session = newSession()
        session.setSteering(1.0)
        val state = session.tick(120.0)
        assertEquals(DrivingSimulationSession.MAX_TICK_SECONDS, state.elapsedSeconds, 0.000001)
        assertTrue(state.lanePosition < 0.1)
    }

    @Test fun invalidTimesAreIgnoredAndInvalidControlsRelease() {
        val session = newSession()
        val before = session.snapshot()
        for (dt in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals(before, session.tick(dt))
        session.setPedals(Double.NaN, Double.NEGATIVE_INFINITY)
        session.setSteering(Double.NaN)
        assertEquals(before, session.snapshot())
        assertEquals(1.0, session.setSteering(10.0).steering, 0.0)
        assertEquals(1.0, session.setPedals(2.0, -2.0).gas, 0.0)
        assertEquals(0.0, session.snapshot().brake, 0.0)
    }

    @Test fun pauseReleasesInputsAndFreezesAllSimulationState() {
        val session = newSession()
        session.setPedals(0.7, 0.0)
        session.setSteering(0.4)
        session.tick(0.1)
        val paused = session.pause()
        assertEquals(0.0, paused.steering, 0.0)
        assertEquals(0.0, paused.gas, 0.0)
        assertEquals(0.0, paused.brake, 0.0)
        session.setSteering(-1.0); session.setPedals(1.0, 0.0)
        session.setSignal(SimulationSignal.LEFT); session.lookMirror(SimulationMirror.LEFT)
        assertEquals(paused, session.tick(0.1))
        session.resume()
        val resumed = session.tick(0.1)
        assertTrue(resumed.elapsedSeconds > paused.elapsedSeconds)
        assertEquals(paused.lanePosition, resumed.lanePosition, 0.0)
    }

    @Test fun mirrorBeforeSignalDoesNotCountAsTheRequiredSequence() {
        val session = newSession()
        session.lookMirror(SimulationMirror.LEFT)
        assertFalse(session.snapshot().mirrorChecked)
        assertEquals(SimulationPhase.MIRROR, session.setSignal(SimulationSignal.LEFT).phase)
        assertFalse(session.snapshot().mirrorChecked)
        assertEquals(SimulationPhase.STEER, session.lookMirror(SimulationMirror.LEFT).phase)
    }

    @Test fun wrongMirrorDoesNotAdvanceAndCorrectMirrorCanRecover() {
        val session = newSession()
        session.setSignal(SimulationSignal.LEFT)
        session.lookMirror(SimulationMirror.RIGHT)
        assertEquals(SimulationPhase.MIRROR, session.snapshot().phase)
        assertEquals(SimulationInterventionKind.WRONG_MIRROR, session.snapshot().interventions.single().kind)
        assertEquals(SimulationPhase.STEER, session.lookMirror(SimulationMirror.LEFT).phase)
    }

    @Test fun wrongSignalGivesOneShortCorrectionWithoutSpamming() {
        val session = newSession()
        repeat(20) {
            session.setSignal(SimulationSignal.RIGHT)
            session.tick(0.1)
            session.setSignal(SimulationSignal.OFF)
        }
        assertEquals(1, session.snapshot().interventions.count { it.kind == SimulationInterventionKind.WRONG_SIGNAL })
        assertFalse(session.snapshot().mirrorChecked)
    }

    @Test fun unsignalledMovementStillMovesTheCarButCannotCompleteTheLesson() {
        val session = newSession()
        session.setSteering(-0.6)
        advance(session, 3.0)
        session.setSteering(0.0)
        advance(session, 1.0)
        assertTrue(session.snapshot().lanePosition < -0.8)
        assertFalse(session.snapshot().completed)
        assertEquals(1, session.snapshot().interventions.count { it.kind == SimulationInterventionKind.NO_SIGNAL })
    }

    @Test fun movementAfterSignalWithoutMirrorGetsItsOwnCorrection() {
        val session = newSession()
        session.setSignal(SimulationSignal.LEFT)
        session.setSteering(-0.4)
        advance(session, 0.5)
        assertEquals(SimulationInterventionKind.MIRROR_FIRST, session.snapshot().interventions.single().kind)
        assertEquals(SimulationPhase.MIRROR, session.snapshot().phase)
    }

    @Test fun turningSignalOffBeforeTargetRequiresAMirrorCheckAgain() {
        val session = newSession()
        prepare(session)
        session.setSteering(-0.4)
        advance(session, 0.5)
        session.setSignal(SimulationSignal.OFF)
        assertFalse(session.snapshot().mirrorChecked)
        session.setSignal(SimulationSignal.LEFT)
        assertEquals(SimulationPhase.MIRROR, session.snapshot().phase)
    }

    @Test fun blindSpotCarStartsBesideLearnerThenPassesWithoutAutomaticSteering() {
        val session = newSession(SimulationScenario.BLIND_SPOT)
        advance(session, 10.0)
        assertTrue(session.snapshot().blindSpotLeft)
        assertEquals(-2.0, session.snapshot().traffic.single { it.id == "left-passing" }.distanceM, 0.000001)
        prepare(session)
        val initial = session.snapshot()
        assertEquals(SimulationPhase.WAIT_FOR_GAP, initial.phase)
        assertTrue(initial.blindSpotLeft)
        assertTrue(initial.traffic.single { it.id == "left-passing" }.distanceM < 0.0)
        advance(session, 10.0)
        val later = session.snapshot()
        assertFalse(later.blindSpotLeft)
        assertEquals(SimulationPhase.STEER, later.phase)
        assertEquals(0.0, later.lanePosition, 0.0)
        assertTrue(later.traffic.single { it.id == "left-passing" }.distanceM > 14.0)
    }

    @Test fun steeringTowardNearbyCarProducesWarningBeforeAnyOverlap() {
        val session = newSession(SimulationScenario.BLIND_SPOT)
        prepare(session)
        session.setSteering(-0.5)
        advance(session, 0.3)
        assertFalse(session.snapshot().failed)
        assertEquals(SimulationInterventionKind.NEARBY_CAR, session.snapshot().interventions.single().kind)
        advance(session, 0.3)
        assertEquals(1, session.snapshot().interventions.count { it.kind == SimulationInterventionKind.NEARBY_CAR })
    }

    @Test fun virtualCollisionStopsAndEverySubsequentActionIsImmutable() {
        val session = newSession(SimulationScenario.BLIND_SPOT)
        prepare(session)
        session.setSteering(-1.0)
        advance(session, 2.0)
        val failed = session.snapshot()
        assertTrue(failed.failed)
        assertEquals(SimulationInterventionKind.COLLISION, failed.interventions.last().kind)
        assertEquals(0.0, failed.steering, 0.0)
        assertFrozen(session, failed)
    }

    @Test fun leavingVirtualRoadStopsAndRequiresANewInstanceToRetry() {
        val session = newSession()
        session.setSteering(1.0)
        advance(session, 3.0)
        val failed = session.snapshot()
        assertTrue(failed.failed)
        assertEquals(SimulationInterventionKind.ROAD_EDGE, failed.interventions.last().kind)
        assertFrozen(session, failed)
        assertFalse(newSession().snapshot().failed)
    }

    @Test fun leftAndRightCoursesCompleteOnlyAfterManualSteeringCenteringAndSignalOff() {
        for (scenario in listOf(SimulationScenario.LEFT_LANE, SimulationScenario.RIGHT_LANE)) {
            val session = newSession(scenario)
            complete(session)
            assertTrue(session.snapshot().reflections.any { it.contains("직접") })
            assertTrue(session.snapshot().interventions.isEmpty())
            assertFrozen(session, session.snapshot())
        }
    }

    @Test fun blindSpotCourseCompletesAfterWaitingAndManualLaneChange() {
        val session = newSession(SimulationScenario.BLIND_SPOT)
        complete(session)
        assertTrue(session.snapshot().reflections.any { it.contains("지나가는") })
        assertTrue(session.snapshot().interventions.isEmpty())
    }

    @Test fun signalStillOnPreventsCompletionEvenAtTargetWithCenteredSteering() {
        val session = newSession()
        prepare(session)
        session.setSteering(-0.6)
        repeat(60) { if (session.snapshot().phase != SimulationPhase.CENTER) session.tick(0.1) }
        session.setSteering(0.0)
        advance(session, 1.0)
        assertEquals(SimulationPhase.CENTER, session.snapshot().phase)
        assertFalse(session.snapshot().completed)
        session.setSignal(SimulationSignal.OFF)
        advance(session, 0.7)
        assertTrue(session.snapshot().completed)
    }

    @Test fun rapidSignalContextSpeedRiseUsesExistingCoachEvenAtSixtyHz() {
        val session = newSession()
        session.setSignal(SimulationSignal.LEFT)
        session.setPedals(1.0, 0.0)
        repeat(180) { session.tick(1.0 / 60.0) }
        assertEquals(1, session.snapshot().interventions.count { it.kind == SimulationInterventionKind.SPEED_RISE })
        assertEquals("simulation_speed_rise", session.snapshot().coachCue.id)
    }

    @Test fun rapidSignalContextSpeedDropGetsShortVirtualCueWithoutOemClaim() {
        val session = newSession()
        session.setSignal(SimulationSignal.RIGHT)
        session.setPedals(0.0, 1.0)
        advance(session, 3.0)
        val correction = session.snapshot().interventions.single { it.kind == SimulationInterventionKind.SPEED_DROP }
        assertEquals("simulation_speed_drop", correction.cue.id)
        assertFalse(correction.cue.text.contains("차량의 경고"))
    }

    @Test fun gentleSpeedChangeDoesNotTriggerAbruptSpeedCorrection() {
        val session = newSession()
        session.setSignal(SimulationSignal.LEFT)
        session.setPedals(0.1, 0.0)
        advance(session, 4.0)
        assertTrue(session.snapshot().interventions.isEmpty())
    }

    @Test fun snapshotCopiesKeepEarlierTrafficAndInterventionHistoryStable() {
        val session = newSession()
        val before = session.snapshot()
        session.setSteering(-0.5)
        advance(session, 0.3)
        assertTrue(before.interventions.isEmpty())
        assertEquals(64.0, before.traffic.single { it.id == "front" }.distanceM, 0.0)
        assertEquals(0.0, before.elapsedSeconds, 0.0)
        assertNotEquals(before.traffic, session.snapshot().traffic)
    }

    private fun assertFrozen(session: DrivingSimulationSession, expected: DrivingSimulationSnapshot) {
        session.setPedals(1.0, 1.0); session.setSteering(1.0)
        session.setSignal(SimulationSignal.RIGHT); session.lookMirror(SimulationMirror.RIGHT)
        session.releaseInputs(); session.pause(); session.resume()
        advance(session, 5.0)
        assertEquals(expected, session.snapshot())
    }
}
