package kr.drivestep.tutor.lessoncoach

enum class LessonTopic(val title: String) {
    LANE_CHANGE("차로 변경 준비"), TURN_PREP("회전 미리 준비"), START_STOP("출발·정지 돌아보기")
}
enum class LessonPhase { READY, ACTIVE, PAUSED, FINISHED }
enum class LessonDirection(val label: String) { LEFT("왼쪽"), RIGHT("오른쪽"), UNKNOWN("미확인") }
enum class LessonSignal { OFF, LEFT, RIGHT, UNKNOWN }
enum class LessonGear { P, R, N, D, UNKNOWN }
enum class VehicleInputSource { SDK, SIMULATION }
enum class NavigationInputSource { NAVI, SIMULATION }
enum class SpeechInputSource { PLEOS_STT, TEXT_DEMO }
enum class TrafficDensity { HIGH, LOW, UNKNOWN }
enum class TrafficFlow { STOPPED, MOVING, UNKNOWN }
enum class LessonReflection(val label: String) { DIFFICULT("어려웠어요"), OKAY("보통이었어요"), EASY("수월했어요") }
enum class LessonEvidence { DRIVER_REPORTED, SDK, SIMULATION, NAVI, APP }
enum class LessonEventKind { START, UTTERANCE, GUIDANCE, VEHICLE, NAVIGATION, PAUSE, RESUME, FINISH }

/** Driver statements are never sensor measurements or permission to change lanes. */
data class ReportedTraffic(
    val density: TrafficDensity = TrafficDensity.UNKNOWN,
    val flow: TrafficFlow = TrafficFlow.UNKNOWN,
    val approaching: Boolean? = null,
    val atMillis: Long? = null,
) {
    val description: String get() = listOfNotNull(
        when (density) { TrafficDensity.HIGH -> "차가 많다고 설명함"; TrafficDensity.LOW -> "차가 적다고 설명함"; else -> null },
        when (flow) { TrafficFlow.STOPPED -> "멈춰 있다고 설명함"; TrafficFlow.MOVING -> "움직인다고 설명함"; else -> null },
        when (approaching) { true -> "차가 다가온다고 설명함"; false -> "접근하지 않는다고 설명함"; null -> null },
    ).joinToString(" · ").ifBlank { "주변 상황 미확인" }
}

/** Per-channel timestamps preserve missing/stale channels in SDK aggregate readings. */
data class VehicleObservation(
    val atMillis: Long,
    val source: VehicleInputSource,
    val speedKmh: Double? = null,
    val signal: LessonSignal = LessonSignal.UNKNOWN,
    val gear: LessonGear = LessonGear.UNKNOWN,
    val brakePressed: Boolean? = null,
    val frontWheelAngleDegrees: Double? = null,
    val isInitial: Boolean = false,
    val speedAtMillis: Long = atMillis,
    val signalAtMillis: Long = atMillis,
    val gearAtMillis: Long = atMillis,
    val brakeAtMillis: Long = atMillis,
    val steeringAtMillis: Long = atMillis,
)

data class NavigationObservation(
    val atMillis: Long,
    val source: NavigationInputSource,
    val maneuverId: String,
    val direction: LessonDirection,
    val remainingMeters: Double,
)

data class LessonEvent(
    val id: Long,
    val atMillis: Long,
    val kind: LessonEventKind,
    val source: LessonEvidence,
    val title: String,
    val detail: String,
    val vehicle: VehicleObservation? = null,
)

data class LessonSnapshot(
    val topic: LessonTopic = LessonTopic.LANE_CHANGE,
    val phase: LessonPhase = LessonPhase.READY,
    val direction: LessonDirection = LessonDirection.UNKNOWN,
    val reportedTraffic: ReportedTraffic = ReportedTraffic(),
    val awaitingDirection: Boolean = false,
    val awaitingConfirmation: Boolean = false,
    val lastUtterance: String = "",
    val speechInputSource: SpeechInputSource? = null,
    val reply: String = "오늘 익힐 항목을 골라 연수를 시작해요.",
    val cueRevision: Long = 0,
    val vehicle: VehicleObservation? = null,
    val navigation: NavigationObservation? = null,
    val events: List<LessonEvent> = emptyList(),
    val reflection: LessonReflection? = null,
    val nextLesson: LessonTopic? = null,
    val nextLessonReason: String = "",
)
