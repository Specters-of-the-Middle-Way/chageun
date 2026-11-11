package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test

class RoadLearningTest {
    @Test fun doublingSpeedHalvesTimeAtTheSameDistance() {
        assertEquals(3.6, FollowingGap.seconds(30.0,30.0)!!, 0.00001)
        assertEquals(1.8, FollowingGap.seconds(60.0,30.0)!!, 0.00001)
        assertEquals(3.6, FollowingGap.seconds(60.0,60.0)!!, 0.00001)
    }
    @Test fun stoppedUnknownAndInvalidValuesNeverBecomeAnAdequateGap() {
        listOf(0.0,1.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach { assertNull(FollowingGap.seconds(it,30.0)) }
        listOf(null,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach { assertNull(FollowingGap.seconds(30.0,it)) }
        assertEquals(0.0,FollowingGap.seconds(30.0,0.0)!!,0.0)
        assertNull(FollowingGap.distance(0.0,3.0)); assertNull(FollowingGap.distance(30.0,Double.NaN))
        assertNull(FollowingGap.distance(30.0,-1.0))
    }
    @Test fun largerExampleTargetRequiresProportionallyMoreDistance() {
        val state = GapStudyState()
        assertTrue(state.reachesExample!!)
        state.speed(60.0); assertFalse(state.reachesExample!!)
        assertEquals(50.0,state.targetMeters!!,0.00001)
        state.target(6.0); assertEquals(100.0,state.targetMeters!!,0.00001)
        state.speed(0.0); assertNull(state.headwaySeconds); assertNull(state.targetMeters); assertNull(state.reachesExample)
    }
    @Test fun studyRejectsInvalidAndOutOfRangeSelections() {
        val state = GapStudyState()
        state.speed(Double.NaN); state.speed(121.0); state.gap(-1.0); state.gap(Double.POSITIVE_INFINITY); state.target(0.0)
        assertEquals(30.0,state.speedKmh,0.0); assertEquals(30.0,state.gapMeters,0.0); assertEquals(3.0,state.targetSeconds,0.0)
        state.gap(0.0); assertFalse(state.reachesExample!!)
    }
    @Test fun newLessonsHaveSourcesValidIllustrationsAndRealPracticeTargets() {
        assertEquals(4,RoadLearningCatalog.guides.size)
        assertEquals(7,DrivingGuideCatalog.guides.size)
        RoadLearningCatalog.guides.forEach { lesson ->
            assertNotNull(ScenarioCatalog.find(lesson.relatedTask!!))
            assertTrue(lesson.sourceUrl.startsWith("https://"))
            assertTrue(lesson.illustrations.isEmpty() || lesson.illustrations.size == lesson.steps.size)
            val state = DrivingGuideSession(lesson)
            state.answer(lesson.answer); assertFalse(state.completed)
            repeat(3) { state.next() }
            state.answer(1-lesson.answer); assertFalse(state.completed)
            state.answer(lesson.answer); assertTrue(state.completed)
            state.previous(); assertFalse(state.completed)
        }
    }
    @Test fun fastApproachCannotPassTheFollowingExerciseOnTenMetersAlone() {
        val session = ScenarioSession(ScenarioCatalog.find("D02")!!).apply { resume(); pedals(1.0,0.0) }
        repeat(80) { session.tick(.1) }
        assertTrue(session.snapshot().distance > 40.0)
        assertEquals(EventStatus.ACTIVE,session.snapshot().status)
        assertTrue(session.snapshot().instruction.contains("목표 3초"))
    }
    @Test fun followingExerciseCanRecoverAfterReducingSpeedAndRebuildingGap() {
        val session = ScenarioSession(ScenarioCatalog.find("D02")!!).apply { resume(); pedals(1.0,0.0) }
        repeat(80) { session.tick(.1) }
        session.pedals(0.0,1.0); repeat(200) { session.tick(.1) }
        assertEquals(EventStatus.ACTIVE,session.snapshot().status)
        assertNull(session.snapshot().leadHeadwaySeconds)
        repeat(60) {
            session.pedals(if(session.snapshot().speedKmh < 18) 1.0 else 0.0,0.0); session.tick(.1)
        }
        assertEquals(EventStatus.COMPLETED,session.snapshot().status)
    }
}
