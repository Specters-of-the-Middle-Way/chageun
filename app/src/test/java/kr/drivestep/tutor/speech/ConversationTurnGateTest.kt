package kr.drivestep.tutor.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTurnGateTest {
    @Test fun explicitStartAllowsOneTurnOnly() {
        val gate = ConversationTurnGate()
        assertNull(gate.ticket())
        gate.start(100)
        val first = requireNotNull(gate.ticket())
        assertTrue(gate.claim(first, 100, inputBusy = false, outputBusy = false))
        assertFalse(gate.claim(first, 100, inputBusy = false, outputBusy = false))
        assertNull(gate.ticket())
    }

    @Test fun successfulResponseWaitsForAcousticTailAndBothDevicesToBeIdle() {
        val gate = ConversationTurnGate()
        gate.start(0)
        gate.waitForResponse()
        assertNull(gate.ticket())
        gate.responseFinished(1_000)
        val next = requireNotNull(gate.ticket())
        assertFalse(gate.claim(next, 1_399, inputBusy = false, outputBusy = false))
        assertFalse(gate.claim(next, 1_400, inputBusy = true, outputBusy = false))
        assertFalse(gate.claim(next, 1_400, inputBusy = false, outputBusy = true))
        assertTrue(gate.claim(next, 1_400, inputBusy = false, outputBusy = false))
    }

    @Test fun stoppedConversationCannotBeRevivedByDelayedCompletionOrTimer() {
        val gate = ConversationTurnGate()
        gate.start(0)
        val stale = requireNotNull(gate.ticket())
        gate.stop()
        gate.responseFinished(100)
        assertFalse(gate.isActive)
        assertNull(gate.ticket())
        assertFalse(gate.claim(stale, 1_000, inputBusy = false, outputBusy = false))
    }

    @Test fun newExplicitStartDoesNotAcceptOldConversationTimer() {
        val gate = ConversationTurnGate()
        gate.start(0)
        val stale = requireNotNull(gate.ticket())
        gate.stop()
        gate.start(1_000)
        assertFalse(gate.claim(stale, 2_000, inputBusy = false, outputBusy = false))
        assertTrue(gate.claim(requireNotNull(gate.ticket()), 2_000, inputBusy = false, outputBusy = false))
    }

    @Test fun newOutputInvalidatesQueuedListenUntilItFinishes() {
        val gate = ConversationTurnGate()
        gate.start(0)
        gate.responseFinished(100)
        val stale = requireNotNull(gate.ticket())
        gate.waitForResponse()
        assertFalse(gate.claim(stale, 10_000, inputBusy = false, outputBusy = false))
        gate.responseFinished(11_000)
        assertNotNull(gate.ticket())
        assertTrue(gate.claim(requireNotNull(gate.ticket()), 11_400, inputBusy = false, outputBusy = false))
    }
}
