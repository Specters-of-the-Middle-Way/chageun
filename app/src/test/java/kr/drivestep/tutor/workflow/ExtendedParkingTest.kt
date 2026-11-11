package kr.drivestep.tutor.workflow

import kr.drivestep.tutor.parking.*
import org.junit.Assert.*
import org.junit.Test

class ExtendedParkingTest {
    private fun until(s: ParkingSimulationSession, condition: (ParkingSnapshot)->Boolean) {
        repeat(5000) {
            val v=s.snapshot()
            if(condition(v))return
            assertFalse("Unexpected collision/edge: $v",v.failed)
            s.tick(.05)
        }
        fail("Parking path did not reach goal: ${s.snapshot()}")
    }
    @Test fun tBayRequiresQuarterTurnAndReverseEntryBeforeParkedFinish() {
        val s=ParkingSimulationSession(ParkingCourse.T_BAY)
        assertEquals(Math.PI/2,s.snapshot().pose.headingRadians,0.0)
        assertFalse(s.snapshot().insideTarget)
        s.setPedals(0.0,1.0);s.setGear(ParkingGear.R);s.setSteering(1.0);s.setPedals(.6,0.0)
        until(s){it.pose.headingRadians < .025};s.setSteering(0.0)
        until(s){it.pose.y<4.2};s.setPedals(0.0,1.0);until(s){it.speedKmh < .01}
        s.setGear(ParkingGear.P)
        assertTrue(s.snapshot().toString(),s.snapshot().insideTarget)
        assertTrue(s.finish().completed)
    }
    @Test fun frontBayAllowsForwardFirstAndRequiresActualTurnAndForwardEntry() {
        val s=ParkingSimulationSession(ParkingCourse.FRONT_BAY)
        assertFalse(s.snapshot().insideTarget)
        s.setPedals(0.0,1.0);s.setGear(ParkingGear.D)
        assertFalse(s.snapshot().interventions.any{it.kind==ParkingInterventionKind.FORWARD_FIRST})
        s.setSteering(-1.0);s.setPedals(.6,0.0)
        until(s){it.pose.headingRadians < .025};s.setSteering(0.0)
        until(s){it.pose.y>13.8};s.setPedals(0.0,1.0);until(s){it.speedKmh < .01}
        assertFalse(s.finish().completed)
        s.setGear(ParkingGear.P)
        assertTrue(s.snapshot().toString(),s.snapshot().insideTarget)
        assertTrue(s.finish().completed)
    }
}
