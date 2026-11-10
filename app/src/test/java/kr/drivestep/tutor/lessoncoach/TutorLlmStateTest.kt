package kr.drivestep.tutor.lessoncoach

import org.junit.Assert.*
import org.junit.Test

class TutorLlmStateTest {
    private fun reply(intent: LessonIntent = LessonIntent.UNKNOWN, text: String = "사이드미러 확인과 사각지대 확인을 나눠 연습해 볼까요?", risk: String = "EDUCATION") =
        TutorLlmReply(TutorLlmProvider.OPENAI, ParsedLessonUtterance(intent), text, risk)

    @Test fun perChannelFreshnessAndProvenanceSurviveContextCapture() {
        val snapshot = LessonSnapshot(vehicle = VehicleObservation(10000, VehicleInputSource.SDK,
            speedKmh = 0.0, signal = LessonSignal.LEFT, gear = LessonGear.P, speedAtMillis = 8000, signalAtMillis = 9999))
        val context = TutorLlmContext.build(snapshot, 10000, true)
        val car = context["vehicle"] as Map<*, *>
        assertEquals("SDK", car["source"])
        assertNull((car["speedKmh"] as Map<*, *>)["value"])
        assertEquals(2000L, (car["speedKmh"] as Map<*, *>)["ageMs"])
        assertEquals("LEFT", (car["signal"] as Map<*, *>)["value"])
        assertTrue((context["limitations"] as List<*>).contains("NO_ADJACENT_TRAFFIC_SENSOR"))
        assertFalse(context.containsKey("profile"))
        assertFalse(context.containsKey("location"))
    }

    @Test fun historyResetsAtPauseAndIsBoundedWithoutSensorEvents() {
        val s = LessonCoachSession()
        s.start(LessonTopic.LANE_CHANGE, 0)
        repeat(10) { s.acceptUtterance("설명 부탁해 $it", SpeechInputSource.TEXT_DEMO, 1000L * (it + 1)) }
        val history = TutorLlmContext.history(s.snapshot(), 12000)
        assertEquals(12, history.size)
        assertTrue(history.all { it["source"] in setOf("TEXT_DEMO", "APP") })
        s.pause(13000); s.resume(14000)
        assertEquals(1, TutorLlmContext.history(s.snapshot(), 14500).size)
        assertTrue(TutorLlmContext.history(s.snapshot(), 200000).isEmpty())
    }

    @Test fun staleTrafficAndNavigationAreNotCurrentContext() {
        val s = LessonSnapshot(reportedTraffic = ReportedTraffic(TrafficDensity.HIGH, atMillis = 1),
            navigation = NavigationObservation(1, NavigationInputSource.SIMULATION, "demo", LessonDirection.LEFT, 100.0))
        val c = TutorLlmContext.build(s, 20000, false)
        assertNull(c["reportedTraffic"]); assertNull(c["navigation"])
        assertEquals("SIMULATION", c["mode"])
    }

    @Test fun networkResponseCannotReviveCancelledOrChangedLesson() {
        val gate = TutorLlmRequestGate()
        val ticket = gate.begin(5, 100)
        assertTrue(gate.accepts(ticket, 5, 200))
        assertFalse(gate.accepts(ticket, 6, 200))
        assertFalse(gate.accepts(ticket, 5, 10200))
        assertTrue("Expired current requests still need a local timeout reply", gate.isCurrent(ticket, 5))
        assertFalse(gate.isCurrent(ticket, 6))
        gate.cancel()
        assertFalse(gate.isCurrent(ticket, 5))
        assertFalse(gate.accepts(ticket, 5, 200))
        gate.begin(5, 200)
        assertFalse(gate.accepts(ticket, 5, 300))
    }

    @Test fun modelDirectionsAlwaysRequireSeparateConfirmationEvenWithTypedTesting() {
        val s = LessonCoachSession(); s.start(LessonTopic.LANE_CHANGE, 0)
        val direction = ParsedLessonUtterance(LessonIntent.LANE_CHANGE, LessonDirection.LEFT)
        val candidate = s.acceptUtterance("그쪽으로 옮기고 싶어", SpeechInputSource.TEXT_DEMO, 100, direction)
        assertTrue(candidate.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, candidate.direction)
        val changed = s.acceptUtterance("응 오른쪽", SpeechInputSource.PLEOS_STT, 200,
            ParsedLessonUtterance(LessonIntent.CONFIRM, LessonDirection.RIGHT))
        assertTrue(changed.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, changed.direction)
        val confirmed = s.acceptUtterance("네 그 방향이 맞습니다", SpeechInputSource.PLEOS_STT, 300,
            ParsedLessonUtterance(LessonIntent.CONFIRM))
        assertEquals(LessonDirection.RIGHT, confirmed.direction)
    }

    @Test fun modelCannotRewriteDirectionConfirmationOrEntryDecision() {
        assertFalse(TutorLlmPolicy.canUseWording(reply(), LessonSnapshot(phase = LessonPhase.ACTIVE, awaitingConfirmation = true)))
        assertEquals(LessonIntent.ENTRY_QUESTION, TutorLlmPolicy.interpretation(reply(risk = "CONTROL")).intent)
        listOf("지금 들어가세요", "안전합니다", "핸들을 30도 돌리세요", "카메라로 확인했어요").forEach {
            assertFalse(it, TutorLlmPolicy.canUseWording(reply(text = it), LessonSnapshot(phase = LessonPhase.ACTIVE)))
        }
    }

    @Test fun acceptedEducationIsTheSingleStoredReplyAndFixtureIsClearlyLabelled() {
        val s = LessonCoachSession(); s.start(LessonTopic.LANE_CHANGE, 0)
        s.acceptUtterance("미러에서 뭘 봐야 해요", SpeechInputSource.TEXT_DEMO, 100, ParsedLessonUtterance(LessonIntent.UNKNOWN))
        val before = s.snapshot().events.size
        val r = reply().copy(provider = TutorLlmProvider.TEST_FIXTURE)
        val after = s.applyLlmWording(r, 110)
        assertEquals(before, after.events.size)
        assertEquals(r.text, after.reply)
        assertEquals("시험 응답 · 실제 LLM 아님", after.events.last().title)
        assertEquals("시험 문장 입력", after.events[after.events.lastIndex - 1].title)
    }
}
