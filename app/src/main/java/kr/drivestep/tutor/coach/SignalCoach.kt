package kr.drivestep.tutor.coach

import kr.drivestep.tutor.practice.ObservationDisposition
import kr.drivestep.tutor.practice.PracticeAction
import kr.drivestep.tutor.practice.PracticeOutcome
import kr.drivestep.tutor.practice.PracticeSnapshot
import kr.drivestep.tutor.practice.PracticeStage
import kr.drivestep.tutor.practice.SignalPattern

/** A fixed, reviewed phrase. The id also names its bundled voice clip. */
data class CoachCue(val id: String, val text: String)

/**
 * Pure cue selection for the stationary indicator exercise.
 *
 * Call onEvent once per newly accepted core event. Re-reading the same snapshot
 * returns the same cue; the playback layer owns deduplication and interruption.
 * The caller must stop/suppress speech when the parked gate or audio focus is lost.
 * No advice here authorizes moving the vehicle or judges actual driving skill.
 */
object SignalCoach {
    val START = CoachCue(
        "signal_start",
        "차는 정차한 상태로 유지해 주세요. 먼저 방향지시등을 모두 꺼 주세요.",
    )
    val LEFT_ON = CoachCue(
        "signal_left_on",
        "정차한 채, 왼쪽으로 출발할 상황을 연습해요. 왼쪽 방향지시등을 켜 주세요.",
    )
    val LEFT_OFF = CoachCue(
        "signal_left_off",
        "좋아요. 왼쪽 신호가 켜진 것을 확인했어요. 이제 왼쪽 방향지시등을 꺼 주세요.",
    )
    val RIGHT_ON = CoachCue(
        "signal_right_on",
        "잘했어요. 왼쪽 신호가 꺼졌어요. 이번에는 정차한 채 오른쪽 골목으로 들어갈 상황을 연습해요. 오른쪽 방향지시등을 켜 주세요.",
    )
    val RIGHT_OFF = CoachCue(
        "signal_right_off",
        "좋아요. 오른쪽 신호가 켜졌어요. 이제 오른쪽 방향지시등을 꺼 주세요.",
    )
    val COMPLETED = CoachCue(
        "signal_completed",
        "좋아요. 왼쪽과 오른쪽 신호를 켜고 끄는 연습을 마쳤어요. 방금 확인한 조작을 함께 복습해요.",
    )
    val WRONG_RIGHT_FOR_LEFT = CoachCue(
        "signal_wrong_right_for_left",
        "지금은 오른쪽 신호가 켜져 있어요. 괜찮아요. 방향지시등을 모두 끈 뒤 왼쪽부터 다시 해 봐요.",
    )
    val WRONG_LEFT_FOR_RIGHT = CoachCue(
        "signal_wrong_left_for_right",
        "지금은 왼쪽 신호가 켜져 있어요. 괜찮아요. 방향지시등을 모두 끈 뒤 오른쪽을 다시 연습해요.",
    )
    val RESET_BEFORE_LEFT = CoachCue(
        "signal_reset_before_left",
        "왼쪽 신호가 보이네요. 이번에는 모두 끄는 순서부터 다시 해 볼게요. 방향지시등을 모두 꺼 주세요.",
    )
    val RESET_BEFORE_RIGHT = CoachCue(
        "signal_reset_before_right",
        "오른쪽 신호가 보이네요. 이번에는 모두 끄는 순서부터 다시 해 볼게요. 방향지시등을 모두 꺼 주세요.",
    )
    val RETRY_LEFT = CoachCue(
        "signal_retry_left",
        "모두 꺼진 것을 확인했어요. 정차한 채 왼쪽 방향지시등을 다시 켜 주세요.",
    )
    val RETRY_RIGHT = CoachCue(
        "signal_retry_right",
        "모두 꺼진 것을 확인했어요. 정차한 채 오른쪽 방향지시등을 다시 켜 주세요.",
    )
    val UNKNOWN_SIGNAL = CoachCue(
        "signal_unknown",
        "방향지시등 신호를 확인할 수 없어 이번 연습을 멈췄어요. 연결을 확인한 뒤 새로 시작해요.",
    )
    val BOTH_SIGNALS = CoachCue(
        "signal_both",
        "두 방향의 신호가 함께 들어와 이번 연습을 멈췄어요. 모두 끈 뒤 새로 시작해요.",
    )

