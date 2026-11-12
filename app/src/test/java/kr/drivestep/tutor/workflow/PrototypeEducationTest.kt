package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test

class PrototypeEducationTest {
    @Test fun doubleParkingRequiresCorrectChoiceInEveryDifferentSituation() {
        val s=DoubleParkingSession()
        repeat(10) { s.next() }; assertEquals(0,s.index); assertFalse(s.completed)
        val explanations=mutableSetOf<String>()
        DoubleParkingCatalog.situations.forEachIndexed { index,item ->
            assertEquals(index,s.index)
            s.choose("invalid"); assertNull(s.selected)
            item.choices.filterNot { it.correct }.forEach { choice ->
                s.choose(choice.id); explanations.add(s.selected!!.explanation)
                s.next(); assertEquals(index,s.index); assertFalse(s.completed)
            }
            s.choose(item.choices.single { it.correct }.id)
            s.pause(); s.next(); s.choose(item.choices.first { !it.correct }.id)
            assertEquals(index,s.index); assertTrue(s.selected!!.correct)
            s.resume(); s.next()
        }
        assertEquals(6,explanations.size); assertTrue(s.completed); assertEquals(3,s.correctCount)
        s.reviewAt(0); assertTrue(s.selected!!.correct); assertTrue(s.completed)
        s.restart(); assertEquals(0,s.correctCount); assertFalse(s.completed); assertNull(s.selected)
    }
    @Test fun retryClearsCurrentAnswerAndCannotSkipToCompletion() {
        val s=DoubleParkingSession(); s.choose("bay"); s.retryCurrent(); s.next()
        assertEquals(0,s.index); assertFalse(s.canAdvance)
        s.choose("bay"); s.next(); s.choose("coordinate"); s.previous()
        s.choose("front"); s.next(); assertEquals(0,s.index); assertFalse(s.completed)
    }
    @Test fun officialNewLessonsRequireComprehensionAndExactSpecification() {
        val repo=ManualRepository()
        for(feature in listOf(ManualFeature.SEAT_VENT,ManualFeature.CHILD_LOCK)) {
            for(vehicle in listOf(Cn7Manual.MANUAL_CLIMATE,Cn7Manual.AUTO_CLIMATE)) {
                val lesson=repo.find(vehicle,feature)!!; val quiz=lesson.quiz!!; val s=ManualLearningSession(lesson)
                assertTrue(lesson.section.contains("PDF")); assertTrue(lesson.applicability!!.contains("장착"))
                s.answer(quiz.answer); assertNull(s.selectedAnswer)
                repeat(4) { s.next() }; s.confirm(); assertFalse(s.learned)
                s.answer(1-quiz.answer); s.confirm(); assertFalse(s.learned)
                s.answer(99); assertEquals(1-quiz.answer,s.selectedAnswer)
                s.answer(quiz.answer); s.previous(); assertFalse(s.canConfirm)
                s.next(); s.confirm(); assertTrue(s.learned)
            }
            assertNull(repo.find(Cn7Manual.AUTO_CLIMATE.copy(year="2025"),feature))
            assertNull(repo.find(ManualRepository.DEMO_VEHICLE,feature))
        }
    }
}
