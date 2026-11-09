package kr.drivestep.tutor.lessoncoach

import org.junit.Assert.*
import org.junit.Test

/** ASR can return a confident-looking wrong direction. A final transcript is not confirmation. */
class LessonVoiceConfirmationTest {
    private fun session() = LessonCoachSession().apply { start(LessonTopic.LANE_CHANGE, 0) }
    private fun voice(session: LessonCoachSession, text: String, at: Long = 100) =
        session.acceptUtterance(text, SpeechInputSource.PLEOS_STT, at)
    private fun vehicle(at: Long, signal: LessonSignal) =
        VehicleObservation(at, VehicleInputSource.SIMULATION, 20.0, signal, LessonGear.D)

    @Test fun wrongRightTranscriptOnlyProducesAReadbackQuestion() {
        val s = session()
        val heard = voice(s, "오른쪽 차로로 변경하고 싶어요")
        assertTrue(heard.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, heard.direction)
        assertTrue(heard.reply.contains("오른쪽 차로 변경 준비가 맞나요"))
        assertFalse(heard.reply.contains("미러와 사각지대부터 확인해요"))
        s.acceptVehicle(vehicle(200, LessonSignal.OFF), 200)
        val action = s.acceptVehicle(vehicle(300, LessonSignal.RIGHT), 300)
        assertEquals(heard.cueRevision, action.cueRevision)
        val navigation = s.acceptNavigation(NavigationObservation(400, NavigationInputSource.SIMULATION,
            "one", LessonDirection.RIGHT, 200.0), 400)
        assertEquals(heard.cueRevision, navigation.cueRevision)
    }

    @Test fun rejectingWrongDirectionClearsCandidateBeforeAskingAgain() {
        val s = session()
        voice(s, "오른쪽 차로 변경 차가 많아")
        val rejected = voice(s, "아니야", 1000)
        assertFalse(rejected.awaitingConfirmation)
        assertTrue(rejected.awaitingDirection)
        assertEquals(LessonDirection.UNKNOWN, rejected.direction)
        assertEquals(TrafficDensity.UNKNOWN, rejected.reportedTraffic.density)
        val corrected = voice(s, "왼쪽", 2000)
        assertTrue(corrected.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, corrected.direction)
        assertTrue(corrected.reply.contains("왼쪽 차로 변경 준비가 맞나요"))
    }

    @Test fun separateConfirmationActivatesOnlyTheConfirmedDirection() {
        val s = session()
        voice(s, "왼쪽 차로 변경 차가 많고 멈춰 있어")
        val result = voice(s, "맞아요", 1000)
        assertFalse(result.awaitingConfirmation)
        assertEquals(LessonDirection.LEFT, result.direction)
        assertEquals(TrafficDensity.HIGH, result.reportedTraffic.density)
        assertEquals(TrafficFlow.STOPPED, result.reportedTraffic.flow)
        assertTrue(result.reply.contains("왼쪽 차로 변경 준비를 도와드릴게요"))
    }

    @Test fun directionCorrectionMustBeConfirmedAgainAndDropsOtherSidesTraffic() {
        val s = session()
        voice(s, "오른쪽 차로 변경 차가 많아")
        val corrected = voice(s, "오른쪽 말고 왼쪽", 1000)
        assertTrue(corrected.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, corrected.direction)
        val approved = voice(s, "네", 2000)
        assertEquals(LessonDirection.LEFT, approved.direction)
        assertEquals(TrafficDensity.UNKNOWN, approved.reportedTraffic.density)
    }

    @Test fun incompleteCorrectionClearsCandidateInsteadOfGuessing() {
        val s = session()
        voice(s, "오른쪽 차로 변경")
        val corrected = voice(s, "오른쪽 말고", 1000)
        assertFalse(corrected.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, corrected.direction)
        assertEquals(LessonDirection.UNKNOWN, voice(s, "네", 2000).direction)
    }

    @Test fun yesWithoutPendingCandidateDoesNotStartAnything() {
        val result = voice(session(), "맞아요")
        assertFalse(result.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, result.direction)
        assertTrue(result.reply.contains("확인 중인 방향이 없어요"))
    }

    @Test fun sameFirstUtteranceCannotConfirmItsOwnDirection() {
        val result = voice(session(), "맞아 왼쪽 차로 변경")
        assertTrue(result.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, result.direction)
    }

    @Test fun voiceDirectionAnswerAlsoRequiresConfirmation() {
        val s = session()
        val question = voice(s, "차로 변경 도와줘")
        assertTrue(question.awaitingDirection)
        val answer = voice(s, "오른쪽", 1000)
        assertFalse(answer.awaitingDirection)
        assertTrue(answer.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, answer.direction)
    }

    @Test fun voiceTurnIsAlsoConfirmedAndRetainsTurnMeaning() {
        val s = session()
        val heard = voice(s, "좌회전해야 해")
        assertTrue(heard.awaitingConfirmation)
        assertTrue(heard.reply.contains("왼쪽 회전 준비가 맞나요"))
        val approved = voice(s, "응", 1000)
        assertEquals(LessonDirection.LEFT, approved.direction)
        assertTrue(approved.reply.contains("회전 목적"))
        assertFalse(approved.reply.contains("왼쪽 차로 변경 준비를 도와"))
    }

    @Test fun expiredConfirmationCannotBeApprovedWithOrWithoutATimerTick() {
        val s = session()
        voice(s, "왼쪽 차로 변경")
        val expired = voice(s, "맞아", 20_000)
        assertFalse(expired.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, expired.direction)
        val other = session()
        voice(other, "왼쪽 차로 변경")
        assertFalse(other.tick(20_000).awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, voice(other, "네", 21_000).direction)
    }

    @Test fun pauseCancelAndEntryQuestionDiscardUnconfirmedDirection() {
        listOf("잠깐", "취소", "지금 들어가도 돼").forEach { command ->
            val s = session()
            voice(s, "왼쪽 차로 변경")
            val result = voice(s, command, 1000)
            assertFalse(command, result.awaitingConfirmation)
            assertEquals(command, LessonDirection.UNKNOWN, result.direction)
        }
    }

    @Test fun sourceSwitchAndFinishDiscardUnconfirmedDirection() {
        val s = session()
        s.acceptVehicle(vehicle(100, LessonSignal.OFF), 100)
        voice(s, "왼쪽 차로 변경", 200)
        val changed = s.acceptVehicle(vehicle(300, LessonSignal.LEFT).copy(source = VehicleInputSource.SDK), 300)
        assertFalse(changed.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, changed.direction)
        val other = session()
        voice(other, "왼쪽 차로 변경")
        assertFalse(other.finish(LessonReflection.OKAY, 1000).awaitingConfirmation)
    }

    @Test fun explicitTextDemoStillUsesTheDirectParserPath() {
        val s = session()
        val result = s.acceptUtterance("왼쪽 차로 변경", SpeechInputSource.TEXT_DEMO, 100)
        assertFalse(result.awaitingConfirmation)
        assertEquals(LessonDirection.LEFT, result.direction)
    }
}
