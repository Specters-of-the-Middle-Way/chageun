package kr.drivestep.tutor.workflow

data class DrivingGuide(val id: String, val title: String, val steps: List<String>, val question: String,
    val choices: List<String>, val answer: Int, val explanation: String, val source: String, val sourceUrl: String,
    val illustrations: List<RoadIllustration> = emptyList(), val relatedTask: String? = null, val additionalSourceUrl: String? = null)

object DrivingGuideCatalog {
    val guides = listOf(
        DrivingGuide("shoulder", "숄더 체크와 사각지대", listOf(
            "차로를 바꾸기 전 룸미러와 해당 방향 사이드미러로 주변 교통을 확인해요. 미러에 보이지 않는 옆·뒤 영역도 있어요.",
            "숄더 체크는 이동하려는 쪽으로 고개를 짧게 돌려 미러의 사각지대를 직접 살피는 확인이에요. 전방을 오래 놓치지 않고 시선을 돌려와요.",
            "방향지시로 의도를 알리고 주변 차량의 거리와 접근 속도를 다시 살펴요. 신호를 켰다고 다른 차가 양보했다고 판단하지 않아요.",
            "진입할 여유가 확실하지 않으면 차로를 유지하고 다음 기회를 기다려요. 앱의 확인 버튼은 실제 사각지대 관측을 대신하지 않아요."),
            "미러에는 차가 없지만 사각지대가 확인되지 않았다면?", listOf("방향지시만 켜고 이동해요", "차로를 유지하며 직접 확인해요"), 1,
            "미러와 직접 확인을 함께 사용해요. 안전한 여유가 확인되기 전에는 차로를 유지해요.",
            "Iowa DOT 운전자 안내서 · 시야와 사각지대 확인", "https://iowadot.gov/mvd/driverslicense/studyguides/korean.pdf"),
        DrivingGuide("double_parking", "다른 차 앞의 이중 주차", listOf(
            "여기서 이중 주차는 주차된 다른 차 앞을 가로막아 세우는 상황이에요. 두 대의 차 사이에 넣는 평행 주차와 구분해요.",
            "빈 정규 주차 공간을 먼저 찾아요. 관리 안내를 확인하고 다른 차의 출차 경로, 보행자 통로, 비상 통행 공간을 막는지 살펴요.",
            "다른 차가 나가야 한다면 직접 차량을 이동할 수 있도록 현장 관리자와 조율해요. 경사나 주변 장애물 때문에 차량이 움직일 위험도 직접 확인해요.",
            "N단 유지와 주차 브레이크 해제는 차량 사양과 조건에 따라 달라요. 이 교육에서는 해당 조작을 지시하지 않으며, 차종에 맞는 설명서를 확인해요."),
            "차를 세우려는 곳이 다른 차의 유일한 출차 통로라면?", listOf("정규 공간을 찾거나 관리자와 이동을 조율해요", "다른 사람이 밀 수 있다고 가정하고 떠나요"), 0,
            "다른 차의 출차와 통행 공간을 먼저 확보해요. 앱은 실제 주변 거리나 차량 고정 상태를 확인하지 못해요.",
            "기획 문서의 이중 주차 상황 · 차량 조작 기준은 CN7 취급설명서", Cn7Manual.URL),
        DrivingGuide("winter", "성에·난방과 도로 살얼음", listOf(
            "출발 전 유리 안쪽 김서림과 바깥쪽 성에를 확인해요. 앞유리 서리제거 기능과 외기 설정으로 시야를 확보해요.",
            "2024 CN7의 바깥쪽 성에 제거는 강한 풍량·최대 온도·앞유리 서리제거 설정을 사용해요. 공조 방식별 기능 교육도 확인할 수 있어요.",
            "살얼음은 눈에 잘 드러나지 않을 수 있어요. 교량, 터널 출입구, 그늘진 도로는 기상과 도로 정보를 특히 확인해요.",
            "결빙이 의심되면 미리 속도를 낮추고 충분한 차간 거리를 확보해요. 급가속·급제동·급조향을 피하고 필요하면 출발을 미뤄요."),
            "기온이 영상이면 노면도 반드시 얼지 않았을까요?", listOf("영상이면 결빙이 없어요", "기온만으로 노면 결빙을 단정할 수 없어요"), 1,
            "그늘·교량 등 노면 조건은 서로 달라요. 날씨 예보는 실제 노면 센서가 아니에요.",
            "국토교통부 · 블랙아이스 예방법 / CN7 설명서 5-87~5-88", "https://www.korea.kr/news/policyNewsView.do?newsId=148922849")
    ) + RoadLearningCatalog.guides
}

/** Completion means comprehension checked, not a measured real driving skill. */
class DrivingGuideSession(val guide: DrivingGuide) {
    var index = 0; private set
    var selected: Int? = null; private set
    val current get() = guide.steps[index]
    val completed get() = selected == guide.answer && index == guide.steps.lastIndex
    fun next() { if (index < guide.steps.lastIndex) index++ }
    fun previous() { if (index > 0) index--; selected = null }
    fun answer(choice: Int) { if (index == guide.steps.lastIndex && choice in guide.choices.indices) selected = choice }
}
