package kr.drivestep.tutor.coach

import kr.drivestep.tutor.practice.PracticeOutcome
import kr.drivestep.tutor.practice.SteeringPracticeSnapshot

/** Fixed feedback about received signals only. The host gates playback and compares feedbackRevision. */
object SteeringCoach {
    val WAIT_INPUTS = CoachCue("steering_wait_inputs", "차를 움직이지 않고 준비 조작을 익혀볼게요. 먼저 P단과 속도, 조향 신호를 확인하고 있어요.")
    val CENTER_START = CoachCue("steering_center_start", "P단과 정지 상태에 해당하는 속도 신호를 받았어요. 먼저 중앙 부근의 조향 신호가 확인되도록 조작해 주세요.")
    val LEFT = CoachCue("steering_left", "P단과 정지 상태에 해당하는 속도, 중앙 부근의 조향 신호를 확인했어요. 정차 상태를 유지하며 왼쪽 조작을 연습해요.")
    val LEFT_CONFIRMED = CoachCue("steering_left_confirmed", "좋아요. 왼쪽 방향의 조향 변경 신호를 받았어요. 이제 중앙 부근으로 돌아와 주세요.")
    val RIGHT = CoachCue("steering_right", "중앙 부근으로 돌아온 조향 변경 신호를 확인했어요. 이번에는 오른쪽 조작을 연습해요.")
    val RIGHT_CONFIRMED = CoachCue("steering_right_confirmed", "좋아요. 오른쪽 방향의 조향 변경 신호를 받았어요. 다시 중앙 부근으로 돌아와 주세요.")
    val COMPLETE = CoachCue("steering_complete", "왼쪽, 중앙, 오른쪽, 중앙 순서의 조향 변경 신호를 확인했어요. 이번 기록은 수신 신호를 확인한 결과이며 실제 주차 성공이나 운전 실력의 평가는 아니에요.")
    val WRONG_RIGHT = CoachCue("steering_wrong_right", "지금은 오른쪽 방향의 조향 신호가 들어왔어요. 괜찮아요. 중앙 부근으로 돌아온 뒤 왼쪽부터 다시 연습해요.")
    val WRONG_LEFT = CoachCue("steering_wrong_left", "지금은 왼쪽 방향의 조향 신호가 들어왔어요. 괜찮아요. 중앙 부근으로 돌아온 뒤 오른쪽을 다시 연습해요.")
    val RESET_LEFT = CoachCue("steering_reset_left", "현재 조향값은 받았지만 연습에 필요한 변경 신호를 확인하지 못했어요. 중앙 복귀 신호부터 확인한 뒤 왼쪽을 다시 연습해요.")
    val RESET_RIGHT = CoachCue("steering_reset_right", "현재 조향값은 받았지만 연습에 필요한 변경 신호를 확인하지 못했어요. 중앙 복귀 신호부터 확인한 뒤 오른쪽을 다시 연습해요.")
    val RETRY_LEFT = CoachCue("steering_retry_left", "중앙 부근의 조향 변경 신호를 확인했어요. 정차 상태를 유지하며 왼쪽을 다시 연습해요.")
    val RETRY_RIGHT = CoachCue("steering_retry_right", "중앙 부근의 조향 변경 신호를 확인했어요. 정차 상태를 유지하며 오른쪽을 다시 연습해요.")
    val INTERRUPTED = CoachCue("steering_interrupted", "연습 조건을 계속 확인할 수 없어 이번 연습을 멈췄어요. 화면의 중단 이유를 확인해 주세요.")

    val catalog: List<CoachCue> = listOf(
        WAIT_INPUTS, CENTER_START, LEFT, LEFT_CONFIRMED, RIGHT, RIGHT_CONFIRMED, COMPLETE,
        WRONG_RIGHT, WRONG_LEFT, RESET_LEFT, RESET_RIGHT, RETRY_LEFT, RETRY_RIGHT, INTERRUPTED,
    )
    private val byId = catalog.associateBy { it.id }

    fun onStart(snapshot: SteeringPracticeSnapshot): CoachCue? =
        if (snapshot.outcome == PracticeOutcome.ACTIVE) snapshot.latestFeedbackKey?.let(byId::get) else null

    /** Invoke only when feedbackRevision changed; repeated snapshots are deliberately deterministic. */
    fun onEvent(snapshot: SteeringPracticeSnapshot): CoachCue? =
        if (snapshot.feedbackRevision > 0) snapshot.latestFeedbackKey?.let(byId::get) else null
}
