package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kr.drivestep.tutor.connected.ConnectedVehicleView
import kr.drivestep.tutor.connected.ReadinessSnapshot
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import kr.drivestep.tutor.speech.TutorSpeechController
import java.util.Locale
import java.util.UUID

/** Created in code with required routing/consent arguments; never inflated from XML. */
@SuppressLint("ViewConstructor")
class WorkflowView(context: Context, private val onExit: () -> Unit, private val onPrepare: () -> Unit,
    private val onParking: () -> Unit, private val onLaneChange: () -> Unit,
    private val canPractice: () -> Boolean, private val recordEnabled: () -> Boolean,
    vehicleEnabled: () -> Boolean, onVehicleSettings: () -> Unit, onReconnect: () -> Unit, onLocationPermission: () -> Unit) : LinearLayout(context) {
    private enum class Page { HOME, CONNECTED, WEATHER, MANUAL, COURSES, TASKS, SCENARIO, REVIEW, HISTORY }
    private val connected = ConnectedVehicleView(context,vehicleEnabled,onVehicleSettings,onReconnect,onLocationPermission)
    private val main = Handler(Looper.getMainLooper())
    private val settings = context.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
    private val store = WorkflowStore(context, recordEnabled)
    private val weather = WeatherSession(UUID.randomUUID().toString())
    private val arbiter = AdviceArbiter().apply { if (settings.getBoolean("muted", false)) mute() }
    private val courses = CourseSelection()
    private val manuals = ManualRepository()
    private var page = Page.HOME
    private var active = false
    private var closed = false
    private var voiceEnabled = true
    private var requestSerial = 0
    private var pendingAdvice: PendingAdvice? = null
    private var manual: ManualLearningSession? = null
    private var demoManual = false
    private var selectedFeature = ManualFeature.AC
    private var goal = RouteGoal.BEGINNER
    private var selectedTask = "D03"
    private var courseTasks = emptyList<String>()
    private var courseIndex = 0
    private var excludeRoundTrip = false
    private var startSelected = false
    private var scenario: ScenarioSession? = null
    private var lastTick = 0L
    private var lastCueRevision = -1
    private var review: ScenarioSnapshot? = null
    private var explanation: String? = null
    private var difficulty: String? = null
    private var saved = false
    private var statusMessage = ""
    private var voiceLabel: TextView? = null
    private var outputStatus = "각 화면의 듣기 버튼으로 설명을 들을 수 있어요. 이 기능은 마이크를 사용하지 않아요."
    private var stats: TextView? = null
    private var guidance: TextView? = null
    private var surface: ScenarioSurfaceView? = null
    private var resumeButton: Button? = null
    private var dialog: AlertDialog? = null
    private val speech = TutorSpeechController(context, onTranscript = {}, onStatus = { status ->
        outputStatus = if (status.contains("대화 시작")) "음성 안내를 껐어요. 화면에서 설명을 확인할 수 있어요." else status.replace("음성 입력과 안내", "음성 안내")
        voiceLabel?.text = outputStatus
        if (status.contains("음성 안내 중")) {
            pendingAdvice?.let { advice ->
                arbiter.acknowledge(advice.id)
                if (advice.id.startsWith("weather:")) weather.markSpoken()
            }
            pendingAdvice = null
            if (page == Page.WEATHER) main.post { if (active && !closed && page == Page.WEATHER) render() }
        } else if (status.contains("못했") || status.contains("우선") || status.contains("멈췄")) pendingAdvice = null
    })
    private val ticker = object : Runnable {
        override fun run() {
            val s = scenario ?: return
            if (!active || closed || page != Page.SCENARIO || s.snapshot().paused) return
            if (!canPractice()) { pause("차량 준비 상태 변경"); stopVoice(); return }
            val now = SystemClock.elapsedRealtime()
            s.tick(((now - lastTick) / 1000.0).coerceIn(0.0, .1)); lastTick = now
            val snapshot = s.snapshot()
            updateScene(snapshot)
            if (snapshot.warning) stopVoice()
            else if (snapshot.cueRevision != lastCueRevision) { lastCueRevision = snapshot.cueRevision; say(snapshot.instruction) }
            if (snapshot.status != EventStatus.ACTIVE) { openReview(snapshot); return }
            main.postDelayed(this, 33)
        }
    }
    init { orientation = VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)); setBackgroundColor(PleosUi.BACKGROUND); render() }
    val isGuidanceSpeaking: Boolean get() = speech.isSpeaking
    fun setSpeechEnabled(value: Boolean) { voiceEnabled = value; speech.setEnabled(value && !arbiter.muted) }
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        connected.setActive(value && page == Page.CONNECTED)
        speech.setActive(value)
        if (!value) { dialog?.dismiss(); dialog = null; pause("화면 이탈·플랫폼 제한"); stopVoice() }
        else render()
    }
    fun openHome() { pause("기능 선택"); page = Page.HOME; render() }
    fun goBack(): Boolean {
        if (page == Page.HOME) return false
        if (page == Page.SCENARIO) { scenario?.cancel(); scenario?.snapshot()?.let(::openReview) }
        else { stopVoice(); page = Page.HOME; render() }
        return true
    }
    fun close() { if (closed) return; pause("앱 종료"); closed = true; dialog?.dismiss(); connected.close(); main.removeCallbacksAndMessages(null); speech.close() }
    override fun onDetachedFromWindow() { pause("화면 분리"); stopVoice(); super.onDetachedFromWindow() }
    private fun stopVoice() { pendingAdvice = null; speech.stop() }
    private fun say(text: String, advice: PendingAdvice? = null) {
        val now = SystemClock.elapsedRealtime()
        val route = if (page == Page.SCENARIO) scenario?.snapshot()?.routeVersion ?: 0 else 0
        val candidate = advice ?: PendingAdvice("manual:${requestSerial++}", text, now, now + 10_000, route)
        val spoken = arbiter.select(candidate, now, route, active && voiceEnabled,
            scenario?.snapshot()?.warning == true && page == Page.SCENARIO, false) ?: return
        pendingAdvice = candidate
        speech.speak(spoken)
    }
    private fun pause(reason: String) {
        main.removeCallbacks(ticker)
        scenario?.takeIf { page == Page.SCENARIO && !it.snapshot().paused }?.let { val point = it.snapshot(); it.pause(reason); store.checkpoint(point) }
        scenario?.snapshot()?.let(::updateScene)
    }
    fun acceptVehicle(value: ReadinessSnapshot) = connected.acceptVehicle(value)
    fun locationPermissionResult() = connected.permissionResult()
    fun preparationChanged() { pause("시트·벨트 상태 변경"); stopVoice() }
    private fun render() {
        connected.setActive(active && page == Page.CONNECTED)
        removeAllViews(); stats = null; guidance = null; surface = null; resumeButton = null
        val header = row()
        header.addView(text("준비와 연습", 28, true), LayoutParams(0, dp(56), 1f))
        header.addView(button(if (arbiter.muted) "안내 재개" else "안내 음소거", "workflow_mute") {
            if (arbiter.muted) arbiter.resume() else { arbiter.mute(); stopVoice() }
            settings.edit().putBoolean("muted", arbiter.muted).apply()
            speech.setEnabled(voiceEnabled && !arbiter.muted); render()
        }, LayoutParams(dp(160), dp(56)))
        header.addView(button("닫기", "workflow_exit") { pause("사용자 종료"); stopVoice(); onExit() }, LayoutParams(dp(90), dp(56)))
        voiceLabel = text(outputStatus, 14).also { addView(it, full()) }
        if (page != Page.HOME) addView(button("기능 선택으로", "workflow_home") { pause("기능 선택"); stopVoice(); page = Page.HOME; render() }, full(52))
        when (page) {
            Page.HOME -> home()
            Page.CONNECTED -> addView(connected,LayoutParams(-1,0,1f))
            Page.WEATHER -> weatherPage()
            Page.MANUAL -> manualPage()
            Page.COURSES -> coursePage()
            Page.TASKS -> taskPage()
            Page.SCENARIO -> scenarioPage()
            Page.REVIEW -> reviewPage()
            Page.HISTORY -> historyPage()
        }
        if (statusMessage.isNotBlank() && page == Page.REVIEW) addView(text(statusMessage, 16), full())
    }
    private fun home() {
        addView(text("날씨 확인 → 차량 기능 학습 → 가상 코스 → 조작 연습 → 복습", 22, true), full())
        addView(text("실제 차량은 P·정차 상태로 두세요. 시트·벨트·경고·위치·내비는 별도 연결 화면에서 확인해요. 주변 인식은 향후 계획이에요."), full())
        addView(button("차량 상태 · 위치와 내비", "workflow_connected") { page = Page.CONNECTED; render() }, full(72))
        addView(button("날씨와 출발 준비", "workflow_weather") { page = Page.WEATHER; render() }, full(72))
        addView(button("차량 기능 배우기", "workflow_manual") { page = Page.MANUAL; render() }, full(72))
        addView(button("목표별 가상 코스 추천", "workflow_courses") { loadCourses(); page = Page.COURSES; render() }, full(72))
        addView(button("상황별 가상 주행 · 18개 과제", "workflow_tasks") { page = Page.TASKS; render() }, full(72))
        addView(button("후진·T자·평행·전면 주차", "workflow_parking") { ready(onParking) }, full(72))
        addView(button("기존 도로 시점 차로 변경", "workflow_lane_change") { ready(onLaneChange) }, full(72))
        addView(button("관측 기록·자기평가·다음 연습", "workflow_history") { page = Page.HISTORY; render() }, full(72))
        val inMemory = scenario?.snapshot()?.takeIf { it.status == EventStatus.ACTIVE && it.paused }
        (inMemory?.task ?: store.pendingTask())?.let { task ->
            if (inMemory != null) addView(text("이번 실행에서 일시 정지한 과제예요. 기록 저장 동의 없이도 준비 확인 후 이어갈 수 있어요."), full())
            else store.pendingDescription()?.let { addView(text(it), full()) }
            addView(button("중단한 ${task.title} 다시 준비", "workflow_recover") {
                if (inMemory != null) ready { page = Page.SCENARIO; render() } else startTask(task.id)
            }, full(64))
        }
        addView(text("실주행 백그라운드 코칭: 사용 불가 · 플랫폼 허용과 실제 데이터 검증이 필요해요. 화면 복귀 시 조작·음성을 자동 재개하지 않아요."), full())
    }
    private fun weatherPage() {
        addView(text("날씨 · 앱 세션 시작 기준", 26, true), full())
        val input = weather.result
        addView(text(if (input?.value == null) "현재 날씨 미확인 · 날씨 제공자는 아직 연결되지 않았어요. 위치 연결은 별도 화면에서 선택할 수 있어요. 준비는 계속할 수 있어요."
            else "가상 날씨 예시: ${input.value!!.condition.name} · ${input.value!!.region}\n출처 SIMULATION · 실제 관측이 아니에요."), full())
        addView(button("현재 날씨 다시 확인", "workflow_weather_retry") {
            stopVoice(); weather.accept(UnavailableWeatherProvider().read("미선택", weather.contextId, SystemClock.elapsedRealtime()), SystemClock.elapsedRealtime()); render()
        }, full(56))
        addView(text("가상 상황을 직접 선택해 안내 흐름을 확인할 수 있어요. 실제 날씨로 저장·표시하지 않아요."), full())
        WeatherCondition.entries.forEach { condition ->
            addView(button(when (condition) { WeatherCondition.CLEAR -> "특별한 조언 없음"; WeatherCondition.RAIN -> "비 예시"; WeatherCondition.SNOW -> "눈 예시"; WeatherCondition.FOG -> "안개 예시"; WeatherCondition.COLD -> "저온 예시" }, "workflow_weather_${condition.name.lowercase()}") {
                stopVoice(); val now = SystemClock.elapsedRealtime(); weather.accept(SimulatedWeatherProvider(condition).read("가상 연습장", weather.contextId, now), now); render()
            }, full(56))
        }
        val advice = weather.advice(SystemClock.elapsedRealtime())
        addView(text(advice?.text ?: "같은 세션의 같은 조언은 반복하지 않아요. 실제 유리·노면 상태는 직접 확인해 주세요."), full())
        addView(button("조언 듣기", "workflow_weather_speak", advice != null) { advice?.let { say(it.text, it) } }, full(60))
        addView(button("관련 시야 확보 기능 배우기", "workflow_weather_manual") { selectedFeature = ManualFeature.DEFOG; manual = null; page = Page.MANUAL; render() }, full(60))
    }
    private fun manualPage() {
        addView(text("차량 기능 · 설명서 근거 확인", 26, true), full())
        addView(text(if (demoManual) "교육용 가상 차량 · SIM-MANUAL-1 · 실제 차량 조작에 적용하지 마세요."
            else "IONIQ 3 · 연식·트림·제조사 설명서 미확인. 실제 버튼 위치와 작동 순서는 제공하지 않아요."), full())
        val selectors = row()
        selectors.addView(button("내 차량 설명서 확인", "workflow_manual_real") { stopVoice(); demoManual = false; manual = null; render() }, weight())
        selectors.addView(button("교육용 가상 설명서", "workflow_manual_demo") { stopVoice(); demoManual = true; manual = null; render() }, weight())
        val features = row()
        ManualFeature.entries.forEach { feature -> features.addView(button(feature.title, "workflow_manual_${feature.name.lowercase()}") {
            stopVoice(); selectedFeature = feature; manual = null; render()
        }, weight()) }
        val lesson = manuals.find(if (demoManual) ManualRepository.DEMO_VEHICLE else ManualVehicle("IONIQ 3", null, null), selectedFeature)
        if (lesson == null) { addView(text("설명서 확인 필요 · 해당 차종·트림·기능의 근거가 없어요. 없는 기능이나 조작 순서를 만들지 않아요.", 22, true), full()); return }
        if (manual?.lesson != lesson) manual = ManualLearningSession(lesson)
        val current = manual!!
        addView(text("${lesson.revision} / ${lesson.section} · ${current.index + 1}/${lesson.steps.size}", 18, true), full())
        addView(text(current.current, 26), full(170))
        if (current.index == 2) addView(button(if (current.simulatedOperation) "가상 상태: 켜짐 · 모의 작동 확인" else "가상 패널에서 기능 켜기", "workflow_manual_operate") { current.operate(); render() }, full(64))
        val actions = row()
        actions.addView(button("이전", "workflow_manual_previous", current.index > 0) { current.previous(); stopVoice(); render() }, weight())
        actions.addView(button("다시 듣기", "workflow_manual_repeat") { say(current.current) }, weight())
        actions.addView(button("다음", "workflow_manual_next", current.index < lesson.steps.lastIndex) { current.next(); render(); say(current.current) }, weight())
        addView(button(if (current.learned) "가상 학습 확인 완료" else "학습 내용을 직접 확인했어요", "workflow_manual_confirm", current.index == lesson.steps.lastIndex && current.simulatedOperation) {
            current.confirm(); store.learned(lesson); render()
        }, full(64))
        addView(text("이 완료 표시는 교육 내용 확인이며 실제 에어컨·열선 작동을 인증하지 않아요."), full())
    }
    private fun loadCourses() { courses.load(RouteRequest(goal, selectedTask, if (startSelected) "가상 연습장" else null, if (excludeRoundTrip) setOf("왕복") else emptySet()), SimulatedRouteProvider(), SystemClock.elapsedRealtime()) }
    private fun coursePage() {
        val confirmed = courses.confirmedRoute(SystemClock.elapsedRealtime())
        addView(text("연습 목표와 가상 코스", 26, true), full())
        val goals = row()
        listOf(RouteGoal.BEGINNER to "초보자 추천", RouteGoal.WEAKNESS to "선택 과제 보완", RouteGoal.RETURN_TRIP to "왕복 연습").forEach { (value, title) ->
            goals.addView(button(title, "workflow_goal_${value.name.lowercase()}") { goal = value; loadCourses(); render() }, weight())
        }
        addView(button(if (startSelected) "출발지: 가상 연습장 선택됨" else "가상 연습장을 출발지로 선택", "workflow_start_location") { startSelected = true; loadCourses(); render() }, full(56))
        addView(button("코스 다시 조회", "workflow_course_refresh", startSelected) { loadCourses(); render() }, full(56))
        addView(text("${if (startSelected) "출발지: 직접 선택한 가상 연습장" else "출발지 미선택"} · GPS 위치 미사용\n목표: ${ScenarioCatalog.find(selectedTask)?.title} · 실제 지도 특성·경사 미확인"), full())
        addView(button(if (excludeRoundTrip) "왕복 코스 제외 중 · 다시 포함" else "왕복 코스 제외", "workflow_course_exclusion") { excludeRoundTrip = !excludeRoundTrip; loadCourses(); render() }, full(52))
        addView(text(courses.message), full())
        courses.candidates.forEach { route ->
            addView(text(String.format(Locale.KOREA, "%s · %.1fkm / %d분\n가상 설정값 · 과제 %s", route.name, route.distanceKm, route.minutes, route.tasks.joinToString()), 20, true), full())
            addView(button("이 가상 코스 선택", "workflow_route_${route.id}") {
                if (courses.choose(route.id, SimulationNavigator(), SystemClock.elapsedRealtime())) { selectedTask = route.tasks.first(); statusMessage = courses.message }
                render()
            }, full(56))
        }
        if (confirmed != null) addView(button("접수된 가상 코스 시작", "workflow_course_start") {
            val current = courses.confirmedRoute(SystemClock.elapsedRealtime())
            if (current == null) { render(); return@button }
            courseTasks = current.tasks; courseIndex = 0; startTask(courseTasks.first(), true)
        }, full(64))
        addView(text("실제 위치·내비 목적지 요청은 ‘차량 상태 · 위치와 내비’에서 별도로 이용해요. 이 화면의 코스와 접수는 가상 연습 전용이에요."), full())
    }
    private fun taskPage() {
        addView(text("상황별 조작 연습", 26, true), full())
        addView(text("가속·제동·조향·방향지시를 화면에서 직접 조작해요. 모든 도로 상황과 수치는 작성된 가상 과제예요."), full())
        ScenarioCatalog.tasks.forEach { task -> addView(button("${task.id} · ${task.title}", "workflow_task_${task.id}") { selectedTask = task.id; startTask(task.id) }, full(64)) }
    }
    private fun ready(action: () -> Unit) {
        if (!active) return
        if (!canPractice()) { stopVoice(); onPrepare(); return }
        stopVoice(); action()
    }
    private fun startTask(id: String, keepCourse: Boolean = false) = ready {
        if (!keepCourse) { courseTasks = emptyList(); courseIndex = 0 }
        val task = ScenarioCatalog.find(id) ?: return@ready
        scenario = ScenarioSession(task, UUID.randomUUID().toString()); review = null; saved = false; explanation = null; difficulty = null
        page = Page.SCENARIO; store.clearCheckpoint(); lastCueRevision = -1; render()
    }
    private fun scenarioPage() {
        val session = scenario ?: return
        val snapshot = session.snapshot()
        addView(text("${snapshot.task.id} · ${snapshot.task.title} · 가상 연습", 26, true), full())
        surface = ScenarioSurfaceView(context).also { addView(it, full(220)); it.update(snapshot) }
        stats = text("", 20, true).also { addView(it, full(44)) }
        guidance = text(snapshot.instruction, 20).also { addView(it, full(80)) }
        val signals = row()
        TrainingSignal.entries.forEach { value -> signals.addView(button(when (value) { TrainingSignal.OFF -> "방향지시 끄기"; TrainingSignal.LEFT -> "왼쪽 방향지시"; TrainingSignal.RIGHT -> "오른쪽 방향지시" }, "workflow_signal_${value.name.lowercase()}") { session.signal(value); updateScene(session.snapshot()) }, weight()) }
        signals.addView(button("미러·사각지대 확인 학습", "workflow_mirror") { session.checkMirror(); updateScene(session.snapshot()) }, weight())
        val steering = SeekBar(context).apply {
            max = 200; progress = 100; contentDescription = "workflow_steering"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser && active) session.steer((progress - 100) / 100.0) }
            })
        }
        addView(text("가상 핸들 · 왼쪽 ← 중앙 → 오른쪽", 16), full())
        addView(steering, full(54))
        val controls = row()
        controls.addView(pedal("가속 · 누르고 유지", "workflow_gas", true), weight(64))
        controls.addView(pedal("브레이크 · 누르고 유지", "workflow_brake", false), weight(64))
        controls.addView(button("핸들 중앙", "workflow_center") { session.steer(0.0); steering.progress = 100 }, weight(64))
        val actions = row()
        resumeButton = button("시작·이어서 연습", "workflow_resume") {
            if (!canPractice()) { onPrepare(); return@button }
            session.resume(); lastCueRevision = session.snapshot().cueRevision; speech.setActive(active); lastTick = SystemClock.elapsedRealtime(); main.removeCallbacks(ticker); main.post(ticker); say(session.snapshot().instruction)
        }.also { actions.addView(it, weight()) }
        actions.addView(button("일시 정지", "workflow_pause") { pause("사용자 일시 정지"); stopVoice() }, weight())
        actions.addView(button("가상 정차·대기", "workflow_wait") { session.waitForGap(); updateScene(session.snapshot()) }, weight())
        actions.addView(button("시도 취소", "workflow_cancel") { session.cancel(); openReview(session.snapshot()) }, weight())
        if (snapshot.task.id == "D15") addView(button("길 놓침·가상 재탐색", "workflow_reroute") { stopVoice(); session.reroute(); updateScene(session.snapshot()); say(session.snapshot().instruction) }, full(56))
        addView(text(snapshot.task.unknown + "\n후·측방 거리 센서: 미지원 · 값 0이나 안전 상태로 대체하지 않아요.", 14), full())
        updateScene(snapshot)
    }
    private fun pedal(label: String, key: String, accelerator: Boolean): PracticeButton = PracticeButton(context).apply {
        text = label; contentDescription = key; textSize = 18f; PleosUi.decorate(this, accelerator)
        setOnTouchListener { view, event ->
            if (active && page == Page.SCENARIO) when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.parent?.requestDisallowInterceptTouchEvent(true); scenario?.pedals(if (accelerator) 1.0 else 0.0, if (accelerator) 0.0 else 1.0) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.parent?.requestDisallowInterceptTouchEvent(false); scenario?.pedals(0.0, 0.0); if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick() }
            }
            true
        }
    }
    private fun updateScene(snapshot: ScenarioSnapshot) {
        stats?.text = String.format(Locale.KOREA, "가상 %.1fkm/h · %.1fm · 대기 %.1f초 · 경로 v%d%s", snapshot.speedKmh, snapshot.distance,
            snapshot.waiting, snapshot.routeVersion, snapshot.leadGapM?.let { String.format(Locale.KOREA, " · 가상 앞차 %.1fm", it) } ?: "")
        guidance?.text = snapshot.instruction
        surface?.update(snapshot)
        resumeButton?.isEnabled = active && snapshot.paused && snapshot.status == EventStatus.ACTIVE
    }
    private fun openReview(snapshot: ScenarioSnapshot) {
        main.removeCallbacks(ticker); stopVoice(); store.clearCheckpoint(); review = snapshot
        page = Page.REVIEW; render()
    }
    private fun reviewPage() {
        val snapshot = review ?: return
        addView(text("복습 · ${snapshot.task.title}", 26, true), full())
        addView(text("확인한 사실 · SIMULATION\n${snapshot.instruction}\n" + String.format(Locale.KOREA, "가상 이동 %.1fm · 경로 v%d · 상태 %s", snapshot.distance, snapshot.routeVersion, snapshot.status.name), 20), full())
        addView(text("미확인 항목\n${snapshot.task.unknown}"), full())
        addView(text("운전자 설명 · 선택하지 않으면 원인 미확인으로 남겨요. 감점이나 숙련도 점수는 만들지 않아요."), full())
        listOf("옆 차 때문에 기다렸어요", "조작 순서가 어려웠어요", "현장 확인이 어려웠어요").forEachIndexed { i, value -> addView(button(value, "workflow_reflect_$i") { explanation = value; render() }, full(52)) }
        addView(button("회고 건너뛰기", "workflow_reflect_skip") { explanation = null; render() }, full(52))
        addView(text("내 설명: ${explanation ?: "미응답 · 원인 미확인"}"), full())
        val courseNext = courseTasks.getOrNull(courseIndex + 1)
        if (courseTasks.isNotEmpty()) addView(text("가상 코스 ${courseIndex + 1}/${courseTasks.size} · ${if (courseNext == null && snapshot.status == EventStatus.COMPLETED) "코스 마침" else "각 과제를 직접 마친 뒤 다음으로 이동해요."}"), full())
        val nextId = if (snapshot.status != EventStatus.COMPLETED && courseTasks.isNotEmpty()) snapshot.task.id else courseNext ?: snapshot.task.nextTask
        addView(button("다음 연습: ${ScenarioCatalog.find(nextId)?.title}", "workflow_next_task") {
            if (courseTasks.isNotEmpty() && snapshot.status == EventStatus.COMPLETED && courseNext != null) { courseIndex++; startTask(nextId, true) }
            else startTask(nextId, courseTasks.isNotEmpty() && snapshot.status != EventStatus.COMPLETED)
        }, full(64))
        addView(button(if (saved) "이 기기에 저장했어요" else if (recordEnabled()) "이번 가상 연습 저장" else "기록 동의 꺼짐 · 저장 없이 이용", "workflow_save", !saved && recordEnabled()) {
            saved = store.save(snapshot, explanation, difficulty); statusMessage = if (saved) "가상 관측과 자기평가를 분리해 저장했어요." else "저장하지 못했어요."; render()
        }, full(60))
        addView(button("가상 코스로 연결", "workflow_review_course") { selectedTask = snapshot.task.nextTask; goal = RouteGoal.WEAKNESS; loadCourses(); page = Page.COURSES; render() }, full(60))
    }
    private fun historyPage() {
        addView(text("이 기기의 가상 연습 기록", 26, true), full())
        val records = store.records()
        if (records.isEmpty()) addView(text("저장한 가상 과제가 없어요. 기록에 동의하지 않아도 연습할 수 있어요."), full())
        records.forEachIndexed { i, record ->
            val task = ScenarioCatalog.find(record.optString("task")) ?: return@forEachIndexed
            addView(text("${task.title} · ${record.optString("status")}\n관측: ${record.optString("facts")}\n미확인: ${record.optString("unknown")}\n내 설명: ${if (record.isNull("explanation")) "미응답 · 원인 미확인" else record.optString("explanation")}"), full())
            addView(button("추천 과제 다시 연습", "workflow_history_$i") { startTask(record.optString("next", task.nextTask)) }, full(56))
        }
        addView(button("이 과제의 기록·중단 지점 삭제", "workflow_delete") {
            dialog?.dismiss()
            dialog = AlertDialog.Builder(context).setTitle("가상 과제 기록을 삭제할까요?")
                .setMessage("이 기능의 가상 과제 기록·중단 지점·가상 설명서 학습 기록만 삭제해요. 기존 프로필·주행·주차·음성 연수 기록은 유지해요.")
                .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> if (active) { store.delete(); render() } }.show()
        }, full(60))
    }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false; this@WorkflowView.addView(this, full()) }
    private fun text(value: String, size: Int = 18, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(PleosUi.TEXT); gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        setPadding(dp(6), dp(8), dp(6), dp(8)); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, key: String, enabled: Boolean = true, action: () -> Unit) = PracticeButton(context).apply {
        text = value; textSize = 18f; isAllCaps = false; minHeight = dp(52); minimumHeight = dp(52); minWidth = 0; minimumWidth = 0
        gravity = Gravity.CENTER; contentDescription = key; isEnabled = enabled && active
        PleosUi.decorate(this, false); setOnClickListener { if (active && !closed) action() }
    }
    private fun full(height: Int = -2) = LayoutParams(-1, if (height < 0) height else dp(height)).apply { bottomMargin = dp(8) }
    private fun weight(height: Int = 56) = LayoutParams(0, dp(height), 1f).apply { marginEnd = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
