package kr.drivestep.tutor.gleo

import kr.drivestep.tutor.lessoncoach.VehicleObservation
import kr.drivestep.tutor.lessoncoach.VehicleInputSource

enum class GleoLessonOperation { START, ASK, REPEAT, END, PREPARATION }
enum class GleoLessonFocus { SUMMARY, REASON, STEPS, CAUTION }
enum class GleoLessonTopic(val title: String, val summary: String, val reason: String, val steps: String, val caution: String) {
    LANE_CHANGE("차로 변경", "진행할 방향을 정하고 방향지시등·미러·사각지대 순서로 준비해요.",
        "미러에 보이지 않는 구역이 있어 미러만으로 옆 차의 위치를 모두 알 수는 없어요. 방향지시등은 내 의도를 알리는 수단이에요.",
        "이동할 방향과 차로 표시를 확인해요. 방향지시등으로 의도를 알리고 미러와 사각지대를 살펴요. 주변을 직접 확인하기 전에는 차로를 유지해요.",
        "주변 차량의 거리와 접근 속도는 제가 확인할 수 없어요. 지금 진입해도 되는지 판단하거나 차로 변경을 지시하지 않아요."),
    MIRRORS("미러와 사각지대", "룸미러와 사이드미러로 뒤와 옆을 보고, 미러 밖 사각지대도 확인해요.",
        "사이드미러 바깥에 있는 차량·이륜차는 미러에서 사라질 수 있어요. 그래서 거울 확인과 짧은 어깨 너머 확인을 함께 연습해요.",
        "정차 중 시트 자세를 먼저 맞추고 미러를 조절해요. 진행 방향을 보면서 룸미러·해당 사이드미러를 확인하고 어깨 너머를 짧게 살피는 순서를 익혀요.",
        "고개를 돌린 채 오래 머물지 않아요. 화면만으로 실제 미러 각도나 사각지대가 확인됐다고 판정하지 않아요."),
    TURN("회전 준비", "회전할 곳을 미리 파악하고 차로와 주변 보행자를 확인하는 순서를 익혀요.",
        "회전 직전에 급하게 차로를 옮기거나 속도를 바꾸면 주변 흐름을 살피기 어려워요. 미리 준비하면 확인할 여유가 생겨요.",
        "방향 안내와 실제 표지·신호를 확인해요. 충분히 앞서 필요한 차로를 준비하고 방향지시등으로 의도를 알려요. 회전 전 횡단보도와 보행자·자전거를 살펴요.",
        "내비 안내만으로 진행하지 않아요. 교차로 신호·표지·보행자 상태는 현장에서 직접 확인해야 해요."),
    START_STOP("부드러운 출발과 정지", "가속·감속은 주변을 미리 보고 부드럽게 조절하는 연습부터 해요.",
        "앞을 멀리 보면 멈출 상황을 일찍 알아차려 급격한 페달 조작을 줄일 수 있어요. 실제 제동 거리는 속도와 노면에 따라 달라져요.",
        "정차 상태에서 페달 위치와 발 자세를 확인해요. 연습 장소에서는 작은 조작으로 반응을 익히고 멈출 지점을 미리 바라보며 감속해요.",
        "긴급 상황에서는 부드러움보다 충돌 회피가 우선이에요. 앱은 실제 제동 시점이나 페달 강도를 정하지 않아요."),
    PARKING("주차 준비", "차량을 천천히 다룰 수 있는지와 주차 공간 주변을 먼저 확인해요.",
        "차량 앞뒤와 옆의 이동 범위가 달라 조향할 때 한쪽만 보면 다른 쪽 장애물을 놓칠 수 있어요.",
        "연습할 주차 방식과 공간을 확인해요. 전후좌우·보행자를 직접 확인하고, 어려우면 멈춰 위치를 다시 살펴요. 차근의 가상 주차에서 기어·핸들·페달 순서를 익힐 수 있어요.",
        "실제 장애물 거리와 빈 공간을 측정하지 않아요. 몇 cm 더 이동하라는 안내나 핸들을 몇 바퀴 돌리라는 실차 지시는 제공하지 않아요."),
    RAIN("비 오는 날", "젖은 노면에서는 감속과 앞차 간격, 시야 확보를 먼저 생각해요.",
        "노면이 젖으면 타이어 접지와 제동 성능이 달라지고 빗물·김서림으로 시야도 줄어들 수 있어요.",
        "출발 전 와이퍼·전조등·김서림 제거 방법을 확인해요. 주행 상황에 맞게 속도를 낮추고 앞차와 여유를 두며 급조향·급가감속을 피하는 연습을 해요.",
        "비 오는 상황을 가정한 학습 조언이에요. 현재 날씨는 별도로 조회해야 하며 예보만으로 노면 상태를 확정하지 않아요."),
    NIGHT("야간 운전", "전조등 상태와 보이는 범위를 먼저 확인하고 여유 있게 운전하는 방법을 익혀요.",
        "어두우면 보행자와 장애물을 늦게 발견할 수 있고 상대 차량의 빛 때문에 시야가 잠시 줄어들 수 있어요.",
        "출발 전 등화 상태를 확인해요. 조명이 부족한 구간에서는 시야에 맞춰 속도를 조절하고 횡단보도·길 가장자리를 살펴요.",
        "실제 조도와 주변 보행자를 앱이 감지한 것은 아니에요. 피곤하거나 시야 확보가 어려우면 안전한 곳에서 쉬어요."),
    OTHER("다른 주제", "", "", "", "")
}

