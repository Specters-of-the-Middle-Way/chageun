package kr.drivestep.tutor.tutor

import kr.drivestep.tutor.coach.CoachCue

/** A parked comprehension lesson; these choices never observe or control the car. */
data class ParkingLessonStep(
    val title: String,
    val description: String,
    val question: String,
    val choices: List<String>,
    val correctChoiceIndex: Int,
    val intro: CoachCue,
    val correctCue: CoachCue,
    val retryCue: CoachCue,
)

object ParkingLesson {
    val steps = listOf(
        ParkingLessonStep(
            title = "빈칸보다 주변부터",
            description = "주차할 공간을 골랐다면 사람과 장애물, 통행하는 차량을 먼저 살펴요. 낮은 물체와 기둥, 옆 차 주변도 함께 확인하는 상황을 떠올려 보세요.",
            question = "주차할 곳을 골랐다면, 먼저 무엇을 확인할까요?",
            choices = listOf("빈칸만 보고 바로 들어가요", "사람·장애물과 주변 공간을 확인해요"),
            correctChoiceIndex = 1,
            intro = CoachCue("parking_space_intro", "이번에는 후진주차의 순서를 말로 익혀볼게요. 실제 차량은 조작하지 않고, 정차한 상태에서 답을 골라요. 주차할 곳을 골랐다면, 먼저 무엇을 확인할까요?"),
            correctCue = CoachCue("parking_space_correct", "좋아요. 빈칸뿐 아니라 사람과 장애물, 통행하는 차량까지 살피는 준비가 먼저예요."),
            retryCue = CoachCue("parking_space_retry", "서두르지 않아도 괜찮아요. 빈칸처럼 보여도 주변에 사람이나 낮은 물체가 있을 수 있어요. 주변을 함께 살피는 답을 골라볼까요?"),
        ),
        ParkingLessonStep(
            title = "멈춘 다음, 후진 준비",
            description = "실제 후진 준비는 차량이 완전히 멈춘 상태에서 해요. 브레이크를 밟고 차량 설명서의 방법에 따라 변속한 뒤, 계기판의 후진 표시를 확인하는 순서를 기억해요.",
            question = "실제 주차에서 후진으로 바꾸기 전의 순서는 무엇일까요?",
            choices = listOf("완전히 멈추고 브레이크를 밟은 뒤 후진 표시를 확인해요", "차가 조금 움직여도 바로 후진으로 바꿔요"),
            correctChoiceIndex = 0,
            intro = CoachCue("parking_reverse_intro", "실제 주차를 준비하는 상황을 생각해볼게요. 차량이 완전히 멈추었는지, 변속 뒤 계기판에 후진이 표시되는지 확인하는 순서가 중요해요. 어떤 답을 고를까요?"),
            correctCue = CoachCue("parking_reverse_correct", "순서를 잘 짚었어요. 완전히 멈추고 브레이크를 밟은 상태에서 변속하며, 계기판 표시도 확인해요. 구체적인 조작 방법은 내 차 설명서를 따라요."),
            retryCue = CoachCue("parking_reverse_retry", "차가 움직일 때 변속하는 답은 다시 생각해봐요. 먼저 완전히 멈추고, 브레이크를 밟은 상태에서 후진 준비를 하는 순서예요."),
        ),
        ParkingLessonStep(
            title = "천천히, 주변을 계속",
            description = "실제 후진 중에는 뒤쪽뿐 아니라 차량 앞쪽과 양옆도 계속 살펴야 해요. 미러와 후방 화면은 확인을 돕지만 모든 물체를 보여주지는 못해요. 잘 보이지 않으면 멈춰 다시 확인하는 상황을 연습해요.",
            question = "주변이 가려져 잘 보이지 않을 때는 어떻게 할까요?",
            choices = listOf("경고음이 없으니 계속 움직여요", "멈춘 뒤 주변을 다시 확인해요"),
            correctChoiceIndex = 1,
            intro = CoachCue("parking_observe_intro", "이번에는 천천히 후진하던 중 주변이 가려진 상황이에요. 경고음이 없더라도 모든 물체가 확인된 것은 아니에요. 잘 보이지 않을 때 어떻게 할까요?"),
            correctCue = CoachCue("parking_observe_correct", "좋아요. 잘 보이지 않을 때는 멈춰서 다시 확인하는 판단이 필요해요. 뒤쪽과 함께 앞쪽, 양옆의 상황도 살펴요."),
            retryCue = CoachCue("parking_observe_retry", "경고음이 없다는 이유만으로 계속 움직이지는 않아요. 화면과 센서가 놓치는 물체도 있어요. 멈춘 뒤 다시 확인하는 답을 골라볼까요?"),
        ),
        ParkingLessonStep(
            title = "정렬이 어렵다면 멈춤부터",
            description = "실제 주차에서 차가 비스듬하거나 간격이 불확실할 수 있어요. 그럴 때는 먼저 멈춰 위치와 주변을 다시 살펴요. 다시 전진하거나 후진할 때도 완전히 정지한 뒤 변속하고 주변을 확인해요.",
            question = "차가 비스듬하고 옆 간격도 불확실하다면 무엇이 먼저일까요?",
            choices = listOf("먼저 멈추고 위치와 주변을 다시 살펴요", "계속 움직이며 핸들을 더 돌려봐요"),
            correctChoiceIndex = 0,
            intro = CoachCue("parking_align_intro", "차가 비스듬하고 옆 간격이 불확실한 상황을 생각해봐요. 몇 바퀴를 돌릴지 외우기보다, 지금 위치와 주변을 확인하는 것이 먼저예요. 어떤 답을 고를까요?"),
            correctCue = CoachCue("parking_align_correct", "맞아요. 먼저 멈추고 위치와 주변을 살펴요. 한 번에 끝내는 것보다, 필요한 만큼 다시 확인하는 과정이 중요해요."),
            retryCue = CoachCue("parking_align_retry", "간격이 확실하지 않은 채로 계속 움직이지는 않아요. 먼저 멈추고 상황을 다시 보는 답으로 돌아가 볼까요?"),
        ),
        ParkingLessonStep(
            title = "마지막 확인까지 차분하게",
            description = "주차 위치를 확인한 뒤에는 완전히 정차하고, 내 차 설명서에 따라 주차 단과 주차 브레이크가 적용됐는지 확인해요. 차에서 내리기 전에는 주변 통행도 다시 살펴요.",
            question = "주차를 마무리할 때 무엇을 확인할까요?",
            choices = listOf("차가 멈춰 보이면 바로 내려요", "주차 단·주차 브레이크와 주변 통행을 확인해요"),
            correctChoiceIndex = 1,
            intro = CoachCue("parking_finish_intro", "이제 주차 마무리를 생각해볼게요. 차가 멈춰 보인다는 것만으로 모든 확인이 끝난 것은 아니에요. 내리기 전에 무엇을 확인할까요?"),
            correctCue = CoachCue("parking_finish_correct", "잘 기억했어요. 완전히 정차한 뒤 주차 단과 주차 브레이크 상태를 확인하고, 내리기 전 주변 통행도 살펴요."),
            retryCue = CoachCue("parking_finish_retry", "바로 내리기 전에 한 번 더 확인해요. 주차 단과 주차 브레이크가 적용됐는지, 주변에 통행하는 사람이 있는지 살피는 답이에요."),
        ),
    )

