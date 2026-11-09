package kr.drivestep.tutor.ride

import org.junit.Assert.*
import org.junit.Test

class DriveLessonSessionTest {
    private fun step(session: DriveLessonSession, count: Int = 1) { repeat(count) { session.tick(0.1) } }
    private fun until(session: DriveLessonSession, label: String, limit: Int = 1000, condition: (RideSnapshot) -> Boolean) {
        repeat(limit) {
            if (condition(session.snapshot())) return
            session.tick(0.1)
        }
        fail("Timed out: $label; ${session.snapshot()}")
    }
    private fun drive(session: DriveLessonSession) {
        session.setPedals(false, true)
        session.setGear(VirtualGear.D)
        session.setPedals(true, false)
    }
    private fun stopAtTarget(session: DriveLessonSession) {
        drive(session)
        until(session, "target approach") { it.distanceM >= 17.0 }
        session.setPedals(false, true)
        until(session, "stopped before line") { it.phase == RidePhase.STOP_AND_PARK }
    }
    private fun green(session: DriveLessonSession) {
        session.setPedals(false, true)
        until(session, "scripted green") { it.phase == RidePhase.RESUME_AFTER_GREEN }
    }

    @Test fun startStopBeginsParkedWithAnExplicitBrakePrompt() {
        val s = DriveLessonSession(RideLesson.START_STOP).snapshot()
        assertEquals(VirtualGear.P, s.gear)
        assertEquals(RidePhase.READY_BRAKE, s.phase)
        assertEquals(0.0, s.speedKmh, 0.0)
        assertFalse(s.completed)
        assertEquals(RideCoach.BRAKE, s.coachCue)
        assertTrue(s.reflection.isEmpty())
    }

