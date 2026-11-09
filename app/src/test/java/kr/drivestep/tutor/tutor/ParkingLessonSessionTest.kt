package kr.drivestep.tutor.tutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingLessonSessionTest {
    @Test
    fun cannotAdvanceBeforeCorrectAnswer() {
        val session = ParkingLessonSession()
        assertFalse(session.next())
        val first = ParkingLesson.steps.first()
        val wrong = 1 - first.correctChoiceIndex
        assertEquals(first.retryCue, session.answer(wrong))
        assertFalse(session.next())
        assertEquals(0, session.snapshot().stepIndex)
        assertFalse(session.snapshot().answerConfirmed)
        assertEquals(1, session.snapshot().wrongAttemptCount)
    }

    @Test
    fun retryKeepsFeedbackAndThenAllowsOneAdvance() {
        val session = ParkingLessonSession()
        val first = ParkingLesson.steps.first()
        session.answer(1 - first.correctChoiceIndex)
        assertEquals(first.retryCue, session.snapshot().latestCue)
        session.answer(first.correctChoiceIndex)
        assertTrue(session.snapshot().answerConfirmed)
        assertEquals(first.correctCue, session.snapshot().latestCue)
        assertNull(session.answer(first.correctChoiceIndex))
        assertTrue(session.next())
        assertEquals(1, session.snapshot().stepIndex)
        assertFalse(session.next())
        assertEquals(ParkingLesson.steps[1].intro, session.snapshot().latestCue)
    }

    @Test
    fun completionRequiresAllFiveConfirmedAnswers() {
        val session = ParkingLessonSession()
        ParkingLesson.steps.forEachIndexed { index, step ->
            assertFalse(session.snapshot().completed)
            assertEquals(index, session.snapshot().stepIndex)
            assertNotNull(session.answer(step.correctChoiceIndex))
            assertTrue(session.next())
        }
        assertTrue(session.snapshot().completed)
        assertEquals(5, session.snapshot().confirmedStepCount)
        assertEquals(ParkingLesson.completeCue, session.snapshot().latestCue)
        assertNull(session.answer(0))
        assertFalse(session.next())
    }

    @Test
    fun invalidChoiceAndRestartDoNotCreateProgress() {
        val session = ParkingLessonSession()
        val original = session.snapshot()
        assertNull(session.answer(-1))
        assertNull(session.answer(2))
        assertEquals(original, session.snapshot())
        session.answer(ParkingLesson.steps.first().correctChoiceIndex)
        session.next()
        session.restart()
        assertEquals(original, session.snapshot())
    }

    @Test
    fun audioCatalogHasUniqueStableIdsAndTwoChoicesPerStep() {
        assertEquals(ParkingLesson.cues.size, ParkingLesson.cues.map { it.id }.distinct().size)
        assertEquals(16, ParkingLesson.cues.size)
        ParkingLesson.steps.forEach { step ->
            assertEquals(2, step.choices.size)
            assertTrue(step.correctChoiceIndex in step.choices.indices)
        }
    }
}
