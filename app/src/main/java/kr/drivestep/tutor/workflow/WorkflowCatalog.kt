package kr.drivestep.tutor.workflow

data class ScenarioTask(val id: String, val title: String, val instruction: String, val unknown: String,
    val nextTask: String, val targetDistance: Double = 60.0)
object ScenarioCatalog {
    private const val REAL_UNKNOWN = "실제 도로·차선·주변 객체를 확인한 결과가 아니에요."
    val tasks = listOf(
        ScenarioTask("D01", "직진·차로 유지", "가속·브레이크와 조향으로 가상 차로 중앙을 유지하며 60m를 이동해요.", REAL_UNKNOWN, "D02"),
        ScenarioTask("D02", "앞차와 거리 맞추기", "움직이는 가상 앞차를 보며 속도를 조절해요. 교육 목표인 시간 간격 3초 이상을 유지하며 이동해요.", "이 연습에서는 3초 간격을 목표로 해요. 실제 앞차와의 거리나 안전 여부를 측정하지는 않아요.", "D03"),
        ScenarioTask("D03", "가속·감속·재출발", "가상 속도 10km/h에 도달한 뒤 브레이크로 멈추고 다시 출발해요.", "감속 이유를 자동으로 실수나 과실로 판단하지 않아요.", "D04"),
        ScenarioTask("D04", "방지턱 준비", "가상 40m 지점에 방지턱이 있어요. 모의 속도 10km/h 이하로 통과해요.", "실제 시설 정보나 차체 충격을 측정하지 않아요.", "D05"),
        ScenarioTask("D05", "교차로·신호 이해", "가상 정지선 30m 앞에서 멈춰요. 화면 신호가 녹색으로 바뀐 뒤 이동해요.", "이 신호는 작성된 교육 상황이며 실제 통행 허가가 아니에요.", "D06"),
        ScenarioTask("D06", "우회전·횡단보도", "오른쪽 방향지시와 주변 확인을 하고 브레이크로 멈춰요. 가상 대기가 끝나면 오른쪽으로 조향해요.", "실제 횡단보도·보행자·회전 가능 시점은 미확인이에요.", "D07"),
        ScenarioTask("D07", "좌회전·비보호 문맥", "왼쪽 방향지시와 주변 확인 후 멈춰 현장을 살피는 순서를 연습해요. 가상 대기 후 왼쪽으로 조향해요.", "실제 신호·비보호 표지·맞은편 차량을 판단하지 않아요.", "D08"),
        ScenarioTask("D08", "유턴·조향 복원", "왼쪽 방향지시와 주변 확인 뒤 가상 차체의 방향을 바꾸고 핸들을 중앙으로 복원해요.", "실제 회전 공간·핸들 각도 지시가 아니에요.", "D09"),
        ScenarioTask("D09", "차로 변경 준비", "왼쪽 방향지시·미러와 사각지대 확인 후 가상 차로 한 칸을 옮기고 핸들을 복원해요.", "미러 확인 버튼은 학습 행동이며 실제 시선 센서가 아니에요.", "D10"),
        ScenarioTask("D10", "옆 차 대기·취소", "가상 옆 차가 있어요. 대기하거나 시도를 취소할 수 있어요. 대기 후 새로 주변을 확인해요.", "기다림과 취소는 감점하지 않아요.", "D09"),
        ScenarioTask("D11", "골목·교행", "가상 교행 지점 앞에서 정차·대기하고 주변 확인 후 이동해요.", "차폭만으로 실제 통과 가능한 공간을 계산하지 않아요.", "D01"),
        ScenarioTask("D12", "간선도로 속도 조절", "가상 연습 속도 30~40km/h 구간을 3초 유지하고 브레이크로 감속해요.", "속도 범위는 과제 설정값이며 실제 제한속도가 아니에요.", "D13"),
        ScenarioTask("D13", "본선 합류 준비", "왼쪽 방향지시·주변 확인 후 가상 합류 간격을 기다려요. 가속·조향 후 핸들을 복원해요.", "실제 본선 간격을 확인하거나 진입을 허가하지 않아요.", "D14"),
        ScenarioTask("D14", "톨게이트·IC·진출", "가상 출구 70m 전에 오른쪽 방향지시와 주변 확인을 하고 오른쪽으로 이동해요.", "실제 차로·결제 수단·진출 가능 여부는 미확인이에요.", "D15", 90.0),
        ScenarioTask("D15", "생활 경로·왕복·재탐색", "가상 왕복 경로를 따라 이동해요. ‘길 놓침·재탐색’으로 이전 안내가 취소되는 흐름도 확인해요.", "GPS나 실제 내비 재탐색 결과가 아니에요.", "D01", 80.0),
        ScenarioTask("D16", "언덕·경사로 준비", "가상 경사 지점에서 브레이크를 3초 유지한 뒤 가속하며 이동해요.", "실제 경사·차량 밀림·차종별 경사로 기능을 관측하지 않아요.", "D03"),
        ScenarioTask("D17", "보호구역 준비", "표시된 가상 보호구역을 과제 속도 20km/h 이하로 이동해요.", "실제 구역·제한속도·보행자 유무는 미확인이에요.", "D05"),
        ScenarioTask("D18", "경고·돌발 상황", "가상 경고가 나타나면 교육 음성이 멈춰요. 브레이크로 정차하고 화면의 경고 해제를 확인해요.", "차량의 실제 경고 입력이나 현재 위험 감지가 아니에요.", "D03"))
    fun find(id: String) = tasks.find { it.id == id }
}

