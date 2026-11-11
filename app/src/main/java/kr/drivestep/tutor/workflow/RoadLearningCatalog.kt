package kr.drivestep.tutor.workflow

/** Original lesson summaries; road rules use Korean sources, general scanning uses DMV guidance. */
object RoadLearningCatalog {
    const val SCANNING_SOURCE = "https://www.dmv.ca.gov/portal/handbook/california-driver-handbook/safe-driving/"
    const val GAP_SOURCE = "https://www.gov.uk/guidance/the-highway-code/general-rules-techniques-and-advice-for-all-drivers-and-riders-103-to-158"
    const val MARKING_SOURCE = "https://law.go.kr/LSW/flDownload.do?bylClsCd=110201&flSeq=144234901"
    const val PEDESTRIAN_SOURCE = "https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1025449721"
    const val TURN_SOURCE = "https://www.dgpolice.go.kr/bbs/view.do?bbsId=k458a172&num=1039&pageNum=38"
    val guides = listOf(
        DrivingGuide("look_ahead", "멀리 보기·차로 유지", listOf(
            "차 바로 앞만 보지 않고 먼 도로의 흐름과 변화까지 살펴요. 한 지점에 시선을 고정하지 않고 앞쪽을 넓게 훑어요.",
            "먼 곳을 본 뒤 가까운 차로의 양쪽 경계와 주변 상황도 확인해요. 보행자나 가려진 공간처럼 변할 수 있는 곳을 살펴요.",
            "방향이나 속도를 바꾸기 전 미러로 뒤·옆을 짧게 확인하고 전방으로 돌아와요. 미러를 오래 보는 동안 앞쪽을 놓치지 않아요.",
            "차로 중앙 유지는 차로 양쪽과 진행 방향을 함께 보는 연습이에요. 고개나 시선을 움직였다고 핸들을 따라 돌리지 않고 작은 조작으로 흐름을 유지해요."),
            "멀리 보기 연습에서 적절한 시선 사용은?", listOf("한 먼 지점만 계속 바라봐요", "먼 흐름·가까운 주변·미러를 살피고 전방으로 돌아와요"), 1,
            "시선을 한 곳에 고정하지 않아요. 화면의 그림은 학습용이며 실제 시선이나 차선 유지 능력을 측정하지 않아요.",
            "California DMV · Safe Driving / 일반적인 시야 확인 요령", SCANNING_SOURCE,
            illustrations = listOf(RoadIllustration.LOOK_AHEAD, RoadIllustration.NEAR_FIELD, RoadIllustration.MIRRORS, RoadIllustration.LANE_CENTER), relatedTask = "D01"),
        DrivingGuide("following_gap", "앞차와 시간 간격", listOf(
            "앞차와 떨어진 거리만 같아도 속도가 빨라지면 반응할 시간은 줄어요. 앞차가 지난 고정 지점을 내 차가 지날 때까지의 시간으로 간격을 생각해 봐요.",
            "아래 숫자는 직접 선택하는 가상 예시예요. 거리 ÷ 내 차의 속도로 시간 간격을 비교해요. 앞차를 따라잡는 시간이나 정지거리를 계산한 값은 아니에요.",
            "3초와 6초는 비교를 위한 교육 목표예요. 비·눈·낮은 시야에서는 더 큰 여유가 필요하며, 실제 안전거리는 노면·차량·속도·주변 상황에 따라 달라요.",
            "예시 속도를 30에서 60km/h로 바꾸고 거리를 그대로 두어 보세요. 시간 간격이 줄어들면 속도를 낮추거나 거리를 더 확보해야 같은 여유를 만들 수 있어요."),
            "같은 30m 간격에서 속도만 두 배로 높이면 시간 간격은?", listOf("절반으로 줄어요", "그대로예요"), 0,
            "시간 간격은 거리를 속도로 나눈 값이에요. 예시 목표에 도달해도 실제 도로에서 안전하다는 판정은 아니에요.",
            "영국 Highway Code 126 · 시간 간격과 젖은 노면에서의 여유 원리 (국내 법정 기준 아님)", GAP_SOURCE, relatedTask = "D02"),
        DrivingGuide("road_markings", "노면 표시 읽기", listOf(
            "같은 방향 차로 사이의 흰색 점선은 진로 변경을 검토할 수 있는 구간이에요. 선만 보고 이동하지 말고 표지·신호·주변 차량과 사각지대를 함께 확인해요.",
            "같은 방향 차로 사이의 흰색 실선은 진로 변경을 제한하는 표시예요. 출구를 놓쳐도 실선을 넘어 급히 이동하지 않고 다음 경로를 찾아요.",
            "도로 중앙의 황색 실선은 반대 방향 교통을 나누며 넘어가지 않는 선이에요. 길 가장자리의 주정차 표시와 구분하고 색뿐 아니라 위치·모양도 읽어요.",
            "횡단보도 앞의 정지선은 정지할 위치를 알려줘요. 횡단보도 위를 비워 두고 차량 신호와 보행자를 함께 확인해요. 선의 모양만으로 출발을 결정하지 않아요."),
            "출구가 보이지만 내 차로와 옆 차로 사이가 흰색 실선이라면?", listOf("출구가 가까우니 실선을 넘어가요", "차로를 유지하고 다음 경로를 확인해요"), 1,
            "이 그림은 같은 방향 차로 사이의 진로변경제한선 예시예요. 표지와 신호를 확인하고 급한 진로 변경을 피하세요.",
            "도로교통법 시행규칙 별표 6 · 중앙선·차선·진로변경제한선·정지선", MARKING_SOURCE,
            illustrations = listOf(RoadIllustration.WHITE_DASH, RoadIllustration.WHITE_SOLID, RoadIllustration.YELLOW_CENTER, RoadIllustration.STOP_LINE), relatedTask = "D05"),
        DrivingGuide("intersection", "비보호 좌회전·횡단보도", listOf(
            "비보호 좌회전은 표지와 신호를 함께 확인해요. 표지가 없는 곳의 녹색 신호를 좌회전 허용으로 해석하지 않으며, 적색 신호에서는 비보호 좌회전을 하지 않아요.",
            "비보호 표시가 있고 녹색 신호여도 맞은편 직진 차량이나 보행자의 진행을 방해할 우려가 있으면 기다려요. 앞차가 갔다고 그대로 따라가지 않아요.",
            "횡단보도를 건너거나 건너려는 보행자가 있으면 횡단보도 앞에서 일시정지해요. 정지선이 있다면 그 앞에 멈추고 횡단 공간을 비워 둬요.",
            "교차로에서 회전할 때도 신호에 따라 건너는 보행자를 방해하지 않아요. 앱의 가상 신호나 대기 완료는 실제 교차로의 통행 허가가 아니에요."),
            "비보호 표지와 녹색 신호가 있지만 맞은편 직진 차량이 접근한다면?", listOf("진행을 방해하지 않도록 기다려요", "녹색이므로 먼저 좌회전해요"), 0,
            "녹색 하나만으로 회전을 결정하지 않아요. 맞은편 교통과 보행자의 진행을 함께 확인해요.",
            "도로교통법 제27조 · 보행자 보호 / 대구수성경찰서 비보호 겸용 좌회전 안내", PEDESTRIAN_SOURCE,
            additionalSourceUrl = TURN_SOURCE, relatedTask = "D07")
    )
}
