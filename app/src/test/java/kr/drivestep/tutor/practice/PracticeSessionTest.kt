package kr.drivestep.tutor.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeSessionTest {
    private fun session() = PracticeSession("test-session", 1000L)

    @Test fun completesOnlyAfterTheWholeObservedSequence() {
        val session = session()
        val sequence = listOf(SignalPattern.OFF, SignalPattern.LEFT, SignalPattern.OFF, SignalPattern.RIGHT, SignalPattern.OFF)
        sequence.forEachIndexed { index, pattern ->
            val result = session.accept(pattern, 1001L + index)
            assertEquals(index + 1, result.completedStepCount)
            assertEquals(index == 4, result.isTerminal)
        }
        val result = session.snapshot()
        assertEquals(PracticeOutcome.COMPLETED, result.outcome)
        assertEquals(1005L, result.endedAtMillis)
        assertEquals(5, result.events.size)
        assertEquals(PracticeAction.NONE, result.nextAction)
    }

    @Test fun initialAlreadyOnSignalCannotCountAsAnExercise() {
        val session = session()
        session.accept(SignalPattern.LEFT, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        assertEquals(0, session.snapshot().completedStepCount)
        assertEquals(PracticeAction.TURN_OFF_ALL, session.snapshot().nextAction)
        session.accept(SignalPattern.OFF, 1003)
        assertEquals(PracticeAction.TURN_LEFT_ON, session.snapshot().nextAction)
        session.accept(SignalPattern.LEFT, 1004)
        assertEquals(2, session.snapshot().completedStepCount)
    }

    @Test fun duplicateCallbacksNeverAdvanceTheExercise() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        repeat(50) { session.accept(SignalPattern.OFF, 1002L + it) }
        assertEquals(1, session.snapshot().completedStepCount)
        session.accept(SignalPattern.LEFT, 1100)
        repeat(50) { session.accept(SignalPattern.LEFT, 1101L + it) }
        assertEquals(2, session.snapshot().completedStepCount)
        assertEquals(ObservationDisposition.DUPLICATE, session.snapshot().events.last().disposition)
    }

    @Test fun wrongDirectionNeedsOffBeforeTheCorrectOnCanCount() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.RIGHT, 1002)
        session.accept(SignalPattern.LEFT, 1003)
        assertEquals(1, session.snapshot().completedStepCount)
        assertEquals(PracticeAction.TURN_OFF_ALL, session.snapshot().nextAction)
        session.accept(SignalPattern.OFF, 1004)
        session.accept(SignalPattern.LEFT, 1005)
        assertEquals(2, session.snapshot().completedStepCount)
    }

    @Test fun switchingToWrongDirectionDuringReleaseRepeatsThatPart() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        session.accept(SignalPattern.RIGHT, 1003)
        assertEquals(PracticeStage.WAIT_LEFT_ON, session.snapshot().stage)
        assertEquals(1, session.snapshot().completedStepCount)
        session.accept(SignalPattern.OFF, 1004)
        assertEquals(PracticeStage.WAIT_LEFT_ON, session.snapshot().stage)
        session.accept(SignalPattern.LEFT, 1005)
        session.accept(SignalPattern.OFF, 1006)
        assertEquals(PracticeStage.WAIT_RIGHT_ON, session.snapshot().stage)
    }

    @Test fun bothIndicatorsInterruptInsteadOfCompletingATurn() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        val result = session.accept(SignalPattern.BOTH, 1002)
        assertEquals(PracticeOutcome.INTERRUPTED, result.outcome)
        assertEquals("BOTH_INDICATORS_ACTIVE", result.interruptionReason)
        assertEquals(1, result.completedStepCount)
        assertEquals(ObservationDisposition.INVALID_SIGNAL, result.events.last().disposition)
    }

    @Test fun unknownSignalInterruptsAndLaterReadingsCannotResume() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        val result = session.accept(SignalPattern.UNKNOWN, 1003)
        assertEquals("SIGNAL_UNKNOWN", result.interruptionReason)
        assertEquals(2, result.completedStepCount)
        assertEquals(result, session.accept(SignalPattern.OFF, 1004))
    }

    @Test fun olderCallbacksAreRecordedButDoNotChangeCurrentState() {
        val session = session()
        session.accept(SignalPattern.OFF, 1100)
        session.accept(SignalPattern.RIGHT, 1050)
        assertEquals(SignalPattern.OFF, session.snapshot().lastSignal)
        assertEquals(ObservationDisposition.OUT_OF_ORDER, session.snapshot().events.last().disposition)
        assertEquals(PracticeStage.WAIT_LEFT_OFF, session.accept(SignalPattern.LEFT, 1101).stage)
    }

    @Test fun preSessionReadingsCannotSupplyTheBaseline() {
        val session = session()
        val result = session.accept(SignalPattern.OFF, 999)
        assertEquals(PracticeStage.WAIT_BASELINE_OFF, result.stage)
        assertNull(result.lastSignal)
        assertEquals(ObservationDisposition.OUT_OF_ORDER, result.events.last().disposition)
    }

    @Test fun explicitInterruptionPreservesPartialProgressAndReason() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        val result = session.interrupt("SOURCE_DISCONNECTED", 1002)
        assertEquals(PracticeOutcome.INTERRUPTED, result.outcome)
        assertEquals(1, result.completedStepCount)
        assertEquals("SOURCE_DISCONNECTED", result.interruptionReason)
        assertEquals(1002L, result.endedAtMillis)
        assertEquals(result, session.accept(SignalPattern.LEFT, 1003))
    }

    @Test fun snapshotsDoNotChangeAfterLaterReadings() {
        val session = session()
        val first = session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        assertEquals(1, first.events.size)
        assertEquals(PracticeStage.WAIT_LEFT_ON, first.stage)
        assertEquals(2, session.snapshot().events.size)
    }

    @Test fun completionIsTerminalAndCannotBeOverwrittenByDisconnect() {
        val session = session()
        listOf(SignalPattern.OFF, SignalPattern.LEFT, SignalPattern.OFF, SignalPattern.RIGHT, SignalPattern.OFF)
            .forEachIndexed { i, signal -> session.accept(signal, 1001L + i) }
        val completed = session.snapshot()
        assertTrue(completed.isTerminal)
        assertEquals(completed, session.interrupt("SOURCE_DISCONNECTED", 2000))
        assertEquals(completed, session.accept(SignalPattern.UNKNOWN, 2001))
    }

    @Test fun elapsedTimeAndSnapshotsDoNotAdvancePractice() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.OFF, 9_999_999)
        assertEquals(1, session.snapshot().completedStepCount)
        assertFalse(session.snapshot().isTerminal)
    }
}
