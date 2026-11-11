package kr.drivestep.tutor.gleo

import kr.drivestep.tutor.lessoncoach.*
import org.junit.Assert.*
import org.junit.Test

class GleoLessonSessionTest {
    private fun ask(topic: GleoLessonTopic? = null, question: String = "", operation: GleoLessonOperation = GleoLessonOperation.ASK) =
        GleoLessonRequest(operation, question, topic)
    @Test fun followUpRetainsTopicAndChangesExplanation() {
        val s = GleoLessonSession()
        val first = s.answer(ask(GleoLessonTopic.LANE_CHANGE, "차로 변경 알려줘"), null, false, 100)
        assertEquals("asking", first.status)
        assertEquals("question", first.data["missingField"])
        val why = s.answer(ask(question = "왜 그래?"), null, false, 200)
        assertEquals("차로 변경", why.data["topic"])
        assertEquals("REASON", why.data["requestedDetail"])
        assertEquals(first.display, why.data["previousGuidance"])
        assertNotEquals(first.display, why.display)
        assertEquals("STEPS", s.answer(ask(question = "순서대로 자세히 설명해 줘"), null, false, 300).data["requestedDetail"])
    }
    @Test fun missingUnsupportedAndExpiredTopicAskInsteadOfInventing() {
        val s = GleoLessonSession()
        assertEquals("asking", s.answer(ask(question = "왜?"), null, false, 0).status)
        s.answer(ask(GleoLessonTopic.MIRRORS), null, false, 100)
        assertEquals("asking", s.answer(ask(question = "왜?"), null, false, 101 + GleoLessonSession.CONTEXT_TTL).status)
        s.answer(ask(GleoLessonTopic.PARKING), null, false, 700000)
        assertEquals("asking", s.answer(ask(GleoLessonTopic.OTHER, "주식 추천해 줘"), null, false, 700100).status)
    }
    @Test fun repeatUsesFreshConsentAndMeasurements() {
        val s = GleoLessonSession()
        val v = VehicleObservation(100, VehicleInputSource.SDK, 0.0, gear = LessonGear.P)
        s.answer(ask(GleoLessonTopic.LANE_CHANGE), v, true, 100)
        val repeat = s.answer(ask(operation = GleoLessonOperation.REPEAT), v, true, 2000)
        assertNull(repeat.data["speedKmh"]); assertEquals("UNKNOWN", repeat.data["gear"])
        val revoked = s.answer(ask(operation = GleoLessonOperation.REPEAT), v.copy(atMillis = 2100, speedAtMillis = 2100), false, 2100)
        assertNull(revoked.data["speedKmh"]); assertEquals("UNKNOWN", revoked.data["vehicleSource"])
    }
    @Test fun simulatedValuesNeverBecomeRealVehicleContext() {
        val s = GleoLessonSession()
        val r = s.answer(ask(GleoLessonTopic.START_STOP), VehicleObservation(100, VehicleInputSource.SIMULATION, 20.0), true, 100)
        assertNull(r.data["speedKmh"])
        assertEquals(false, r.data["maneuverAuthorized"])
        assertEquals("NOT_OBSERVED", r.data["surroundingTraffic"])
    }
    @Test fun maneuverPermissionQuestionReturnsLimitations() {
        val s = GleoLessonSession()
        val r = s.answer(ask(GleoLessonTopic.LANE_CHANGE, "지금 끼어들어도 돼?"), null, false, 100)
        assertEquals(GleoLessonTopic.LANE_CHANGE.caution, r.display)
        assertEquals(false, r.data["maneuverAuthorized"])
    }
    @Test fun endClearsBoundedTemporaryConversation() {
        val s = GleoLessonSession()
        repeat(10) { s.answer(ask(GleoLessonTopic.PARKING, "질문 $it"), null, false, it.toLong()) }
        assertEquals(6, s.turns.size)
        s.answer(ask(operation = GleoLessonOperation.END), null, false, 20)
        assertTrue(s.ended); assertTrue(s.turns.isEmpty()); assertNull(s.topic)
        assertEquals("asking", s.answer(ask(question = "왜 그래?"), null, false, 30).status)
    }
}
