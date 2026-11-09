package kr.drivestep.tutor.growth

import kr.drivestep.tutor.coach.CoachCue
import org.junit.Assert.*
import org.junit.Test

class GrowthSessionTest {
    private fun session(id: String = "ready") = GrowthSession(GrowthCurriculum.find(id)!!)
    private fun correct(session: GrowthSession): GrowthChoice = session.lesson.steps[session.snapshot().stepIndex].choices.single { it.correct }
    private fun wrong(session: GrowthSession): GrowthChoice = session.lesson.steps[session.snapshot().stepIndex].choices.first { !it.correct }

    @Test fun initialStateOffersTheFirstCueWithoutAwardingPoints() {
        val session = session()
        val s = session.snapshot()
        assertEquals(0, s.score)
        assertEquals(0, s.completedSteps)
        assertFalse(s.completed)
        assertFalse(s.answeredCorrectly)
        assertEquals(session.lesson.steps.first().cue, s.feedback)
        assertTrue(s.attempts.isEmpty())
    }

    @Test fun nextCannotSkipAnUnansweredStep() {
        val session = session()
        assertEquals(session.snapshot(), session.next())
    }

    @Test fun invalidOrFutureChoiceDoesNotChangeAnything() {
        val session = session()
        val baseline = session.snapshot()
        assertEquals(baseline, session.choose("missing"))
        assertEquals(baseline, session.choose(session.lesson.steps[1].choices.first().id))
    }

    @Test fun incorrectChoiceGivesFeedbackAndFreezesSceneWithoutAdvancing() {
        val session = session()
        val s = session.choose(wrong(session).id)
        assertEquals(0, s.stepIndex)
        assertEquals(0, s.completedSteps)
        assertEquals(1, s.mistakes)
        assertFalse(s.answeredCorrectly)
        assertEquals("STOP", s.animation)
        assertEquals(wrong(session).feedback, s.feedback)
        assertEquals(s, session.next())
    }

    @Test fun repeatedIncorrectTapDoesNotMultiplyPenaltyOrRevision() {
        val session = session()
        val id = wrong(session).id
        val once = session.choose(id)
        repeat(5) { assertEquals(once, session.choose(id)) }
    }

    @Test fun correctAnswerAfterRetryUnlocksNextAndKeepsMistake() {
        val session = session()
        session.choose(wrong(session).id)
        val answer = correct(session)
        val s = session.choose(answer.id)
        assertTrue(s.answeredCorrectly)
        assertEquals(1, s.completedSteps)
        assertEquals(1, s.mistakes)
        assertEquals(23, s.score)
        assertEquals(answer.animation, s.animation)
        assertEquals(2, s.attempts.size)
    }

    @Test fun answeredStepIgnoresDoubleTapAndAnotherChoice() {
        val session = session()
        val correct = correct(session)
        val wrong = wrong(session)
        val once = session.choose(correct.id)
        assertEquals(once, session.choose(correct.id))
        assertEquals(once, session.choose(wrong.id))
    }

    @Test fun nextResetsSelectionButKeepsEarnedProgress() {
        val session = session()
        val oldCorrect = correct(session).id
        session.choose(oldCorrect)
        val next = session.next()
        assertEquals(1, next.stepIndex)
        assertEquals(1, next.completedSteps)
        assertFalse(next.answeredCorrectly)
        assertNull(next.selectedChoiceId)
        assertEquals(session.lesson.steps[1].cue, next.feedback)
        assertEquals(next, session.choose(oldCorrect))
        assertEquals(next, session.next())
    }

    @Test fun lastCorrectAnswerCompletesExactlyOnceAndCapsScoreAt100() {
        val session = session()
        for (index in session.lesson.steps.indices) {
            val result = session.choose(correct(session).id)
            if (index < session.lesson.steps.lastIndex) {
                assertFalse(result.completed)
                session.next()
            }
        }
        val done = session.snapshot()
        assertTrue(done.completed)
        assertEquals(100, done.score)
        assertEquals(done.totalSteps, done.completedSteps)
        assertEquals(done.totalSteps - 1, done.stepIndex)
        assertEquals(done, session.next())
        assertEquals(done, session.choose(session.lesson.steps.last().choices.first().id))
    }

    @Test fun completedScoreRetainsEveryUniqueIncorrectDecision() {
        val session = session()
        repeat(session.lesson.steps.size) {
            session.choose(wrong(session).id)
            session.choose(correct(session).id)
            session.next()
        }
        assertTrue(session.snapshot().completed)
        assertEquals(3, session.snapshot().mistakes)
        assertEquals(70, session.snapshot().score)
    }

    @Test fun manyDistinctWrongAnswersNeverProduceNegativeScores() {
        val good = GrowthChoice("good", "good", true, CoachCue("ok", "ok"), "WAIT")
        val bad = (1..15).map { GrowthChoice("bad_$it", "bad", false, CoachCue("retry_$it", "retry"), "PROCEED") }
        val lesson = GrowthLesson("bounds", "bounds", "bounds", 1, Competency.RULES, "setup",
            listOf(GrowthStep("step", "prompt", CoachCue("intro", "intro"), bad + good)), "test")
        val session = GrowthSession(lesson)
        bad.forEach { assertEquals(0, session.choose(it.id).score) }
        assertEquals(0, session.choose(good.id).score)
        assertTrue(session.snapshot().completed)
    }

    @Test fun snapshotsDoNotChangeWhenFurtherAnswersArrive() {
        val session = session()
        val original = session.snapshot()
        session.choose(wrong(session).id)
        session.choose(correct(session).id)
        assertTrue(original.attempts.isEmpty())
        assertEquals(0, original.score)
        assertEquals(2, session.snapshot().attempts.size)
    }

    @Test fun everyLessonHasAnUnambiguousPlayablePathAndUniqueVoiceIds() {
        assertEquals(9, GrowthCurriculum.lessons.size)
        assertEquals(27, GrowthCurriculum.lessons.sumOf { it.steps.size })
        val ids = GrowthCurriculum.cues.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
        assertEquals(81, ids.size)
        GrowthCurriculum.lessons.forEach { lesson ->
            val session = GrowthSession(lesson)
            lesson.steps.forEach {
                session.choose(correct(session).id)
                session.next()
            }
            assertTrue(session.snapshot().completed)
            assertEquals(100, session.snapshot().score)
        }
    }

    @Test fun wrongAnswersNeverUseMovingAnimations() {
        GrowthCurriculum.lessons.flatMap { it.steps }.flatMap { it.choices }.filterNot { it.correct }.forEach {
            assertEquals("STOP", it.animation)
        }
    }

    @Test fun lessonWithDuplicateChoiceIdsIsRejected() {
        val lesson = GrowthCurriculum.lessons.first()
        val step = lesson.steps.first()
        val invalid = lesson.copy(steps = listOf(step, step.copy(id = "other")))
        assertThrows(IllegalArgumentException::class.java) { GrowthSession(invalid) }
    }
}
