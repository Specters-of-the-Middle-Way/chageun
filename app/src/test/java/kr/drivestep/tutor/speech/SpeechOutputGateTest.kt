package kr.drivestep.tutor.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechOutputGateTest {
    @Test fun replacementWaitsForOldStopAndLateReleaseCannotCancelNewOwner() {
        val gate=SpeechOutputGate(); var old=0L; var drain=0L; var starts=0
        old=gate.acquire { drain=gate.beginDrain(); gate.release(old) }
        val next=gate.acquire { fail("New owner must stay active") }
        gate.whenReady(next) { starts++ }; assertEquals(0,starts)
        gate.release(old); gate.finishDrain(drain,true); assertEquals(1,starts)
        gate.finishDrain(drain,true); assertEquals(1,starts)
    }
    @Test fun failedCleanupDropsPendingSpeechAndRequiresAnotherExplicitRequest() {
        val gate=SpeechOutputGate(); val drain=gate.beginDrain(); var interrupted=0; var starts=0
        val request=gate.acquire { interrupted++ }; gate.whenReady(request) { starts++ }
        gate.finishDrain(drain,false); assertEquals(1,interrupted); assertEquals(0,starts)
        gate.whenReady(request) { starts++ }; assertEquals(0,starts)
        val fresh=gate.acquire {}; gate.whenReady(fresh) { starts++ }; assertEquals(1,starts)
    }
    @Test fun cancelledQueuedRequestAndOutOfOrderCleanupNeverReplay() {
        val gate=SpeechOutputGate(); val a=gate.beginDrain(); val b=gate.beginDrain(); var starts=0
        val old=gate.acquire {}; gate.whenReady(old) { fail("Obsolete speech") }
        val fresh=gate.acquire {}; gate.whenReady(fresh) { starts++ }
        gate.finishDrain(b,true); assertEquals(0,starts); assertEquals(1,gate.activeDrains)
        gate.release(fresh); gate.finishDrain(a,true); assertEquals(0,starts); assertEquals(0,gate.activeDrains)
    }
    @Test fun differentRoutesInterruptBeforeNewOutputStarts() {
        val gate=SpeechOutputGate(); val sequence=mutableListOf<String>()
        val weather=gate.acquire { sequence+="weather stop" }; gate.whenReady(weather) { sequence+="weather start" }
        val coach=gate.acquire { sequence+="coach stop" }; gate.whenReady(coach) { sequence+="coach start" }
        assertEquals(listOf("weather start","weather stop","coach start"),sequence)
    }
}
