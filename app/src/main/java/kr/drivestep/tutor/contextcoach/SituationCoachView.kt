package kr.drivestep.tutor.contextcoach

import kr.drivestep.tutor.design.PleosUi
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kr.drivestep.tutor.coach.CoachCue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SIMULATION-only signal workbench. It never imports, queries or controls a vehicle SDK. */
// Created in code with required host/session arguments; never inflated from XML.
// This automotive screen uses the platform Activity/theme, not AppCompat widgets.
@SuppressLint("ViewConstructor", "UseSwitchCompatOrMaterialCode")
class SituationCoachView(
    context: Context,
    private val onCue: (CoachCue) -> Unit,
    private val stopVoice: () -> Unit,
    private val onExit: () -> Unit,
) : LinearLayout(context) {
    private enum class Page { LIVE, REVIEW }
    private enum class Preset { DROP, RISE, WARNING }
    private val main = Handler(Looper.getMainLooper())
    private val engine = SituationCoachEngine()
    private val prefs = context.getSharedPreferences("drivestep_situation_coach_v1", Context.MODE_PRIVATE)
    private var active = false
    private var running = false
    private var closed = false
    private var syncing = false
    private var page = Page.LIVE
    private var preset: Preset? = null
    private var presetStarted = 0L
    private var latestInputAt = -1L
    private var replayRunning = false
    private var replayStarted = 0L
    private var reviewRecord: JSONObject? = null
    private var reviewSamples = emptyList<SituationInput>()
    private var liveSamples = ArrayDeque<SituationInput>()
    private var signal = SituationSignal.OFF
    private var speed = 0.0
    private var warning = SituationWarning.UNKNOWN
    private var gear = SituationGear.D
    private var brake = false
    private var steering = 0.0
    private var road = SituationRoad.UNKNOWN
    private var vehicleWarningActive = false
    private var lastResult: SituationResult? = null
    private var latestEvent: SituationEvent? = null
    private var storageProblem: String? = null
    private var records = loadRecords()

    private var eventTitle: TextView? = null
    private var eventCue: TextView? = null
    private var reasonLabel: TextView? = null
    private var evidenceLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var observationLabel: TextView? = null
    private var speedLabel: TextView? = null
    private var steeringLabel: TextView? = null
    private var presetLabel: TextView? = null
    private var speedSlider: SeekBar? = null
    private var steeringSlider: SeekBar? = null
    private var brakeSwitch: Switch? = null
    private var warningSoundSwitch: Switch? = null
    private var resumeButton: Button? = null
    private var pauseButton: Button? = null
    private var lastReviewButton: Button? = null
    private var chart: ObservationChart? = null
    private var historyContainer: LinearLayout? = null
    private var replayLabel: TextView? = null
    private var replayButton: Button? = null
    private val manualControls = mutableListOf<View>()
    private val presetControls = mutableListOf<View>()
    private val choiceButtons = mutableListOf<Pair<Button, () -> Boolean>>()
    private val ink = Color.rgb(24, 45, 50)
    private val muted = Color.rgb(89, 111, 113)
    private val teal = Color.rgb(8, 127, 113)
    private val soft = Color.rgb(225, 242, 235)
    private val amber = Color.rgb(154, 99, 35)

    private val ticker = object : Runnable {
        override fun run() {
            if (!canInput() || !isAttachedToWindow) return
            val now = SystemClock.elapsedRealtime()
            updatePreset(now)
            observe(now)
            if (canInput()) main.postDelayed(this, 200)
        }
    }
    private val replayTicker = object : Runnable {
        override fun run() {
            if (!replayRunning || !active || closed || page != Page.REVIEW || !isAttachedToWindow) return
            if (reviewSamples.isEmpty()) { stopReplay(); return }
            val elapsed = SystemClock.elapsedRealtime() - replayStarted
            val span = (reviewSamples.last().atElapsedMillis - reviewSamples.first().atElapsedMillis).coerceAtLeast(1)
            val cursor = elapsed.coerceAtMost(span)
            val index = reviewSamples.indexOfLast { it.atElapsedMillis - reviewSamples.first().atElapsedMillis <= cursor }.coerceAtLeast(0)
            updateReplay(index)
            if (elapsed >= span) {
                stopReplay()
                replayLabel?.text = "다시 보기를 마쳤어요. 아래에서 차근의 안내를 살펴보세요."
            } else main.postDelayed(this, 100)
        }
    }

    init {
        orientation = VERTICAL
        render()
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (!value) pauseAll()
        updateEnabled()
    }

    fun goBack(): Boolean {
        if (page == Page.LIVE) return false
        pauseAll(); page = Page.LIVE; render()
        return true
    }

    fun close() {
        if (closed) return
        pauseAll(); closed = true
        main.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() {
        pauseAll()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateEnabled() // Never resume observations or replay just because a view returned.
    }

    private fun render() {
        eventTitle = null; eventCue = null; reasonLabel = null; evidenceLabel = null
        statusLabel = null; observationLabel = null; speedLabel = null; steeringLabel = null
        presetLabel = null; speedSlider = null; steeringSlider = null; brakeSwitch = null
        warningSoundSwitch = null; resumeButton = null; pauseButton = null; lastReviewButton = null
        chart = null; historyContainer = null; replayLabel = null; replayButton = null
        manualControls.clear(); presetControls.clear(); choiceButtons.clear()
        removeAllViews()
        if (page == Page.LIVE) livePage() else reviewPage()
        updateEnabled()
        // Only a page rebuild resets the host scroll; signal ticks preserve it.
        post {
            var ancestor = parent
            while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
            (ancestor as? ScrollView)?.scrollTo(0, 0)
        }
    }

    private fun livePage() {
        addView(label("상황이 생기면 먼저 도와주는 코치", 27, ink, true))
        addView(label("가상 상황 연습 · 실제 차량은 P에 놓고 멈춰 주세요. 아래 조작은 화면 속 차량에만 적용돼요.", 14, amber))
        val demos = row(this)
        listOf(
            Triple("왼쪽 깜빡이 → 감속", "situation_demo_decelerate", Preset.DROP),
            Triple("오른쪽 깜빡이 → 속도 증가", "situation_demo_accelerate", Preset.RISE),
            Triple("왼쪽 변경 경고", "situation_demo_warning", Preset.WARNING),
        ).forEach { (title, key, demo) ->
            val view = button(title, key) { startPreset(demo) }
            presetControls.add(view); demos.addView(view, weight(52))
        }
        presetLabel = label("상황을 선택하면 화면 속 차량이 움직이고, 차근이 대응 방법을 안내해요.", 14, muted).also { addView(it) }
        gap(this, 8)
        val columns = row(this)
        val inputs = card(columns, asColumn = true)
        val coach = card(columns, asColumn = true)
        inputs.addView(label("연습 상황 직접 설정", 20, teal, true))
        inputs.addView(label("방향지시등", 15, ink, true))
        val signals = row(inputs)
        signalChoice(signals, "꺼짐", "situation_off", SituationSignal.OFF)
        signalChoice(signals, "← 왼쪽", "situation_left", SituationSignal.LEFT)
        signalChoice(signals, "오른쪽 →", "situation_right", SituationSignal.RIGHT)
        signalChoice(signals, "비상등", "situation_hazard", SituationSignal.HAZARD)
        speedLabel = label("가상 속도 0 km/h", 16, teal, true).also { inputs.addView(it) }
        speedSlider = slider(120, 0, "situation_speed") { value ->
            manualChange { speed = value.toDouble() }
        }.also { inputs.addView(it, LayoutParams(-1, dp(38))) }
        inputs.addView(label("차로 변경 경고 · ‘없음’은 안전 허가가 아니에요", 14, ink, true))
        val warnings = row(inputs)
        listOf(
            Triple("미확인", "situation_warning_unknown", SituationWarning.UNKNOWN),
            Triple("없음", "situation_warning_clear", SituationWarning.CLEAR),
            Triple("왼쪽", "situation_warning_left", SituationWarning.LEFT),
            Triple("오른쪽", "situation_warning_right", SituationWarning.RIGHT),
            Triple("양쪽", "situation_warning_both", SituationWarning.BOTH),
        ).forEach { (title, key, value) ->
            choice(warnings, title, key, { warning == value }) { manualChange { warning = value } }
        }
        inputs.addView(label("가상 기어", 15, ink, true))
        val gears = row(inputs)
        for (value in listOf(SituationGear.P, SituationGear.D, SituationGear.R, SituationGear.N)) {
            choice(gears, value.name, "situation_gear_${value.name.lowercase(Locale.ROOT)}", { gear == value }) {
                manualChange { gear = value }
            }
        }
        brakeSwitch = toggle("가상 브레이크 눌림", "situation_brake") { checked -> manualChange { brake = checked } }
            .also { inputs.addView(it) }
        steeringLabel = label("가상 전륜 조향각 0° · 핸들 회전각 아님", 14, teal).also { inputs.addView(it) }
        steeringSlider = slider(40, 20, "situation_steering") { value -> manualChange { steering = (value - 20).toDouble() } }
            .also { inputs.addView(it, LayoutParams(-1, dp(36))) }
        inputs.addView(label("연습할 도로", 14, ink, true))
        val roads = row(inputs)
        listOf("미확인" to SituationRoad.UNKNOWN, "직선" to SituationRoad.STRAIGHT,
            "교차로" to SituationRoad.INTERSECTION, "주차" to SituationRoad.PARKING).forEach { (title, value) ->
            choice(roads, title, "situation_road_${value.name.lowercase(Locale.ROOT)}", { road == value }) {
                manualChange { road = value }
            }
        }
        warningSoundSwitch = toggle("가상 차량 경고음이 재생 중", "situation_warning_audio") { checked ->
            if (canInput()) {
                vehicleWarningActive = checked
                if (checked) stopVoice()
                observe(SystemClock.elapsedRealtime())
            }
        }.also { inputs.addView(it) }
        inputs.addView(label("이 스위치는 경고음 우선 처리 시험입니다. 실제 경고음을 재생하거나 차량 상태를 읽지 않아요.", 12, muted))

        coach.addView(label("상황에 반응하는 코치", 18, teal, true))
        eventTitle = label("필요한 순간을 기다리고 있어요", 23, ink, true).also { coach.addView(it) }
        eventCue = label("", 19, ink).also { coach.addView(it) }
        gap(coach, 8)
        reasonLabel = label("신호 하나로 차로 변경을 확정하지 않아요. 방향지시와 속도 변화 또는 입력한 차량 경고를 함께 살펴요.", 15, muted).also { coach.addView(it) }
        evidenceLabel = label("", 14, muted).also { coach.addView(it) }
        gap(coach, 8)
        chart = ObservationChart(context).also { coach.addView(it, LayoutParams(-1, dp(120))) }
        observationLabel = label("속도 관측을 시작하기 전이에요.", 14, teal).also { coach.addView(it) }
        statusLabel = label("연습을 시작하거나 위에서 상황을 선택해 주세요.", 15, ink).also { coach.addView(it) }
        lastReviewButton = button("이번 상황 복습 · 다시 보기", "situation_review_last", secondary = true) {
            records.firstOrNull()?.let(::openReview)
        }.also { coach.addView(it) }
        coach.addView(label("안전한 끼어들기 간격·충돌 가능성·법규 위반을 판정하는 기능은 아닙니다.", 13, muted))
        val commands = row(this)
        resumeButton = button("가상 신호 받기 시작 / 이어하기", "situation_resume") { resumeLive() }.also { commands.addView(it, weight(48)) }
        pauseButton = button("잠시 멈추기", "situation_pause", secondary = true) { pauseAll() }.also { commands.addView(it, weight(48)) }
        commands.addView(button("입력 초기화", "situation_reset", secondary = true) { resetInputs() }, weight(48))
        commands.addView(button("이전 메뉴", "situation_exit", secondary = true) { exit() }, weight(48))
        gap(this, 12)
        addView(label("정차 후 돌아볼 상황 기록", 21, ink, true))
        historyContainer = LinearLayout(context).apply { orientation = VERTICAL }.also { addView(it) }
        updateLiveControls()
        updateLivePresentation()
        updateHistory()
    }

    private fun signalChoice(parent: LinearLayout, title: String, key: String, value: SituationSignal) {
        choice(parent, title, key, { signal == value }) { manualChange { signal = value } }
    }

    private fun choice(parent: LinearLayout, title: String, key: String, selected: () -> Boolean, action: () -> Unit) {
        val view = button(title, key, secondary = true, action = action)
        manualControls.add(view); choiceButtons.add(view to selected)
        parent.addView(view, weight(40))
    }

    private fun slider(maximum: Int, initial: Int, key: String, changed: (Int) -> Unit) = SeekBar(context).apply {
        max = maximum; progress = initial; contentDescription = key
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !syncing && canInput()) changed(progress)
            }
        })
        manualControls.add(this)
    }

    private fun toggle(title: String, key: String, changed: (Boolean) -> Unit) = Switch(context).apply {
        text = title; textSize = 15f; setTextColor(ink); contentDescription = key
        layoutParams = LayoutParams(-1, dp(42))
        setOnCheckedChangeListener { _, checked -> if (!syncing && canInput()) changed(checked) }
        manualControls.add(this)
    }

    private fun manualChange(change: () -> Unit) {
        if (!canInput()) return
        preset = null
        change()
        presetLabel?.setIfChanged("설정한 연습 상황에 맞춰 안내하고 있어요.")
        observe(SystemClock.elapsedRealtime())
    }

    private fun startPreset(value: Preset) {
        if (!active || closed) return
        running = false; main.removeCallbacks(ticker)
        stopVoice(); engine.reset(); latestInputAt = -1L; liveSamples.clear()
        latestEvent = null; lastResult = null
        preset = value; presetStarted = SystemClock.elapsedRealtime()
        gear = SituationGear.D; road = SituationRoad.STRAIGHT; brake = false; steering = 0.0
        warning = SituationWarning.CLEAR
        signal = when (value) { Preset.DROP -> SituationSignal.LEFT; Preset.RISE -> SituationSignal.RIGHT; Preset.WARNING -> SituationSignal.OFF }
        speed = if (value == Preset.DROP) 45.0 else 30.0
        running = true
        observe(presetStarted)
        main.postDelayed(ticker, 200)
    }

    private fun updatePreset(now: Long) {
        val demo = preset ?: return
        val seconds = ((now - presetStarted) / 1000.0).coerceAtLeast(0.0)
        val fraction = ((seconds - 1.0) / 3.0).coerceIn(0.0, 1.0)
        when (demo) {
            Preset.DROP -> { signal = SituationSignal.LEFT; speed = 45.0 - 15.0 * fraction; brake = fraction > .15 }
            Preset.RISE -> { signal = SituationSignal.RIGHT; speed = 30.0 + 15.0 * fraction; brake = false }
            Preset.WARNING -> { signal = SituationSignal.OFF; speed = 30.0; warning = if (seconds >= 1.0) SituationWarning.LEFT else SituationWarning.CLEAR }
        }
        presetLabel?.setIfChanged("${if (demo == Preset.DROP) "감속" else if (demo == Preset.RISE) "속도 증가" else "변경 경고"} 시연 ${String.format(Locale.KOREA, "%.1f", seconds.coerceAtMost(4.5))}/4.5초 · 입력을 직접 바꾸면 시연을 멈춰요.")
        if (seconds >= 4.5) {
            preset = null
            presetLabel?.text = "시연 입력이 끝났어요. 같은 입력을 관찰하며 안내 반복을 쉬는 과정도 볼 수 있어요."
        }
    }

    private fun observe(now: Long) {
        if (!canInput() || now <= latestInputAt) { updateLiveControls(); return }
        latestInputAt = now
        val input = SituationInput(now, SituationSource.SIMULATION, signal, speed, warning, gear,
            brakePressed = brake, steering = steering, roadContext = road, vehicleWarningActive = vehicleWarningActive)
        liveSamples.addLast(input)
        while (liveSamples.isNotEmpty() && (now - liveSamples.first().atElapsedMillis > 6000 || liveSamples.size > 60)) liveSamples.removeFirst()
        val result = engine.accept(input)
        lastResult = result
        result.event?.let { event ->
            if (event.source != SituationSource.SIMULATION) return@let
            latestEvent = event
            Log.d("SituationCoach", "event=${event.kind.name} source=SIMULATION muted=${event.speechSuppressed}")
            saveEvent(event)
            if (event.speechSuppressed || vehicleWarningActive) {
                stopVoice()
            } else if (active && running) {
                stopVoice(); onCue(event.cue)
            }
            updateHistory()
        }
        updateLiveControls(); updateLivePresentation()
    }

    private fun updateLiveControls() {
        syncing = true
        speedSlider?.progress = speed.toInt().coerceIn(0, 120)
        steeringSlider?.progress = (steering + 20).toInt().coerceIn(0, 40)
        brakeSwitch?.isChecked = brake
        warningSoundSwitch?.isChecked = vehicleWarningActive
        syncing = false
        speedLabel?.setIfChanged("가상 속도 ${String.format(Locale.KOREA, "%.1f", speed)} km/h")
        steeringLabel?.setIfChanged("가상 전륜 조향각 ${steering.toInt()}° · 핸들 회전각 아님")
        choiceButtons.forEach { (view, selected) ->
            val key = selected()
            if (view.tag != key) {
                view.tag = key; view.background = rounded(if (key) teal else soft)
                view.setTextColor(if (key) Color.WHITE else teal)
            }
        }
        updateEnabled()
    }

    private fun updateLivePresentation() {
        if (page != Page.LIVE) return
        val event = latestEvent
        eventTitle?.setIfChanged(event?.title ?: "필요한 순간을 기다리고 있어요")
        eventCue?.setIfChanged(event?.cue?.text ?: "시연 중 속도와 깜빡이·경고를 직접 바꿔 보세요.")
        reasonLabel?.setIfChanged(event?.reason ?: "신호 하나로 차로 변경을 확정하지 않아요. 방향지시와 속도 변화 또는 입력한 차량 경고를 함께 살펴요.")
        evidenceLabel?.setIfChanged(event?.evidence?.take(6)?.joinToString("\n") ?: "기어 ${gear.name} · ${roadLabel(road)} · 경고 ${warningLabel(warning)}\n화면 속 차량의 상태예요.")
        chart?.show(liveSamples.toList())
        val result = lastResult
        observationLabel?.setIfChanged("최근 관측 변화 ${result?.speedDeltaKmh?.let { String.format(Locale.KOREA, "%+.1f km/h", it) } ?: "수집 중"}" +
            if ((result?.cooldownRemainingMs ?: 0L) > 0) " · 추가 발화 대기 ${(result!!.cooldownRemainingMs + 999) / 1000}초" else "")
        statusLabel?.setIfChanged(if (!running) "연습을 멈췄어요. 준비되면 다시 시작해 주세요." else result?.status ?: "화면 속 상황을 살펴보고 있어요.")
        if (event?.speechSuppressed == true) {
            statusLabel?.setIfChanged("차량 경고음 우선: 추가 음성은 재생하지 않고 기록만 남겼어요. 나중에 자동 재생하지 않습니다.")
        }
        lastReviewButton?.isEnabled = records.isNotEmpty() && active
    }

    private fun resumeLive() {
        if (!active || closed || page != Page.LIVE) return
        engine.reset(); latestInputAt = -1L; liveSamples.clear(); lastResult = null; latestEvent = null; preset = null
        running = true
        presetLabel?.text = "새 가상 관측부터 시작해요. 이전 대기 음성을 재생하지 않습니다."
        observe(SystemClock.elapsedRealtime())
        main.removeCallbacks(ticker); main.postDelayed(ticker, 200)
    }

    private fun resetInputs() {
        if (!active || closed) return
        pauseAll()
        engine.reset(); latestInputAt = -1L; liveSamples.clear(); lastResult = null; latestEvent = null
        signal = SituationSignal.OFF; speed = 0.0; warning = SituationWarning.UNKNOWN
        gear = SituationGear.D; brake = false; steering = 0.0; road = SituationRoad.UNKNOWN; vehicleWarningActive = false
        presetLabel?.text = "연습 상황을 처음으로 돌렸어요. 저장한 기록은 그대로예요."
        updateLiveControls(); updateLivePresentation()
    }

    private fun pauseAll() {
        running = false; replayRunning = false; preset = null
        main.removeCallbacks(ticker); main.removeCallbacks(replayTicker)
        stopVoice()
        updateEnabled()
        updateLivePresentation()
    }

    private fun canInput() = active && running && !closed && page == Page.LIVE

    private fun updateEnabled() {
        manualControls.forEach { it.isEnabled = canInput() }
        presetControls.forEach { it.isEnabled = active && !closed }
        resumeButton?.isEnabled = active && !closed
        resumeButton?.visibility = if (running) GONE else VISIBLE
        pauseButton?.visibility = if (running) VISIBLE else GONE
        lastReviewButton?.isEnabled = records.isNotEmpty() && active && !closed
        replayButton?.isEnabled = active && !closed && reviewSamples.isNotEmpty()
    }

    private fun exit() {
        pauseAll(); onExit()
    }

    private fun updateHistory() {
        val container = historyContainer ?: return
        container.removeAllViews()
        if (records.isEmpty()) container.addView(label(storageProblem ?: "상황이 감지되면 관련 관측과 복습 기록이 자동으로 생겨요. 최대 20회 보관합니다.", 14, muted))
        for (record in records) {
            val suffix = if (record.optBoolean("speechSuppressed")) " · 경고음 우선" else ""
            container.addView(button("${date(record.optLong("wallAt"))}  ${record.optString("title")}$suffix", "situation_review_${record.optString("id")}", secondary = true) { openReview(record) })
        }
        storageProblem?.let { container.addView(label(it, 14, amber)) }
    }

    private fun openReview(record: JSONObject) {
        if (!active || closed) return
        pauseAll()
        reviewRecord = record
        reviewSamples = decodeSamples(record)
        page = Page.REVIEW
        render()
    }

    private fun reviewPage() {
        val record = reviewRecord ?: run { page = Page.LIVE; livePage(); return }
        addView(label("이때, 코치가 왜 반응했을까요?", 27, ink, true))
        addView(label("저장 당시의 가상 관측 복습 · 실제 도로 재구성이나 안전 판정이 아닙니다.", 14, amber))
        val header = card(this)
        header.addView(label(record.optString("title"), 23, teal, true))
        header.addView(label(record.optString("reason"), 17, ink))
        header.addView(label(if (record.optBoolean("speechSuppressed")) "당시 차량 경고음 우선으로 추가 음성을 재생하지 않았어요." else "당시 코치: ${record.optString("cueText")}", 16, muted))
        chart = ObservationChart(context).also { header.addView(it, LayoutParams(-1, dp(170))) }
        replayLabel = label("다시 보기를 누르면 연습했던 상황을 순서대로 보여드려요.", 16, teal).also { header.addView(it) }
        val actions = row(this)
        replayButton = button("연습 다시 보기", "situation_replay") { startReplay() }.also { actions.addView(it, weight(48)) }
        actions.addView(button("상황 연습으로 돌아가기", "situation_review_back", secondary = true) { goBack() }, weight(48))
        actions.addView(button("이전 메뉴", "situation_exit", secondary = true) { exit() }, weight(48))
        gap(this, 10)
        addView(label("반응에 사용한 변수", 21, ink, true))
        val evidence = record.optJSONArray("evidence") ?: JSONArray()
        for (i in 0 until evidence.length()) addView(label("• ${evidence.optString(i)}", 16, muted))
        gap(this, 10)
        addView(label("저장된 입력 순서", 21, ink, true))
        val displayed = meaningfulSamples(reviewSamples)
        val base = reviewSamples.firstOrNull()?.atElapsedMillis ?: 0L
        displayed.forEach { input ->
            addView(label("+${String.format(Locale.KOREA, "%.1f", (input.atElapsedMillis - base) / 1000.0)}초  ${signalLabel(input.signal)} · ${String.format(Locale.KOREA, "%.1f", input.speedKmh ?: 0.0)} km/h · 경고 ${warningLabel(input.warning)}", 15, muted))
        }
        if (displayed.size < reviewSamples.size) addView(label("관측을 간격을 두고 표시했어요. 재현에는 저장된 전체 관측을 사용해요.", 13, muted))
        chart?.show(reviewSamples, 0)
    }

    private fun startReplay() {
        if (!active || closed || reviewSamples.isEmpty() || page != Page.REVIEW) return
        stopVoice(); main.removeCallbacks(replayTicker)
        replayRunning = true; replayStarted = SystemClock.elapsedRealtime()
        updateReplay(0); main.postDelayed(replayTicker, 100)
    }

    private fun stopReplay() {
        replayRunning = false; main.removeCallbacks(replayTicker)
    }

    private fun updateReplay(index: Int) {
        val input = reviewSamples.getOrNull(index) ?: return
        val start = reviewSamples.first().atElapsedMillis
        chart?.show(reviewSamples, index)
        replayLabel?.setIfChanged("+${String.format(Locale.KOREA, "%.1f", (input.atElapsedMillis - start) / 1000.0)}초 · ${signalLabel(input.signal)} · 가상 ${String.format(Locale.KOREA, "%.1f", input.speedKmh ?: 0.0)} km/h\n경고 ${warningLabel(input.warning)} · 기어 ${input.gear.name} · 브레이크 ${if (input.brakePressed == true) "눌림" else "놓임"}")
    }

    private fun meaningfulSamples(samples: List<SituationInput>): List<SituationInput> {
        if (samples.size <= 10) return samples
        val every = ((samples.size - 1) / 8.0).coerceAtLeast(1.0)
        return (0..8).map { samples[(it * every).toInt().coerceAtMost(samples.lastIndex)] }.distinct() + listOf(samples.last()).filter { it != samples[((8 * every).toInt()).coerceAtMost(samples.lastIndex)] }
    }

    private fun loadRecords(): MutableList<JSONObject> = try {
        val array = JSONArray(prefs.getString("records", "[]"))
        MutableList(minOf(array.length(), 20)) { array.getJSONObject(it) }
    } catch (_: Exception) {
        storageProblem = "상황 기록을 읽지 못해 새 저장을 멈췄어요. 기존 기록을 덮어쓰지 않습니다."
        mutableListOf()
    }

    private fun saveEvent(event: SituationEvent) {
        if (storageProblem != null || event.source != SituationSource.SIMULATION || records.any { it.optString("id") == event.id }) return
        val record = JSONObject().apply {
            put("version", 1); put("id", event.id); put("source", SituationSource.SIMULATION.name)
            put("wallAt", System.currentTimeMillis()); put("kind", event.kind.name); put("title", event.title)
            put("cueId", event.cue.id); put("cueText", event.cue.text); put("reason", event.reason)
            put("speechSuppressed", event.speechSuppressed); put("evidence", JSONArray(event.evidence))
            put("observations", JSONArray().apply { event.observations.forEach { put(encode(it)) } })
        }
        records.add(0, record)
        if (records.size > 20) records = records.take(20).toMutableList()
        prefs.edit().putString("records", JSONArray(records).toString()).apply()
    }

    private fun encode(input: SituationInput) = JSONObject().apply {
        put("at", input.atElapsedMillis); put("source", SituationSource.SIMULATION.name)
        put("signal", input.signal.name); put("speed", input.speedKmh ?: JSONObject.NULL)
        put("warning", input.warning.name); put("gear", input.gear.name)
        put("brake", input.brakePressed ?: JSONObject.NULL); put("steering", input.steering ?: JSONObject.NULL)
        put("road", input.roadContext.name); put("warningAudio", input.vehicleWarningActive)
    }

    private fun decodeSamples(record: JSONObject): List<SituationInput> = try {
        val array = record.getJSONArray("observations")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.getString("source") == SituationSource.SIMULATION.name)
            SituationInput(item.getLong("at"), SituationSource.SIMULATION,
                SituationSignal.valueOf(item.getString("signal")), if (item.isNull("speed")) null else item.getDouble("speed"),
                SituationWarning.valueOf(item.getString("warning")), SituationGear.valueOf(item.getString("gear")),
                if (item.isNull("brake")) null else item.getBoolean("brake"),
                if (item.isNull("steering")) null else item.getDouble("steering"),
                SituationRoad.valueOf(item.getString("road")), item.optBoolean("warningAudio"))
        }
    } catch (_: Exception) { emptyList() }

    private fun signalLabel(value: SituationSignal) = when (value) {
        SituationSignal.OFF -> "깜빡이 꺼짐"; SituationSignal.LEFT -> "왼쪽 깜빡이"
        SituationSignal.RIGHT -> "오른쪽 깜빡이"; SituationSignal.HAZARD -> "비상등"; SituationSignal.UNKNOWN -> "깜빡이 미확인"
    }
    private fun warningLabel(value: SituationWarning) = when (value) {
        SituationWarning.UNKNOWN -> "미확인"; SituationWarning.CLEAR -> "없음으로 입력"
        SituationWarning.LEFT -> "왼쪽"; SituationWarning.RIGHT -> "오른쪽"; SituationWarning.BOTH -> "양쪽"
    }
    private fun roadLabel(value: SituationRoad) = when (value) {
        SituationRoad.UNKNOWN -> "도로 미확인"; SituationRoad.STRAIGHT -> "직선 도로"
        SituationRoad.INTERSECTION -> "교차로"; SituationRoad.PARKING -> "주차장"
    }
    private fun row(parent: LinearLayout) = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP; parent.addView(this, LayoutParams(-1, -2)) }
    private fun card(parent: LinearLayout, asColumn: Boolean = false) = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); background = rounded(Color.WHITE)
        parent.addView(this, if (asColumn) weight(-2) else LayoutParams(-1, -2))
    }
    private fun label(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1.02f)
    }
    private fun button(value: String, key: String, secondary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 15f; isAllCaps = false; contentDescription = key
        setTextColor(if (secondary) teal else Color.WHITE); background = rounded(if (secondary) soft else teal)
        minHeight = dp(42); setPadding(dp(6), dp(3), dp(6), dp(3))
        layoutParams = LayoutParams(-1, dp(48)).apply { topMargin = dp(5) }
        setOnClickListener { action() }
    }
    private fun weight(height: Int = -2) = LayoutParams(0, if (height < 0) height else dp(height), 1f).apply { marginEnd = dp(7); topMargin = dp(3) }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun gap(parent: LinearLayout, amount: Int) { parent.addView(View(context), LayoutParams(1, dp(amount))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun date(value: Long) = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(value))
    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }
}

