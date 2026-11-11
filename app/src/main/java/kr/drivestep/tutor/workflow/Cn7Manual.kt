package kr.drivestep.tutor.workflow

/** Reviewed against Hyundai D20240603032, 2024 CN7 Korean owner's manual. */
object Cn7Manual {
    const val URL = "https://ownersmanual.hyundai.com/full_pdf/CN7/2024/ko_KR"
    val MANUAL_CLIMATE = ManualVehicle("아반떼 CN7", "2024", "수동 공조")
    val AUTO_CLIMATE = ManualVehicle("아반떼 CN7", "2024", "자동 공조")
    fun find(vehicle: ManualVehicle, feature: ManualFeature): ManualLesson? {
        if (vehicle !in listOf(MANUAL_CLIMATE, AUTO_CLIMATE) || feature == ManualFeature.OTHER) return null
        val auto = vehicle == AUTO_CLIMATE
        val section: String
        val steps: List<String>
        when (feature) {
            ManualFeature.AC -> {
                section = if (auto) "5-78~5-85 · 자동 공조" else "5-71~5-76 · 수동 공조"
                steps = if (auto) listOf(
                    "2024 CN7 자동 공조 교육이에요. 실제 패널에 AUTO와 온도 조절부가 있는지 먼저 확인해요.",
                    "환기가 가능한 곳에서 엔진이 작동 중일 때 AUTO를 선택하면 설정 온도에 따라 냉난방을 조절해요.",
                    "원하는 온도로 낮추고 AUTO의 바람 세기를 선택해요. 바람과 표시창으로 설정을 확인해요.",
                    "실내 순환을 오래 유지하지 말고 환기해요. 이 학습은 버튼의 의미를 익히는 과정이에요.")
                else listOf(
                    "2024 CN7 수동 공조 교육이에요. 풍량·온도·바람 방향 조절부와 A/C 표시를 찾아요.",
                    "환기가 가능한 곳에서 엔진이 작동 중일 때 풍량을 정하고 A/C를 선택해 표시등을 확인해요.",
                    "온도를 청색 방향으로 낮추고 원하는 바람 방향을 선택해요. 빠른 냉방에 실내 순환을 썼다면 이후 환기해요.",
                    "A/C를 다시 선택하면 냉방을 끌 수 있어요. 설정과 실제 바람을 함께 확인해요.")
            }
            ManualFeature.HEAT -> {
                section = if (auto) "5-78~5-82 · 자동 공조" else "5-71~5-74 · 수동 공조"
                steps = listOf("난방은 실내 온도와 바람 방향을 맞추는 기능이에요. 밀폐된 공간에서 엔진을 켜거나 난방을 켜 놓고 잠들지 않아요.",
                    if (auto) "엔진 작동 중 AUTO를 선택하고 원하는 온도로 올려요." else "엔진 작동 중 온도를 따뜻한 쪽으로 조절하고 발 쪽 바람 방향을 선택해요.",
                    if (auto) "AUTO의 바람 세기와 설정 온도를 확인해요. 필요하면 풍량을 직접 조절할 수 있어요." else "풍량을 조절하고 평상시에는 외기가 들어오도록 설정해요.",
                    "유리에 습기가 끼면 앞유리 김서림 제거 기능을 이용해요. 시야가 확보된 뒤 출발해요.")
            }
            ManualFeature.DEFOG -> {
                section = if (auto) "5-88 · 자동 공조 유리창 습기/성에 제거" else "5-87 · 수동 공조 유리창 습기/성에 제거"
                steps = listOf("유리 안쪽의 김서림과 바깥쪽 성에를 구분해요. 앞유리 서리제거 표시와 뒷유리 열선 표시는 달라요.",
                    "안쪽 김서림은 풍량과 온도를 설정한 뒤 앞유리 서리제거를 선택해요. 빠르게 제거하려면 풍량을 높여요.",
                    "습기 방지 설정에 따라 외기와 A/C가 자동 선택돼요. 자동으로 선택되지 않으면 직접 설정해요.",
                    "바깥쪽 성에는 강한 풍량과 최대 온도, 앞유리 서리제거 설정을 사용해요. 유리가 충분히 맑아진 것을 직접 확인해요.")
            }
            ManualFeature.SEAT_HEAT -> {
                section = "3-10~3-11 · 좌석 히터 (장착 차량)"
                steps = listOf("좌석 히터가 장착된 차량만 해당해요. 내 차에 해당 좌석의 열선 스위치가 있는지 확인해요.",
                    "엔진 작동 중 해당 좌석의 열선 스위치를 선택하면 단계 표시등으로 상태를 확인할 수 있어요.",
                    "스위치로 온도 단계를 바꿔요. 자동 온도 조절로 단계가 달라질 수 있으니 표시등을 함께 확인해요.",
                    "오래 사용하면 낮은 온도에서도 화상을 입을 수 있어요. 열에 민감한 사람은 특히 주의하고, 필요하지 않으면 꺼요.")
            }
            ManualFeature.WIPER -> {
                section = "5-67~5-68 · 와이퍼/와셔"
                steps = listOf("2024 CN7의 와이퍼 레버와 MIST·OFF·INT·LO·HI 표시를 찾아요. 유리가 마르거나 와이퍼가 얼어붙었을 때는 작동시키지 않아요.",
                    "시동 ON 상태에서 MIST는 한 번 닦기, OFF는 정지예요. INT는 간헐 작동이며 조절 노브로 간격을 바꿔요.",
                    "LO와 HI로 연속 작동 속도를 선택해요. 워셔액이 충분한지 확인한 뒤 레버를 운전자 쪽으로 당기면 워셔액과 와이퍼가 함께 작동해요.",
                    "겨울에는 유리를 따뜻하게 한 뒤 워셔액을 사용해요. 얼어붙은 유리의 시야가 확보되었는지 직접 확인하고, 필요 없으면 OFF로 돌려요.")
            }
            ManualFeature.LIGHTS -> {
                section = "5-62~5-64 · 조명 위치와 전조등"
                steps = listOf("조명 스위치 끝의 OFF·AUTO·미등·전조등 표시를 찾아요. AUTO는 장착 차량에만 해당하며 실제 레버 표시를 먼저 확인해요.",
                    "미등 위치는 미등·차폭등·번호판등 등을 켜요. 전조등 위치는 시동 ON 상태에서 전조등도 함께 켜요. 미등과 전조등을 구분해요.",
                    "AUTO는 주변 밝기에 따라 켜지고 꺼지는 보조 기능이에요. 터널이나 악천후로 시야가 부족하면 수동으로 전조등을 켜고 상태를 직접 확인해요.",
                    "마주 오는 차가 있거나 앞차를 따라갈 때는 하향등을 사용해요. 엔진을 끈 채 조명을 오래 켜 두면 배터리가 방전될 수 있어요.")
            }
            ManualFeature.REAR_DEFOG -> {
                section = "5-89 · 뒷유리 서리제거(열선)"
                steps = listOf("뒷유리 열선 버튼과 앞유리 바람 방향 버튼을 구분해요. 뒷유리 열선은 유리에 설치된 열선으로 서리를 제거해요.",
                    "엔진 시동 상태에서 뒷유리 서리제거 버튼을 누르면 표시등이 켜져요. 장착된 실외 미러 열선도 함께 작동해요.",
                    "작동 조건에서 약 20분 후 자동으로 멈추지만, 서리가 제거되면 버튼을 다시 눌러 직접 꺼요. 시동을 다시 걸었을 때는 꺼진 상태예요.",
                    "뒷유리를 닦을 때 날카로운 도구로 열선을 손상시키지 않아요. 실제 시야와 표시등을 확인하며, 이 앱은 열선을 조작하거나 작동 여부를 측정하지 않아요.")
            }
            ManualFeature.OTHER -> return null
        }
        return ManualLesson(feature, vehicle, "CN7-2024-D20240603032", section, InputSource.MANUAL, steps, URL)
    }
}