    val catalog: List<CoachCue> = listOf(
        START, LEFT_ON, LEFT_OFF, RIGHT_ON, RIGHT_OFF, COMPLETED,
        WRONG_RIGHT_FOR_LEFT, WRONG_LEFT_FOR_RIGHT, RESET_BEFORE_LEFT, RESET_BEFORE_RIGHT,
        RETRY_LEFT, RETRY_RIGHT, UNKNOWN_SIGNAL, BOTH_SIGNALS,
    )

    /** Optional opening phrase. Do not replay it when reviewing or resuming a record. */
    fun onStart(snapshot: PracticeSnapshot): CoachCue? =
        if (snapshot.outcome == PracticeOutcome.ACTIVE && snapshot.stage == PracticeStage.WAIT_BASELINE_OFF) START else null

    fun onEvent(snapshot: PracticeSnapshot): CoachCue? {
        val event = snapshot.events.lastOrNull() ?: return null
        if (event.disposition == ObservationDisposition.DUPLICATE ||
            event.disposition == ObservationDisposition.OUT_OF_ORDER
        ) return null

        // Explicit lifecycle/source interruption does not create an observation.
        // Do not turn its preceding successful observation into fresh praise.
        if (event.stageAfter != snapshot.stage) return null
        if (snapshot.outcome == PracticeOutcome.INTERRUPTED) {
            if (event.disposition != ObservationDisposition.INVALID_SIGNAL) return null
            return when (event.pattern) {
                SignalPattern.UNKNOWN -> UNKNOWN_SIGNAL
                SignalPattern.BOTH -> BOTH_SIGNALS
                else -> null
            }
        }
        if (event.disposition == ObservationDisposition.INVALID_SIGNAL) return null

        if (event.disposition == ObservationDisposition.CORRECTION_REQUIRED) {
            return when (snapshot.stage) {
                PracticeStage.WAIT_LEFT_ON -> when (event.pattern) {
                    SignalPattern.RIGHT -> WRONG_RIGHT_FOR_LEFT
                    SignalPattern.LEFT -> RESET_BEFORE_LEFT
                    else -> null
                }
                PracticeStage.WAIT_RIGHT_ON -> when (event.pattern) {
                    SignalPattern.LEFT -> WRONG_LEFT_FOR_RIGHT
                    SignalPattern.RIGHT -> RESET_BEFORE_RIGHT
                    else -> null
                }
                else -> null
            }
        }

        if (event.disposition == ObservationDisposition.WAITING) {
            if (snapshot.stage == PracticeStage.WAIT_BASELINE_OFF &&
                (event.pattern == SignalPattern.LEFT || event.pattern == SignalPattern.RIGHT)
            ) return START
            if (event.pattern != SignalPattern.OFF) return null
            return when (snapshot.nextAction) {
                PracticeAction.TURN_LEFT_ON -> RETRY_LEFT
                PracticeAction.TURN_RIGHT_ON -> RETRY_RIGHT
                else -> null
            }
        }

        if (event.disposition != ObservationDisposition.ADVANCED) return null
        if (snapshot.outcome == PracticeOutcome.COMPLETED) {
            return if (event.stageBefore == PracticeStage.WAIT_RIGHT_OFF && event.pattern == SignalPattern.OFF) COMPLETED else null
        }
        return when (snapshot.nextAction) {
            PracticeAction.TURN_LEFT_ON -> LEFT_ON
            PracticeAction.TURN_LEFT_OFF -> LEFT_OFF
            PracticeAction.TURN_RIGHT_ON -> RIGHT_ON
            PracticeAction.TURN_RIGHT_OFF -> RIGHT_OFF
            else -> null
        }
    }
}
