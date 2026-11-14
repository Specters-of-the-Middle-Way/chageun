package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ScenarioSessionTest {
    private fun session(id: String) = ScenarioSession(ScenarioCatalog.find(id)!!).also { it.resume() }
    private fun until(s: ScenarioSession, predicate: (ScenarioSnapshot) -> Boolean) {
        repeat(6000) {
            if (predicate(s.snapshot())) return
            assertEquals("Unexpected terminal result: ${s.snapshot()}", EventStatus.ACTIVE, s.snapshot().status)
            s.tick(.1)
        }
        fail("Timed out: ${s.snapshot()}")
    }
    private fun hold(s: ScenarioSession, seconds: Double) { repeat((seconds * 10).toInt()) { s.tick(.1) } }
    private fun drive(s: ScenarioSession, speedKmh: Double, predicate: (ScenarioSnapshot) -> Boolean) {
        repeat(6000) {
            val v = s.snapshot()
            if (predicate(v)) return
            assertEquals(EventStatus.ACTIVE, v.status)
            s.pedals(if (v.speedKmh < speedKmh) 1.0 else 0.0, 0.0); s.tick(.1)
        }
        fail("Did not reach travel goal: ${s.snapshot()}")
    }
    private fun wait(s: ScenarioSession, seconds: Double) { s.waitForGap(); hold(s, seconds); s.pedals(0.0,0.0) }
    @Test fun allEighteenTasksHaveDistinctControlDrivenCompletionPaths() {
        assertEquals((1..18).map { "D%02d".format(it) }, ScenarioCatalog.tasks.map { it.id })
        for (task in ScenarioCatalog.tasks) {
            val s = session(task.id)
            when (task.id) {
                "D01", "D02", "D04", "D17" -> drive(s, if(task.id=="D04")8.0 else 18.0) { it.status == EventStatus.COMPLETED }
                "D03" -> {
                    s.pedals(1.0,0.0); until(s){it.speedKmh >= 11}
                    s.pedals(0.0,1.0); until(s){it.phase==2}
                    s.pedals(1.0,0.0); until(s){it.status==EventStatus.COMPLETED}
                }
                "D05" -> { wait(s, 6.5); drive(s,18.0){it.status==EventStatus.COMPLETED} }
                "D06", "D07", "D08" -> {
                    s.signal(if(task.id=="D06")TrainingSignal.RIGHT else TrainingSignal.LEFT); s.checkMirror()
                    if(task.id!="D08") wait(s,2.5)
                    s.steer(if(task.id=="D06")1.0 else -1.0); s.pedals(1.0,0.0)
                    until(s){abs(it.rotation) >= if(task.id=="D08")155 else 65}
                    s.steer(0.0); until(s){it.status==EventStatus.COMPLETED}
                }
                "D09", "D10", "D13" -> {
                    if(task.id!="D09") wait(s,6.5)
                    s.signal(TrainingSignal.LEFT); s.checkMirror(); s.steer(-1.0); s.pedals(1.0,0.0)
                    until(s){it.lateral <= -3.1}; s.steer(0.0)
                    until(s){it.status==EventStatus.COMPLETED}
                }
                "D11" -> { wait(s,5.5); s.checkMirror(); drive(s,12.0){it.status==EventStatus.COMPLETED} }
                "D12" -> {
                    drive(s,34.0){it.phase==1}; s.pedals(0.0,1.0); until(s){it.status==EventStatus.COMPLETED}
                }
                "D14" -> {
                    s.signal(TrainingSignal.RIGHT); s.checkMirror(); s.steer(1.0); s.pedals(1.0,0.0)
                    until(s){it.lateral >= 3.1}; s.steer(0.0)
                    until(s){it.status==EventStatus.COMPLETED}
                }
                "D15" -> { s.reroute(); drive(s,18.0){it.status==EventStatus.COMPLETED} }
                "D16" -> { wait(s,3.5); drive(s,12.0){it.status==EventStatus.COMPLETED} }
                "D18" -> {
                    drive(s,22.0){it.warning}; s.pedals(0.0,1.0); until(s){it.status==EventStatus.COMPLETED}
                    assertFalse(s.snapshot().warning)
                }
            }
            assertEquals(task.id, EventStatus.COMPLETED, s.snapshot().status)
            assertTrue(s.snapshot().events.all { it.source == InputSource.SIMULATION })
            assertTrue(s.snapshot().paused)
        }
    }
    @Test fun passingTimeAloneNeverCertifiesAnyTask() {
        ScenarioCatalog.tasks.forEach { task ->
            val s=session(task.id); hold(s,60.0)
            assertEquals(task.id,EventStatus.ACTIVE,s.snapshot().status)
            assertEquals(0.0,s.snapshot().distance,0.0)
        }
    }
    @Test fun pauseRemovesMomentumAndControlsAndNeedsExplicitResume() {
        val s=session("D01");s.pedals(1.0,0.0);hold(s,2.0)
        assertTrue(s.snapshot().speedKmh>0)
        s.pause("SCREEN_LEFT");val point=s.snapshot().distance
        hold(s,10.0);assertEquals(point,s.snapshot().distance,0.0)
        s.resume();hold(s,2.0);assertEquals(point,s.snapshot().distance,0.0)
        assertEquals(EventStatus.INTERRUPTED,s.snapshot().events.last().status)
    }
    @Test fun waitingAndCancellationAreNormalOutcomesWithoutMasteryPenalty() {
        val s=session("D10");wait(s,8.0);s.cancel()
        assertEquals(EventStatus.CANCELLED,s.snapshot().status)
        assertTrue(s.snapshot().instruction.contains("실패나 감점이 아니"))
        val elapsed=s.snapshot().elapsed;s.tick(1.0);assertEquals(elapsed,s.snapshot().elapsed,0.0)
    }
    @Test fun routeVersionChangeInvalidatesOldCueAndSurroundingConfirmation() {
        val s=session("D15");s.checkMirror()
        val cue=PendingAdvice("old","옛 경로",0,10000,s.snapshot().routeVersion)
        s.reroute()
        assertEquals(2,s.snapshot().routeVersion);assertFalse(s.snapshot().mirrorChecked)
        assertNull(s.arbiter.select(cue,1,2,true,false,false))
        assertEquals(EventStatus.CANCELLED,s.snapshot().events.last().status)
    }
    @Test fun exitingLateAndIgnoringBumpRemainExpiredOrUnconfirmed() {
        val exit=session("D14");exit.pedals(1.0,0.0);until(exit){it.status!=EventStatus.ACTIVE}
        assertEquals(EventStatus.EXPIRED,exit.snapshot().status)
        val bump=session("D04");bump.pedals(1.0,0.0);until(bump){it.status!=EventStatus.ACTIVE}
        assertEquals(EventStatus.UNCONFIRMED,bump.snapshot().status)
    }
    @Test fun turnSignalOffAloneCannotConfirmLaneChangeAndNonFiniteInputsDoNotMoveCar() {
        val s=session("D09");s.signal(TrainingSignal.LEFT);s.signal(TrainingSignal.OFF)
        s.pedals(Double.NaN,Double.POSITIVE_INFINITY);s.steer(Double.NaN);s.tick(Double.NaN);hold(s,5.0)
        assertEquals(EventStatus.ACTIVE,s.snapshot().status);assertEquals(0.0,s.snapshot().distance,0.0)
    }

    @Test fun oppositeSteeringCannotConfirmAnIntendedRightTurn() {
        val s=session("D06");s.signal(TrainingSignal.RIGHT);s.checkMirror();wait(s,2.5)
        s.steer(-1.0);s.pedals(1.0,0.0);until(s){it.rotation < -70};s.steer(0.0);hold(s,1.0)
        assertEquals(EventStatus.ACTIVE,s.snapshot().status)
    }

    @Test fun d06WaitReleasesBrakeAndSteeringMovesRightWithoutHoldingGas() {
        val s = session("D06")
        s.signal(TrainingSignal.RIGHT)
        s.checkMirror()
        s.waitForGap()
        until(s) { it.phase == 1 }
        assertTrue(s.snapshot().waiting >= 2.0)

        s.steer(0.8)
        until(s) { it.phase == 2 }
        assertTrue(s.snapshot().lateral > 0.0)
        assertTrue(s.snapshot().instruction.contains("핸들 중앙"))

        s.steer(0.0)
        until(s) { it.status == EventStatus.COMPLETED }
        assertEquals(EventStatus.COMPLETED, s.snapshot().status)
    }
}