enum class ManualFeature(val title: String) { AC("에어컨"), HEAT("난방"), DEFOG("앞유리 김서림 제거"), SEAT_HEAT("좌석 열선"),
    WIPER("와이퍼·워셔"), LIGHTS("전조등"), REAR_DEFOG("뒷유리 열선"), OTHER("기타 기능") }
data class ManualVehicle(val model: String, val year: String?, val trim: String?)
data class ManualLesson(val feature: ManualFeature, val vehicle: ManualVehicle, val revision: String,
    val section: String, val source: InputSource, val steps: List<String>, val sourceUrl: String? = null)
/** Exact reference selection is required; unverified models/years never fall back to another manual. */
class ManualRepository {
    fun find(vehicle: ManualVehicle, feature: ManualFeature): ManualLesson? {
        Cn7Manual.find(vehicle, feature)?.let { return it }
        if (vehicle != DEMO_VEHICLE || feature == ManualFeature.OTHER) return null
        val title = feature.title
        val purpose = when (feature) {
            ManualFeature.AC -> "실내 공기를 냉각하는 기능을 가상 패널로 익혀요."
            ManualFeature.HEAT -> "실내를 따뜻하게 하는 기능을 가상 패널로 익혀요."
            ManualFeature.DEFOG -> "앞유리의 김서림을 줄여 시야를 확보하는 기능을 가상 패널로 익혀요."
            ManualFeature.SEAT_HEAT -> "좌석을 따뜻하게 하는 기능을 가상 패널로 익혀요."
            ManualFeature.WIPER -> "앞유리를 닦는 와이퍼와 워셔의 역할을 가상 패널로 익혀요."
            ManualFeature.LIGHTS -> "전방 시야와 내 차의 위치를 알리는 등화의 역할을 가상 패널로 익혀요."
            ManualFeature.REAR_DEFOG -> "뒷유리의 서리를 제거하는 열선의 역할을 가상 패널로 익혀요."
            ManualFeature.OTHER -> return null
        }
        return ManualLesson(feature, vehicle, "SIM-MANUAL-1", "가상 패널 / ${feature.name}", InputSource.SIMULATION,
            listOf(purpose,
                "가상 패널에서 '$title'로 표시된 영역을 찾아요. 실제 차량의 버튼 위치를 뜻하지 않아요.",
                "가상 설정을 선택한 뒤 화면의 상태 표시를 확인하는 순서를 익혀요.",
                "실제 차량에서는 차종·연식·트림이 맞는 제조사 설명서를 먼저 확인해 주세요."))
    }
    companion object { val DEMO_VEHICLE = ManualVehicle("교육용 가상 차량", "가상 1", "학습") }
}
class ManualLearningSession(val lesson: ManualLesson) {
    var index = 0; private set
    var learned = false; private set
    var simulatedOperation = false; private set
    fun operate() { if (index == 2 && lesson.source == InputSource.SIMULATION) simulatedOperation = true }
    val current get() = lesson.steps[index]
    fun next() { if (index < lesson.steps.lastIndex) index++ }
    fun previous() { if (index > 0) index-- }
    val canConfirm get() = index == lesson.steps.lastIndex && (lesson.source == InputSource.MANUAL || simulatedOperation)
    fun confirm() { if (canConfirm) learned = true }
}
