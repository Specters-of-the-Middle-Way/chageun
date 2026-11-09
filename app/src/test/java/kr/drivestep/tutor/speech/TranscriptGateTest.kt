package kr.drivestep.tutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptGateTest {
    @Test fun partialCorrectionDoesNotExecuteAndFinalIsDeliveredOnce() {
        val gate = TranscriptGate()
        val token = gate.begin()
        assertNull(gate.accept(token, "오른쪽", false))
        assertEquals("오른쪽 말고 왼쪽", gate.accept(token, "오른쪽 말고 왼쪽", true))
        assertNull(gate.accept(token, "오른쪽 말고 왼쪽", true))
    }

    @Test fun canceledOrOlderRequestCannotDeliverToNewTurn() {
        val gate = TranscriptGate()
        val previous = gate.begin()
        gate.cancel()
        assertNull(gate.accept(previous, "왼쪽", true))
        val current = gate.begin()
        assertNull(gate.accept(previous, "오른쪽", true))
        assertEquals("왼쪽", gate.accept(current, " 왼쪽 ", true))
    }

    @Test fun EmptyOrOverlongResultsNeverExecute() {
        val gate = TranscriptGate()
        val token = gate.begin()
        assertNull(gate.accept(token, "  ", true))
        assertNull(gate.accept(token, "가".repeat(501), true))
        assertEquals("그만", gate.accept(token, "그만", true))
    }
}
