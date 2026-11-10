package kr.drivestep.tutor.lessoncoach

import org.junit.Assert.*
import org.junit.Test

class LessonUtteranceParserTest {
    private val parser = LessonUtteranceParser()

    @Test fun endingConversationDoesNotConfuseStoppedTrafficOrRequestCancellation() {
        listOf("대화 종료", "대화 종료해 줘", "대화 종료해 주세요", "그만 들어 줘").forEach {
            assertEquals(it, LessonIntent.PAUSE, parser.parse(it).intent)
        }
        assertEquals(LessonIntent.REPORT, parser.parse("옆 차가 멈춰 있어").intent)
        assertEquals(LessonIntent.CANCEL, parser.parse("차로 변경 취소").intent)
    }

    @Test fun synonymsAndShortDirectionAnswers() {
        listOf("왼쪽", "왼편", "좌측", "좌").forEach { assertEquals(LessonDirection.LEFT, parser.parse(it).direction) }
        listOf("오른쪽", "오른편", "우측", "우").forEach { assertEquals(LessonDirection.RIGHT, parser.parse(it).direction) }
        assertEquals(LessonIntent.LANE_CHANGE, parser.parse("우측으로 끼고 싶어요").intent)
    }

    @Test fun conflictingDirectionsAndNegatedDirectionStayUnknown() {
        listOf("왼쪽 오른쪽 차로 변경", "왼쪽이 아니야", "우측은 아니야", "왼쪽 말고", "왼쪽으로 안 가").forEach {
            assertEquals(it, LessonDirection.UNKNOWN, parser.parse(it).direction)
        }
    }

    @Test fun explicitCorrectionsAreUnderstood() {
        assertEquals(LessonDirection.RIGHT, parser.parse("왼쪽 말고 오른쪽").direction)
        assertEquals(LessonDirection.LEFT, parser.parse("오른쪽이 아니라 왼쪽").direction)
        assertEquals(LessonDirection.LEFT, parser.parse("오른쪽 대신 왼쪽").direction)
        assertEquals(LessonDirection.RIGHT, parser.parse("좌회전 말고 우회전").direction)
    }

    @Test fun densityAndMovementAreIndependent() {
        val result = parser.parse("왼쪽 차가 빽빽한데 움직이고 있어")
        assertEquals(TrafficDensity.HIGH, result.traffic.density)
        assertEquals(TrafficFlow.MOVING, result.traffic.flow)
        assertEquals(TrafficFlow.STOPPED, parser.parse("옆 차들이 움직이지 않아").traffic.flow)
    }

    @Test fun negationDoesNotReverseTrafficMeaning() {
        listOf("차가 많지 않아", "차가 많진 않아", "차가 안 많아", "차가 많은 건 아니야").forEach {
            assertEquals(it, TrafficDensity.LOW, parser.parse(it).traffic.density)
        }
        listOf("차가 없는 건 아니야", "차가 없진 않아", "차가 많은지 모르겠어", "차가 많지 않은 건 아니야").forEach {
            assertEquals(it, TrafficDensity.UNKNOWN, parser.parse(it).traffic.density)
        }
        assertEquals(TrafficFlow.UNKNOWN, parser.parse("차들이 멈춰 있지 않아").traffic.flow)
    }

    @Test fun approachingIsOnlyADriverReport() {
        assertEquals(true, parser.parse("뒤에서 차가 계속 와").traffic.approaching)
        assertEquals(false, parser.parse("차가 다가오지 않아").traffic.approaching)
        assertNull(parser.parse("뒤에 차가 오는지 모르겠어").traffic.approaching)
    }

    @Test fun stationaryTrafficDoesNotPauseTheTutor() {
        assertEquals(LessonIntent.REPORT, parser.parse("옆 차가 멈춰 있어").intent)
        assertEquals(LessonIntent.PAUSE, parser.parse("잠깐 멈춰").intent)
        assertEquals(LessonIntent.CANCEL, parser.parse("차로 변경 안 할래").intent)
        assertEquals(LessonIntent.REPEAT, parser.parse("다시 설명해 줘").intent)
        assertEquals(LessonIntent.RESUME, parser.parse("다시 시작해 줘").intent)
    }

    @Test fun entryPermissionQuestionIsNotALaneChangeCommand() {
        listOf("지금 왼쪽으로 들어가도 돼?", "오른쪽으로 바꿔도 돼?", "지금 안전해?").forEach {
            assertEquals(it, LessonIntent.ENTRY_QUESTION, parser.parse(it).intent)
        }
    }

    @Test fun turnsAndUnrelatedSpeechRemainDistinct() {
        assertEquals(LessonIntent.TURN, parser.parse("좌회전해야 해").intent)
        assertEquals(LessonIntent.UNKNOWN, parser.parse("음악 틀어줘").intent)
        assertEquals(LessonIntent.UNKNOWN, parser.parse("   ").intent)
    }

    @Test fun uncertaintyAboutWhatToDoPreservesReportedTraffic() {
        listOf(
            "지금 차가 너무 많은데 어떻게 해야 될지 모르겠어",
            "좌회전으로 끼어들게 할 건데 차가 너무 많아서 어떻게 해야 될지 모르겠어",
            "차가 많아요 어떻게 해야 할지 모르겠어요",
        ).forEach { text ->
            val result = parser.parse(text)
            assertEquals(text, TrafficDensity.HIGH, result.traffic.density)
            assertTrue(text, result.hasTrafficReport)
        }
        val stopped = parser.parse("차들이 멈춰 있는데 어떻게 해야 할지 모르겠어")
        assertEquals(TrafficFlow.STOPPED, stopped.traffic.flow)
        assertEquals(TrafficDensity.LOW, parser.parse("차가 많지 않은데 어떻게 해야 할지 모르겠어").traffic.density)
    }

    @Test fun actionUncertaintyDoesNotEraseUncertaintyAboutTheObservation() {
        listOf(
            "차가 많은지 모르겠어 어떻게 해야 될지 모르겠어",
            "차가 많은지 잘 모르겠는데 어떻게 해야 할지 모르겠어",
            "차가 없는 건 아니야 어떻게 해야 될지 모르겠어",
        ).forEach { text -> assertEquals(text, TrafficDensity.UNKNOWN, parser.parse(text).traffic.density) }
        assertEquals(TrafficFlow.UNKNOWN, parser.parse("차가 멈춰 있는지 모르겠는데 어떻게 해야 할지 모르겠어").traffic.flow)
        assertNull(parser.parse("차가 다가오는지 모르겠는데 어떻게 해야 할지 모르겠어").traffic.approaching)
        assertEquals(LessonIntent.ENTRY_QUESTION,
            parser.parse("왼쪽으로 들어가도 돼 어떻게 해야 될지 모르겠어").intent)
    }
}