/** Saved/live input plot only; displaying a change does not infer its cause or safety. */
private class ObservationChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val speedPath = Path()
    private var samples = emptyList<SituationInput>()
    private var selected = -1
    private var lower = 0.0
    private var upper = 50.0
    fun show(values: List<SituationInput>, cursor: Int = -1) {
        samples = values; selected = cursor
        val speeds = values.mapNotNull { it.speedKmh?.takeIf(Double::isFinite) }
        lower = ((speeds.minOrNull() ?: 0.0) - 5).coerceAtLeast(0.0)
        upper = ((speeds.maxOrNull() ?: 45.0) + 5).coerceAtLeast(lower + 10)
        contentDescription = if (values.isEmpty()) "아직 가상 속도 관측이 없어요" else "가상 속도 ${values.first().speedKmh ?: 0.0}에서 ${values.last().speedKmh ?: 0.0} 킬로미터로 변한 관측 그래프"
        invalidate()
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val density = resources.displayMetrics.density
        val left = 40f * density; val right = (width - 10f * density).coerceAtLeast(left + 1)
        val top = 23f * density; val bottom = (height - 24f * density).coerceAtLeast(top + 1)
        paint.style = Paint.Style.FILL; paint.textSize = PleosUi.canvasTextSize(context, 12f * density); paint.color = Color.rgb(89, 111, 113)
        c.drawText("가상 속도 변화 · km/h", 4f * density, 15f * density, paint)
        paint.strokeWidth = density
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            paint.color = Color.rgb(217, 230, 223); c.drawLine(left, y, right, y, paint)
            paint.color = Color.rgb(89, 111, 113)
            c.drawText(String.format(Locale.KOREA, "%.0f", upper - (upper - lower) * i / 2), 3f * density, y + 4f * density, paint)
        }
        if (samples.isEmpty()) return
        val first = samples.first().atElapsedMillis
        val span = (samples.last().atElapsedMillis - first).coerceAtLeast(1)
        fun x(index: Int) = left + (right - left) * ((samples[index].atElapsedMillis - first).toDouble() / span).toFloat()
        fun y(index: Int) = bottom - (bottom - top) * (((samples[index].speedKmh ?: lower) - lower) / (upper - lower)).coerceIn(0.0, 1.0).toFloat()
        val path = speedPath.apply { rewind() }
        samples.indices.forEach { index -> if (index == 0) path.moveTo(x(index), y(index)) else path.lineTo(x(index), y(index)) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * density; paint.color = Color.rgb(8, 127, 113); c.drawPath(path, paint)
        val index = if (selected in samples.indices) selected else samples.lastIndex
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(214, 139, 42); c.drawCircle(x(index), y(index), 5f * density, paint)
        paint.textSize = PleosUi.canvasTextSize(context, 12f * density); paint.color = Color.rgb(89, 111, 113)
        c.drawText("0초", left, height - 5f * density, paint)
        c.drawText(String.format(Locale.KOREA, "%.1f초", span / 1000.0), (right - 44f * density).coerceAtLeast(left), height - 5f * density, paint)
    }
}