data class GleoLessonRequest(val operation: GleoLessonOperation = GleoLessonOperation.ASK,
    val question: String = "", val topic: GleoLessonTopic? = null, val focus: GleoLessonFocus? = null)
data class GleoLessonTurn(val question: String, val guidance: String, val topic: String)

/** Gleo owns speech/NLU/final wording; this session supplies bounded, grounded lesson context. */
class GleoLessonSession {
    var topic: GleoLessonTopic? = null; private set
    var turns: List<GleoLessonTurn> = emptyList(); private set
    var ended = false; private set
    private var lastAt = -1L
    private var lastReply: GleoReply? = null
    private var lastFocus = GleoLessonFocus.SUMMARY

    fun clear() { topic = null; turns = emptyList(); lastReply = null; lastAt = -1; ended = true }
    fun answer(request: GleoLessonRequest, vehicle: VehicleObservation?, consent: Boolean, now: Long): GleoReply {
        if (lastAt >= 0 && now - lastAt !in 0..CONTEXT_TTL) clear()
        val q = request.question.trim().take(600)
        if (request.operation == GleoLessonOperation.END || q in listOf("그만", "대화 종료", "연수 끝", "그만할래")) {
            clear()
            return GleoReply("success", "The coaching conversation ended and its temporary context was cleared.", "연수 대화를 마쳤어요. 차근의 임시 대화 내용도 비웠어요.")
        }
        if (request.operation == GleoLessonOperation.START) clear()
        ended = false; lastAt = now
        // A broad/general query must not silently reuse an unrelated subject; CURRENT is parsed as null.
        request.topic?.let { topic = it }
        if (topic == GleoLessonTopic.OTHER) topic = null
        val selected = topic
        if (selected == null) return GleoReply("asking", "Ask which driving lesson the driver wants to discuss.",
            "어떤 상황이 궁금하세요? 차로 변경, 미러, 회전, 출발·정지, 주차, 비 오는 날, 야간 운전을 함께 알아볼 수 있어요.",
            mapOf("conversationActive" to true, "missingField" to "topic", "missingInformation" to "driving topic"))
        val focus = request.focus ?: if (request.operation == GleoLessonOperation.REPEAT) lastFocus else when {
            q.contains("왜") || q.contains("이유") -> GleoLessonFocus.REASON
            q.contains("자세") || q.contains("순서") || q.contains("단계") -> GleoLessonFocus.STEPS
            q.contains("주의") -> GleoLessonFocus.CAUTION
            else -> GleoLessonFocus.SUMMARY
        }
        val guidance = if (request.operation == GleoLessonOperation.REPEAT && lastReply != null) requireNotNull(lastReply).display
            else if (listOf("지금 들어", "끼어들어도", "진입해도", "안전해", "몇 센티", "몇 바퀴").any(q::contains)) selected.caution
            else when (focus) { GleoLessonFocus.SUMMARY -> selected.summary; GleoLessonFocus.REASON -> selected.reason
                GleoLessonFocus.STEPS -> selected.steps; GleoLessonFocus.CAUTION -> selected.caution }
        val previous = turns.lastOrNull()
        val data = linkedMapOf<String, Any?>("topic" to selected.title, "driverQuestion" to q,
            "guidance" to guidance,
            "limitations" to selected.caution, "requestedDetail" to focus.name,
            "surroundingTraffic" to "NOT_OBSERVED", "maneuverAuthorized" to false,
            "conversationActive" to true, "missingField" to "question",
            "followUpQuestion" to "어떤 부분이 더 궁금하세요? 마치려면 차근 연수 대화 종료라고 말씀해 주세요.",
            "previousQuestion" to previous?.question,
            "previousGuidance" to previous?.guidance)
        val observed = vehicle?.takeIf { consent && it.source == VehicleInputSource.SDK }
        data["vehicleSource"] = if (observed == null) "UNKNOWN" else "SDK"
        data["speedKmh"] = observed?.takeIf { now - it.speedAtMillis in 0..1500 }?.speedKmh?.takeIf { it.isFinite() && it >= 0 }
        data["gear"] = observed?.takeIf { now - it.gearAtMillis in 0..1500 }?.gear?.name ?: "UNKNOWN"
        // The lesson remains open and asks for the driver's next question or explicit end.
        // Gleo's asking state owns follow-up listening; the app does not reopen its own STT.
        val reply = GleoReply("asking", "The coaching guidance is ready. The ongoing lesson needs the driver's next question or an explicit end request.", guidance, data)
        turns = (turns + GleoLessonTurn(q.ifBlank { selected.title }, guidance, selected.title)).takeLast(6)
        lastReply = reply; lastFocus = focus
        return reply
    }
    companion object { const val CONTEXT_TTL = 10 * 60_000L }
}
