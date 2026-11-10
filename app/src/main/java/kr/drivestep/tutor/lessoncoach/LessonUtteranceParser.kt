package kr.drivestep.tutor.lessoncoach

import java.util.Locale

enum class LessonIntent { LANE_CHANGE, TURN, REPORT, REPEAT, PAUSE, RESUME, CANCEL, CONFIRM, REJECT, ENTRY_QUESTION, UNKNOWN }

data class ParsedLessonUtterance(
    val intent: LessonIntent,
    val direction: LessonDirection = LessonDirection.UNKNOWN,
    val traffic: ReportedTraffic = ReportedTraffic(),
    val hasTrafficReport: Boolean = false,
)

/** A deliberately bounded grammar. Unsupported or contradictory speech stays unknown. */
class LessonUtteranceParser {
    fun parse(text: String): ParsedLessonUtterance {
        val value = text.lowercase(Locale.ROOT).replace(Regex("[\\s,.!?~。？！]+"), "").take(300)
        fun result(intent: LessonIntent) = ParsedLessonUtterance(intent)
        if (value.isBlank()) return result(LessonIntent.UNKNOWN)
        if (value in setOf("대화종료", "대화종료해줘", "대화종료해주세요", "대화그만", "그만듣기", "그만들어", "그만들어줘", "그만들어주세요")) {
            return result(LessonIntent.PAUSE)
        }
        if (has(value, "취소", "그만", "중단", "하지마", "가지마", "안할래", "안바꿀", "변경하지", "바꾸지")) return result(LessonIntent.CANCEL)
        if (value in setOf("네", "예", "응", "맞아", "맞아요", "그래", "그래요", "네맞아요", "응맞아")) return result(LessonIntent.CONFIRM)
        if (value in setOf("아니", "아니야", "아니요", "아니오", "아닙니다", "틀렸어", "틀렸어요")) return result(LessonIntent.REJECT)
        if (has(value, "다시말", "다시설명", "한번더", "다시들", "반복해")) return result(LessonIntent.REPEAT)
        if (has(value, "다시시작", "계속안내", "재개", "다시안내")) return result(LessonIntent.RESUME)
        if (has(value, "조용", "잠깐쉬", "일시정지") || value in setOf("잠깐", "멈춰", "멈춰줘", "잠깐멈춰")) return result(LessonIntent.PAUSE)

        val direction = direction(value)
        // Not knowing what to do does not retract a stated observation such as
        // "차가 많은데 어떻게 해야 될지 모르겠어". Other uncertainty stays intact.
        val observationText = value.replace(Regex("어떻게(?:해야(?:할지|될지)|할지|하면될지)(?:잘)?모르겠"), "")
        val uncertain = has(observationText, "모르겠", "잘몰라", "잘모르", "확실하지않", "없는건아니", "없는것은아니", "없진않",
            "많지않은건아니", "많지않은것은아니", "안많은건아니")
        val density = when {
            uncertain -> TrafficDensity.UNKNOWN
            has(value, "많지않", "많진않", "안많", "많은건아니", "많은것은아니", "차가없", "차없", "차들이없", "차가별로없", "한산", "차가적", "차들이적") -> TrafficDensity.LOW
            has(value, "많", "빽빽", "빼곡", "혼잡", "막혀") -> TrafficDensity.HIGH
            else -> TrafficDensity.UNKNOWN
        }
        val flow = when {
            uncertain -> TrafficFlow.UNKNOWN
            has(value, "멈춰있지않", "정지해있지않", "서있지않") -> TrafficFlow.UNKNOWN
            has(value, "멈춰있", "멈춰서", "멈췄", "서있", "정지해", "안움직", "움직이지않") -> TrafficFlow.STOPPED
            has(value, "움직", "흐르", "지나가", "계속와", "계속오") -> TrafficFlow.MOVING
            else -> TrafficFlow.UNKNOWN
        }
        val approaching = when {
            uncertain -> null
            has(value, "안다가", "다가오지않", "접근하지않") -> false
            has(value, "다가", "접근", "계속와", "계속오") || Regex("뒤에서.*(와|오)").containsMatchIn(value) -> true
            else -> null
        }
        val reported = density != TrafficDensity.UNKNOWN || flow != TrafficFlow.UNKNOWN || approaching != null || uncertain
        val traffic = ReportedTraffic(density, flow, approaching)
        val intent = when {
            has(value, "들어가도", "끼어들어도", "변경해도", "바꿔도", "가도돼", "가도되", "지금가도", "안전해", "안전한지") -> LessonIntent.ENTRY_QUESTION
            has(value, "차로변경", "차선변경", "차로바꾸", "차로를바꾸", "차선바꾸", "차선을바꾸", "옆차로", "끼어", "끼고", "차로로가") -> LessonIntent.LANE_CHANGE
            has(value, "좌회전", "우회전") -> LessonIntent.TURN
            direction != LessonDirection.UNKNOWN && has(value, "가고싶", "옮기고싶", "바꾸고싶", "차로로이동") -> LessonIntent.LANE_CHANGE
            reported -> LessonIntent.REPORT
            else -> LessonIntent.UNKNOWN
        }
        val corrected = corrected(value)
        val turnDirection = if (intent == LessonIntent.TURN) {
            when {
                corrected.contains("좌회전") && !corrected.contains("우회전") -> LessonDirection.LEFT
                corrected.contains("우회전") && !corrected.contains("좌회전") -> LessonDirection.RIGHT
                else -> direction
            }
        } else direction
        return ParsedLessonUtterance(intent, turnDirection, traffic, reported)
    }

    private fun direction(value: String): LessonDirection {
        // Only the explicit correction suffix can replace a preceding direction.
        val corrected = corrected(value)
        if (has(corrected, "쪽은아니", "쪽이아니", "측은아니", "쪽아니", "쪽으로안가", "쪽으로가지않")) return LessonDirection.UNKNOWN
        val left = has(corrected, "왼쪽", "왼편", "좌측") || corrected in setOf("좌", "좌로")
        val right = has(corrected, "오른쪽", "오른편", "우측") || corrected in setOf("우", "우로")
        return when {
            left && !right -> LessonDirection.LEFT
            right && !left -> LessonDirection.RIGHT
            else -> LessonDirection.UNKNOWN
        }
    }

    private fun corrected(value: String) = value.substringAfterLast("말고").substringAfterLast("아니라").substringAfterLast("대신")
    private fun has(value: String, vararg terms: String) = terms.any(value::contains)
}
