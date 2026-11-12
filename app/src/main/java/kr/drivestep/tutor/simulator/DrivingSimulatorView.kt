package kr.drivestep.tutor.simulator

import android.app.AlertDialog
import kr.drivestep.tutor.design.showReadable
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import kr.drivestep.tutor.design.PracticeButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import kr.drivestep.tutor.journey.PreparationStyle
import kr.drivestep.tutor.journey.PreparationColumns
import kr.drivestep.tutor.coach.CoachCue
import kr.drivestep.tutor.design.PleosUi
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An entirely virtual, parked-only driving practice. No real vehicle input or control is used.
 * The host owns the P/UX gate and calls setActive(false) whenever that gate or this screen closes.
 * A suspended view keeps its session, but only an explicit Resume restarts time and input.
 */
// Created in code with required host/session arguments; never inflated from XML.
@SuppressLint("ViewConstructor")
class DrivingSimulatorView(
    context: Context,
    private val onCue: (CoachCue) -> Unit,
    private val stopVoice: () -> Unit,
    private val onExit: () -> Unit,
    private val recordEnabled: () -> Boolean = { true },
) : LinearLayout(context) {
    private enum class Page { BRIEF, DRIVE, REFLECTION }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("drivestep_driving_simulator_v1", Context.MODE_PRIVATE)
    private val ink = PleosUi.TEXT
    private val muted = PleosUi.SECONDARY
    private val teal = PreparationStyle.accent
    private val tealSoft = PreparationStyle.softAccent
    private val amber = Color.rgb(114, 77, 24)
    private var page = Page.BRIEF
    private var selected = runCatching {
        SimulationScenario.valueOf(prefs.getString("selected", SimulationScenario.LEFT_LANE.name)!!)
    }.getOrDefault(SimulationScenario.LEFT_LANE)
    private var active = false
    private var running = false
    private var closed = false
    private var syncingControls = false
    private var gasHeld = false
    private var brakeHeld = false
    private var suppressPedalClick = false
    private var inputGeneration = 0L
    private var lastTickMillis = 0L
    private var startedAtMillis = 0L
    private var saved = false
    private var session: DrivingSimulationSession? = null
    private var snapshot: DrivingSimulationSnapshot? = null
    private var lastSpokenRevision = -1L
    private var lastLoggedPhase: SimulationPhase? = null
    private var lastLoggedSpeed = Double.NaN
    private var lastLoggedLane = Double.NaN
    private var lastLoggedSignal: SimulationSignal? = null
    private var phaseEvents = JSONArray()
    private var storageProblem: String? = null
    private var records = loadRecords()
    private var reviewRecord: JSONObject? = null
    private var reviewIndex = 0
    private var deleteDialog: AlertDialog? = null

    private var road: CockpitRoadView? = null
    private var phaseLabel: TextView? = null
    private var cueLabel: TextView? = null
    private var steeringLabel: TextView? = null
    private var steering: SeekBar? = null
    private var startButton: Button? = null
    private var pauseButton: Button? = null
    private var resumeButton: Button? = null
    private var repeatButton: Button? = null
    private var gasButton: Button? = null
    private var brakeButton: Button? = null
    private val inputControls = mutableListOf<View>()
    private val signalButtons = mutableMapOf<SimulationSignal, Button>()
    private val mirrorButtons = mutableMapOf<SimulationMirror, Button>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!canInput() || !isAttachedToWindow) return
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - lastTickMillis) / 1000.0).coerceIn(0.0, 0.1)
            lastTickMillis = now
            session?.tick(dt)?.let(::consume)
            if (canInput()) main.postDelayed(this, 33)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PleosUi.BACKGROUND)
        setPadding(dp(24), dp(18), dp(24), dp(18))
        render()
    }

    fun setActive(value: Boolean) {
        if (!value) dismissDeleteDialog()
        if (closed || active == value) return
        active = value
        if (!value) pausePractice() else {
            records = loadRecords()
            if (page == Page.REFLECTION) {
                val retained = reviewRecord
                val index = records.indexOfFirst {
                    it.optLong("at") == retained?.optLong("at") &&
                        it.optString("scenario") == retained?.optString("scenario")
                }
                if (index < 0) {
                    reviewRecord = null; reviewIndex = 0; saved = false; page = Page.BRIEF
                } else { reviewIndex = index; reviewRecord = records[index] }
            }
            if (page != Page.DRIVE) render()
        }
        updateEnabled()
    }

    fun goBack(): Boolean {
        if (page == Page.BRIEF) return false
        if (page == Page.DRIVE) finishPractice("중간에 마침") else showBrief()
        return true
    }

    fun close() {
        dismissDeleteDialog()
        if (closed) return
        pausePractice()
        if (page == Page.DRIVE) snapshot?.let { saveRecord(it, "중간에 마침") }
        closed = true
        main.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() {
        dismissDeleteDialog()
        pausePractice()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateEnabled()
    }

    private fun render() {
        road = null; phaseLabel = null; cueLabel = null; steeringLabel = null; steering = null
        startButton = null; pauseButton = null; resumeButton = null; repeatButton = null
        gasButton = null; brakeButton = null
        inputControls.clear(); signalButtons.clear(); mirrorButtons.clear()
        removeAllViews()
        when (page) {
            Page.BRIEF -> briefPage()
            Page.DRIVE -> drivingPage()
            Page.REFLECTION -> reflectionPage()
        }
        updateEnabled()
    }

    private fun briefPage() {
        val heading = horizontal(this, -2)
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        heading.addView(words, weighted(-2))
        words.addView(label("차로 변경 연습", 32, ink, true), full(-2))
        words.addView(label("방향지시등부터 미러 확인, 부드러운 조향까지 익혀요.", 20, muted), full(-2).apply { topMargin = dp(8) })
        heading.addView(button("나가기", "sim_exit", true) { exit() }, fixed(116, -2))

        val pageBody = scrollBody()
        val columns = PreparationColumns(context, 1.3f, 1f, 1_100)
        pageBody.addView(columns, full(-2))
        val preview = card()
        columns.addView(preview)
        preview.addView(label("운전석에서 미리 보기", 24, ink, true), full(-2))
        preview.addView(CockpitRoadView(context).apply {
            update(DrivingSimulationSession(selected).snapshot(), false)
            background = PreparationStyle.surface(context); clipToOutline = true
        }, full(380).apply { topMargin = dp(18); bottomMargin = dp(18) })
        preview.addView(label("방향 알리기  →  미러와 옆 공간 확인  →  차로 이동", 20, teal, true), full(-2))
        preview.addView(label("가상 속도 40 km/h로 시작해요. 페달에서 손을 떼면 현재 속도를 유지해요.", 18, muted), full(-2).apply { topMargin = dp(12) })

        val choices = card()
        columns.addView(choices)
        choices.addView(label("연습할 상황", 24, ink, true), full(-2).apply { bottomMargin = dp(18) })
        listOf(
            Triple(SimulationScenario.LEFT_LANE, "왼쪽 차로 변경\n왼쪽 방향지시등과 미러부터 확인해요.", "sim_start_left"),
            Triple(SimulationScenario.RIGHT_LANE, "오른쪽 차로 변경\n오른쪽 방향지시등과 미러부터 확인해요.", "sim_start_right"),
            Triple(SimulationScenario.BLIND_SPOT, "옆 차 기다리기\n옆 차가 지나간 뒤 차로를 옮겨요.", "sim_start_blindspot"),
        ).forEach { (scenario, title, key) ->
            choices.addView(button(title, key, selected != scenario) {
                selected = scenario
                if (recordEnabled()) prefs.edit().putString("selected", selected.name).apply()
                render()
            }.apply {
                textSize = 23f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = SpannableString(title).apply { setSpan(RelativeSizeSpan(.8f), title.indexOf('\n') + 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                setLineSpacing(dp(8).toFloat(), 1f); minimumHeight = dp(112)
                PreparationStyle.button(this, selected == scenario, soft = true)
            }, full(-2).apply { bottomMargin = dp(14) })
        }
        val lastStatus = prefs.getString("last_status", "")
        val note = storageProblem ?: when {
            lastStatus == "running" || lastStatus == "paused" -> "이전 연습은 멈춰 있어요. 시작을 누르면 새로 연습해요."
            !recordEnabled() -> "기록 저장이 꺼져 있어요. 이번 연습은 기기에 저장하지 않아요."
            else -> "연습 기록은 이 기기에 저장해요. 실제 운전 점수와는 구분해요."
        }
        pageBody.addView(label(note, 18, muted), full(-2).apply { topMargin = dp(18) })
        pageBody.addView(label("화면 속 가상 연습이에요. 실제 차량은 P에 놓고 멈춘 상태로 이용해 주세요.", 18, muted), full(-2).apply { topMargin = dp(10) })
        if (records.isNotEmpty()) {
            val history = horizontal(pageBody, -2)
            history.addView(button("지난 연습 · ${records.size}회", "sim_history", true) {
                reviewIndex = 0; reviewRecord = records.first(); page = Page.REFLECTION; render()
            }, weighted(-2))
            history.addView(button("주행 기록 삭제", "sim_delete_history", true) { confirmDeleteHistory() }, weighted(-2))
        }
        startButton = button("${scenarioTitle(selected)} 시작", "sim_begin") { begin() }.also {
            addView(it, full(-2).apply { topMargin = dp(16) })
        }
    }

    private fun confirmDeleteHistory() {
        if (!active || closed || deleteDialog != null) return
        val dialog = AlertDialog.Builder(context)
            .setTitle("주행 연습 기록을 삭제할까요?")
            .setMessage("이 기기에 저장된 주행 시뮬레이션 기록을 모두 삭제합니다. 삭제한 기록은 복원할 수 없어요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                if (active && !closed) {
                    val ok = runCatching {
                        prefs.edit().remove("records").remove("last_status").commit()
                    }.getOrDefault(false)
                    if (ok) {
                        records = emptyList(); reviewRecord = null; reviewIndex = 0; saved = false
                    }
                    storageProblem = if (ok) "주행 연습 기록을 삭제했어요." else "기록을 삭제하지 못했어요. 다시 시도해 주세요."
                    Log.i("DrivingSim", "history_deleted success=$ok")
                    render()
                }
            }.create()
        deleteDialog = dialog
        dialog.setOnDismissListener { if (deleteDialog === dialog) deleteDialog = null }
        dialog.showReadable()
    }

    private fun dismissDeleteDialog() {
        deleteDialog?.dismiss(); deleteDialog = null
    }

    private fun drivingPage() {
        val heading = horizontal(this, -2)
        phaseLabel = label("", 24, teal, true).also { it.contentDescription = "sim_phase"; heading.addView(it, weighted(-2)) }
        pauseButton = button("잠시 멈춤", "sim_pause", true) { pausePractice() }.also { heading.addView(it, fixed(128, -2)) }
        resumeButton = button("이어하기", "sim_resume") { resumePractice() }.also { heading.addView(it, fixed(128, -2)) }
        heading.addView(button("마치기", "sim_finish", true) { finishPractice("중간에 마침") }, fixed(108, -2))
        heading.addView(button("나가기", "sim_exit", true) { exit() }, fixed(108, -2))

        val pageBody = scrollBody()
        road = CockpitRoadView(context).also {
            it.background = PreparationStyle.surface(context); it.clipToOutline = true
            pageBody.addView(it, full(370))
        }
        val coaching = card(18, tealSoft)
        pageBody.addView(coaching, full(-2).apply { topMargin = dp(14) })
        val cueRow = horizontal(coaching, -2)
        cueLabel = label("", 23, ink, true).also { it.contentDescription = "sim_guidance"; cueRow.addView(it, weighted(-2)) }
        repeatButton = button("다시 듣기", "sim_repeat", true) {
            if (canInput()) snapshot?.coachCue?.let { stopVoice(); onCue(it) }
        }.also { cueRow.addView(it, fixed(132, -2)) }

        val signals = PreparationColumns(context, breakpoint = 1_050)
        pageBody.addView(signals, full(-2).apply { topMargin = dp(14) })
        val signalCard = card(16); signals.addView(signalCard)
        signalCard.addView(label("방향지시등", 19, muted, true), full(-2).apply { bottomMargin = dp(10) })
        val signalRow = horizontal(signalCard, -2)
        listOf(Triple(SimulationSignal.LEFT, "← 왼쪽", "sim_signal_left"),
            Triple(SimulationSignal.OFF, "끄기", "sim_signal_off"),
            Triple(SimulationSignal.RIGHT, "오른쪽 →", "sim_signal_right")).forEach { (signal, title, key) ->
            val control = button(title, key, true) { if (canInput()) session?.setSignal(signal)?.let(::consume) }
            signalButtons[signal] = control; inputControls.add(control); signalRow.addView(control, weighted(-2))
        }
        val mirrorCard = card(16); signals.addView(mirrorCard)
        mirrorCard.addView(label("미러와 옆 공간 확인", 19, muted, true), full(-2).apply { bottomMargin = dp(10) })
        val mirrors = horizontal(mirrorCard, -2)
        listOf(Triple(SimulationMirror.LEFT, "왼쪽 미러 보기", "sim_mirror_left"),
            Triple(SimulationMirror.RIGHT, "오른쪽 미러 보기", "sim_mirror_right")).forEach { (mirror, title, key) ->
            val control = button(title, key, true) {
                if (canInput()) { session?.lookMirror(mirror)?.let(::consume); Log.i("DrivingSim", "input mirror=$mirror scenario=$selected") }
            }
            mirrorButtons[mirror] = control; inputControls.add(control); mirrors.addView(control, weighted(-2))
        }

        val controls = PreparationColumns(context, 1.2f, 1f, 1_050)
        pageBody.addView(controls, full(-2).apply { topMargin = dp(12) })
        val wheel = card(16); controls.addView(wheel)
        steeringLabel = label("가상 핸들 · 중앙", 20, ink, true).also { wheel.addView(it, full(-2).apply { bottomMargin = dp(8) }) }
        val steeringRow = horizontal(wheel, -2)
        steering = SeekBar(context).apply {
            max = 200; progress = 100; contentDescription = "sim_steering"; tag = "sim_steering"
            progressTintList = android.content.res.ColorStateList.valueOf(teal); thumbTintList = progressTintList
            setPadding(dp(16), 0, dp(16), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !syncingControls && canInput()) session?.setSteering((progress - 100) / 100.0)?.let(::consume)
                }
            })
        }.also { inputControls.add(it); steeringRow.addView(it, weighted(64)) }
        val center = button("핸들 중앙", "sim_center", true) { if (canInput()) session?.setSteering(0.0)?.let(::consume) }
        inputControls.add(center); steeringRow.addView(center, fixed(140, -2))
        val pedals = card(16); controls.addView(pedals)
        pedals.addView(label("가상 페달 · 누르는 동안 조작", 19, muted), full(-2).apply { bottomMargin = dp(8) })
        val pedalRow = horizontal(pedals, -2)
        brakeButton = pedal("브레이크", "sim_brake", false).also { pedalRow.addView(it, weighted(-2)) }
        gasButton = pedal("가속", "sim_gas", true).also { pedalRow.addView(it, weighted(-2)) }
        snapshot?.let(::updateScene)
    }

    private fun reflectionPage() {
        val record = reviewRecord ?: records.firstOrNull()
        val heading = horizontal(this, -2)
        heading.addView(label("차로 변경 돌아보기", 32, ink, true), weighted(-2))
        heading.addView(button("나가기", "sim_exit", true) { exit() }, fixed(116, -2))
        val body = scrollBody()
        val summary = card(24, tealSoft)
        body.addView(summary, full(-2))
        if (record == null) summary.addView(label("다음 연습에서 한 동작씩 함께 해 봐요.", 23, ink), full(-2))
        else {
            val scenario = runCatching { SimulationScenario.valueOf(record.optString("scenario")) }.getOrDefault(selected)
            summary.addView(label("${scenarioTitle(scenario)} · ${record.optString("outcome")}", 26, teal, true), full(-2))
            summary.addView(label("${date(record.optLong("at"))} · 가상 주행 ${record.optDouble("elapsedSeconds", 0.0).roundToInt()}초", 19, muted), full(-2).apply { topMargin = dp(12) })
            val notes = record.optJSONArray("reflections") ?: JSONArray()
            val results = card()
            body.addView(results, full(-2).apply { topMargin = dp(20) })
            results.addView(label("이번에 확인한 움직임", 24, ink, true), full(-2).apply { bottomMargin = dp(12) })
            for (i in 0 until minOf(notes.length(), 5)) {
                val note = horizontal(results, -2).apply { setPadding(0, dp(14), 0, dp(14)) }
                note.addView(label(String.format(Locale.ROOT, "%02d", i + 1), 21, teal, true), fixed(54, -2))
                note.addView(label(notes.optString(i), 21, ink), weighted(-2))
            }
        }
        body.addView(label("화면 속 조작을 돌아보는 기록이에요. 실제 운전 실력 평가와는 구분해요.", 18, muted), full(-2).apply { topMargin = dp(18) })
        if (records.size > 1) {
            body.addView(label("회고 ${reviewIndex + 1} / ${records.size} · 최근 20회", 19, muted), full(-2).apply { topMargin = dp(18) })
            val history = horizontal(body, -2)
            history.addView(button("이전 기록", "sim_history_older", true) {
                if (reviewIndex + 1 < records.size) { reviewIndex++; reviewRecord = records[reviewIndex]; render() }
            }.apply { isEnabled = reviewIndex + 1 < records.size }, weighted(-2))
            history.addView(button("다음 기록", "sim_history_newer", true) {
                if (reviewIndex > 0) { reviewIndex--; reviewRecord = records[reviewIndex]; render() }
            }.apply { isEnabled = reviewIndex > 0 }, weighted(-2))
        }
        storageProblem?.let { body.addView(label(it, 19, amber), full(-2)) }
        val actions = PreparationColumns(context, breakpoint = 800)
        addView(actions, full(-2).apply { topMargin = dp(16) })
        startButton = button("같은 코스 다시 연습", "sim_restart") {
            selected = runCatching { SimulationScenario.valueOf(record?.optString("scenario").orEmpty()) }.getOrDefault(selected)
            begin()
        }.also { actions.addView(it) }
        actions.addView(button("코스 선택으로", "sim_courses", true) { showBrief() })
    }

    private fun begin() {
        if (!active || closed) return
        stopVoice(); main.removeCallbacks(ticker)
        session = DrivingSimulationSession(selected)
        snapshot = session!!.snapshot()
        gasHeld = false; brakeHeld = false; inputGeneration++
        startedAtMillis = System.currentTimeMillis(); saved = false
        lastSpokenRevision = -1L; lastLoggedPhase = null; lastLoggedSignal = null
        lastLoggedSpeed = Double.NaN; lastLoggedLane = Double.NaN
        phaseEvents = JSONArray(); reviewRecord = null; reviewIndex = 0
        page = Page.DRIVE; running = true
        if (recordEnabled()) prefs.edit().putString("selected", selected.name).putString("last_status", "running").apply()
        render()
        consume(snapshot!!)
        lastTickMillis = SystemClock.elapsedRealtime()
        main.postDelayed(ticker, 33)
    }

    private fun consume(value: DrivingSimulationSnapshot) {
        snapshot = value
        observeChanges(value)
        updateScene(value)
        if (canInput() && value.coachRevision != lastSpokenRevision) {
            lastSpokenRevision = value.coachRevision
            stopVoice(); onCue(value.coachCue)
        }
        if (page == Page.DRIVE && (value.completed || value.failed)) {
            running = false; main.removeCallbacks(ticker)
            releaseHeldInputs()
            saveRecord(value, if (value.completed) "연습 마침" else "함께 다시 연습")
            reviewRecord = records.firstOrNull(); reviewIndex = 0
            page = Page.REFLECTION
            render()
        }
    }

    private fun updateScene(value: DrivingSimulationSnapshot) {
        road?.update(value, !running || !active || value.paused)
        phaseLabel?.setTextIfChanged(if (running && active) {
            "${scenarioTitle(value.scenario)}  ·  ${phaseTitle(value.phase)}"
        } else "잠시 멈췄어요 · 이어하기를 눌러주세요")
        cueLabel?.setTextIfChanged(value.coachCue.text)
        val amount = (abs(value.steering) * 100).roundToInt()
        steeringLabel?.setTextIfChanged(when {
            amount <= 2 -> "가상 핸들 · 중앙"
            value.steering < 0 -> "가상 핸들 · 왼쪽 ${amount}%"
            else -> "가상 핸들 · 오른쪽 ${amount}%"
        })
        syncingControls = true
        steering?.progress = (100 + value.steering * 100).roundToInt().coerceIn(0, 200)
        syncingControls = false
        signalButtons.forEach { (signal, control) ->
            styleButton(control, selected = value.signal == signal)
        }
        val targetMirror = if (value.targetLane < 0) SimulationMirror.LEFT else SimulationMirror.RIGHT
        mirrorButtons.forEach { (mirror, control) ->
            val checked = value.mirrorChecked && mirror == targetMirror
            control.setTextIfChanged((if (mirror == SimulationMirror.LEFT) "왼쪽 미러 " else "오른쪽 미러 ") + if (checked) "확인 ✓" else "보기")
            styleButton(control, selected = checked)
        }
        gasButton?.let { styleButton(it, gasHeld) }
        brakeButton?.let { styleButton(it, brakeHeld) }
        updateEnabled()
    }

    private fun pausePractice() {
        running = false; main.removeCallbacks(ticker)
        releaseHeldInputs()
        session?.pause()?.let { snapshot = it; updateScene(it) }
        stopVoice()
        if (page == Page.DRIVE) {
            if (recordEnabled()) prefs.edit().putString("last_status", "paused").apply()
            Log.i("DrivingSim", "paused scenario=$selected phase=${snapshot?.phase}")
        }
        updateEnabled()
    }

    private fun resumePractice() {
        if (!active || closed || page != Page.DRIVE || running) return
        val current = session ?: return
        releaseHeldInputs()
        running = true
        consume(current.resume())
        if (recordEnabled()) prefs.edit().putString("last_status", "running").apply()
        lastTickMillis = SystemClock.elapsedRealtime()
        main.removeCallbacks(ticker); main.postDelayed(ticker, 33)
        Log.i("DrivingSim", "resumed scenario=$selected phase=${snapshot?.phase}")
    }

    private fun finishPractice(outcome: String) {
        pausePractice()
        snapshot?.let { saveRecord(it, outcome) }
        reviewRecord = records.firstOrNull(); reviewIndex = 0
        page = Page.REFLECTION; render()
    }

    private fun showBrief() {
        pausePractice()
        session = null; snapshot = null; page = Page.BRIEF
        render()
    }

    private fun exit() {
        pausePractice()
        if (page == Page.DRIVE) snapshot?.let { saveRecord(it, "중간에 마침") }
        session = null; snapshot = null; page = Page.BRIEF
        render(); onExit()
    }

    private fun releaseHeldInputs() {
        inputGeneration++; gasHeld = false; brakeHeld = false
        session?.releaseInputs()?.let { snapshot = it }
    }

    private fun pedal(title: String, key: String, gas: Boolean): Button {
        val control = button(title, key, secondary = true) {
            if (!suppressPedalClick && canInput()) {
                // Accessibility clicks have no hold duration, so apply a short local pulse.
                val generation = inputGeneration
                if (gas) gasHeld = true else brakeHeld = true
                applyPedals()
                main.postDelayed({
                    if (generation == inputGeneration) {
                        if (gas) gasHeld = false else brakeHeld = false
                        applyPedals()
                    }
                }, 300)
            }
        }.apply { textSize = 21f }
        control.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (canInput()) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        if (gas) gasHeld = true else brakeHeld = true
                        view.isPressed = true; applyPedals()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gas) gasHeld = false else brakeHeld = false
                    view.isPressed = false; applyPedals()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        suppressPedalClick = true; view.performClick(); suppressPedalClick = false
                    }
                    true
                }
                else -> true
            }
        }
        inputControls.add(control)
        return control
    }

    private fun applyPedals() {
        if (!canInput()) return
        session?.setPedals(if (gasHeld) 1.0 else 0.0, if (brakeHeld) 1.0 else 0.0)?.let(::consume)
    }

    private fun canInput(): Boolean = active && running && !closed && page == Page.DRIVE

    private fun updateEnabled() {
        inputControls.forEach { it.isEnabled = canInput(); it.alpha = 1f }
        startButton?.isEnabled = active && !closed
        repeatButton?.isEnabled = canInput()
        pauseButton?.apply { visibility = if (running) View.VISIBLE else View.GONE; isEnabled = canInput() }
        resumeButton?.apply { visibility = if (running) View.GONE else View.VISIBLE; isEnabled = active && !closed }
    }

    private fun observeChanges(value: DrivingSimulationSnapshot) {
        val phaseChanged = value.phase != lastLoggedPhase
        if (phaseChanged) {
            phaseEvents.put(JSONObject().put("phase", value.phase.name)
                .put("elapsedSeconds", value.elapsedSeconds)
                .put("speedKmh", value.speedKmh).put("lanePosition", value.lanePosition))
        }
        if (phaseChanged || value.signal != lastLoggedSignal || lastLoggedSpeed.isNaN() ||
            abs(value.speedKmh - lastLoggedSpeed) >= 5.0 || lastLoggedLane.isNaN() ||
            abs(value.lanePosition - lastLoggedLane) >= 0.25) {
            Log.i("DrivingSim", String.format(Locale.ROOT,
                "scenario=%s phase=%s speed=%.1f lane=%.2f steering=%.2f signal=%s cue=%d",
                value.scenario, value.phase, value.speedKmh, value.lanePosition, value.steering,
                value.signal, value.coachRevision))
            lastLoggedPhase = value.phase; lastLoggedSpeed = value.speedKmh
            lastLoggedLane = value.lanePosition; lastLoggedSignal = value.signal
        }
    }

    private fun saveRecord(value: DrivingSimulationSnapshot, outcome: String) {
        if (saved) return
        val notes = value.reflections.filter { it.isNotBlank() }.distinct().take(5).toMutableList()
        if (notes.size < 3 && !value.completed) notes.add("${phaseTitle(value.phase)} 단계까지 함께했어요. 다음에는 이 움직임부터 천천히 이어가요.")
        if (notes.size < 3) notes.add("신호를 켠 뒤 미러를 확인하고, 옆 공간을 살펴보는 순서를 기억해요.")
        if (notes.size < 3) notes.add("차로에 들어선 뒤 핸들을 중앙으로 돌리고 깜빡이를 꺼 마무리해요.")
        val interventions = JSONArray()
        value.interventions.forEach {
            interventions.put(JSONObject().put("kind", it.kind.name)
                .put("elapsedSeconds", it.atElapsedSeconds).put("cue", it.cue.text))
        }
        val record = JSONObject().put("at", startedAtMillis).put("scenario", value.scenario.name)
            .put("outcome", outcome).put("elapsedSeconds", value.elapsedSeconds)
            .put("phase", value.phase.name).put("distanceM", value.distanceM)
            .put("speedKmh", value.speedKmh).put("lanePosition", value.lanePosition)
            .put("reflections", JSONArray(notes)).put("interventions", interventions)
            .put("phaseEvents", phaseEvents)
        records = (listOf(record) + if (recordEnabled()) loadRecords() else records).take(20)
        val persisted = !recordEnabled() || runCatching {
            prefs.edit().putString("records", JSONArray(records).toString())
                .putString("last_status", if (value.completed) "completed" else "finished")
                .putString("selected", selected.name).commit()
        }.getOrDefault(false)
        if (!persisted) storageProblem = "이번 회고를 기기에 저장하지 못했어요. 이 화면에서는 확인할 수 있어요."
        saved = true
        Log.i("DrivingSim", "record consent=${recordEnabled()} scenario=${value.scenario} outcome=$outcome phase=${value.phase} events=${phaseEvents.length()}")
    }

    private fun loadRecords(): List<JSONObject> = runCatching {
        val array = JSONArray(prefs.getString("records", "[]"))
        (0 until minOf(array.length(), 20)).mapNotNull { array.optJSONObject(it) }
    }.getOrElse {
        storageProblem = "이전 회고를 불러오지 못했어요. 새 연습은 시작할 수 있어요."
        emptyList()
    }

    private fun phaseTitle(phase: SimulationPhase): String = when (phase) {
        SimulationPhase.SIGNAL -> "방향 알리기"
        SimulationPhase.MIRROR -> "미러 확인"
        SimulationPhase.WAIT_FOR_GAP -> "옆 공간 기다리기"
        SimulationPhase.STEER -> "차로 옮기기"
        SimulationPhase.CENTER -> "핸들 중앙 · 신호 끄기"
        SimulationPhase.COMPLETE -> "연습 마침"
        SimulationPhase.FAILED -> "함께 다시 연습"
    }

    private fun scenarioTitle(scenario: SimulationScenario): String = when (scenario) {
        SimulationScenario.LEFT_LANE -> "왼쪽 차로 변경"
        SimulationScenario.RIGHT_LANE -> "오른쪽 차로 변경"
        SimulationScenario.BLIND_SPOT -> "옆 차 기다리기"
    }

    private fun card(padding: Int = 24, fill: Int = PleosUi.SURFACE) = LinearLayout(context).apply {
        orientation = VERTICAL; background = PreparationStyle.surface(context, fill)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun scrollBody(): LinearLayout {
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(18) })
        return LinearLayout(context).apply { orientation = VERTICAL; scroll.addView(this) }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(title: String, key: String, secondary: Boolean = false, action: () -> Unit) = PracticeButton(context).apply {
        text = title; textSize = 20f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(64); minimumHeight = dp(64)
        setPadding(dp(18), dp(12), dp(18), dp(12)); gravity = Gravity.CENTER
        contentDescription = key; tag = key
        setTypeface(typeface, Typeface.BOLD)
        styleButton(this, !secondary, force = true)
        setOnClickListener { action() }
    }

    private fun styleButton(button: Button, selected: Boolean, force: Boolean = false) {
        if (!force && button.background is RippleDrawable && button.isSelected == selected) return
        button.isSelected = selected
        PreparationStyle.button(button, selected)
    }

    private fun horizontal(parent: LinearLayout, height: Int): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
        parent.addView(this, full(height))
    }

    private fun weighted(height: Int = LayoutParams.MATCH_PARENT, weight: Float = 1f) =
        LayoutParams(0, if (height < 0) height else dp(height), weight).apply { marginEnd = dp(6) }

    private fun fixed(width: Int, height: Int) = LayoutParams(dp(width), if (height < 0) height else dp(height)).apply { marginEnd = dp(6) }
    private fun full(height: Int) = LayoutParams(LayoutParams.MATCH_PARENT, if (height < 0) height else dp(height))
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
    private fun date(millis: Long): String = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(millis))
    private fun TextView.setTextIfChanged(value: String) { if (text.toString() != value) text = value }
}
