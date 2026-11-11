package kr.drivestep.tutor.journey

import kr.drivestep.tutor.connected.*
import kr.drivestep.tutor.speech.SpeechStatusText
import kr.drivestep.tutor.workflow.InputValidity
import org.junit.Assert.*
import org.junit.Test

class PreparationFeedbackTest {
    private fun snapshot(buckled: Boolean = true) = ReadinessSnapshot(
        SeatAxis.entries.associateWith { Observed(SeatPosition((it.ordinal + 1) * 11, 0, 100), InputValidity.VALID, 1_000) },
        Observed(buckled, InputValidity.VALID, 1_000), Observed(), false)

    @Test fun seatStepShowsOnlyItsOwnObservedPosition() {
        SeatAxis.entries.forEach { axis ->
            val text = PreparationVehicleStatus.text(axis.step, snapshot(), true, 2_000)
            assertTrue(text.contains("${axis.title} 위치값: ${(axis.ordinal + 1) * 11}"))
            assertTrue(text.contains("범위 0~100"))
            SeatAxis.entries.filter { it != axis }.forEach { assertFalse(text.contains(it.title)) }
            assertFalse(text.contains("안전벨트"))
        }
    }
    @Test fun absentPositionChannelsDoNotPresentAnotherSeatOrVirtualValue() {
        for (step in 3..7) {
            val text = PreparationVehicleStatus.text(step, snapshot(), true, 2_000)
            assertTrue(text.contains("정보는 제공되지 않아요")); assertFalse(text.contains("위치값:"))
        }
    }
    @Test fun staleMissingDisabledAndBeltStatesRemainDistinct() {
        assertTrue(PreparationVehicleStatus.text(0, snapshot(), false, 2_000).contains("연결을 켜면"))
        assertTrue(PreparationVehicleStatus.text(0, snapshot(), true, 5_000).contains("확인하지 못했어요"))
        assertTrue(PreparationVehicleStatus.text(0, null, true, 2_000).contains("확인하지 못했어요"))
        assertEquals("안전벨트: 채워짐", PreparationVehicleStatus.text(8, snapshot(), true, 2_000))
        assertEquals("안전벨트: 채워지지 않음", PreparationVehicleStatus.text(8, snapshot(false), true, 2_000))
        assertTrue(PreparationVehicleStatus.text(8, snapshot(), true, 5_000).contains("확인하지 못했어요"))
    }
    @Test fun successfulSpeechHasNoResultCaptionButErrorsAndProgressRemain() {
        assertEquals("", SpeechStatusText.forDisplay("Pleos 음성 안내 완료"))
        assertEquals("", SpeechStatusText.forDisplay("Android 음성 안내 완료 · Pleos 출력 대체"))
        listOf("음성 안내 중", "음성 출력을 사용할 수 없어요.", "음성 안내를 멈췄어요.").forEach {
            assertEquals(it, SpeechStatusText.forDisplay(it))
        }
    }
}