    val completeCue = CoachCue("parking_complete", "후진주차를 준비하고 마무리하는 다섯 가지 상황을 모두 확인했어요. 오늘은 설명을 이해한 결과이며 실제 주차 실력을 평가한 것은 아니에요. 필요한 부분은 다시 복습할 수 있어요.")
    val cues: List<CoachCue> = steps.flatMap { listOf(it.intro, it.correctCue, it.retryCue) } + completeCue

    val sourceUrls = listOf(
        "https://ownersmanual.hyundai.com/full_webhelp/US4/2026/ko_KR/id_sws_fbv_rgc.html",
        "https://ownersmanual.hyundai.com/full_webhelp/ne1n/2025/ko_kr/id6f8c73b8aefa.html",
        "https://ownersmanual.hyundai.com/ivi/ccNC/AVNT/KOR/Korean/Settings_vehicle.html",
    )
}

data class ParkingLessonSnapshot(
    val stepIndex: Int,
    val selectedChoiceIndex: Int?,
    val answerConfirmed: Boolean,
    val wrongAttemptCount: Int,
    val completed: Boolean,
    val latestCue: CoachCue,
) {
    val confirmedStepCount: Int get() = if (completed) ParkingLesson.steps.size else stepIndex + if (answerConfirmed) 1 else 0
}

/** Keep this instance in the Activity while its lesson view is recreated. */
class ParkingLessonSession {
    private var stepIndex = 0
    private var selectedChoiceIndex: Int? = null
    private var answerConfirmed = false
    private var wrongAttemptCount = 0
    private var completed = false
    private var latestCue = ParkingLesson.steps.first().intro

    fun snapshot() = ParkingLessonSnapshot(stepIndex, selectedChoiceIndex, answerConfirmed, wrongAttemptCount, completed, latestCue)

    /** Null means no state change: invalid choice, an already confirmed answer, or completion. */
    fun answer(choiceIndex: Int): CoachCue? {
        if (completed || answerConfirmed) return null
        val step = ParkingLesson.steps[stepIndex]
        if (choiceIndex !in step.choices.indices) return null
        selectedChoiceIndex = choiceIndex
        answerConfirmed = choiceIndex == step.correctChoiceIndex
        if (!answerConfirmed) wrongAttemptCount++
        latestCue = if (answerConfirmed) step.correctCue else step.retryCue
        return latestCue
    }

    /** A correct answer must be confirmed before moving on; repeated taps cannot skip steps. */
    fun next(): Boolean {
        if (completed || !answerConfirmed) return false
        if (stepIndex == ParkingLesson.steps.lastIndex) {
            completed = true
            latestCue = ParkingLesson.completeCue
        } else {
            stepIndex++
            selectedChoiceIndex = null
            answerConfirmed = false
            latestCue = ParkingLesson.steps[stepIndex].intro
        }
        return true
    }

    fun restart() {
        stepIndex = 0
        selectedChoiceIndex = null
        answerConfirmed = false
        wrongAttemptCount = 0
        completed = false
        latestCue = ParkingLesson.steps.first().intro
    }
}
