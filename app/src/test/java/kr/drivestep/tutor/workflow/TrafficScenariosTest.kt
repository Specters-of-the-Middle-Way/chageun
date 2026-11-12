package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test

class TrafficScenariosTest {
    private fun session(id:String,variant:Int)=ScenarioSession(ScenarioCatalog.find(id)!!,variant=variant).also { it.resume() }
    private fun run(s:ScenarioSession,seconds:Double) { repeat((seconds*10).toInt()) { s.tick(.1) } }
    @Test fun sceneAndSnapshotUseSameActorsAndAreReproducible() {
        for(id in listOf("D10","D11","D13")) for(variant in 0..1) {
            val a=session(id,variant); val b=session(id,variant)
            a.waitForGap(); b.waitForGap(); run(a,4.0); run(b,4.0)
            assertEquals(a.snapshot(),b.snapshot())
            val v=a.snapshot()
            assertEquals(TrafficScenarios.scene(id,variant,v.elapsed,v.distance,v.speedKmh/3.6),v.traffic)
            assertEquals(EventStatus.ACTIVE,v.status)
        }
    }
    @Test fun stationaryOccluderNeverClearsWithTimeAndCancelIsNotFailure() {
        for(id in listOf("D10","D13")) {
            val s=session(id,1); s.waitForGap(); run(s,120.0); s.checkMirror()
            assertFalse(s.snapshot().traffic!!.entryAvailable); assertFalse(s.snapshot().mirrorChecked)
            s.signal(TrainingSignal.LEFT); s.steer(-1.0); s.pedals(1.0,0.0); run(s,10.0)
            assertTrue(s.snapshot().lateral>=-.4); assertEquals(EventStatus.ACTIVE,s.snapshot().status)
            s.cancel(); assertEquals(EventStatus.CANCELLED,s.snapshot().status)
            assertTrue(s.snapshot().instruction.contains("실패나 감점이 아니"))
        }
    }
    @Test fun entryDependsOnPositionRatherThanAnElapsedSixSecondGate() {
        val close=TrafficScenarios.scene("D10",0,7.0,30.0,0.0)!!
        val far=TrafficScenarios.scene("D10",0,7.0,0.0,0.0)!!
        assertFalse(close.entryAvailable); assertTrue(far.entryAvailable)
        assertEquals(5.0,close.actors.single().relativeM,0.001)
    }
    @Test fun blockedSpaceCannotLoseItsVisibleOccluderByHoldingTheAccelerator() {
        for(id in listOf("D10","D13")) {
            val s=session(id,1)
            repeat(300) { s.pedals(1.0,0.0); s.tick(.1) }
            val v=s.snapshot(); assertTrue(v.distance<=1.5)
            assertTrue(v.traffic!!.visibilityBlocked)
            assertTrue(v.traffic.actors.single().relativeM in 2.5..4.0)
            assertFalse(TrafficScenarios.scene(id,1,30.0,100.0,0.0)!!.visibilityBlocked)
        }
    }
    @Test fun obstructionConfirmationIsRejectedAndPauseRequiresFreshObservation() {
        val s=session("D10",0); s.checkMirror(); assertFalse(s.snapshot().mirrorChecked)
        s.waitForGap(); run(s,7.0); assertTrue(s.snapshot().traffic!!.entryAvailable)
        assertFalse(s.snapshot().mirrorChecked); s.checkMirror(); assertTrue(s.snapshot().mirrorChecked)
        s.pause(); val paused=s.snapshot().traffic; run(s,20.0)
        assertEquals(paused,s.snapshot().traffic); assertFalse(s.snapshot().mirrorChecked)
        s.resume(); assertFalse(s.snapshot().mirrorChecked); s.checkMirror(); assertTrue(s.snapshot().mirrorChecked)
    }
    @Test fun narrowRoadPedestrianMovesFromOcclusionAcrossOccupiedCorridorThenClears() {
        val s=session("D11",1); assertTrue(s.snapshot().traffic!!.visibilityBlocked)
        s.waitForGap(); run(s,4.0); assertFalse(s.snapshot().traffic!!.visibilityBlocked)
        assertTrue(s.snapshot().traffic!!.corridorOccupied); s.checkMirror(); assertFalse(s.snapshot().mirrorChecked)
        run(s,4.5); assertTrue(s.snapshot().traffic!!.entryAvailable)
        s.checkMirror(); s.pedals(1.0,0.0)
        repeat(200) { if(s.snapshot().status==EventStatus.ACTIVE) s.tick(.1) }
        assertEquals(EventStatus.COMPLETED,s.snapshot().status)
    }
    @Test fun narrowRoadNeedsAnExplicitStopEvenAfterTrafficPasses() {
        val s=session("D11",0); run(s,10.0); s.checkMirror(); s.pedals(1.0,0.0); run(s,2.0)
        assertEquals(0,s.snapshot().phase); assertEquals(EventStatus.ACTIVE,s.snapshot().status)
        s.waitForGap(); run(s,1.0); assertEquals(1,s.snapshot().phase)
    }
}
