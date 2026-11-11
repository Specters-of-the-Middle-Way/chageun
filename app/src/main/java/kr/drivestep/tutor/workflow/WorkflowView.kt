package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import kr.drivestep.tutor.journey.PreparationStyle
import kr.drivestep.tutor.journey.PreparationColumns
import kr.drivestep.tutor.R
import kr.drivestep.tutor.connected.ConnectedVehicleView
import kr.drivestep.tutor.connected.ReadinessSnapshot
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import kr.drivestep.tutor.speech.TutorSpeechController
import kr.drivestep.tutor.speech.SpeechStatusText
import kr.drivestep.tutor.vehicle.TrainingVehicle
import java.util.Locale
import java.util.UUID

/** Created in code with required routing/consent arguments; never inflated from XML. */
@SuppressLint("ViewConstructor")
class WorkflowView(context: Context, private val onExit: () -> Unit, private val onPrepare: () -> Unit,
    private val onParking: () -> Unit, private val onLaneChange: () -> Unit,
    private val canPractice: () -> Boolean, private val recordEnabled: () -> Boolean,
    vehicleEnabled: () -> Boolean, onVehicleSettings: () -> Unit, onReconnect: () -> Unit, onLocationPermission: () -> Unit) : LinearLayout(context) {
    private enum class Page { HOME, CONNECTED, WEATHER, WEATHER_PRACTICE, MANUAL, GUIDES, COURSES, TASKS, SCENARIO, REVIEW, HISTORY }
    private val connected = ConnectedVehicleView(context,vehicleEnabled,onVehicleSettings,onReconnect,onLocationPermission)
    private val liveWeather = LiveWeatherView(context, onLocationPermission, { say(it) }, { stopVoice() }, ::sayWeatherAutomatically)
    private val main = Handler(Looper.getMainLooper())
    private val settings = context.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
    private val store = WorkflowStore(context, recordEnabled)
    private val weather = WeatherSession(UUID.randomUUID().toString())
    private val arbiter = AdviceArbiter().apply { if (settings.getBoolean("muted", false)) mute() }
    private val courses = CourseSelection()
    private val manuals = ManualRepository()
    private var page = Page.HOME
    private var renderedPage: Page? = null
    private var practiceScrollY = 0
    private var active = false
    private var closed = false
    private var voiceEnabled = true
    private var requestSerial = 0
    private var pendingAdvice: PendingAdvice? = null
    private var manual: ManualLearningSession? = null
    private var demoManual = false
    private var manualReference: ManualVehicle? = null
    private var guide = DrivingGuideSession(DrivingGuideCatalog.guides.first())
    private val gapStudy = GapStudyState()
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
    private var pauseButton: Button? = null
    private var mirrorButton: Button? = null
    private var scenarioSteering: SeekBar? = null
    private val scenarioInputs = mutableListOf<View>()
    private val signalButtons = mutableMapOf<TrainingSignal, Button>()
    private var dialog: AlertDialog? = null
    private val speech = TutorSpeechController(context, onTranscript = {}, onStatus = { status ->
        outputStatus = SpeechStatusText.forDisplay(if (status.contains("대화 시작")) "음성 안내를 껐어요. 화면에서 설명을 확인할 수 있어요." else status.replace("음성 입력과 안내", "음성 안내"))
        voiceLabel?.let { it.text = outputStatus; it.visibility = if (outputStatus.isBlank()) GONE else VISIBLE }
        if (status.contains("음성 안내 중")) {
            pendingAdvice?.let { advice ->
                arbiter.acknowledge(advice.id)
                if (advice.id.startsWith("weather:")) weather.markSpoken()
            }
            pendingAdvice = null
            if (page in setOf(Page.WEATHER, Page.WEATHER_PRACTICE)) main.post {
                if (active && !closed && page in setOf(Page.WEATHER, Page.WEATHER_PRACTICE)) render()
            }
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
    val showingWeather: Boolean get() = page == Page.WEATHER && active && !closed
    fun setSpeechEnabled(value: Boolean) { voiceEnabled = value; speech.setEnabled(value && !arbiter.muted) }
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        connected.setActive(value && page == Page.CONNECTED)
        liveWeather.setActive(value && page == Page.WEATHER)
        speech.setActive(value)
        if (!value) { dialog?.dismiss(); dialog = null; pause("화면 이탈·플랫폼 제한"); stopVoice() }
        else render()
    }
    fun openHome() { pause("기능 선택"); page = Page.HOME; render() }
    fun openWeather(reading: LiveWeatherReading? = null) {
        pause("날씨 확인"); page = Page.WEATHER
        reading?.let(liveWeather::showReading)
        render()
    }
    fun goBack(): Boolean {
        if (page == Page.HOME) return false
        if (page == Page.SCENARIO) { scenario?.cancel(); scenario?.snapshot()?.let(::openReview) }
        else { stopVoice(); page = Page.HOME; render() }
        return true
    }
    fun close() { if (closed) return; pause("앱 종료"); closed = true; dialog?.dismiss(); connected.close(); liveWeather.close(); main.removeCallbacksAndMessages(null); speech.close() }
    override fun onDetachedFromWindow() { pause("화면 분리"); stopVoice(); liveWeather.setActive(false); super.onDetachedFromWindow() }
    private fun stopVoice() { pendingAdvice = null; speech.stop() }
    private fun sayWeatherAutomatically(text: String): Boolean {
        if (page != Page.WEATHER || speech.isSpeaking) return false
        return say(text)
    }
    private fun say(text: String, advice: PendingAdvice? = null): Boolean {
        val now = SystemClock.elapsedRealtime()
        val route = if (page == Page.SCENARIO) scenario?.snapshot()?.routeVersion ?: 0 else 0
        val candidate = advice ?: PendingAdvice("manual:${requestSerial++}", text, now, now + 10_000, route)
        val spoken = arbiter.select(candidate, now, route, active && voiceEnabled,
            scenario?.snapshot()?.warning == true && page == Page.SCENARIO, false) ?: return false
        pendingAdvice = candidate
        speech.speak(spoken)
        return true
    }
    private fun pause(reason: String) {
        main.removeCallbacks(ticker)
        scenario?.takeIf { page == Page.SCENARIO && !it.snapshot().paused }?.let { val point = it.snapshot(); it.pause(reason); store.checkpoint(point) }
        scenario?.snapshot()?.let(::updateScene)
    }
    fun acceptVehicle(value: ReadinessSnapshot) = connected.acceptVehicle(value)
    fun locationPermissionResult() { connected.permissionResult(); liveWeather.permissionResult() }
    fun preparationChanged() { pause("시트·벨트 상태 변경"); stopVoice() }
    private fun render() {
        val scrollY = if (renderedPage == page) (0 until childCount).map { getChildAt(it) }.filterIsInstance<ScrollView>().firstOrNull()?.scrollY ?: 0 else 0
        practiceScrollY = scrollY
        renderedPage = page
        connected.setActive(active && page == Page.CONNECTED)
        liveWeather.setActive(active && page == Page.WEATHER)
        removeAllViews(); stats = null; guidance = null; surface = null; resumeButton = null
        pauseButton = null; mirrorButton = null; scenarioSteering = null; scenarioInputs.clear(); signalButtons.clear()
        if (page in setOf(Page.COURSES, Page.TASKS, Page.SCENARIO, Page.REVIEW)) practiceHeader()
        else {
        val header = row()
        header.addView(text("준비와 연습", 28, true), LayoutParams(0, dp(56), 1f))
        header.addView(button(if (arbiter.muted) "안내 재개" else "안내 음소거", "workflow_mute") {
            if (arbiter.muted) arbiter.resume() else { arbiter.mute(); stopVoice() }
            settings.edit().putBoolean("muted", arbiter.muted).apply()
            speech.setEnabled(voiceEnabled && !arbiter.muted); render()
        }, LayoutParams(dp(160), dp(56)))
        header.addView(button("닫기", "workflow_exit") { pause("사용자 종료"); stopVoice(); onExit() }, LayoutParams(dp(90), dp(56)))
        voiceLabel = if (page == Page.HOME) null else text(outputStatus, 14).also {
            it.contentDescription = "workflow_speech_status"; it.visibility = if (outputStatus.isBlank()) GONE else VISIBLE
            addView(it, full())
        }
        if (page != Page.HOME) addView(button("기능 선택으로", "workflow_home") { pause("기능 선택"); stopVoice(); page = Page.HOME; render() }, full(52))
        }
        val contentStart = childCount
        when (page) {
            Page.HOME -> home()
            Page.CONNECTED -> addView(connected,LayoutParams(-1,0,1f))
            Page.WEATHER -> weatherPage()
            Page.WEATHER_PRACTICE -> weatherPracticePage()
            Page.MANUAL -> manualPage()
            Page.GUIDES -> guidePage()
            Page.COURSES -> coursePage()
            Page.TASKS -> taskPage()
            Page.SCENARIO -> scenarioPage()
            Page.REVIEW -> reviewPage()
            Page.HISTORY -> historyPage()
        }
        if (page in setOf(Page.HOME, Page.WEATHER, Page.WEATHER_PRACTICE, Page.MANUAL, Page.GUIDES, Page.HISTORY)) {
            val body = LinearLayout(context).apply { orientation = VERTICAL }
            while (childCount > contentStart) {
                val child = getChildAt(contentStart); removeViewAt(contentStart); body.addView(child)
            }
            addView(ScrollView(context).apply { isFillViewport = true; addView(body); post { scrollTo(0, scrollY) } }, LayoutParams(-1, 0, 1f))
        }
    }
    private fun home() {
        val inMemory = scenario?.snapshot()?.takeIf { it.status == EventStatus.ACTIVE && it.paused }
        val pending = inMemory?.task ?: store.pendingTask()
        addView(WorkflowHomeView(context, canPractice(), pending?.title,
            if (inMemory != null) "준비 상태를 확인하고 이어가요." else "준비 상태를 확인하고 처음부터 다시 연습해요.",
            active && !closed) { destination ->
            if (active && !closed) when (destination) {
                WorkflowHomeAction.PREPARE -> { stopVoice(); onPrepare() }
                WorkflowHomeAction.WEATHER -> { page = Page.WEATHER; render() }
                WorkflowHomeAction.VEHICLE -> { page = Page.CONNECTED; render() }
                WorkflowHomeAction.MANUAL -> { page = Page.MANUAL; render() }
                WorkflowHomeAction.GUIDES -> { page = Page.GUIDES; render() }
                WorkflowHomeAction.COURSES -> { loadCourses(); page = Page.COURSES; render() }
                WorkflowHomeAction.DRIVE -> { page = Page.TASKS; render() }
                WorkflowHomeAction.PARKING -> ready(onParking)
                WorkflowHomeAction.LANE_CHANGE -> ready(onLaneChange)
                WorkflowHomeAction.HISTORY -> { page = Page.HISTORY; render() }
                WorkflowHomeAction.RECOVER -> pending?.let { task ->
                    if (inMemory != null) ready { page = Page.SCENARIO; render() } else startTask(task.id)
                }
            }
        }, full())
    }
    private fun weatherPage() {
        (liveWeather.parent as? ViewGroup)?.removeView(liveWeather)
        addView(liveWeather, full())
        addView(button("날씨별 대응 교육 보기", "workflow_weather_practice") {
            stopVoice(); page = Page.WEATHER_PRACTICE; render()
        }, full(64))
    }
    private fun weatherPracticePage() {
        addView(text("날씨별 대응 교육 · 가상 상황", 26, true), full())
        val input = weather.result
        addView(text(if (input?.value == null) "가상 상황 미선택 · 현재 날씨 조회와 별도의 교육이에요."
            else "가상 날씨 예시: ${weatherLabel(input.value!!.condition)}\n대응 방법을 익히는 연습이에요. 현재 날씨와는 달라요."), full())
        addView(button("가상 상황 선택 초기화", "workflow_weather_retry") {
            stopVoice(); weather.accept(UnavailableWeatherProvider().read("미선택", weather.contextId, SystemClock.elapsedRealtime()), SystemClock.elapsedRealtime()); render()
        }, full(56))
        addView(text("날씨를 골라 상황별 대응 방법을 익혀 보세요."), full())
        WeatherCondition.entries.forEach { condition ->
            addView(button(when (condition) { WeatherCondition.CLEAR -> "특별한 조언 없음"; WeatherCondition.RAIN -> "비 예시"; WeatherCondition.SNOW -> "눈 예시"; WeatherCondition.FOG -> "안개 예시"; WeatherCondition.COLD -> "저온 예시" }, "workflow_weather_${condition.name.lowercase()}") {
                stopVoice(); val now = SystemClock.elapsedRealtime(); weather.accept(SimulatedWeatherProvider(condition).read("가상 연습장", weather.contextId, now), now); render()
            }, full(56))
        }
        val advice = weather.advice(SystemClock.elapsedRealtime())
        addView(text(advice?.text ?: "같은 세션의 같은 조언은 반복하지 않아요. 실제 유리·노면 상태는 직접 확인해 주세요."), full())
        addView(button("조언 듣기", "workflow_weather_speak", advice != null) { advice?.let { say(it.text, it) } }, full(60))
        addView(button("관련 시야 확보 기능 배우기", "workflow_weather_manual") { selectedFeature = ManualFeature.DEFOG; manual = null; page = Page.MANUAL; render() }, full(60))
        addView(button("성에와 도로 살얼음 교육", "workflow_weather_winter") { guide = DrivingGuideSession(DrivingGuideCatalog.guides.first { it.id == "winter" }); page = Page.GUIDES; stopVoice(); render() }, full(60))
    }
    private fun manualPage() {
        addView(text("차량 기능 · 설명서 근거 확인", 26, true), full())
        addView(text(if (demoManual) "교육용 가상 차량 · SIM-MANUAL-1 · 실제 차량 조작에 적용하지 마세요."
            else manualReference?.let { "${it.model} · ${it.year}년형 · ${it.trim} · 선택한 설명서 기준" }
                ?: "${TrainingVehicle.NAME} · ${TrainingVehicle.VARIANT} · 연식·공조 사양을 직접 확인하고 아래 설명서를 선택해 주세요."), full())
        val selectors = row()
        selectors.addView(button("내 차량 설명서 확인", "workflow_manual_real") { stopVoice(); demoManual = false; manualReference = null; manual = null; render() }, weight())
        selectors.addView(button("교육용 가상 설명서", "workflow_manual_demo") { stopVoice(); demoManual = true; manual = null; render() }, weight())
        if (!demoManual) {
            addView(text("2024 CN7 가솔린 설명서로 학습하기 · 내 차의 연식과 공조 패널이 맞는지 확인해 주세요. 열선은 장착 차량만 해당해요."), full())
            val references = row()
            references.addView(button("2024 · 수동 공조", "workflow_manual_cn7_manual") { selectManual(Cn7Manual.MANUAL_CLIMATE) }, weight())
            references.addView(button("2024 · 자동 공조", "workflow_manual_cn7_auto") { selectManual(Cn7Manual.AUTO_CLIMATE) }, weight())
        }
        ManualFeature.entries.chunked(4).forEach { group ->
            val features = row()
            group.forEach { feature -> features.addView(button(feature.title, "workflow_manual_${feature.name.lowercase()}") {
                stopVoice(); selectedFeature = feature; manual = null; render()
            }, weight()) }
        }
        val lesson = manuals.find(if (demoManual) ManualRepository.DEMO_VEHICLE else manualReference ?: ManualVehicle(TrainingVehicle.NAME, null, null), selectedFeature)
        if (lesson == null) { addView(text("설명서 확인 필요 · 해당 차종·트림·기능의 근거가 없어요. 없는 기능이나 조작 순서를 만들지 않아요.", 22, true), full()); return }
        if (manual?.lesson != lesson) manual = ManualLearningSession(lesson)
        val current = manual!!
        addView(text("${lesson.revision} / ${lesson.section} · ${current.index + 1}/${lesson.steps.size}", 18, true), full())
        lesson.sourceUrl?.let { url -> addView(button("현대 공식 설명서 원문", "workflow_manual_source") { openSource(url) }, full(52)) }
        addView(text(current.current, 26), full(170))
        if (current.index == 2 && lesson.source == InputSource.SIMULATION) addView(button(if (current.simulatedOperation) "가상 상태: 켜짐 · 모의 작동 확인" else "가상 패널에서 기능 켜기", "workflow_manual_operate") { current.operate(); render() }, full(64))
        val actions = row()
        actions.addView(button("이전", "workflow_manual_previous", current.index > 0) { current.previous(); stopVoice(); render() }, weight())
        actions.addView(button("다시 듣기", "workflow_manual_repeat") { say(current.current) }, weight())
        actions.addView(button("다음", "workflow_manual_next", current.index < lesson.steps.lastIndex) { current.next(); render(); say(current.current) }, weight())
        addView(button(if (current.learned) (if (demoManual) "가상 학습 확인 완료" else "설명서 학습 확인 완료") else "학습 내용을 직접 확인했어요", "workflow_manual_confirm", current.canConfirm) {
            current.confirm(); store.learned(lesson); render()
        }, full(64))
        addView(text("이 완료 표시는 교육 내용 확인이며 실제 에어컨·열선 작동을 인증하지 않아요."), full())
    }
    private fun selectManual(reference: ManualVehicle) { stopVoice(); demoManual = false; manualReference = reference; manual = null; render() }
    private fun openSource(url: String) {
        stopVoice()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "원문 주소: $url", Toast.LENGTH_LONG).show() }
    }
    private fun guidePage() {
        addView(text("정차 중 상황별 교육", 26, true), full())
        DrivingGuideCatalog.guides.chunked(3).forEach { group ->
            val choices = row()
            group.forEach { item -> choices.addView(button((if (guide.guide == item) "✓ " else "") + item.title, "workflow_guide_${item.id}") {
                stopVoice(); guide = DrivingGuideSession(item); render()
            }, weight(64)) }
        }
        val item = guide.guide
        addView(text("${item.title} · ${guide.index + 1}/${item.steps.size}", 24, true), full())
        item.illustrations.getOrNull(guide.index)?.let { addView(RoadConceptView(context,it), full(210)) }
        addView(text(guide.current, 24), full())
        val steps = row()
        steps.addView(button("이전", "workflow_guide_previous", guide.index > 0) { guide.previous(); stopVoice(); render() }, weight())
        steps.addView(button("음성으로 듣기", "workflow_guide_speak") { say(guide.current) }, weight())
        steps.addView(button("다음", "workflow_guide_next", guide.index < item.steps.lastIndex) { guide.next(); stopVoice(); render() }, weight())
        if (guide.index == item.steps.lastIndex) {
            addView(text(item.question, 22, true), full())
            item.choices.forEachIndexed { i, choice -> addView(button(choice, "workflow_guide_answer_$i") {
                guide.answer(i); stopVoice(); render()
            }, full(64)) }
            if (guide.selected != null) addView(text((if (guide.completed) "이해 확인 완료 · " else "다시 생각해 봐요 · ") + item.explanation), full())
            item.relatedTask?.let { id ->
                addView(button("이어서 가상 연습: ${ScenarioCatalog.find(id)?.title}", "workflow_guide_practice", guide.completed) {
                    startTask(id)
                }, full(60))
            }
        }
        if (item.id == "following_gap") addView(GapStudyView(context,gapStudy,{ active && !closed && page == Page.GUIDES },::stopVoice), full())
        addView(text("출처: ${item.source}\n학습 확인은 실제 운전 능력 평가나 주변 감지 결과가 아니에요.", 15), full())
        addView(button("교육 참고 자료", "workflow_guide_source") { openSource(item.sourceUrl) }, full(52))
        item.additionalSourceUrl?.let { url -> addView(button("비보호 좌회전 참고 자료", "workflow_guide_additional_source") { openSource(url) },full(52)) }
    }
    private fun loadCourses() { courses.load(RouteRequest(goal, selectedTask, if (startSelected) "가상 연습장" else null, if (excludeRoundTrip) setOf("왕복") else emptySet()), SimulatedRouteProvider(), SystemClock.elapsedRealtime()) }
    private fun coursePage() {
        val confirmed = courses.confirmedRoute(SystemClock.elapsedRealtime())
        val body = practiceBody()
        body.addView(practiceText("목표를 고르고, 여러 주행 상황을 하나의 코스로 연습해요.", 21), full())
        val settingsRow = PreparationColumns(context, breakpoint = 1_100)
        body.addView(settingsRow, full())
        val target = practiceCard(); settingsRow.addView(target)
        target.addView(practiceText("1  어떤 연습을 할까요?", 24, true), full())
        val goals = practiceRow(target)
        listOf(RouteGoal.BEGINNER to "초보자 추천", RouteGoal.WEAKNESS to "선택 과제 보완", RouteGoal.RETURN_TRIP to "왕복 연습").forEach { (value, title) ->
            goals.addView(practiceButton(title, "workflow_goal_${value.name.lowercase()}", primary = goal == value) {
                goal = value; loadCourses(); render()
            }, practiceWeight())
        }
        target.addView(practiceText(when (goal) {
            RouteGoal.BEGINNER -> "직진부터 출발·정지, 회전까지 기본 동작을 차근차근 익혀요."
            RouteGoal.WEAKNESS -> "더 익히고 싶은 상황을 직접 골라 집중해서 연습해요."
            RouteGoal.RETURN_TRIP -> "경로 변경과 차로 변경을 순서대로 연습해요."
        }, 20), full())
        if (goal == RouteGoal.WEAKNESS) {
            target.addView(practiceButton("연습할 과제: ${ScenarioCatalog.find(selectedTask)?.title}", "workflow_course_task") {
                chooseTask("보완할 가상 과제") { selectedTask = it; loadCourses(); render() }
            }, full())
            target.addView(practiceText("내 기록으로 복습 추천", 22, true), full())
            val recommendations = store.recommendations(System.currentTimeMillis())
            if (recommendations.isEmpty()) target.addView(practiceText(if (recordEnabled()) "아직 추천할 기록이 없어요. 위에서 연습할 과제를 직접 골라 주세요."
                else "기록 동의가 꺼져 있어 저장 기록으로 추천하지 않아요. 과제는 직접 선택할 수 있어요.", 19), full())
            recommendations.forEach { recommendation ->
                target.addView(practiceText("${recommendation.task.title} · ${recommendation.reason}", 20), full())
                target.addView(practiceButton("이 과제로 코스 보기", "workflow_recommend_${recommendation.task.id}") {
                    selectedTask = recommendation.task.id; loadCourses(); render()
                }, full())
            }
            target.addView(practiceText("내가 남긴 어려움만 참고해요. 운전 실력을 자동 평가하지 않아요.", 18), full())
        }
        val location = practiceCard(); settingsRow.addView(location)
        location.addView(practiceText("2  연습할 곳과 조건", 24, true), full())
        location.addView(practiceButton(if (startSelected) "✓ 가상 연습장 선택됨" else "가상 연습장을 출발지로 선택", "workflow_start_location", primary = startSelected) {
            startSelected = true; loadCourses(); render()
        }, full())
        location.addView(practiceText("현재 위치와 연결되지 않는 화면 속 연습장이에요. 실제 도로와 경사는 반영하지 않아요.", 19), full())
        val conditions = practiceRow(location)
        conditions.addView(practiceButton(if (excludeRoundTrip) "✓ 왕복 제외 · 해제" else "왕복 코스 제외", "workflow_course_exclusion", primary = excludeRoundTrip) {
            excludeRoundTrip = !excludeRoundTrip; loadCourses(); render()
        }, practiceWeight())
        conditions.addView(practiceButton("코스 다시 보기", "workflow_course_refresh", startSelected) { loadCourses(); render() }, practiceWeight())

        body.addView(practiceText("3  연습 코스 선택", 25, true), full().apply { topMargin = dp(12) })
        body.addView(practiceText(courses.message, 19).apply { contentDescription = "workflow_course_status" }, full())
        if (courses.candidates.isEmpty()) {
            val empty = practiceCard(PreparationStyle.softAccent); body.addView(empty, full())
            empty.addView(practiceText(if (startSelected) "조건에 맞는 코스가 없어요" else "가상 연습장을 먼저 선택해 주세요", 24, true), full())
            empty.addView(practiceText(if (startSelected) "왕복 제외를 해제하거나 다른 연습 목표를 골라 주세요." else "출발지를 선택하면 목표에 맞는 코스를 볼 수 있어요.", 20), full())
        }
        courses.candidates.forEach { route -> courseOption(body, route, confirmed?.id == route.id) }
        body.addView(practiceText("실제 목적지는 ‘차량 상태 · 위치와 내비’에서 설정할 수 있어요. 여기서 고른 코스는 화면 속 연습에만 사용돼요.", 18), full())
        val footer = practiceCard(PreparationStyle.softAccent); addView(footer, full().apply { topMargin = dp(10) })
        footer.addView(practiceText(confirmed?.let { "선택한 코스 · ${it.name} · ${it.tasks.size}개 과제" } ?: "위에서 연습할 코스를 선택해 주세요", 21, true).apply {
            contentDescription = "workflow_course_selection"
        }, full())
        footer.addView(practiceButton("선택한 코스로 연습 시작", "workflow_course_start", confirmed != null, primary = confirmed != null) {
            val current = courses.confirmedRoute(SystemClock.elapsedRealtime())
            if (current == null) { render(); return@practiceButton }
            courseTasks = current.tasks; courseIndex = 0; startTask(courseTasks.first(), true)
        }, full())
    }
    private fun courseOption(body: LinearLayout, route: TrainingRoute, selected: Boolean) {
        val card = practiceCard(if (selected) PreparationStyle.softAccent else PleosUi.SURFACE)
        body.addView(card, full())
        val columns = PreparationColumns(context, 2.4f, 1f, 1_100); card.addView(columns, full())
        val details = LinearLayout(context).apply { orientation = VERTICAL }; columns.addView(details)
        details.addView(practiceText(route.name, 24, true), full())
        details.addView(practiceText(String.format(Locale.KOREA, "%d개 과제 · 설정 거리 %.1fkm · 약 %d분", route.tasks.size, route.distanceKm, route.minutes), 20), full())
        details.addView(practiceText(route.tasks.mapIndexed { index, id -> "${index + 1}. ${ScenarioCatalog.find(id)?.title.orEmpty()}" }.joinToString("  →  "), 20), full())
        columns.addView(practiceButton(if (selected) "✓ 선택한 코스" else "이 코스 선택", "workflow_route_${route.id}", primary = selected) {
            courses.choose(route.id, SimulationNavigator(), SystemClock.elapsedRealtime()); render()
        })
    }
    private fun taskPage() {
        val body = practiceBody()
        body.addView(practiceText("익히고 싶은 상황을 골라 한 동작씩 연습해요.", 21), full())
        val intro = practiceCard(PreparationStyle.softAccent)
        body.addView(intro, full())
        intro.addView(practiceText("처음이라면 가속·감속·재출발부터", 24, true), full())
        intro.addView(practiceText("화면의 페달로 출발하고, 멈춘 뒤 다시 출발하는 순서를 익혀요.", 20), full())
        intro.addView(practiceButton("기본 조작 시작", "workflow_quick_start", primary = true) { selectedTask = "D03"; startTask("D03") }, full())
        val groups = listOf(
            "기본 조작과 속도" to listOf("D01", "D02", "D03", "D04", "D12", "D16"),
            "교차로와 회전" to listOf("D05", "D06", "D07", "D08"),
            "차로 변경과 합류" to listOf("D09", "D10", "D13", "D14"),
            "도로 상황과 돌발 대응" to listOf("D11", "D15", "D17", "D18"))
        groups.forEach { (title, ids) ->
            body.addView(practiceText(title, 25, true), full().apply { topMargin = dp(16) })
            ids.chunked(2).forEach { pair ->
                val columns = PreparationColumns(context, breakpoint = 1_100)
                body.addView(columns, full())
                pair.forEach { id ->
                    val task = requireNotNull(ScenarioCatalog.find(id))
                    val caption = task.title + "\n" + task.instruction
                    columns.addView(practiceButton(caption, "workflow_task_$id") { selectedTask = id; startTask(id) }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 23f; minHeight = dp(136)
                        text = SpannableString(caption).apply {
                            setSpan(StyleSpan(Typeface.BOLD), 0, task.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(RelativeSizeSpan(.83f), task.title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    })
                }
            }
        }
        body.addView(practiceText("화면 속 가상 연습이에요. 실제 차량은 P에 놓고 멈춘 상태로 이용해 주세요.", 18), full())
    }
    private fun chooseTask(title: String, chosen: (String) -> Unit) {
        stopVoice(); dialog?.dismiss()
        dialog = AlertDialog.Builder(context).setTitle(title)
            .setItems(ScenarioCatalog.tasks.map { "${it.id} · ${it.title}" }.toTypedArray()) { _, index ->
                if (active && !closed) chosen(ScenarioCatalog.tasks[index].id)
            }.setNegativeButton("취소", null).show()
    }
    private fun ready(action: () -> Unit) {
        if (!active) return
        if (!canPractice()) { stopVoice(); onPrepare(); return }
        stopVoice(); action()
    }
    private fun startTask(id: String, keepCourse: Boolean = false) = ready {
        if (!keepCourse) { courseTasks = emptyList(); courseIndex = 0 }
        val task = ScenarioCatalog.find(id) ?: return@ready
        scenario = ScenarioSession(task, UUID.randomUUID().toString()); review = null; saved = false; explanation = null; difficulty = null; statusMessage = ""
        page = Page.SCENARIO; store.clearCheckpoint(); lastCueRevision = -1; render()
    }
    private fun scenarioPage() {
        val session = scenario ?: return
        val snapshot = session.snapshot()
        val actions = practiceRow(this)
        resumeButton = practiceButton("시작·이어서 연습", "workflow_resume", primary = true) {
            if (!canPractice()) { onPrepare(); return@practiceButton }
            session.resume(); updateScene(session.snapshot()); lastCueRevision = session.snapshot().cueRevision; speech.setActive(active)
            lastTick = SystemClock.elapsedRealtime(); main.removeCallbacks(ticker); main.post(ticker); say(session.snapshot().instruction)
        }.also { actions.addView(it, practiceWeight()) }
        pauseButton = practiceButton("잠시 멈춤", "workflow_pause") { pause("사용자 일시 정지"); stopVoice() }.also { actions.addView(it, practiceWeight()) }
        actions.addView(practiceButton("연습 마치기", "workflow_cancel") { session.cancel(); openReview(session.snapshot()) }, practiceWeight())

        val body = practiceBody()
        if (courseTasks.isNotEmpty()) body.addView(practiceText("가상 코스 · ${courseIndex + 1} / ${courseTasks.size}번째 과제", 22, true).apply {
            contentDescription = "workflow_course_progress"
        }, full())
        val sceneRow = PreparationColumns(context, 1.45f, 1f, 1_100)
        body.addView(sceneRow, full())
        val sceneCard = practiceCard(); sceneRow.addView(sceneCard)
        surface = ScenarioSurfaceView(context).also { sceneCard.addView(it, full(310)); it.update(snapshot) }
        val coaching = practiceCard(PreparationStyle.softAccent); sceneRow.addView(coaching)
        coaching.addView(practiceText("지금 할 일", 19), full())
        guidance = practiceText(snapshot.instruction, 23, true).also {
            it.contentDescription = "workflow_guidance"; coaching.addView(it, full())
        }
        stats = practiceText("", 20).also { it.contentDescription = "workflow_stats"; coaching.addView(it, full().apply { topMargin = dp(12) }) }
        coaching.addView(practiceText("화면 속 가상 연습 수치예요.", 18), full())

        val signals = PreparationColumns(context, breakpoint = 1_100)
        body.addView(signals, full())
        val signalCard = practiceCard(); signals.addView(signalCard)
        signalCard.addView(practiceText("방향지시등", 19, true), full())
        val signalRow = practiceRow(signalCard)
        TrainingSignal.entries.forEach { value ->
            val title = when (value) { TrainingSignal.OFF -> "끄기"; TrainingSignal.LEFT -> "← 왼쪽"; TrainingSignal.RIGHT -> "오른쪽 →" }
            val button = practiceButton(title, "workflow_signal_${value.name.lowercase()}") { session.signal(value); updateScene(session.snapshot()) }
            signalButtons[value] = button; scenarioInputs.add(button); signalRow.addView(button, practiceWeight())
        }
        val mirrorCard = practiceCard(); signals.addView(mirrorCard)
        mirrorCard.addView(practiceText("미러와 옆 공간", 19, true), full())
        mirrorButton = practiceButton("미러·사각지대 확인 학습", "workflow_mirror") { session.checkMirror(); updateScene(session.snapshot()) }.also {
            mirrorCard.addView(it, full()); scenarioInputs.add(it)
        }

        val controls = PreparationColumns(context, 1.15f, 1f, 1_100)
        body.addView(controls, full())
        val wheel = practiceCard(); controls.addView(wheel)
        wheel.addView(practiceText("가상 핸들 · 왼쪽 ← 중앙 → 오른쪽", 19, true), full())
        val steeringRow = practiceRow(wheel)
        val steering = SeekBar(context).apply {
            max = 200; progress = 100; contentDescription = "workflow_steering"
            progressTintList = android.content.res.ColorStateList.valueOf(PreparationStyle.accent); thumbTintList = progressTintList
            setPadding(dp(16), 0, dp(16), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser && active) session.steer((progress - 100) / 100.0) }
            })
        }
        scenarioSteering = steering
        steeringRow.addView(steering, practiceWeight(64)); scenarioInputs.add(steering)
        val center = practiceButton("핸들 중앙", "workflow_center") { session.steer(0.0); steering.progress = 100 }
        steeringRow.addView(center, LayoutParams(dp(140), -2)); scenarioInputs.add(center)
        val pedals = practiceCard(); controls.addView(pedals)
        pedals.addView(practiceText("가상 페달 · 누르는 동안 조작", 19, true), full())
        val pedalRow = practiceRow(pedals)
        listOf(pedal("가속", "workflow_gas", true), pedal("브레이크", "workflow_brake", false)).forEach {
            pedalRow.addView(it, practiceWeight()); scenarioInputs.add(it)
        }
        val wait = practiceButton("가상 정차·대기", "workflow_wait") { session.waitForGap(); updateScene(session.snapshot()) }
        body.addView(wait, full()); scenarioInputs.add(wait)
        if (snapshot.task.id == "D15") {
            val reroute = practiceButton("길 놓침·가상 재탐색", "workflow_reroute") { stopVoice(); session.reroute(); updateScene(session.snapshot()); say(session.snapshot().instruction) }
            body.addView(reroute, full()); scenarioInputs.add(reroute)
        }
        body.addView(practiceText(snapshot.task.unknown + "\n실제 차량의 뒤쪽과 옆쪽 거리는 직접 확인해 주세요.", 18), full())
        updateScene(snapshot)
    }
    private fun pedal(label: String, key: String, accelerator: Boolean): PracticeButton = practiceButton(label, key) {}.apply {
        setOnTouchListener { view, event ->
            if (active && page == Page.SCENARIO) when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.parent?.requestDisallowInterceptTouchEvent(true); scenario?.pedals(if (accelerator) 1.0 else 0.0, if (accelerator) 0.0 else 1.0) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.parent?.requestDisallowInterceptTouchEvent(false); scenario?.pedals(0.0, 0.0); if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick() }
            }
            true
        }
    }
    private fun updateScene(snapshot: ScenarioSnapshot) {
        val lead = snapshot.leadGapM?.let { context.getString(R.string.road_lead_gap,it) } ?: ""
        val headway = if (snapshot.task.id == "D02") snapshot.leadHeadwaySeconds?.let { context.getString(R.string.road_lead_headway,it) }
            ?: context.getString(R.string.road_lead_headway_stopped) else ""
        stats?.text = context.getString(R.string.road_scenario_stats,snapshot.speedKmh,snapshot.distance,
            snapshot.waiting,lead,headway)
        guidance?.text = snapshot.instruction
        surface?.update(snapshot)
        val allowed = active && !snapshot.paused && snapshot.status == EventStatus.ACTIVE && canPractice()
        if (snapshot.paused) scenarioSteering?.let { if (it.progress != 100) it.progress = 100 }
        scenarioInputs.forEach { it.isEnabled = allowed; it.alpha = if (allowed) 1f else .45f }
        signalButtons.forEach { (signal, button) -> practiceSelection(button, snapshot.signal == signal) }
        mirrorButton?.let { practiceSelection(it, snapshot.mirrorChecked) }
        pauseButton?.visibility = if (snapshot.paused) GONE else VISIBLE
        pauseButton?.isEnabled = allowed
        resumeButton?.visibility = if (snapshot.paused) VISIBLE else GONE
        resumeButton?.isEnabled = active && snapshot.paused && snapshot.status == EventStatus.ACTIVE
    }
    private fun openReview(snapshot: ScenarioSnapshot) {
        main.removeCallbacks(ticker); stopVoice(); store.clearCheckpoint(); review = snapshot
        page = Page.REVIEW; render()
    }
    private fun reviewPage() {
        val snapshot = review ?: return
        val body = practiceBody()
        val summary = practiceCard(PreparationStyle.softAccent); body.addView(summary, full())
        summary.addView(practiceText("${snapshot.task.title} · ${statusLabel(snapshot.status.name)}", 26, true), full())
        summary.addView(practiceText(snapshot.instruction, 21), full())
        summary.addView(practiceText(String.format(Locale.KOREA, "가상 이동 %.1fm", snapshot.distance), 19), full())
        val columns = PreparationColumns(context, breakpoint = 1_100); body.addView(columns, full())
        val feeling = practiceCard(); columns.addView(feeling)
        feeling.addView(practiceText("이번 연습은 어땠나요?", 24, true), full())
        feeling.addView(practiceText("내 소감은 화면 속 조작 결과와 따로 남겨요.", 19), full())
        listOf("옆 차 때문에 기다렸어요", "조작 순서가 어려웠어요", "현장 확인이 어려웠어요").forEachIndexed { i, value ->
            feeling.addView(practiceButton(value, "workflow_reflect_$i", !saved, explanation == value) { explanation = value; render() }, full())
        }
        feeling.addView(practiceButton("회고 건너뛰기", "workflow_reflect_skip", !saved) { explanation = null; render() }, full())
        feeling.addView(practiceText("내 설명: ${explanation ?: "미응답 · 원인 미확인"}", 19), full())
        val nextPractice = practiceCard(); columns.addView(nextPractice)
        nextPractice.addView(practiceText("다음에 더 익힐 것", 24, true), full())
        nextPractice.addView(practiceText(difficulty?.let { ScenarioCatalog.find(it)?.title } ?: "선택 사항이에요. 어려움을 자동으로 판단하지 않아요.", 20), full())
        nextPractice.addView(practiceButton("이번 과제를 더 연습하고 싶어요", "workflow_difficulty_current", !saved, difficulty == snapshot.task.id) { difficulty = snapshot.task.id; render() }, full())
        nextPractice.addView(practiceButton("다른 과제 선택", "workflow_difficulty_choose", !saved) {
            chooseTask("어려웠던 가상 과제") { difficulty = it; render() }
        }, full())
        nextPractice.addView(practiceButton("어려움 선택 안 함", "workflow_difficulty_skip", !saved) { difficulty = null; render() }, full())
        nextPractice.addView(practiceText("실제 운전에서는 직접 확인해 주세요.\n${snapshot.task.unknown}", 18), full())
        val saveRow = PreparationColumns(context, breakpoint = 1_100); body.addView(saveRow, full())
        saveRow.addView(practiceButton(if (saved) "이 기기에 저장했어요" else if (recordEnabled()) "이번 가상 연습 저장" else "기록 동의 꺼짐 · 저장 없이 이용", "workflow_save", !saved && recordEnabled()) {
            saved = store.save(snapshot, explanation, difficulty); statusMessage = if (saved) "가상 관측과 자기평가를 분리해 저장했어요." else "저장하지 못했어요."; render()
        })
        saveRow.addView(practiceButton("가상 코스로 연결", "workflow_review_course") { selectedTask = difficulty ?: snapshot.task.nextTask; goal = RouteGoal.WEAKNESS; loadCourses(); page = Page.COURSES; render() })
        if (statusMessage.isNotBlank()) body.addView(practiceText(statusMessage, 19), full())
        val courseNext = courseTasks.getOrNull(courseIndex + 1)
        val courseFinished = courseTasks.isNotEmpty() && courseNext == null && snapshot.status == EventStatus.COMPLETED
        if (courseTasks.isNotEmpty()) body.addView(practiceText("가상 코스 ${courseIndex + 1}/${courseTasks.size} · ${if (courseFinished) "코스 마침" else "각 과제를 직접 마친 뒤 다음으로 이동해요."}", 22, true).apply {
            contentDescription = "workflow_course_progress"
        }, full())
        if (courseFinished) {
            addView(practiceButton("코스 마치고 목록으로", "workflow_course_finish", primary = true) {
                courseTasks = emptyList(); courseIndex = 0; loadCourses(); page = Page.COURSES; render()
            }, full().apply { topMargin = dp(12) })
            return
        }
        val nextId = if (snapshot.status != EventStatus.COMPLETED && courseTasks.isNotEmpty()) snapshot.task.id else courseNext ?: difficulty ?: snapshot.task.nextTask
        val nextLabel = if (courseTasks.isNotEmpty() && snapshot.status != EventStatus.COMPLETED) "현재 과제 다시 연습" else "다음 연습"
        addView(practiceButton("$nextLabel: ${ScenarioCatalog.find(nextId)?.title}", "workflow_next_task", primary = true) {
            if (courseTasks.isNotEmpty() && snapshot.status == EventStatus.COMPLETED && courseNext != null) { courseIndex++; startTask(nextId, true) }
            else startTask(nextId, courseTasks.isNotEmpty() && snapshot.status != EventStatus.COMPLETED)
        }, full().apply { topMargin = dp(12) })
    }
    private fun historyPage() {
        addView(text("이 기기의 가상 연습 기록", 26, true), full())
        val records = store.records()
        if (records.isEmpty()) addView(text("저장한 가상 과제가 없어요. 기록에 동의하지 않아도 연습할 수 있어요."), full())
        records.forEachIndexed { i, record ->
            val task = ScenarioCatalog.find(record.optString("task")) ?: return@forEachIndexed
            addView(text("${task.title} · ${statusLabel(record.optString("status"))}\n연습 내용: ${record.optString("facts")}\n직접 확인할 점: ${record.optString("unknown")}\n내 소감: ${if (record.isNull("explanation")) "남긴 소감이 없어요" else record.optString("explanation")}"), full())
            addView(button("추천 과제 다시 연습", "workflow_history_$i") { startTask(record.optString("next", task.nextTask)) }, full(56))
            addView(text("내가 선택한 어려움: ${ScenarioCatalog.find(record.optString("difficulty"))?.title ?: "미선택"}"), full())
        }
        addView(button("이 과제의 기록·중단 지점 삭제", "workflow_delete") {
            dialog?.dismiss()
            dialog = AlertDialog.Builder(context).setTitle("가상 과제 기록을 삭제할까요?")
                .setMessage("이 기능의 가상 과제 기록·중단 지점·가상 설명서 학습 기록만 삭제해요. 기존 프로필·주행·주차·음성 연수 기록은 유지해요.")
                .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> if (active) { store.delete(); render() } }.show()
        }, full(60))
    }
    private fun practiceHeader() {
        val header = practiceRow(this)
        val title = when (page) { Page.COURSES -> "가상 코스"; Page.SCENARIO -> scenario?.snapshot()?.task?.title ?: "주행 연습"; Page.REVIEW -> "주행 연습 돌아보기"; else -> "주행 연습" }
        header.addView(practiceText(title, 30, true), practiceWeight())
        header.addView(practiceButton(if (arbiter.muted) "안내 재개" else "안내 음소거", "workflow_mute") {
            if (page == Page.SCENARIO) pause("음성 설정 변경")
            if (arbiter.muted) arbiter.resume() else { arbiter.mute(); stopVoice() }
            settings.edit().putBoolean("muted", arbiter.muted).apply(); speech.setEnabled(voiceEnabled && !arbiter.muted); render()
        }, LayoutParams(dp(152), -2).apply { marginEnd = dp(8) })
        header.addView(practiceButton("준비와 연습", "workflow_home") { pause("기능 선택"); stopVoice(); page = Page.HOME; render() }, LayoutParams(dp(152), -2).apply { marginEnd = dp(8) })
        header.addView(practiceButton("닫기", "workflow_exit") { pause("사용자 종료"); stopVoice(); onExit() }, LayoutParams(dp(88), -2))
        voiceLabel = practiceText(outputStatus, 18).also {
            it.contentDescription = "workflow_speech_status"; it.visibility = if (outputStatus.isBlank()) GONE else VISIBLE; addView(it, full())
        }
    }
    private fun practiceBody(): LinearLayout {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        val savedScroll = practiceScrollY
        return LinearLayout(context).apply { orientation = VERTICAL; scroll.addView(this); scroll.post { scroll.scrollTo(0, savedScroll) } }
    }
    private fun practiceCard(fill: Int = PleosUi.SURFACE) = LinearLayout(context).apply {
        orientation = VERTICAL; background = PreparationStyle.surface(context, fill); setPadding(dp(20), dp(18), dp(20), dp(18))
    }
    private fun practiceText(value: String, size: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (bold) PleosUi.TEXT else PleosUi.SECONDARY)
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun practiceButton(value: String, key: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = PracticeButton(context).apply {
        text = value; textSize = 20f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = dp(64); minimumHeight = dp(64)
        setPadding(dp(18), dp(12), dp(18), dp(12)); gravity = Gravity.CENTER; contentDescription = key; isEnabled = enabled && active
        isSelected = primary; PreparationStyle.button(this, primary)
        setOnClickListener { if (active && !closed) action() }
    }
    private fun practiceSelection(button: Button, selected: Boolean) {
        if (button.isSelected == selected) return
        button.isSelected = selected; PreparationStyle.button(button, selected)
    }
    private fun practiceRow(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false; parent.addView(this, full())
    }
    private fun practiceWeight(height: Int = -2) = LayoutParams(0, if (height < 0) height else dp(height), 1f).apply { marginEnd = dp(8) }

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
    private fun weatherLabel(value: WeatherCondition) = when (value) {
        WeatherCondition.CLEAR -> "맑음"
        WeatherCondition.RAIN -> "비"
        WeatherCondition.SNOW -> "눈"
        WeatherCondition.FOG -> "안개"
        WeatherCondition.COLD -> "추운 날"
    }
    private fun statusLabel(value: String) = when (value) {
        EventStatus.CANDIDATE.name -> "준비 중"
        EventStatus.ACTIVE.name -> "연습 중"
        EventStatus.COMPLETED.name -> "연습 완료"
        EventStatus.CANCELLED.name -> "연습 취소"
        EventStatus.EXPIRED.name -> "다시 준비해 주세요"
        EventStatus.INTERRUPTED.name -> "연습 중단"
        else -> "완료 여부 확인 필요"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
