package kr.drivestep.tutor.journey

import kr.drivestep.tutor.connected.ReadinessSnapshot
import kr.drivestep.tutor.connected.SeatAxis
import kr.drivestep.tutor.workflow.InputValidity

/** Presents only the current preparation step's observed value, never a simulated slider value. */
object PreparationVehicleStatus {
    fun text(step: Int, snapshot: ReadinessSnapshot?, enabled: Boolean, now: Long): String {
        val axis = SeatAxis.entries.firstOrNull { it.step == step }
        if (axis == null && step != 8) {
            val title = when (step) {
                3 -> "헤드레스트 위치"
                4 -> "핸들 위치"
                5 -> "룸미러 각도"
                6 -> "왼쪽 사이드미러 각도"
                7 -> "오른쪽 사이드미러 각도"
                else -> "현재 단계의 차량 상태"
            }
            return "$title 정보는 제공되지 않아요.\n차량에서 직접 확인해 주세요."
        }
        val title = axis?.title ?: "안전벨트"
        if (!enabled) return "차량 정보 연결을 켜면\n$title 상태를 확인할 수 있어요."
        if (axis != null) {
            val field = snapshot?.seats?.get(axis)?.at(now)
            val position = field?.value?.takeIf { field.quality == InputValidity.VALID }
                ?: return "$title 위치를 확인하지 못했어요.\n차량 연결 상태를 확인해 주세요."
            return "$title 위치값: ${position.raw}\n앞좌석 왼쪽 · 범위 ${position.min}~${position.max}"
        }
        val belt = snapshot?.belt?.at(now)
        return if (belt?.quality == InputValidity.VALID && belt.value != null) {
            "안전벨트: ${if (belt.value) "채워짐" else "채워지지 않음"}"
        } else "안전벨트 상태를 확인하지 못했어요.\n직접 착용 상태를 확인해 주세요."
    }
}