    @Test fun merelyWaitingDoesNotCompleteAnyStartStopAction() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        step(session, 600)
        assertEquals(RidePhase.READY_BRAKE, session.snapshot().phase)
        assertEquals(0.0, session.snapshot().distanceM, 0.0)
        assertFalse(session.snapshot().completed)
    }

    @Test fun brakeThenDriveRequiresActualInputAndKeepsPedalHeld() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        assertEquals(RidePhase.READY_DRIVE, session.setPedals(false, true).phase)
        val s = session.setGear(VirtualGear.D)
        assertEquals(RidePhase.DRIVE_TO_LINE, s.phase)
        assertEquals(VirtualGear.D, s.gear)
        assertTrue(s.brakePressed)
        step(session, 30)
        assertEquals(0.0, session.snapshot().distanceM, 0.0)
    }

    @Test fun gearWithoutBrakeIsRejectedAndSameErrorDoesNotSpam() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        val first = session.setGear(VirtualGear.D)
        assertEquals(VirtualGear.P, first.gear)
        assertEquals(RideCoach.BRAKE_FOR_GEAR, first.coachCue)
        repeat(10) { session.setGear(VirtualGear.D) }
        assertEquals(first.feedbackRevision, session.snapshot().feedbackRevision)
        assertEquals(1, session.snapshot().interventions)
    }

    @Test fun gasInParkGivesCoachingButNeverMovesVehicle() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        session.setPedals(true, false)
        step(session, 60)
        val s = session.snapshot()
        assertEquals(RideCoach.GAS_IN_P, s.coachCue)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(1, s.interventions)
    }

    @Test fun reverseIsOutsideTheseLessonsAndDoesNotChangeGear() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        session.setPedals(false, true)
        val s = session.setGear(VirtualGear.R)
        assertEquals(VirtualGear.P, s.gear)
        assertEquals(RideCoach.REVERSE, s.coachCue)
        assertFalse(s.completed)
    }

    @Test fun movingGearChangeIsRejectedEvenWithBrakePressed() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        step(session, 20)
        session.setPedals(false, true)
        val s = session.setGear(VirtualGear.P)
        assertEquals(VirtualGear.D, s.gear)
        assertEquals(RideCoach.MOVING_GEAR, s.coachCue)
        assertTrue(s.speedKmh > 0.0)
    }

    @Test fun simultaneousPedalsBrakeButDoNotAdvanceThePreparation() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        session.setPedals(true, true)
        step(session, 30)
        assertEquals(RidePhase.READY_BRAKE, session.snapshot().phase)
        assertEquals(RideCoach.BOTH_PEDALS, session.snapshot().coachCue)
        assertEquals(0.0, session.snapshot().distanceM, 0.0)
        assertEquals(RidePhase.READY_DRIVE, session.setPedals(false, true).phase)
    }

    @Test fun completeStartStopNeedsAccelerationBrakingThenManualPark() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        stopAtTarget(session)
        val stopped = session.snapshot()
        assertFalse(stopped.completed)
        assertTrue(stopped.distanceM <= stopped.stopLineM)
        assertEquals(RideCoach.PARK, stopped.coachCue)
        assertTrue(stopped.brakePressed)
        val complete = session.setGear(VirtualGear.P)
        assertTrue(complete.completed)
        assertEquals(RidePhase.COMPLETED, complete.phase)
        assertEquals(RideCoach.START_COMPLETE, complete.coachCue)
        assertEquals(0, complete.retries)
        assertTrue(complete.reflection.any { it.contains("P 선택") })
    }

    @Test fun earlyStopDoesNotCountAsTargetStopOrAutoComplete() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        until(session, "early movement") { it.distanceM >= 2.0 }
        session.setPedals(false, true)
        until(session, "early stop") { it.speedKmh == 0.0 }
        assertEquals(RidePhase.DRIVE_TO_LINE, session.snapshot().phase)
        assertEquals(RideCoach.EARLY_STOP, session.snapshot().coachCue)
        assertFalse(session.snapshot().completed)
    }

    @Test fun passingTargetLineReturnsToManualPreparationForRetry() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        until(session, "line retry") { it.retries == 1 }
        val s = session.snapshot()
        assertEquals(RidePhase.READY_BRAKE, s.phase)
        assertEquals(VirtualGear.P, s.gear)
        assertEquals(0.0, s.distanceM, 0.0)
        assertFalse(s.gasPressed)
        assertFalse(s.completed)
        assertEquals(RideCoach.RETRY_LINE, s.coachCue)
        step(session, 50)
        assertEquals(1, session.snapshot().retries)
    }

    @Test fun respondingAsSoonAsStopPromptIsGivenIsAccepted() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        until(session, "stop prompt") { it.coachCue == RideCoach.STOP_LINE }
        session.setPedals(false, true)
        until(session, "immediate response stop") { it.speedKmh == 0.0 }
        assertEquals(RidePhase.STOP_AND_PARK, session.snapshot().phase)
        assertEquals(0, session.snapshot().retries)
    }

    @Test fun stopPromptAllowsSeveralSecondsToListenBeforeBraking() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        until(session, "stop prompt") { it.coachCue == RideCoach.STOP_LINE }
        step(session, 50) // Five seconds to hear the short cue and react.
        session.setPedals(false, true)
        until(session, "late but calm stop") { it.speedKmh == 0.0 }
        assertEquals(RidePhase.STOP_AND_PARK, session.snapshot().phase)
        assertEquals(0, session.snapshot().retries)
    }

    @Test fun correctStoppingAndPAfterRetryStillCompletesWithoutHidingRetry() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        until(session, "first retry") { it.retries == 1 }
        stopAtTarget(session)
        session.setGear(VirtualGear.P)
        assertTrue(session.snapshot().completed)
        assertEquals(1, session.snapshot().retries)
    }

    @Test fun crosswalkBeginsWithAnExplicitlyScriptedRedAndPedestrian() {
        val s = DriveLessonSession(RideLesson.CROSSWALK).snapshot()
        assertEquals(RidePhase.APPROACH_CROSSWALK, s.phase)
        assertEquals(VirtualSignal.RED, s.signal)
        assertTrue(s.pedestrianPresent)
        assertEquals(VirtualGear.D, s.gear)
        assertEquals(6.0, s.speedKmh, 0.0)
        assertEquals(24.0, s.stopLineM, 0.0)
    }

    @Test fun crosswalkDoesNotTurnGreenJustBecauseTimePassesWithoutBrakeInput() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        step(session, 200)
        val s = session.snapshot()
        assertEquals(RidePhase.APPROACH_CROSSWALK, s.phase)
        assertEquals(VirtualSignal.RED, s.signal)
        assertTrue(s.pedestrianPresent)
        assertFalse(s.completed)
    }

    @Test fun pedestrianLeavesBeforeSignalChangesAndUserMustAccelerateToComplete() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        session.setPedals(false, true)
        until(session, "pedestrian wait") { it.phase == RidePhase.WAIT_FOR_PEDESTRIAN }
        assertTrue(session.snapshot().pedestrianPresent)
        until(session, "still red after pedestrian") { it.phase == RidePhase.WAIT_FOR_GREEN }
        assertFalse(session.snapshot().pedestrianPresent)
        assertEquals(VirtualSignal.RED, session.snapshot().signal)
        until(session, "green") { it.phase == RidePhase.RESUME_AFTER_GREEN }
        val green = session.snapshot()
        assertEquals(VirtualSignal.GREEN, green.signal)
        assertFalse(green.completed)
        step(session, 50)
        assertEquals(green.distanceM, session.snapshot().distanceM, 0.0)
        session.setPedals(true, false)
        until(session, "user departure") { it.completed }
        assertEquals(RideCoach.CROSS_COMPLETE, session.snapshot().coachCue)
        assertTrue(session.snapshot().reflection.any { it.contains("직접 가속") })
    }

    @Test fun scriptedWaitsLeaveTimeForEachShortCoachCue() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        session.setPedals(false, true)
        until(session, "pedestrian wait") { it.phase == RidePhase.WAIT_FOR_PEDESTRIAN }
        step(session, 50)
        assertEquals(RidePhase.WAIT_FOR_PEDESTRIAN, session.snapshot().phase)
        until(session, "red wait") { it.phase == RidePhase.WAIT_FOR_GREEN }
        step(session, 30)
        assertEquals(RidePhase.WAIT_FOR_GREEN, session.snapshot().phase)
        assertEquals(VirtualSignal.RED, session.snapshot().signal)
    }

    @Test fun releasingBrakePausesScriptedWaitingWithoutCompletingIt() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        session.setPedals(false, true)
        until(session, "waiting") { it.phase == RidePhase.WAIT_FOR_PEDESTRIAN }
        val time = session.snapshot().waitingSeconds
        session.setPedals(false, false)
        step(session, 80)
        assertEquals(time, session.snapshot().waitingSeconds, 0.0)
        assertEquals(VirtualSignal.RED, session.snapshot().signal)
        assertEquals(RideCoach.HOLD_BRAKE, session.snapshot().coachCue)
    }

    @Test fun earlyAccelerationRequiresStoppingAgainInsteadOfPassingTheWait() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        session.setPedals(false, true)
        until(session, "waiting") { it.phase == RidePhase.WAIT_FOR_PEDESTRIAN }
        val wrong = session.setPedals(true, false)
        assertEquals(RidePhase.APPROACH_CROSSWALK, wrong.phase)
        assertEquals(RideCoach.TOO_EARLY, wrong.coachCue)
        assertEquals(0.0, wrong.waitingSeconds, 0.0)
        step(session, 8)
        assertTrue(session.snapshot().speedKmh > 0.0)
        session.setPedals(false, true)
        until(session, "stopped again") { it.phase == RidePhase.WAIT_FOR_PEDESTRIAN }
        assertEquals(VirtualSignal.RED, session.snapshot().signal)
        assertFalse(session.snapshot().completed)
    }

    @Test fun crossingRedLineRetriesAndPreservesTheInterventionCount() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        session.setPedals(true, false)
        until(session, "red line retry") { it.retries == 1 }
        val s = session.snapshot()
        assertEquals(0.0, s.speedKmh, 0.0)
        assertEquals(VirtualSignal.RED, s.signal)
        assertTrue(s.pedestrianPresent)
        assertEquals(RideCoach.RETRY_LINE, s.coachCue)
        assertEquals(1, s.interventions)
    }

    @Test fun steeringIsNormalizedAndOnlyChangesPositionWhileMoving() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        session.setSteering(-8.0)
        assertEquals(-1.0, session.snapshot().steering, 0.0)
        step(session, 30)
        assertEquals(0.0, session.snapshot().laneOffset, 0.0)
        drive(session)
        step(session, 20)
        assertTrue(session.snapshot().laneOffset < 0.0)
        val input = session.snapshot().steering
        session.setSteering(Double.NaN)
        assertEquals(input, session.snapshot().steering, 0.0)
    }

    @Test fun laneDepartureProducesCoachingThenExplicitRetry() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        session.setSteering(1.0)
        until(session, "lane warning") { it.coachCue == RideCoach.LANE }
        val warning = session.snapshot()
        assertEquals(1, warning.interventions)
        assertTrue(warning.laneOffset >= 0.35)
        until(session, "lane retry") { it.retries == 1 }
        assertEquals(RideCoach.RETRY_LANE, session.snapshot().coachCue)
        assertEquals(0.0, session.snapshot().laneOffset, 0.0)
        assertEquals(0.0, session.snapshot().steering, 0.0)
    }

    @Test fun laneWarningAllowsTimeToListenBeforeTheRetryBoundary() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        session.setSteering(1.0)
        until(session, "lane warning") { it.coachCue == RideCoach.LANE }
        step(session, 40) // Four seconds to hear the short cue and react.
        assertEquals(0, session.snapshot().retries)
        assertEquals(RideCoach.LANE, session.snapshot().coachCue)
        session.setPedals(false, true)
        session.setSteering(-1.0)
        until(session, "controlled stop") { it.speedKmh == 0.0 }
        assertEquals(0, session.snapshot().retries)
    }

    @Test fun aNewLaneExcursionCanBeCoachedAfterReturningTowardCenter() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        drive(session)
        session.setSteering(1.0)
        until(session, "first lane warning") { it.coachCue == RideCoach.LANE }
        val firstRevision = session.snapshot().feedbackRevision
        session.setSteering(-1.0)
        until(session, "back toward center") { it.laneOffset < 0.19 }
        session.setSteering(1.0)
        until(session, "new lane intervention") { it.interventions == 2 }
        assertTrue(session.snapshot().feedbackRevision > firstRevision)
        assertEquals(RideCoach.LANE, session.snapshot().coachCue)
        assertEquals(0, session.snapshot().retries)
    }

    @Test fun aLargeBackgroundDeltaCannotSkipScriptedWaits() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        session.setPedals(false, true)
        until(session, "wait") { it.phase == RidePhase.WAIT_FOR_PEDESTRIAN }
        val before = session.snapshot()
        val after = session.tick(1000.0)
        assertEquals(0.25, after.practiceSeconds - before.practiceSeconds, 0.00001)
        assertTrue(after.pedestrianPresent)
        assertEquals(VirtualSignal.RED, after.signal)
    }

    @Test fun invalidOrNonPositiveTicksDoNotMutateTheSession() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        val before = session.snapshot()
        listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals(before, session.tick(it))
        }
    }

    @Test fun parkingDuringGreenDoesNotInventARedSignalWhenDriveIsReselected() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        green(session)
        session.setGear(VirtualGear.P)
        val result = session.setGear(VirtualGear.D)
        assertEquals(VirtualSignal.GREEN, result.signal)
        assertEquals(RidePhase.RESUME_AFTER_GREEN, result.phase)
        assertEquals(RideCoach.GREEN, result.coachCue)
        assertFalse(result.completed)
    }

    @Test fun terminalSnapshotIgnoresEveryFurtherInput() {
        val session = DriveLessonSession(RideLesson.CROSSWALK)
        green(session)
        session.setPedals(true, false)
        until(session, "complete") { it.completed }
        val done = session.snapshot()
        assertEquals(done, session.tick(0.25))
        assertEquals(done, session.setPedals(false, true))
        assertEquals(done, session.setGear(VirtualGear.P))
        assertEquals(done, session.setSteering(1.0))
    }

    @Test fun reflectionSnapshotsAreNotMutatedByLaterEvents() {
        val session = DriveLessonSession(RideLesson.START_STOP)
        val before = session.snapshot()
        session.setPedals(false, true)
        assertTrue(before.reflection.isEmpty())
        assertFalse(session.snapshot().reflection.isEmpty())
    }

    @Test fun voiceCatalogHasUniqueSafeResourceNames() {
        assertEquals(23, RideCoach.catalog.size)
        assertEquals(RideCoach.catalog.size, RideCoach.catalog.map { it.id }.distinct().size)
        assertTrue(RideCoach.catalog.all { it.id.matches(Regex("[a-z][a-z0-9_]*")) && it.text.isNotBlank() })
    }
}
