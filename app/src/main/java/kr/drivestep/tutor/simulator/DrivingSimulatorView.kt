package kr.drivestep.tutor.simulator

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val teal = PleosUi.SELECTED
    private val tealSoft = PleosUi.SURFACE
    private val line = PleosUi.LINE
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
        setPadding(dp(8), dp(3), dp(8), dp(6))
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
        val heading = horizontal(this, 52)
        heading.addView(label("차로 변경 연습", 23, ink, true), weighted())
        heading.addView(badge(), LayoutParams(dp(240), dp(30)))
        heading.addView(button("나가기", "sim_exit", secondary = true) { exit() }, fixed(82, 48))

        val preview = CockpitRoadView(context)
        preview.update(DrivingSimulationSession(selected).snapshot(), false)
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6); bottomMargin = dp(10)
        })
        addView(label("신호를 켜고, 미러를 본 뒤, 핸들로 차로를 옮겨 봐요.", 20, ink, true), full(36))
        addView(label("화면 속 차는 40 km/h로 출발해요. 페달에서 손을 떼면 현재 가상 속도를 유지해요.", 14, muted), full(27))
        val choices = horizontal(this, 72)
        listOf(
            Triple(SimulationScenario.LEFT_LANE, "왼쪽 차로 변경\n신호 · 왼쪽 미러 · 핸들", "sim_start_left"),
            Triple(SimulationScenario.RIGHT_LANE, "오른쪽 차로 변경\n신호 · 오른쪽 미러 · 핸들", "sim_start_right"),
            Triple(SimulationScenario.BLIND_SPOT, "옆 차를 기다린 뒤 변경\n미러 관찰 · 기다림 · 핸들", "sim_start_blindspot"),
        ).forEach { (scenario, title, key) ->
            val choice = button(title, key, secondary = selected != scenario) {
                selected = scenario
                if (recordEnabled()) prefs.edit().putString("selected", selected.name).apply()
                render()
            }.apply { textSize = 16f }
            choices.addView(choice, weighted(62))
        }
        val actions = horizontal(this, 52)
        startButton = button("${scenarioTitle(selected)} 시작", "sim_begin") { begin() }.also {
            actions.addView(it, weighted(48, 1.5f))
        }
        if (records.isNotEmpty()) {
            actions.addView(button("지난 회고 · ${records.size}회", "sim_history", secondary = true) {
                reviewIndex = 0; reviewRecord = records.first(); page = Page.REFLECTION; render()
            }, weighted(48))
            actions.addView(button("주행 기록 삭제", "sim_delete_history", secondary = true) {
                confirmDeleteHistory()
            }, fixed(132, 48))
        }
        val lastStatus = prefs.getString("last_status", "")
        val note = storageProblem ?: when {
            lastStatus == "running" || lastStatus == "paused" -> "이전 연습은 멈춘 상태예요. 시작을 누르면 새로 연습해요."
            records.isNotEmpty() -> if (recordEnabled()) "최근 ${records.size}회 회고 · 실제 운전 점수와 구분해요." else "기록 저장 꺼짐 · 이번 회고는 앱을 종료하면 사라져요."
            else -> "도로와 조작은 모두 화면 속 시뮬레이션이에요. 실제 차는 P·정차를 유지해요."
        }
        addView(label(note, 13, muted), full(25))
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
        dialog.show()
    }

    private fun dismissDeleteDialog() {
        deleteDialog?.dismiss(); deleteDialog = null
    }

    private fun drivingPage() {
        val heading = horizontal(this, 52)
        phaseLabel = label("", 16, teal, true).also { heading.addView(it, weighted()) }
        heading.addView(badge(), fixed(230, 28))
        pauseButton = button("잠시 멈춤", "sim_pause", secondary = true) { pausePractice() }.also {
            heading.addView(it, fixed(92, 48))
        }
        resumeButton = button("이어하기", "sim_resume") { resumePractice() }.also {
            heading.addView(it, fixed(92, 48))
        }
        heading.addView(button("마치기", "sim_finish", secondary = true) {
            finishPractice("중간에 마침")
        }, fixed(72, 48))
        heading.addView(button("나가기", "sim_exit", secondary = true) { exit() }, fixed(72, 48))

        road = CockpitRoadView(context).also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4); bottomMargin = dp(6)
            })
        }

        val coaching = horizontal(this, 58).apply {
            background = background(tealSoft, 12)
            setPadding(dp(12), 0, dp(8), 0)
        }
        coaching.addView(label("안내", 13, teal, true), fixed(55, 48))
        cueLabel = label("", 19, ink, true).apply { maxLines = 2 }.also {
            coaching.addView(it, weighted(LayoutParams.MATCH_PARENT))
        }
        repeatButton = button("다시 듣기", "sim_repeat", secondary = true) {
            if (canInput()) snapshot?.coachCue?.let { stopVoice(); onCue(it) }
        }.also { coaching.addView(it, fixed(88, 48)) }

        val signals = horizontal(this, 58)
        signals.addView(label("가상\n깜빡이", 13, muted, true), fixed(52, 48))
        listOf(
            Triple(SimulationSignal.LEFT, "← 왼쪽", "sim_signal_left"),
            Triple(SimulationSignal.OFF, "꺼짐", "sim_signal_off"),
            Triple(SimulationSignal.RIGHT, "오른쪽 →", "sim_signal_right"),
        ).forEach { (signal, title, key) ->
            val control = button(title, key, secondary = true) {
                if (canInput()) session?.setSignal(signal)?.let(::consume)
            }
            signalButtons[signal] = control; inputControls.add(control)
            signals.addView(control, weighted(48))
        }
        signals.addView(label("가상\n미러", 13, muted, true), fixed(48, 48))
        listOf(
            Triple(SimulationMirror.LEFT, "왼쪽 미러 보기", "sim_mirror_left"),
            Triple(SimulationMirror.RIGHT, "오른쪽 미러 보기", "sim_mirror_right"),
        ).forEach { (mirror, title, key) ->
            val control = button(title, key, secondary = true) {
                if (canInput()) {
                    session?.lookMirror(mirror)?.let(::consume)
                    Log.i("DrivingSim", "input mirror=$mirror scenario=$selected")
                }
            }
            mirrorButtons[mirror] = control; inputControls.add(control)
            signals.addView(control, weighted(48, 1.2f))
        }

        val controls = horizontal(this, 86)
        val steeringColumn = LinearLayout(context).apply { orientation = VERTICAL }
        controls.addView(steeringColumn, weighted(LayoutParams.MATCH_PARENT, 1.35f))
        steeringLabel = label("가상 핸들 · 중앙", 15, ink, true).also {
            steeringColumn.addView(it, full(28))
        }
        val steeringRow = horizontal(steeringColumn, 52)
        val center = button("핸들 중앙", "sim_center", secondary = true) {
            if (canInput()) session?.setSteering(0.0)?.let(::consume)
        }
        inputControls.add(center)
        steering = SeekBar(context).apply {
            max = 200; progress = 100
            contentDescription = "sim_steering"; tag = "sim_steering"
            setPadding(dp(16), 0, dp(16), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !syncingControls && canInput()) {
                        session?.setSteering((progress - 100) / 100.0)?.let(::consume)
                    }
                }
            })
        }.also {
            inputControls.add(it)
            steeringRow.addView(it, weighted(48))
        }
        steeringRow.addView(center, fixed(100, 48))
        val pedals = LinearLayout(context).apply { orientation = VERTICAL }
        controls.addView(pedals, weighted(LayoutParams.MATCH_PARENT, 1f))
        pedals.addView(label("가상 페달 · 손을 떼면 현재 속도 유지", 13, muted), full(28))
        val pedalRow = horizontal(pedals, 52)
        brakeButton = pedal("브레이크 · 길게 누르기", "sim_brake", false).also {
            pedalRow.addView(it, weighted(50))
        }
        gasButton = pedal("가속 · 길게 누르기", "sim_gas", true).also {
            pedalRow.addView(it, weighted(50))
        }
        snapshot?.let { updateScene(it) }
    }

    private fun reflectionPage() {
        val record = reviewRecord ?: records.firstOrNull()
        val heading = horizontal(this, 52)
        heading.addView(label("방금의 움직임을 돌아봐요", 25, ink, true), weighted())
        heading.addView(badge(), fixed(240, 30))
        heading.addView(button("나가기", "sim_exit", secondary = true) { exit() }, fixed(85, 48))
        if (record == null) {
            addView(label("다음 연습에서 한 동작씩 함께 해 봐요.", 20, ink), full(80))
        } else {
            val scenario = runCatching {
                SimulationScenario.valueOf(record.optString("scenario"))
            }.getOrDefault(selected)
            val seconds = record.optDouble("elapsedSeconds", 0.0).roundToInt()
            addView(label("${scenarioTitle(scenario)} · ${record.optString("outcome")} · ${seconds}초", 17, teal, true), full(37))
            addView(label("${date(record.optLong("at"))}  ·  화면 속 가상 주행 기록", 13, muted), full(28))
            val notes = record.optJSONArray("reflections") ?: JSONArray()
            val notesColumn = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(notesColumn, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            for (i in 0 until minOf(notes.length(), 5)) {
                val note = horizontal(notesColumn, 64).apply {
                    background = background(if (i % 2 == 0) PleosUi.SURFACE else tealSoft, 12)
                    setPadding(dp(15), 0, dp(15), 0)
                }
                note.addView(label(String.format(Locale.ROOT, "%02d", i + 1), 18, teal, true), fixed(42, 50))
                note.addView(label(notes.optString(i), 19, ink).apply { maxLines = 2 }, weighted(60))
            }
        }
        if (records.size > 1) {
            val history = horizontal(this, 52)
            history.addView(label("회고 ${reviewIndex + 1} / ${records.size} · 최근 20회까지 보관", 13, muted), weighted())
            history.addView(button("이전 기록", "sim_history_older", secondary = true) {
                if (reviewIndex + 1 < records.size) {
                    reviewIndex++; reviewRecord = records[reviewIndex]; render()
                }
            }.apply { isEnabled = reviewIndex + 1 < records.size }, fixed(94, 48))
            history.addView(button("다음 기록", "sim_history_newer", secondary = true) {
                if (reviewIndex > 0) { reviewIndex--; reviewRecord = records[reviewIndex]; render() }
            }.apply { isEnabled = reviewIndex > 0 }, fixed(94, 48))
        }
        storageProblem?.let { addView(label(it, 13, amber), full(28)) }
        val actions = horizontal(this, 56)
        startButton = button("같은 코스 다시 연습", "sim_restart") {
            val savedScenario = record?.optString("scenario")
            selected = runCatching { SimulationScenario.valueOf(savedScenario.orEmpty()) }.getOrDefault(selected)
            begin()
        }.also { actions.addView(it, weighted(50)) }
        actions.addView(button("코스 선택으로", "sim_courses", secondary = true) { showBrief() }, weighted(50))
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
        }.apply { textSize = 14f }
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
        inputControls.forEach { it.isEnabled = canInput(); it.alpha = if (it.isEnabled) 1f else 0.45f }
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

    private fun badge() = label("실제 차 P·정차  |  화면 속 가상 주행", 12, amber, true).apply {
        gravity = Gravity.CENTER
        background = background(PleosUi.RAISED, 9)
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(title: String, key: String, secondary: Boolean = false, action: () -> Unit) = PracticeButton(context).apply {
        text = title; textSize = 17f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(7), 0, dp(7), 0); gravity = Gravity.CENTER
        contentDescription = key; tag = key
        setTypeface(typeface, Typeface.BOLD)
        styleButton(this, !secondary)
        setOnClickListener { action() }
    }

    private fun styleButton(button: Button, selected: Boolean) {
        PleosUi.decorate(button, selected)
    }

    private fun background(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun horizontal(parent: LinearLayout, height: Int): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, full(height))
    }

    private fun weighted(height: Int = LayoutParams.MATCH_PARENT, weight: Float = 1f) =
        LayoutParams(0, if (height < 0) height else dp(height), weight).apply { marginEnd = dp(6) }

    private fun fixed(width: Int, height: Int) = LayoutParams(dp(width), dp(height)).apply { marginEnd = dp(6) }
    private fun full(height: Int) = LayoutParams(LayoutParams.MATCH_PARENT, if (height < 0) height else dp(height))
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
    private fun date(millis: Long): String = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(millis))
    private fun TextView.setTextIfChanged(value: String) { if (text.toString() != value) text = value }
}
