package kr.drivestep.tutor.growth

import kr.drivestep.tutor.coach.CoachCue

data class SetupGuide(val title: String, val text: String, val animation: String, val cue: CoachCue)
object SetupGuides {
    val intro = CoachCue("growth_profile", "다시 운전을 시작하는 마음, 함께 준비해 볼게요. 체격은 확인 순서를 돕는 참고예요. 편안한 자세와 내 차 설명서를 함께 확인해요.")
    val steps = listOf(
        SetupGuide("시트 거리 · 페달을 끝까지", "P단과 주차 브레이크를 확인하세요. 엉덩이와 등을 시트에 붙인 상태에서 브레이크 페달을 충분히 밟을 수 있고 무릎이 약간 굽혀지는지 직접 확인해요. 키만으로 시트 이동량을 정하지 않아요.", "SEAT", CoachCue("growth_setup_pedal", "등과 엉덩이를 시트에 붙여요. 페달을 충분히 밟아도 무릎이 약간 굽혀지는지 확인해 주세요.")),
        SetupGuide("시트 높이 · 앞을 편안하게", "계기판과 전방을 편하게 볼 수 있도록 높이를 조절해요. 머리 위 공간과 시야를 함께 확인하고, 에어백과의 거리는 내 차 사용설명서 기준을 따릅니다.", "SEAT", CoachCue("growth_setup_height", "계기판과 앞길이 편안하게 보이나요? 머리 위 공간도 살펴보면서 높이를 맞춰 주세요.")),
        SetupGuide("핸들 위치 · 어깨를 편안하게", "핸들이 계기판 시야를 가리지 않게 맞추고, 등을 붙인 채 팔에 무리 없이 조작할 수 있는지 확인해요. 조절 뒤에는 잠금 상태를 확인합니다. 주행 중에는 조절하지 않아요.", "WHEEL", CoachCue("growth_setup_wheel", "등을 붙인 상태에서도 핸들을 편안하게 잡을 수 있나요? 조절 뒤 잠금 상태까지 확인해 주세요.")),
        SetupGuide("머리 받침과 안전벨트", "머리 받침은 내 차 설명서의 높이 기준에 맞춰요. 안전벨트가 꼬이지 않았는지, 어깨와 골반을 바르게 지나가는지 확인하세요. 두꺼운 물건을 몸과 벨트 사이에 두지 않아요.", "BELT", CoachCue("growth_setup_belt", "머리 받침 높이와 안전벨트를 함께 확인해요. 벨트가 꼬이지 않고 몸에 바르게 닿는지 살펴봐 주세요.")),
        SetupGuide("사이드미러 · 옆과 뒤를 함께", "좌석을 맞춘 다음 좌우 사이드미러를 조절해요. 옆과 뒤의 도로가 보이는지 확인하고, 거울만으로 사각지대가 모두 사라진다고 생각하지 않아요. 실제 조작법은 차종 설명서를 따라요.", "MIRRORS", CoachCue("growth_setup_side", "좌석을 먼저 맞춘 뒤 사이드미러를 조절해요. 거울에 보이지 않는 사각지대가 남는다는 것도 기억해 주세요.")),
        SetupGuide("백미러 · 뒤 유리 중앙", "평소 운전 자세에서 뒤 유리 쪽 시야가 가운데에 들어오는지 확인해요. 디지털 미러는 차종에 따라 표시와 조절 방식이 달라요. 출발 전 주변과 사각지대는 따로 확인합니다.", "MIRRORS", CoachCue("growth_setup_rear", "평소 운전 자세로 앉아 뒤쪽 시야를 맞춰요. 미러를 맞췄어도 주변 확인은 따로 해 주세요.")),
    )
    val complete = CoachCue("growth_setup_done", "준비 확인을 마쳤어요. 이제 가상 도로에서 천천히 연습해 볼까요? 이 확인은 직접 체크한 기록이에요.")
    val cues = listOf(intro, complete) + steps.map { it.cue }
}
