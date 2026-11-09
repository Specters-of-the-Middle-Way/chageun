package kr.drivestep.tutor.coach

import kr.drivestep.tutor.practice.PracticeSession
import kr.drivestep.tutor.practice.SignalPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCoachTest {
    private fun session() = PracticeSession("voice-test", 1000L)

    @Test fun openingAsksForParkedOffBaselineWithoutClaimingSuccess() {
        val session = session()
        assertEquals(SignalCoach.START, SignalCoach.onStart(session.snapshot()))
        assertNull(SignalCoach.onEvent(session.snapshot()))
        assertEquals(SignalCoach.START, SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1001)))
        assertEquals(SignalCoach.LEFT_ON, SignalCoach.onEvent(session.accept(SignalPattern.OFF, 1002)))
        assertNull(SignalCoach.onStart(session.snapshot()))
    }

    @Test fun completeExerciseNarratesOnlyConfirmedActions() {
        val session = session()
        val signals = listOf(SignalPattern.OFF, SignalPattern.LEFT, SignalPattern.OFF, SignalPattern.RIGHT, SignalPattern.OFF)
        val expected = listOf(SignalCoach.LEFT_ON, SignalCoach.LEFT_OFF, SignalCoach.RIGHT_ON, SignalCoach.RIGHT_OFF, SignalCoach.COMPLETED)
        signals.forEachIndexed { i, signal ->
            assertEquals(expected[i], SignalCoach.onEvent(session.accept(signal, 1001L + i)))
        }
        assertNull(SignalCoach.onStart(session.snapshot()))
    }

    @Test fun duplicateAndOutOfOrderCallbacksAreSilent() {
        val session = session()
        session.accept(SignalPattern.OFF, 1100)
        assertNull(SignalCoach.onEvent(session.accept(SignalPattern.OFF, 1101)))
        assertNull(SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1099)))
        assertEquals(SignalCoach.LEFT_OFF, SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1102)))
        assertNull(SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1103)))
    }

    @Test fun oppositeDirectionNamesActualSignalAndRetriesAfterOff() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        assertEquals(SignalCoach.WRONG_RIGHT_FOR_LEFT, SignalCoach.onEvent(session.accept(SignalPattern.RIGHT, 1002)))
        assertEquals(SignalCoach.RETRY_LEFT, SignalCoach.onEvent(session.accept(SignalPattern.OFF, 1003)))
        assertEquals(SignalCoach.LEFT_OFF, SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1004)))
    }

    @Test fun switchingStraightToCorrectSideStillRequestsTheMissingOffStep() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.RIGHT, 1002)
        assertEquals(SignalCoach.RESET_BEFORE_LEFT, SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1003)))
        assertEquals(SignalCoach.RETRY_LEFT, SignalCoach.onEvent(session.accept(SignalPattern.OFF, 1004)))
    }

    @Test fun rightSideCorrectionAndRetryUseRightSideInstructions() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        session.accept(SignalPattern.OFF, 1003)
        assertEquals(SignalCoach.WRONG_LEFT_FOR_RIGHT, SignalCoach.onEvent(session.accept(SignalPattern.LEFT, 1004)))
        assertEquals(SignalCoach.RESET_BEFORE_RIGHT, SignalCoach.onEvent(session.accept(SignalPattern.RIGHT, 1005)))
        assertEquals(SignalCoach.RETRY_RIGHT, SignalCoach.onEvent(session.accept(SignalPattern.OFF, 1006)))
        assertEquals(SignalCoach.RIGHT_OFF, SignalCoach.onEvent(session.accept(SignalPattern.RIGHT, 1007)))
    }

    @Test fun wrongDirectionDuringReleaseDoesNotProduceCompletionPraise() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        assertEquals(SignalCoach.WRONG_RIGHT_FOR_LEFT, SignalCoach.onEvent(session.accept(SignalPattern.RIGHT, 1003)))
        assertEquals(SignalCoach.RETRY_LEFT, SignalCoach.onEvent(session.accept(SignalPattern.OFF, 1004)))
    }

    @Test fun unknownSignalStopsWithNeutralExplanation() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        val cue = SignalCoach.onEvent(session.accept(SignalPattern.UNKNOWN, 1002))
        assertEquals(SignalCoach.UNKNOWN_SIGNAL, cue)
        assertFalse(cue!!.text.contains("잘했"))
        assertFalse(cue.text.contains("완료"))
    }

    @Test fun bothSignalsStopWithNeutralExplanation() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        assertEquals(SignalCoach.BOTH_SIGNALS, SignalCoach.onEvent(session.accept(SignalPattern.BOTH, 1002)))
    }

    @Test fun lifecycleInterruptionDoesNotReplayPreviousPraise() {
        val session = session()
        session.accept(SignalPattern.OFF, 1001)
        session.accept(SignalPattern.LEFT, 1002)
        assertNull(SignalCoach.onEvent(session.interrupt("DRIVING_RESTRICTION", 1003)))
        assertNull(SignalCoach.onStart(session.snapshot()))
    }

    @Test fun clipCatalogHasUniqueResourceSafeIdsAndNonblankText() {
        assertEquals(SignalCoach.catalog.size, SignalCoach.catalog.map { it.id }.toSet().size)
        assertTrue(SignalCoach.catalog.all { it.id.matches(Regex("[a-z][a-z0-9_]*")) && it.text.isNotBlank() })
        assertTrue(SignalCoach.catalog.contains(SignalCoach.COMPLETED))
        assertFalse(SignalCoach.COMPLETED.text.contains("안전운전"))
        assertFalse(SignalCoach.COMPLETED.text.contains("숙련"))
    }
}
