package kr.drivestep.tutor.parking

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ParkingSimulationSessionTest {
    private fun session(course: ParkingCourse = ParkingCourse.REVERSE_BAY) = ParkingSimulationSession(course)
    private fun advance(s: ParkingSimulationSession, seconds: Double) = repeat((seconds*100).roundToInt()) { s.tick(0.01) }
    private fun reverse(s: ParkingSimulationSession) {
        s.setPedals(0.0, 1.0); s.setGear(ParkingGear.R); s.setPedals(1.0, 0.0)
    }
    private fun stop(s: ParkingSimulationSession) { s.setPedals(0.0, 1.0); advance(s, 0.6) }
    private fun driveUntil(s: ParkingSimulationSession, predicate: (ParkingSnapshot)->Boolean) {
        repeat(3500) {
            val state = s.snapshot()
            if (predicate(state)) return
            assertFalse("Unexpected virtual failure at ${state.pose}", state.failed)
            s.tick(0.01)
        }
        fail("Timed out at ${s.snapshot().pose}")
    }
    private fun parkedReverse(s: ParkingSimulationSession): ParkingSnapshot {
        reverse(s); driveUntil(s) { it.pose.y < 4.3 }; stop(s)
        s.setGear(ParkingGear.P)
        return s.snapshot()
    }

    @Test fun officialBodyAndWheelbaseAreSeparateFromSteeringAssumption() {
        val state = session().snapshot()
        assertEquals("아반떼", state.vehicle.name)
        assertEquals(4.710, state.vehicle.lengthM, 0.0)
        assertEquals(1.825, state.vehicle.widthM, 0.0)
        assertEquals(2.720, state.vehicle.wheelbaseM, 0.0)
        assertEquals(1.420, state.vehicle.heightM, 0.0)
        assertEquals(32.0, state.vehicle.maxRoadWheelAngleDegrees, 0.0)
    }

    @Test fun startsParkedWithoutClaimingCompletionOrMoving() {
        val s = session(); val before = s.snapshot()
        advance(s, 2.0)
        assertEquals(before.pose, s.snapshot().pose)
        assertEquals(ParkingGear.P, s.snapshot().gear)
        assertEquals(0.0, s.snapshot().speedKmh, 0.0)
        assertFalse(s.snapshot().isTerminal)
        assertFalse(s.snapshot().insideTarget)
    }

    @Test fun selectingGearRequiresBrakeAndKeepsPoseUntouched() {
        val s = session(); val start = s.snapshot().pose
        val rejected = s.setGear(ParkingGear.R)
        assertEquals(ParkingGear.P, rejected.gear)
        assertEquals(ParkingInterventionKind.BRAKE_FOR_GEAR, rejected.interventions.single().kind)
        s.setPedals(0.0, 1.0)
        assertEquals(ParkingGear.R, s.setGear(ParkingGear.R).gear)
        assertEquals(start, s.snapshot().pose)
    }

    @Test fun movingVehicleCannotReverseGearEvenWhenBrakeIsPressed() {
        val s = session(); reverse(s); advance(s, 1.0)
        s.setPedals(0.0, 1.0)
        val rejected = s.setGear(ParkingGear.D)
        assertEquals(ParkingGear.R, rejected.gear)
        assertEquals(ParkingInterventionKind.STOP_FOR_GEAR, rejected.interventions.last().kind)
        stop(s)
        assertEquals(ParkingGear.D, s.setGear(ParkingGear.D).gear)
    }

    @Test fun parkSelectionWhileMovingIsRejected() {
        val s = session(); reverse(s); advance(s, 1.0); s.setPedals(0.0, 1.0)
        assertEquals(ParkingGear.R, s.setGear(ParkingGear.P).gear)
    }

    @Test fun pedalInParkCannotMoveCarAndSuggestsGear() {
        val s = session(); val start=s.snapshot().pose
        s.setPedals(1.0, 0.0); advance(s, 3.0)
        assertEquals(start, s.snapshot().pose)
        assertEquals(ParkingInterventionKind.SELECT_GEAR, s.snapshot().interventions.single().kind)
    }

    @Test fun reverseMotionIsContinuousAndLimitedToFiveKmh() {
        val s = session(); reverse(s)
        val early=s.tick(0.1)
        assertTrue(early.pose.y < 10.0 && early.pose.y > 9.98)
        advance(s, 1.5)
        assertEquals(5.0, s.snapshot().speedKmh, 0.00001)
        assertTrue(s.snapshot().signedSpeedMps < 0.0)
    }

    @Test fun forwardStartsWithFriendlyCourseReminderButStillMovesForward() {
        val s = session(); s.setPedals(0.0,1.0);s.setGear(ParkingGear.D);s.setPedals(1.0,0.0)
        advance(s, 1.0)
        assertTrue(s.snapshot().pose.y > 10.0)
        assertEquals(ParkingInterventionKind.FORWARD_FIRST, s.snapshot().interventions.single().kind)
    }

    @Test fun steeringAtRestDoesNotTeleportOrRotateCar() {
        val s = session(); val pose=s.snapshot().pose
        s.setSteering(1.0); advance(s, 2.0)
        assertEquals(pose, s.snapshot().pose)
    }

    @Test fun steeringUsesWheelbaseAndReversesYawWithTravelDirection() {
        val s = session(); reverse(s);s.setSteering(0.5);advance(s,0.5)
        assertTrue(s.snapshot().pose.headingRadians < 0.0)
        assertNotEquals(0.0,s.snapshot().pose.x-8.0,0.000001)
        val forward=session();forward.setPedals(0.0,1.0);forward.setGear(ParkingGear.D)
        forward.setSteering(0.5);forward.setPedals(1.0,0.0);advance(forward,0.5)
        assertTrue(forward.snapshot().pose.headingRadians > 0.0)
    }

    @Test fun brakeWinsWhenBothPedalsArePressed() {
        val s=session();reverse(s);advance(s,1.0)
        s.setPedals(1.0,1.0);advance(s,0.6)
        assertEquals(0.0,s.snapshot().speedKmh,0.0)
    }

    @Test fun releasingGasCoastsToRestInsteadOfMaintainingCruise() {
        val s=session();reverse(s);advance(s,1.0)
        s.setPedals(0.0,0.0);advance(s,2.0)
        assertEquals(0.0,s.snapshot().speedKmh,0.0)
    }

    @Test fun targetAlignmentNeverCompletesAutomatically() {
        val s=session(); val parked=parkedReverse(s)
        assertTrue(parked.insideTarget)
        assertEquals(ParkingPhase.ALIGNED,parked.phase)
        advance(s,1.0)
        assertFalse(s.snapshot().completed)
        assertTrue(s.finish().completed)
    }

    @Test fun completionNeedsParkEvenAfterStoppingInBay() {
        val s=session();reverse(s);driveUntil(s){it.pose.y<4.3};stop(s)
        assertFalse(s.finish().completed)
        assertEquals(ParkingInterventionKind.STOP_AND_P,s.snapshot().interventions.last().kind)
        s.setGear(ParkingGear.P);assertTrue(s.finish().completed)
    }

    @Test fun drivingBeyondAlignedPositionRemovesAlignedPrompt() {
        val s=session();reverse(s)
        driveUntil(s){it.phase==ParkingPhase.ALIGNED}
        driveUntil(s){it.pose.y<3.15}
        assertEquals(ParkingPhase.MANEUVER,s.snapshot().phase)
        assertEquals(ParkingSimulationCues.ADJUST,s.snapshot().coachCue)
        stop(s);s.setGear(ParkingGear.P)
        assertFalse(s.finish().completed)
    }

    @Test fun finishOutsideBayCannotSucceed() {
        val s=session()
        assertFalse(s.finish().completed)
        assertFalse(s.snapshot().isTerminal)
        assertEquals(ParkingInterventionKind.ADJUST_POSITION,s.snapshot().interventions.single().kind)
    }

    @Test fun parallelParkingCanBeCompletedWithTwoManualReverseArcs() {
        val s=session(ParkingCourse.PARALLEL)
        val radius=2.720/tan(Math.toRadians(32.0))
        val angle=acos(1.0-3.3/(2.0*radius))
        reverse(s);s.setSteering(1.0)
        driveUntil(s){it.pose.headingRadians <= -angle}
        s.setSteering(-1.0)
        driveUntil(s){it.pose.headingRadians >= -0.005}
        s.setSteering(0.0);stop(s);s.setGear(ParkingGear.P)
        val state=s.finish()
        assertTrue("Pose ${state.pose}, clearance ${state.clearanceM}",state.completed)
        assertTrue(state.insideTarget)
        assertTrue(state.angleErrorDegrees < 1.0)
    }

    @Test fun parallelStraightReverseCannotBeMistakenForParking() {
        val s=session(ParkingCourse.PARALLEL);reverse(s)
        driveUntil(s){it.pose.y<8.3};stop(s);s.setGear(ParkingGear.P)
        assertFalse(s.snapshot().insideTarget)
        assertFalse(s.finish().completed)
    }

    @Test fun bodyInsideBayStillNeedsCorrectOrientationBeforeFinish() {
        val s=session(ParkingCourse.PARALLEL)
        val radius=2.720/tan(Math.toRadians(32.0))
        val angle=acos(1.0-3.3/(2.0*radius))
        reverse(s);s.setSteering(1.0)
        driveUntil(s){it.pose.headingRadians <= -angle}
        s.setSteering(-1.0)
        driveUntil(s){it.pose.headingRadians >= 0.18}
        s.setSteering(0.0);stop(s);s.setGear(ParkingGear.P)
        assertTrue(s.snapshot().insideTarget)
        assertTrue(s.snapshot().angleErrorDegrees > 8.0)
        assertFalse(s.finish().completed)
        assertFalse(s.snapshot().isTerminal)
    }

    @Test fun collisionStopsAllMotionAndCannotBeOverriddenByNewControls() {
        val s=session();reverse(s);s.setSteering(1.0)
        driveUntil(s){it.isTerminal}
        val failed=s.snapshot()
        assertTrue(failed.failed)
        assertEquals(ParkingInterventionKind.COLLISION,failed.interventions.last().kind)
        assertEquals(0.0,failed.speedKmh,0.0)
        s.setPedals(1.0,0.0);s.setSteering(1.0);s.setGear(ParkingGear.D);s.resume();s.finish()
        assertEquals(failed,s.tick(10.0))
    }

    @Test fun drivingBeyondWorldBoundaryFailsFromBodyCornersNotCentre() {
        val s=session();s.setPedals(0.0,1.0);s.setGear(ParkingGear.D);s.setPedals(1.0,0.0)
        driveUntil(s){it.failed}
        assertTrue(s.snapshot().pose.y < s.snapshot().worldHeightM)
        assertEquals(ParkingInterventionKind.ROAD_EDGE,s.snapshot().interventions.last().kind)
    }

    @Test fun completedSessionIsImmutableIncludingPauseAndInputRelease() {
        val s=session();parkedReverse(s);val done=s.finish()
        s.setGear(ParkingGear.R);s.setPedals(1.0,0.0);s.setSteering(1.0)
        s.pause();s.resume();s.releaseInputs();s.finish()
        assertEquals(done,s.tick(1.0))
    }

    @Test fun pauseClearsPedalsSteeringAndMomentumWithoutMovingPose() {
        val s=session();reverse(s);s.setSteering(0.3);advance(s,0.5)
        val pose=s.snapshot().pose;val paused=s.pause()
        assertEquals(pose,paused.pose);assertEquals(0.0,paused.speedKmh,0.0)
        assertEquals(0.0,paused.gas,0.0);assertEquals(0.0,paused.steering,0.0)
        s.setGear(ParkingGear.D);s.setPedals(1.0,0.0);s.setSteering(1.0);s.finish()
        assertEquals(paused,s.tick(100.0))
        s.resume();advance(s,1.0)
        assertEquals(pose,s.snapshot().pose)
    }

    @Test fun oversizedTickCapsElapsedTimeAndDisplacement() {
        val s=session();reverse(s)
        val result=s.tick(100.0)
        assertEquals(0.1,result.elapsedSeconds,0.000001)
        assertTrue(result.distanceM < 0.02)
    }

    @Test fun invalidTimeDoesNotAdvanceOrAlterState() {
        val s=session();reverse(s);val before=s.snapshot()
        for(t in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,0.0)) assertEquals(before,s.tick(t))
    }

    @Test fun nonFiniteControlsSafelyReleaseAndFiniteInputsClamp() {
        val s=session();s.setPedals(Double.NaN,Double.NEGATIVE_INFINITY);s.setSteering(Double.NaN)
        assertEquals(0.0,s.snapshot().gas,0.0);assertEquals(0.0,s.snapshot().brake,0.0);assertEquals(0.0,s.snapshot().steering,0.0)
        assertEquals(1.0,s.setSteering(99.0).steering,0.0)
        assertEquals(-1.0,s.setSteering(-99.0).steering,0.0)
        assertEquals(1.0,s.setPedals(99.0,-1.0).gas,0.0)
    }

    @Test fun predictedTrajectoryIsVirtualAndCannotMoveTheActualModel() {
        val s=session();reverse(s);s.setPedals(0.0,1.0);s.setSteering(1.0)
        val first=s.snapshot();val second=s.snapshot()
        assertEquals(first,second)
        assertEquals(19,first.trajectory.size)
        assertEquals(first.pose.x,first.trajectory.first().x,0.0)
        assertTrue(first.trajectory.last().y < first.pose.y)
        assertEquals(0.0,first.distanceM,0.0)
    }

    @Test fun repeatedRejectedActionDoesNotSpamSameIntervention() {
        val s=session();repeat(10){s.setGear(ParkingGear.R)}
        assertEquals(1,s.snapshot().interventions.size)
        assertEquals(2L,s.snapshot().coachRevision)
    }

    @Test fun containsRequiresAllFourCornersAndTargetOrientation() {
        val outer=ParkingRect("target",0.0,0.0,3.0,5.6)
        assertTrue(ParkingGeometry.contains(outer,ParkingRect("car",0.0,0.0,1.825,4.710)))
        assertFalse(ParkingGeometry.contains(outer,ParkingRect("car",0.7,0.0,1.825,4.710)))
        assertFalse(ParkingGeometry.contains(outer,ParkingRect("car",0.0,0.0,1.825,4.710,Math.PI/4)))
    }

    @Test fun collisionDetectsCrossingEdgesWithoutContainedCorners() {
        val tall=ParkingRect("tall",0.0,0.0,1.0,6.0)
        val wide=ParkingRect("wide",0.0,0.0,6.0,1.0)
        assertTrue(ParkingGeometry.overlaps(tall,wide))
        assertFalse(ParkingGeometry.overlaps(tall,wide.copy(x=5.0)))
        assertEquals(0.0,ParkingGeometry.clearance(tall,wide),0.0)
    }

    @Test fun clearanceMeasuresGapBetweenBodiesNotCentres() {
        val a=ParkingRect("a",0.0,0.0,2.0,4.0)
        val b=ParkingRect("b",4.0,0.0,2.0,4.0)
        assertEquals(2.0,ParkingGeometry.clearance(a,b),0.000001)
        assertEquals(ParkingGeometry.clearance(a,b),ParkingGeometry.clearance(b,a),0.000001)
    }

    @Test fun snapshotsDoNotExposeMutableTrailOrInterventions() {
        val s=session();val old=s.snapshot();reverse(s);advance(s,1.0)
        assertEquals(1,old.trail.size);assertEquals(0,old.interventions.size)
        assertTrue(s.snapshot().trail.size > old.trail.size)
    }

    @Test fun coursesHaveDistinctGeometryAndNoInitialOverlap() {
        for(c in ParkingCourse.entries) {
            val state=session(c).snapshot()
            assertTrue(state.clearanceM > 0.5)
            assertFalse(state.insideTarget)
            val body=ParkingRect("ego",state.pose.x,state.pose.y,state.vehicle.widthM,state.vehicle.lengthM)
            assertTrue(state.obstacles.none{ParkingGeometry.overlaps(body,it)})
            assertTrue(state.obstacles.flatMap(ParkingGeometry::corners).all {
                it.x in 0.0..state.worldWidthM && it.y in 0.0..state.worldHeightM
            })
        }
    }
}
