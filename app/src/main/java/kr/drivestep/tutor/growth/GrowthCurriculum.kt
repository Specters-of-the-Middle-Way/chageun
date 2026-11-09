package kr.drivestep.tutor.growth

import kr.drivestep.tutor.coach.CoachCue

enum class Competency { SETUP, CONTROL, RULES, JUDGMENT, COURTESY }

data class GrowthLesson(
    val id: String, val title: String, val subtitle: String, val chapter: Int,
    val competency: Competency, val scene: String, val steps: List<GrowthStep>, val sourceNote: String,
)

data class GrowthStep(val id: String, val prompt: String, val cue: CoachCue, val choices: List<GrowthChoice>)

data class GrowthChoice(
    val id: String, val label: String, val correct: Boolean, val feedback: CoachCue, val animation: String,
)

/** Parked, fictional decision practice. No choice authorizes or controls a real maneuver. */
object GrowthCurriculum {
    val lessons: List<GrowthLesson> = listOf(
        GrowthLesson(
            id = "ready", title = "내 차와 친해지기", subtitle = "시트·미러·벨트를 직접 확인해요", chapter = 1,
            competency = Competency.SETUP, scene = "setup",
            sourceNote = "차량 사용설명서 기반의 준비 안내. 체격만으로 안전한 시트 절대값을 계산하지 않습니다.",
            steps = listOf(
                GrowthStep(
                    id = "ready_seat", prompt = "처음 운전석에 앉았어요. 페달과 핸들 거리를 어떻게 맞출까요?",
                    cue = CoachCue("growth_ready_seat_intro", "먼저 차를 움직이지 않고 자세를 맞춰요. 키 하나로 시트 위치가 정해지지는 않아요."),
                    choices = listOf(
                        GrowthChoice("ready_seat_check", "페달·핸들에 닿는 자세를 직접 확인", true, CoachCue("growth_ready_seat_ok", "좋아요. 등과 어깨를 받친 채 페달을 충분히 밟고 핸들에 무리 없이 닿는지 확인해요."), "OBSERVE"),
                        GrowthChoice("ready_seat_height", "키만 입력하고 추천값을 그대로 적용", false, CoachCue("growth_ready_seat_retry", "키가 같아도 팔과 다리 길이는 달라요. 내 몸으로 페달과 핸들에 닿는지 확인해 볼까요?"), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "ready_mirror", prompt = "자세를 맞췄어요. 미러를 조절할 순서는 무엇일까요?",
                    cue = CoachCue("growth_ready_mirror_intro", "운전 자세가 달라지면 미러에 보이는 범위도 달라져요. 자세를 먼저 맞춘 다음 조절해요."),
                    choices = listOf(
                        GrowthChoice("ready_mirror_only", "미러에 보이는 부분만 믿고 출발", false, CoachCue("growth_ready_mirror_retry", "미러만으로 주변 전체를 볼 수는 없어요. 사각지대도 확인하는 순서를 기억해요."), "STOP"),
                        GrowthChoice("ready_mirror_adjust", "운전 자세 유지 → 미러 조절 → 사각 확인", true, CoachCue("growth_ready_mirror_ok", "맞아요. 미러를 조절해도 사각지대는 남아요. 직접 주변을 살피는 확인을 함께 해요."), "OBSERVE"),
                    ),
                ),
                GrowthStep(
                    id = "ready_belt", prompt = "출발 전 마지막 확인이에요. 무엇부터 할까요?",
                    cue = CoachCue("growth_ready_belt_intro", "차가 아직 움직이지 않는 지금, 벨트와 머리받침, 주변 상황을 한 번 더 확인해요."),
                    choices = listOf(
                        GrowthChoice("ready_belt_ready", "벨트·머리받침·주변을 확인", true, CoachCue("growth_ready_belt_ok", "차분하게 준비했어요. 내 차 설명서에 맞춘 조절과 주변 확인이 출발의 시작이에요."), "WAIT"),
                        GrowthChoice("ready_belt_later", "출발한 뒤 벨트와 시트를 조절", false, CoachCue("growth_ready_belt_retry", "움직이는 중에 자세를 고치려 하면 주의가 나뉘어요. 정차한 지금 준비를 마쳐요."), "STOP"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "departure", title = "차분하게 출발하기", subtitle = "확인·방향지시·양보 순서를 연결해요", chapter = 1,
            competency = Competency.CONTROL, scene = "departure",
            sourceNote = "정차 상태의 가상 합류 연습. 주변 빈 공간을 실제로 인식하거나 진입을 허가하지 않습니다.",
            steps = listOf(
                GrowthStep(
                    id = "departure_observe", prompt = "가상 차량을 길 가장자리에서 출발시키려 해요. 첫 행동은?",
                    cue = CoachCue("growth_departure_observe_intro", "바로 출발하기보다 주변부터 살펴볼까요? 뒤와 옆에서 오는 사람과 차를 확인해요."),
                    choices = listOf(
                        GrowthChoice("departure_observe_go", "앞이 비어 보이니 바로 출발", false, CoachCue("growth_departure_observe_retry", "앞쪽이 비어도 옆과 뒤에서 다가올 수 있어요. 먼저 주변을 확인해요."), "STOP"),
                        GrowthChoice("departure_observe_look", "주변과 사각지대부터 확인", true, CoachCue("growth_departure_observe_ok", "좋아요. 보이는 곳과 가려진 곳을 함께 확인하는 준비예요."), "OBSERVE"),
                    ),
                ),
                GrowthStep(
                    id = "departure_signal", prompt = "주변을 확인했어요. 왼쪽으로 합류할 의사를 어떻게 알릴까요?",
                    cue = CoachCue("growth_departure_signal_intro", "이제 가상 차량의 진로를 알리는 순서예요. 방향지시는 진입 우선권을 주지는 않아요."),
                    choices = listOf(
                        GrowthChoice("departure_signal_left", "왼쪽 방향지시등으로 의사 표시", true, CoachCue("growth_departure_signal_ok", "맞아요. 방향지시로 알린 뒤에도 다른 차의 진행을 방해하지 않는지 살펴야 해요."), "SIGNAL"),
                        GrowthChoice("departure_signal_hazard", "비상등으로 합류 방향 대신 표시", false, CoachCue("growth_departure_signal_retry", "비상등은 어느 방향으로 갈지 알려주지 못해요. 합류 방향을 알리는 신호를 선택해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "departure_yield", prompt = "방향지시 후 다른 차가 가까이 오고 있어요. 다음 행동은?",
                    cue = CoachCue("growth_departure_yield_intro", "깜빡이를 켰어도 바로 들어갈 수 있는 것은 아니에요. 다가오는 차의 진행을 먼저 살펴요."),
                    choices = listOf(
                        GrowthChoice("departure_yield_push", "차 앞부분부터 조금씩 밀어 넣기", false, CoachCue("growth_departure_yield_retry", "상대가 급히 피하거나 멈춰야 하는 합류는 피해야 해요. 공간을 다시 확인하며 기다려요."), "STOP"),
                        GrowthChoice("departure_yield_wait", "기다렸다가 충분한 공간을 다시 확인", true, CoachCue("growth_departure_yield_ok", "좋은 판단이에요. 급히 끼어들기보다 충분한 간격이 확인될 때까지 기다려요."), "WAIT"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "solid_line", title = "실선 앞에서 판단하기", subtitle = "늦은 차로 선택에도 선을 지켜요", chapter = 2,
            competency = Competency.RULES, scene = "solid_line",
            sourceNote = "백색 실선인 진로변경제한선을 가정합니다. 다른 차선 표시나 현장 통제는 별도로 확인해야 합니다.",
            steps = listOf(
                GrowthStep(
                    id = "solid_line_notice", prompt = "백색 실선 구간에서 내비가 곧 우회전하라고 해요. 어떻게 할까요?",
                    cue = CoachCue("growth_solid_line_notice_intro", "이번 가상 도로의 백색 실선은 진로변경을 제한하는 표시예요. 안내가 급해도 표시를 먼저 따라요."),
                    choices = listOf(
                        GrowthChoice("solid_line_notice_keep", "실선을 넘지 않고 현재 차로 유지", true, CoachCue("growth_solid_line_notice_ok", "잘 판단했어요. 이번 길을 놓쳐도 실선을 넘어 급하게 이동하지 않아요."), "WAIT"),
                        GrowthChoice("solid_line_notice_cross", "길을 놓치지 않으려고 실선을 넘기", false, CoachCue("growth_solid_line_notice_retry", "내비 안내가 도로 표시보다 우선하지는 않아요. 실선을 넘지 않는 선택으로 돌아와요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "solid_line_reroute", prompt = "갈림길을 지나쳤어요. 다음 선택은?",
                    cue = CoachCue("growth_solid_line_reroute_intro", "길을 놓친 상황에서도 급한 조작을 줄이는 연습이에요. 갈 수 있는 다음 경로를 찾아요."),
                    choices = listOf(
                        GrowthChoice("solid_line_reroute_brake", "갑자기 멈춰 뒤차 앞에서 방향 바꾸기", false, CoachCue("growth_solid_line_reroute_retry", "갑작스러운 정지와 진로변경은 뒤차를 놀라게 해요. 진행 가능한 경로에서 다시 찾아요."), "STOP"),
                        GrowthChoice("solid_line_reroute_route", "현재 진행을 유지하며 경로 재탐색", true, CoachCue("growth_solid_line_reroute_ok", "좋아요. 재탐색은 실수를 만회하려고 무리하지 않는 방법이에요."), "PROCEED"),
                    ),
                ),
                GrowthStep(
                    id = "solid_line_dotted", prompt = "이제 점선 구간이에요. 차로 변경 전에 무엇이 필요할까요?",
                    cue = CoachCue("growth_solid_line_dotted_intro", "점선이 보인다고 바로 이동하지는 않아요. 신호와 주변 차량의 간격을 함께 확인해요."),
                    choices = listOf(
                        GrowthChoice("solid_line_dotted_confirm", "방향지시 후 사각·주변 간격 확인", true, CoachCue("growth_solid_line_dotted_ok", "맞아요. 점선 여부와 주변 확인은 함께 필요한 조건이에요."), "OBSERVE"),
                        GrowthChoice("solid_line_dotted_free", "점선이면 확인 없이 즉시 이동", false, CoachCue("growth_solid_line_dotted_retry", "점선도 무조건 진입해도 된다는 뜻은 아니에요. 다른 차의 진행에 영향을 주는지 확인해요."), "STOP"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "right_turn", title = "우회전 신호 읽기", subtitle = "차량 신호와 보행자를 따로 확인해요", chapter = 2,
            competency = Competency.RULES, scene = "right_turn",
            sourceNote = "도로교통법 제27조와 경찰청 우회전 안내 기반. 아래 단계는 서로 다른 가상 조건이며 신호 색 하나로 판단하지 않습니다.",
            steps = listOf(
                GrowthStep(
                    id = "right_turn_red", prompt = "우회전 전용 신호가 없는 교차로예요. 전방 차량 신호는 빨간색이에요.",
                    cue = CoachCue("growth_right_turn_red_intro", "빨간 차량 신호 앞에서는 먼저 완전히 멈추는 순서를 연습해요. 정지선이 표시된 상황이에요."),
                    choices = listOf(
                        GrowthChoice("right_turn_red_roll", "보행자가 안 보이니 서행하며 바로 우회전", false, CoachCue("growth_right_turn_red_retry", "서행은 완전한 정지와 달라요. 빨간 차량 신호 앞에서는 먼저 멈춰요."), "STOP"),
                        GrowthChoice("right_turn_red_stop", "정지선 앞에서 완전히 정지", true, CoachCue("growth_right_turn_red_ok", "맞아요. 정지선 앞에서 완전히 멈춘 다음 통행 상황을 확인해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "right_turn_people", prompt = "이번 횡단보도에는 건너려는 보행자가 있어요. 다음 행동은?",
                    cue = CoachCue("growth_right_turn_people_intro", "이미 건너는 사람뿐 아니라 건너려고 하는 사람도 살펴요. 신호 색만으로 출발을 정하지 않아요."),
                    choices = listOf(
                        GrowthChoice("right_turn_people_wait", "일시정지하고 보행자 통행 보호", true, CoachCue("growth_right_turn_people_ok", "좋아요. 보행자가 건너거나 건너려는 때에는 정지해 통행을 보호해요."), "WAIT"),
                        GrowthChoice("right_turn_people_pass", "보행자 앞 공간으로 먼저 통과", false, CoachCue("growth_right_turn_people_retry", "보행자가 멈추거나 피해야 하는 통행은 피해야 해요. 멈추고 보행자 통행을 보호해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "right_turn_arrow", prompt = "다른 교차로에 왔어요. 이번에는 우회전 전용 신호가 빨간색이에요.",
                    cue = CoachCue("growth_right_turn_arrow_intro", "우회전 전용 신호가 설치된 교차로에서는 그 신호를 따라요. 빈 길처럼 보여도 신호를 확인해요."),
                    choices = listOf(
                        GrowthChoice("right_turn_arrow_empty", "횡단보도가 비었으니 우회전", false, CoachCue("growth_right_turn_arrow_retry", "전용 신호가 설치된 곳에서는 그 신호를 따라야 해요. 빨간 신호에서는 기다려요."), "STOP"),
                        GrowthChoice("right_turn_arrow_obey", "녹색 화살표 신호까지 정지", true, CoachCue("growth_right_turn_arrow_ok", "맞아요. 전용 신호가 있으면 녹색 화살표 신호를 따르고, 보행자도 계속 살펴요."), "STOP"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "highway", title = "추월 후 돌아오기", subtitle = "고속도로 차로의 역할을 익혀요", chapter = 2,
            competency = Competency.RULES, scene = "highway",
            sourceNote = "승용차·편도 2차로 고속도로·원활한 통행을 가정합니다. 통행량 증가 등으로 80km/h 미만 통행이 불가피한 경우의 예외와 다른 도로를 구분합니다.",
            steps = listOf(
                GrowthStep(
                    id = "highway_lane", prompt = "원활한 편도 2차로 고속도로예요. 추월하지 않고 주행할 차로는?",
                    cue = CoachCue("growth_highway_lane_intro", "이번 장면은 일반 시내도로가 아니라 고속도로예요. 추월차로와 주행차로를 구분해요."),
                    choices = listOf(
                        GrowthChoice("highway_lane_right", "주행차로인 오른쪽 차로 선택", true, CoachCue("growth_highway_lane_ok", "맞아요. 원활한 고속도로에서 추월 목적이 없는 주행은 주행차로를 이용해요."), "RIGHT_LANE"),
                        GrowthChoice("highway_lane_left", "편하니 계속 1차로 정속 주행", false, CoachCue("growth_highway_lane_retry", "이 장면의 1차로는 추월차로예요. 추월 목적이 없으면 주행차로를 이용해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "highway_return", prompt = "추월을 마쳤고 복귀할 차로의 간격도 확인했어요. 다음 행동은?",
                    cue = CoachCue("growth_highway_return_intro", "추월 뒤에는 다시 주변을 확인해요. 방향지시를 하고 충분한 간격으로 복귀하는 연습이에요."),
                    choices = listOf(
                        GrowthChoice("highway_return_stay", "앞에 차가 없으니 추월차로 계속 유지", false, CoachCue("growth_highway_return_retry", "추월을 마쳤다면 주행차로로 돌아오는 순서를 기억해요. 복귀 전 주변 확인도 필요해요."), "STOP"),
                        GrowthChoice("highway_return_return", "방향지시 후 주행차로로 복귀", true, CoachCue("growth_highway_return_ok", "좋아요. 추월 뒤에도 상대 차와 충분한 간격을 두고 주행차로로 돌아와요."), "RIGHT_LANE"),
                    ),
                ),
                GrowthStep(
                    id = "highway_exception", prompt = "차가 몰려 모두 느리게 움직여요. 예외를 어떻게 이해할까요?",
                    cue = CoachCue("growth_highway_exception_intro", "정체 등 부득이한 사정의 예외와, 내가 느리게 운전하는 경우는 달라요."),
                    choices = listOf(
                        GrowthChoice("highway_exception_traffic", "통행량 등 불가피한 조건의 예외로 이해", true, CoachCue("growth_highway_exception_ok", "맞아요. 내가 일부러 느리게 간다는 이유만으로 추월차로를 계속 쓰는 예외가 되지는 않아요."), "OBSERVE"),
                        GrowthChoice("highway_exception_slow", "내 속도만 80 미만이면 언제나 1차로 가능", false, CoachCue("growth_highway_exception_retry", "내 속도 하나로 예외가 정해지지는 않아요. 통행량 증가 등 불가피한 조건을 구분해요."), "STOP"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "alley", title = "골목에서 마주쳤을 때", subtitle = "빈 공간과 다시 확인할 지점을 찾아요", chapter = 3,
            competency = Competency.JUDGMENT, scene = "alley",
            sourceNote = "정차 가상 상황입니다. 뒤쪽 양보공간과 후방 이동 구간이 비어 있다고 설정한 장면이며 실제 거리·회전각·후진 가능 여부를 계산하지 않습니다.",
            steps = listOf(
                GrowthStep(
                    id = "alley_stop", prompt = "좁은 골목에서 맞은편 차를 만났어요. 옆 간격이 불확실해요.",
                    cue = CoachCue("growth_alley_stop_intro", "맞은편 차와 서둘러 지나가려 하지 말고 먼저 멈춰요. 간격을 확신할 수 없는 상황이에요."),
                    choices = listOf(
                        GrowthChoice("alley_stop_squeeze", "거울만 접고 틈으로 바로 통과", false, CoachCue("growth_alley_stop_retry", "차체와 사람의 여유 공간까지 확인해야 해요. 거울만 접고 통과할 수 있다고 판단하지 않아요."), "STOP"),
                        GrowthChoice("alley_stop_stop", "정지하고 서로의 위치부터 확인", true, CoachCue("growth_alley_stop_ok", "좋아요. 급히 핸들을 돌리기보다 멈춰서 위치를 확인하는 판단이에요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "alley_space", prompt = "가상 장면에서 내 차 뒤쪽에 넓어진 양보공간이 보여요. 먼저 무엇을 볼까요?",
                    cue = CoachCue("growth_alley_space_intro", "양보공간이 보여도 바로 후진하지 않아요. 그곳까지 가는 구간과 보행자 여부를 먼저 살펴요."),
                    choices = listOf(
                        GrowthChoice("alley_space_check", "후방 이동 구간·사람·장애물 확인", true, CoachCue("growth_alley_space_ok", "맞아요. 도착할 공간뿐 아니라 이동 중 지나는 구간까지 확인해야 해요."), "OBSERVE"),
                        GrowthChoice("alley_space_angle", "화면 각도를 외워 확인 없이 후진", false, CoachCue("growth_alley_space_retry", "그림의 각도는 실제 차에 적용할 수 없어요. 내 차 주변과 이동 구간을 먼저 확인해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "alley_yield", prompt = "이번 가상 장면은 후방 구간과 양보공간이 비어 있는 조건이에요.",
                    cue = CoachCue("growth_alley_yield_intro", "확인된 가상 공간으로 조금 이동한 뒤 다시 멈추는 과정을 보여드릴게요. 실제 후진 허가나 추천 각도는 아니에요."),
                    choices = listOf(
                        GrowthChoice("alley_yield_rush", "상대가 기다리니 한 번에 빠르게 이동", false, CoachCue("growth_alley_yield_retry", "상대가 기다린다고 서두를 필요는 없어요. 움직이는 구간을 확인하며 필요한 때 다시 멈춰요."), "STOP"),
                        GrowthChoice("alley_yield_space", "가상 양보공간으로 이동 후 다시 정지", true, CoachCue("growth_alley_yield_ok", "차분하게 양보공간을 활용했어요. 실제로는 움직이는 동안에도 주변을 계속 확인해야 해요."), "REVERSE_SAFE"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "hill", title = "좁은 언덕에서 양보하기", subtitle = "통행 원칙과 현장 조건을 함께 봐요", chapter = 3,
            competency = Competency.JUDGMENT, scene = "hill",
            sourceNote = "도로교통법 제20조: 좁은 비탈길에서 서로 마주보며 진행할 때 올라가는 차가 도로 우측 가장자리로 양보. 무조건 후진하거나 특정 각도로 피하라는 의미가 아닙니다.",
            steps = listOf(
                GrowthStep(
                    id = "hill_hold", prompt = "가상 차량이 좁은 비탈길을 올라가다 내려오는 차를 만났어요.",
                    cue = CoachCue("growth_hill_hold_intro", "먼저 멈춰 차량이 밀리지 않도록 유지하는 상황을 연습해요. 실제 페달 조작은 차종 설명서를 따라요."),
                    choices = listOf(
                        GrowthChoice("hill_hold_hold", "멈추고 밀리지 않게 정지 상태 유지", true, CoachCue("growth_hill_hold_ok", "좋아요. 위치를 확인하는 동안 정지 상태를 유지하는 준비가 먼저예요."), "STOP"),
                        GrowthChoice("hill_hold_reverse", "당황해서 바로 후진으로 변속", false, CoachCue("growth_hill_hold_retry", "상대 차를 만났다고 바로 후진하지 않아요. 먼저 멈추고 주변과 양보할 공간을 확인해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "hill_rule", prompt = "좁은 비탈길에서 서로 마주보며 진행할 때의 통행 원칙은?",
                    cue = CoachCue("growth_hill_rule_intro", "이번에는 좁은 비탈길의 양보 원칙을 알아봐요. 평지 골목과 같은 장면으로 단정하지 않아요."),
                    choices = listOf(
                        GrowthChoice("hill_rule_downhill", "내려오는 차가 언제나 후진해야 함", false, CoachCue("growth_hill_rule_retry", "내려오는 차가 언제나 후진한다는 규칙은 아니에요. 이 상황에서는 올라가는 차의 우측 양보 원칙을 기억해요."), "STOP"),
                        GrowthChoice("hill_rule_uphill", "올라가는 차가 우측 가장자리로 양보", true, CoachCue("growth_hill_rule_ok", "맞아요. 좁은 비탈길의 이 상황에서는 올라가는 차가 우측 가장자리로 양보하는 원칙이에요."), "OBSERVE"),
                    ),
                ),
                GrowthStep(
                    id = "hill_room", prompt = "내 차가 올라가는 차예요. 이번 가상 장면의 우측 양보공간은 비어 있어요.",
                    cue = CoachCue("growth_hill_room_intro", "사람과 장애물, 가장자리 상태를 확인한 가상 공간이에요. 확인되지 않은 공간으로 이동하라는 안내는 아니에요."),
                    choices = listOf(
                        GrowthChoice("hill_room_yield", "확인된 가상 우측 공간에서 양보", true, CoachCue("growth_hill_room_ok", "좋아요. 실제 현장에서 공간이 없거나 시야가 가리면 멈춰 확인하고 무리한 조작을 피해야 해요."), "YIELD"),
                        GrowthChoice("hill_room_turn", "핸들을 무조건 끝까지 돌려 피하기", false, CoachCue("growth_hill_room_retry", "핸들을 얼마나 돌릴지는 차와 공간에 따라 달라요. 확인 없이 끝까지 돌리는 방법으로 판단하지 않아요."), "STOP"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "tunnel", title = "터널의 공기와 시야", subtitle = "편의 설정과 법규를 구분해요", chapter = 3,
            competency = Competency.JUDGMENT, scene = "tunnel",
            sourceNote = "창문 닫기·내기순환은 오염 공기 유입을 줄이는 편의 안내이며 보편적인 법규상 의무로 채점하지 않습니다. 터널 자동 공조 기능이 이미 있는 차종도 있습니다.",
            steps = listOf(
                GrowthStep(
                    id = "tunnel_air", prompt = "터널 진입을 앞둔 가상 상황이에요. 공기 유입 설정은 어떻게 준비할까요?",
                    cue = CoachCue("growth_tunnel_air_intro", "터널 공기 유입을 줄이는 편의 기능을 알아봐요. 창문과 공조 설정은 내 차 설명서를 먼저 확인해요."),
                    choices = listOf(
                        GrowthChoice("tunnel_air_screen", "터널 안에서 메뉴를 오래 보며 찾기", false, CoachCue("growth_tunnel_air_retry", "운전 중 화면을 오래 보며 설정을 찾지 않도록 미리 익혀요. 지금은 가상 상황에서 기능을 알아보는 시간이에요."), "STOP"),
                        GrowthChoice("tunnel_air_prepare", "진입 전 창문·공조의 기능을 확인", true, CoachCue("growth_tunnel_air_ok", "좋아요. 창문 닫기나 내기순환, 자동 기능을 미리 알아두는 편의 준비예요. 법규 위반 점수는 아니에요."), "OBSERVE"),
                    ),
                ),
                GrowthStep(
                    id = "tunnel_vision", prompt = "내기순환 중 유리에 김이 서려 시야가 흐려지는 가상 상황이에요.",
                    cue = CoachCue("growth_tunnel_vision_intro", "편의 설정을 계속 유지하는 것보다 시야 확보가 먼저예요. 내 차의 김서림 제거 방법을 알아봐요."),
                    choices = listOf(
                        GrowthChoice("tunnel_vision_clear", "시야 확보와 김서림 제거 방법 확인", true, CoachCue("growth_tunnel_vision_ok", "맞아요. 내기순환을 무조건 계속 유지하지 않아요. 차종별 김서림 제거 방법을 미리 익혀요."), "OBSERVE"),
                        GrowthChoice("tunnel_vision_keep", "터널이 끝날 때까지 내기순환만 고정", false, CoachCue("growth_tunnel_vision_retry", "앞이 잘 보이는지가 더 중요해요. 내기순환을 항상 고정해야 한다고 외우지 않아요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "tunnel_attention", prompt = "터널 진입 뒤 앞차와의 거리가 가까워진 가상 장면이에요.",
                    cue = CoachCue("growth_tunnel_attention_intro", "공조 설정을 익혔더라도 운전 중에는 전방 확인이 우선이에요. 앞차와의 간격을 살펴요."),
                    choices = listOf(
                        GrowthChoice("tunnel_attention_menu", "거리를 그대로 두고 설정 화면 확인", false, CoachCue("growth_tunnel_attention_retry", "앞차와 가까워졌다면 화면보다 전방과 간격을 먼저 살펴요. 설정은 안전하게 할 수 있을 때 다뤄요."), "STOP"),
                        GrowthChoice("tunnel_attention_gap", "전방을 보며 충분한 차간거리 유지", true, CoachCue("growth_tunnel_attention_ok", "좋아요. 설정 화면보다 전방과 차간거리에 주의를 돌리는 판단이에요."), "WAIT"),
                    ),
                ),
            ),
        ),
        GrowthLesson(
            id = "courtesy", title = "고마움을 안전하게 표현하기", subtitle = "감사 표시보다 예측 가능한 운전", chapter = 3,
            competency = Competency.COURTESY, scene = "courtesy",
            sourceNote = "비상등 두 번은 확인된 법적 감사 의무가 아닙니다. 앱은 감사 상황을 추정해 실제 비상등을 자동 점등하지 않습니다. 비상등은 위험·긴급 상황 안내와 구분합니다.",
            steps = listOf(
                GrowthStep(
                    id = "courtesy_thanks", prompt = "다른 차가 충분한 공간을 내어주었어요. 가장 먼저 할 행동은?",
                    cue = CoachCue("growth_courtesy_thanks_intro", "양보를 받으면 고맙지만, 표시를 하느라 주의를 빼앗기지 않는 게 먼저예요."),
                    choices = listOf(
                        GrowthChoice("courtesy_thanks_steady", "방향지시·간격을 지키며 차분하게 합류", true, CoachCue("growth_courtesy_thanks_ok", "좋아요. 상대가 예측할 수 있는 신호와 충분한 간격이 먼저예요."), "SIGNAL"),
                        GrowthChoice("courtesy_thanks_auto", "앱이 감사 비상등을 자동으로 두 번 점등", false, CoachCue("growth_courtesy_thanks_retry", "감사 표시가 먼저는 아니에요. 비상등은 차종에 따라 방향지시에 영향을 줄 수 있어 합류 신호와 혼동하지 않아야 해요."), "STOP"),
                    ),
                ),
                GrowthStep(
                    id = "courtesy_mistake", prompt = "길을 잘못 들었어요. 뒤차에 미안해 급히 되돌아가고 싶어요.",
                    cue = CoachCue("growth_courtesy_mistake_intro", "누구나 길을 놓칠 수 있어요. 미안함 때문에 급한 조작을 더하지 않는 연습이에요."),
                    choices = listOf(
                        GrowthChoice("courtesy_mistake_sorry", "비상등을 켜면 급하게 끼어들어도 괜찮음", false, CoachCue("growth_courtesy_mistake_retry", "감사나 사과 표시가 무리한 조작을 허용하지는 않아요. 급한 끼어들기를 피하고 다음 경로를 찾아요."), "STOP"),
                        GrowthChoice("courtesy_mistake_reroute", "급조작을 피하고 가능한 경로로 재탐색", true, CoachCue("growth_courtesy_mistake_ok", "괜찮아요. 실수를 만회하려고 급히 끼어들거나 후진하지 않는 판단이 좋아요."), "PROCEED"),
                    ),
                ),
                GrowthStep(
                    id = "courtesy_hazard", prompt = "감사 관습과 고장·위험 상황의 신호는 어떻게 구분할까요?",
                    cue = CoachCue("growth_courtesy_hazard_intro", "표시를 외우기보다 왜 사용하는지 알아봐요. 위험 상황의 신호와 감사 관습은 같은 규칙이 아니에요."),
                    choices = listOf(
                        GrowthChoice("courtesy_hazard_purpose", "위험 신호와 감사 관습을 구분해 이해", true, CoachCue("growth_courtesy_hazard_ok", "잘 정리했어요. 상황을 정확히 살피고, 차종 설명서와 교통 안내에 맞는 신호를 사용해요."), "OBSERVE"),
                        GrowthChoice("courtesy_hazard_two", "모든 감사·사과 상황에 두 번 점등이 의무", false, CoachCue("growth_courtesy_hazard_retry", "두 번 점등이 모든 상황의 법적 의무는 아니에요. 주변에 혼동을 주지 않는 신호 사용을 먼저 생각해요."), "STOP"),
                    ),
                ),
            ),
        ),
    )

    val cues: List<CoachCue> = lessons.flatMap { lesson ->
        lesson.steps.flatMap { step -> listOf(step.cue) + step.choices.map { it.feedback } }
    }

    fun find(id: String): GrowthLesson? = lessons.firstOrNull { it.id == id }
}
