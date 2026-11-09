package kr.drivestep.tutor.ride

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
import android.widget.Switch
import android.widget.TextView
import kr.drivestep.tutor.coach.CoachCue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Parked-only, isolated driving preview controlled by virtual pedals/gears/steering.
 * No vehicle API is used here. Inputs are held locally and are released on exit/pause.
 * MainActivity owns the actual P/UX gate and must call setActive(false) on screen exit.
 */
// Created in code with required host/session arguments; never inflated from XML.
// This automotive screen uses the platform Activity/theme, not AppCompat widgets.
@SuppressLint("ViewConstructor", "UseSwitchCompatOrMaterialCode")
class DriveCoachView(
    context: Context,
    private val onCue: (CoachCue) -> Unit,
    private val stopVoice: () -> Unit,
    private val onExit: () -> Unit,
) : LinearLayout(context) {
    private enum class Page { COURSES, DRIVE, REFLECTION }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("drivestep_ride_preview_v1", Context.MODE_PRIVATE)
    private var page = Page.COURSES
    private var selected = RideLesson.START_STOP
    private var session: DriveLessonSession? = null
    private var snapshot: RideSnapshot? = null
    private var active = false
    private var running = false
    private var closed = false
    private var gasTouch = false
    private var brakeTouch = false
    private var brakeLatched = false
    private var syncingControls = false
    private var lastTickMillis = 0L
    private var startedAtMillis = 0L
    private var saved = false
    private var lastFeedbackRevision = -1L
    private var lastLoggedPhase: RidePhase? = null
    private var inputGeneration = 0L
    private var storageProblem: String? = null
    private var records = readRecords()

    private var roadView: RoadView? = null
    private var instruction: TextView? = null
    private var mentorText: TextView? = null
    private var dashboard: TextView? = null
    private var steeringLabel: TextView? = null
    private var pauseLabel: TextView? = null
    private var gasButton: Button? = null
    private var brakeButton: Button? = null
    private var holdSwitch: Switch? = null
    private var steeringSlider: SeekBar? = null
    private var resumeButton: Button? = null
    private var pauseButton: Button? = null
    private var startButton: Button? = null
    private val gearButtons = mutableMapOf<VirtualGear, Button>()
    private val ink = Color.rgb(24, 45, 50)
    private val muted = Color.rgb(89, 111, 113)
    private val teal = Color.rgb(8, 127, 113)
    private val soft = Color.rgb(225, 242, 235)
    private val amber = Color.rgb(154, 99, 35)

    private val ticker = object : Runnable {
        override fun run() {
            if (!running || !active || !isAttachedToWindow || closed || page != Page.DRIVE) return
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - lastTickMillis) / 1000.0).coerceIn(0.0, 0.25)
            lastTickMillis = now
            session?.let { applySnapshot(it.tick(dt)) }
            if (running && page == Page.DRIVE) main.postDelayed(this, 50)
        }
    }

    init {
        orientation = VERTICAL
        render()
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (!value) pausePractice(forceStopVoice = true)
        updateEnabled()
    }

    /** Returns true when handled inside this preview, false at its course menu. */
    fun goBack(): Boolean {
        if (page == Page.COURSES) return false
        leaveCourse()
        return true
    }

    fun close() {
        if (closed) return
        pausePractice()
        if (page == Page.DRIVE) saveReflection("중간에 마침")
        closed = true
        main.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() {
        pausePractice(forceStopVoice = active)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateEnabled() // A detached practice always waits for explicit Resume.
    }

    private fun render() {
        roadView = null; instruction = null; mentorText = null; dashboard = null
        steeringLabel = null; pauseLabel = null; gasButton = null; brakeButton = null
        holdSwitch = null; steeringSlider = null; resumeButton = null; pauseButton = null; startButton = null
        gearButtons.clear()
        removeAllViews()
        when (page) {
            Page.COURSES -> courses()
            Page.DRIVE -> drive()
            Page.REFLECTION -> reflection()
        }
        updateEnabled()
    }

    private fun courses() {
        addView(label("제가 옆에서 함께할게요", 29, ink, true))
        addView(label("정답을 고르는 대신, 직접 조작하고 코치의 안내를 들어요.", 17, muted))
        gap(this, 10)
        val choiceRow = row(this)
        for (lesson in RideLesson.entries) {
            val chosen = lesson == selected
            val title = if (lesson == RideLesson.START_STOP) "출발하고 멈추기" else "횡단보도 앞에서 기다리기"
            val desc = if (lesson == RideLesson.START_STOP) "브레이크 · 기어 · 가속 · 정지" else "감속 · 보행자 · 신호 · 재출발"
            val key = if (lesson == RideLesson.START_STOP) "ride_start_stop" else "ride_crosswalk"
            val button = button("$title\n$desc", key, secondary = !chosen) {
                selected = lesson; stopVoice(); render()
            }
            choiceRow.addView(button, weight(88))
        }
        gap(this, 12)
        val explanation = card(this)
        explanation.addView(label(if (selected == RideLesson.START_STOP) "첫 출발을 함께 연습해요" else "이번에는 멈추고 기다려 봐요", 23, ink, true))
        explanation.addView(label(if (selected == RideLesson.START_STOP)
            "먼저 브레이크를 유지하고 D를 선택해요. 가속으로 출발한 다음, 코치가 목표선을 알려주면 브레이크로 멈추고 P로 마무리해요."
            else "화면 속 가상 차량이 천천히 움직이며 시작해요. 브레이크로 멈춘 뒤 보행자와 신호를 기다리고, 코치 안내를 듣고 다시 출발해요.", 19, ink))
        gap(explanation, 8)
        explanation.addView(label("가속·브레이크는 누르고 있는 동안만 작동해요. 마우스 하나로 기어를 고를 때는 ‘가상 브레이크 유지’를 켜세요.", 16, teal))
        explanation.addView(label("가상 입력만 사용합니다. 실제 차량은 P·정차 상태를 유지하며, 이 화면이 차량을 제어하지 않습니다.", 15, amber))
        startButton = button("코치와 연수 시작", "ride_start") { startCourse() }.also { addView(it) }
        addView(button("이전 메뉴로", "ride_exit", secondary = true) { exitPreview() })
        gap(this, 16)
        addView(label("지난 연습에서 익힌 것", 21, ink, true))
        if (records.isEmpty()) addView(label(storageProblem ?: "연습을 마치면 조작 과정의 회고가 이 차량에 남아요. 점수나 순위는 매기지 않아요.", 15, muted))
        records.take(5).forEach { record ->
            val name = if (record.optString("lesson") == RideLesson.START_STOP.name) "출발·정지" else "횡단보도·신호"
            addView(label("${date(record.optLong("at"))}  $name · ${record.optString("outcome")}", 16, ink))
            val notes = record.optJSONArray("reflection")
            addView(label(if (notes != null && notes.length() > 0) notes.optString(notes.length() - 1) else "다음에도 한 동작씩 이어가요.", 14, muted))
            gap(this, 6)
        }
        if (records.size > 5) addView(label("최근 5회 표시 · 이 기기에 최대 20회 보관", 13, muted))
    }

    private fun startCourse() {
        if (!active || closed) return
        stopVoice()
        session = DriveLessonSession(selected)
        snapshot = session!!.snapshot()
        gasTouch = false; brakeTouch = false; brakeLatched = false
        saved = false; startedAtMillis = System.currentTimeMillis(); lastFeedbackRevision = -1L
        lastLoggedPhase = null; inputGeneration++
        page = Page.DRIVE; running = true
        lastTickMillis = SystemClock.elapsedRealtime()
        render()
        applySnapshot(snapshot!!, forceCue = true)
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 50)
    }

    private fun drive() {
        val heading = row(this)
        heading.addView(label(if (selected == RideLesson.START_STOP) "출발부터 정지까지, 함께" else "멈추고 기다리는 연습", 26, ink, true), weight(46, 2f))
        heading.addView(button("이전 메뉴", "ride_exit", secondary = true) { exitPreview() }, weight(46, 0.7f))
        addView(label("가상 조작 전용 · 실제 차는 P·정차 유지 · 차량 제어 없음", 14, amber))
        gap(this, 8)
        val sceneRow = row(this)
        val scene = RoadView(context).also { roadView = it }
        sceneRow.addView(scene, weight(278, 1.35f))
        val coaching = card(sceneRow, attachAsColumn = true)
        coaching.addView(label("옆자리 코치 · 지금 할 조작", 15, teal, true))
        instruction = label("브레이크부터 천천히", 24, ink, true).also { coaching.addView(it) }
        gap(coaching, 8)
        mentorText = label("", 18, ink).also { coaching.addView(it) }
        gap(coaching, 10)
        dashboard = label("", 17, teal, true).also { coaching.addView(it) }
        pauseLabel = label("", 15, amber).also { coaching.addView(it) }
        gap(this, 8)
        val controls = row(this)
        val steeringColumn = LinearLayout(context).apply { orientation = VERTICAL }
        controls.addView(steeringColumn, weight(-2, 1.35f))
        steeringLabel = label("가상 핸들 · 중앙", 16, teal, true).also { steeringColumn.addView(it) }
        steeringSlider = SeekBar(context).apply {
            max = 100; progress = 50; contentDescription = "ride_steering"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && canInput()) session?.let { applySnapshot(it.setSteering((progress - 50) / 50.0)) }
                }
            })
        }.also { steeringColumn.addView(it, LayoutParams(-1, dp(44))) }
        val gears = row(steeringColumn)
        for (gear in VirtualGear.entries) {
            val key = "ride_gear_${gear.name.lowercase(Locale.ROOT)}"
            val view = button(gear.name, key, secondary = true) {
                if (canInput()) session?.let { applySnapshot(it.setGear(gear)) }
            }
            gearButtons[gear] = view
            gears.addView(view, LayoutParams(dp(62), dp(46)).apply { marginEnd = dp(5) })
        }
        holdSwitch = Switch(context).apply {
            text = "가상 브레이크 유지"; textSize = 15f; setTextColor(ink)
            contentDescription = "ride_brake_hold"
            isChecked = brakeLatched
            setOnCheckedChangeListener { _, checked ->
                if (!syncingControls && canInput()) {
                    brakeLatched = checked; sendPedals()
                }
            }
        }.also { gears.addView(it, LayoutParams(0, dp(46), 1f)) }
        steeringColumn.addView(label("유지는 마우스 체험용 눌림 고정 · 실차 AUTO HOLD와 무관", 12, muted))
        val pedalColumn = LinearLayout(context).apply { orientation = VERTICAL }
        controls.addView(pedalColumn, weight(-2, 1f))
        pedalColumn.addView(label("화면 페달을 누르고 있어요", 16, teal, true))
        val pedals = row(pedalColumn)
        brakeButton = pedal("브레이크\n누르고 있기", "ride_brake", gas = false).also { pedals.addView(it, weight(88)) }
        gasButton = pedal("가속\n누르고 있기", "ride_gas", gas = true).also { pedals.addView(it, weight(88)) }
        gap(this, 6)
        val commands = row(this)
        pauseButton = button("잠시 쉬기", "ride_pause", secondary = true) { pausePractice() }.also { commands.addView(it, weight(46)) }
        resumeButton = button("내가 준비되면 이어하기", "ride_resume") { resumePractice() }.also { commands.addView(it, weight(46)) }
        snapshot?.let { updateSnapshot(it) }
    }

    private fun pedal(title: String, key: String, gas: Boolean): Button {
        var suppressClick = false
        var touched = false
        var gestureGeneration = inputGeneration
        val view = button(title, key) {}
        fun set(value: Boolean) {
            if (gas) gasTouch = value else brakeTouch = value
            sendPedals()
        }
        view.setOnClickListener {
            // Accessibility click is a short virtual press; touch holding uses DOWN/UP.
            if (!suppressClick && canInput()) {
                val token = inputGeneration
                set(true)
                main.postDelayed({ if (token == inputGeneration && !touched) set(false) }, 350)
            }
        }
        view.setOnTouchListener { v, event ->
            if (event.actionMasked != MotionEvent.ACTION_DOWN && gestureGeneration != inputGeneration) {
                // A retry/pause invalidated this held finger. Wait for a fresh DOWN.
                touched = false; v.isPressed = false
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!canInput()) return@setOnTouchListener false
                    gestureGeneration = inputGeneration
                    touched = true; v.isPressed = true; set(true); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touched && (event.x < 0 || event.y < 0 || event.x > v.width || event.y > v.height)) {
                        touched = false; v.isPressed = false; set(false)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touched = false; v.isPressed = false; set(false)
                    suppressClick = true; v.performClick(); suppressClick = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touched = false; v.isPressed = false; set(false); true
                }
                else -> true
            }
        }
        return view
    }

    private fun sendPedals() {
        if (!canInput()) return
        session?.let { applySnapshot(it.setPedals(gasTouch, brakeTouch || brakeLatched)) }
    }

    private fun applySnapshot(value: RideSnapshot, forceCue: Boolean = false) {
        val previous = snapshot
        if (previous != null && value.retries > previous.retries) {
            inputGeneration++
            gasTouch = false; brakeTouch = false; brakeLatched = false
            gasButton?.isPressed = false; brakeButton?.isPressed = false
        }
        snapshot = value
        if (lastLoggedPhase != value.phase) {
            lastLoggedPhase = value.phase
            Log.d("DriveRide", "phase=${value.phase} revision=${value.feedbackRevision} speed=${value.speedKmh} distance=${value.distanceM} gear=${value.gear} lesson=${value.lesson}")
        }
        if ((forceCue || value.feedbackRevision != lastFeedbackRevision) && active && running) {
            lastFeedbackRevision = value.feedbackRevision
            stopVoice()
            onCue(value.coachCue)
        }
        if (value.completed && page == Page.DRIVE) {
            running = false; main.removeCallbacks(ticker)
            gasTouch = false; brakeTouch = false; brakeLatched = false
            saveReflection("끝까지 함께함")
            page = Page.REFLECTION
            render()
        } else updateSnapshot(value)
    }

    private fun updateSnapshot(value: RideSnapshot) {
        roadView?.update(value, !running, value.practiceSeconds)
        instruction?.setIfChanged(if (!running) "준비되면 이어가요" else instructionFor(value))
        mentorText?.setIfChanged(value.coachCue.text)
        dashboard?.setIfChanged("가상 ${String.format(Locale.KOREA, "%.1f", value.speedKmh)} km/h   ·   기어 ${value.gear.name}")
        pauseLabel?.setIfChanged(if (!running) "화면 입력을 해제했어요. 자동으로 다시 출발하지 않습니다." else "실제 차량 상태가 아닌 연습용 수치예요.")
        steeringLabel?.setIfChanged("가상 핸들 · " + if (kotlin.math.abs(value.steering) < .03) "중앙" else if (value.steering < 0) "왼쪽" else "오른쪽")
        syncingControls = true
        holdSwitch?.isChecked = brakeLatched
        val target = (value.steering * 50 + 50).toInt()
        if (steeringSlider?.progress != target) steeringSlider?.progress = target
        syncingControls = false
        for ((gear, button) in gearButtons) {
            val selected = gear == value.gear
            val style = "$selected:${canInput()}"
            if (button.tag != style) {
                button.tag = style
                button.background = rounded(if (selected) teal else soft)
                button.setTextColor(if (selected) Color.WHITE else teal)
            }
        }
        updateEnabled()
    }

    private fun instructionFor(value: RideSnapshot): String = when (value.phase) {
        RidePhase.READY_BRAKE -> "브레이크를 눌러요"
        RidePhase.READY_DRIVE -> "브레이크를 유지하고 D"
        RidePhase.DRIVE_TO_LINE -> if (value.stopLineM - value.distanceM <= 16) "가속을 놓고 브레이크" else "브레이크를 놓고 가속"
        RidePhase.STOP_AND_PARK -> "브레이크를 유지하고 P"
        RidePhase.APPROACH_CROSSWALK -> "브레이크로 멈춰요"
        RidePhase.WAIT_FOR_PEDESTRIAN -> "브레이크를 유지하며 기다려요"
        RidePhase.WAIT_FOR_GREEN -> "아직 적색 · 멈춘 채 기다려요"
        RidePhase.RESUME_AFTER_GREEN -> "주변 확인 후 가속해요"
        RidePhase.COMPLETED -> "한 동작씩 함께 해냈어요"
    }

    private fun pausePractice(forceStopVoice: Boolean = false) {
        val shouldStopVoice = forceStopVoice || running || page == Page.DRIVE
        // Commit paused/input state before external callbacks can re-enter the host.
        running = false
        main.removeCallbacks(ticker)
        inputGeneration++
        gasTouch = false; brakeTouch = false; brakeLatched = false
        gasButton?.isPressed = false; brakeButton?.isPressed = false
        session?.let { snapshot = it.setPedals(false, false) }
        if (shouldStopVoice) stopVoice()
        snapshot?.let(::updateSnapshot)
        updateEnabled()
    }

    private fun resumePractice() {
        if (!active || closed || page != Page.DRIVE || snapshot?.completed != false) return
        stopVoice()
        running = true
        lastTickMillis = SystemClock.elapsedRealtime()
        snapshot?.let { applySnapshot(it, forceCue = true) }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 50)
    }

    private fun canInput() = active && running && !closed && page == Page.DRIVE

    private fun updateEnabled() {
        val allowed = canInput()
        gasButton?.isEnabled = allowed; brakeButton?.isEnabled = allowed
        holdSwitch?.isEnabled = allowed; steeringSlider?.isEnabled = allowed
        gearButtons.values.forEach { it.isEnabled = allowed }
        startButton?.isEnabled = active && !closed
        pauseButton?.visibility = if (running) VISIBLE else GONE
        resumeButton?.visibility = if (running) GONE else VISIBLE
        resumeButton?.isEnabled = active && !closed
    }

    private fun leaveCourse() {
        pausePractice()
        if (page == Page.DRIVE) saveReflection("중간에 마침")
        session = null; snapshot = null; page = Page.COURSES
        stopVoice(); render()
    }

    private fun exitPreview() {
        pausePractice()
        if (page == Page.DRIVE) saveReflection("중간에 마침")
        session = null; snapshot = null; page = Page.COURSES
        stopVoice(); render(); onExit()
    }

    private fun reflection() {
        val value = snapshot ?: return
        addView(label("오늘은 여기까지 함께했어요", 28, ink, true))
        addView(label("정답 점수보다, 직접 해본 조작을 돌아봐요.", 17, muted))
        gap(this, 12)
        val panel = card(this)
        panel.addView(label(if (selected == RideLesson.START_STOP) "출발 · 감속 · 정지 · P" else "정지 · 기다림 · 신호 확인 · 재출발", 22, teal, true))
        value.reflection.forEach { panel.addView(label("• $it", 18, ink)) }
        if (value.interventions > 0 || value.retries > 0) {
            gap(panel, 8)
            panel.addView(label("코치가 다시 안내한 순간 ${value.interventions}회 · 시작 위치에서 다시 해본 연습 ${value.retries}회", 16, muted))
        }
        gap(panel, 8)
        panel.addView(label("이 회고는 가상 화면에서 직접 조작한 과정이에요. 실제 도로의 운전 능력이나 안전을 평가한 결과는 아닙니다.", 15, muted))
        addView(button("같은 코스 한 번 더", "ride_again") { startCourse() })
        addView(button("다른 코스 둘러보기", "ride_courses", secondary = true) { leaveCourse() })
        addView(button("이전 메뉴로", "ride_exit", secondary = true) { exitPreview() })
        storageProblem?.let { addView(label(it, 15, amber)) }
    }

    private fun readRecords(): MutableList<JSONObject> {
        return try {
            val array = JSONArray(prefs.getString("records", "[]"))
            MutableList(minOf(array.length(), 20)) { array.getJSONObject(it) }
        } catch (_: Exception) {
            storageProblem = "지난 회고를 불러오지 못했어요. 기존 기록을 덮어쓰지 않도록 새 저장을 멈췄습니다."
            mutableListOf()
        }
    }

    private fun saveReflection(outcome: String) {
        if (saved || storageProblem != null) return
        val value = snapshot ?: return
        saved = true
        val record = JSONObject().apply {
            put("at", startedAtMillis); put("lesson", value.lesson.name); put("outcome", outcome)
            put("seconds", value.practiceSeconds); put("interventions", value.interventions); put("retries", value.retries)
            put("reflection", JSONArray(value.reflection))
        }
        records.add(0, record)
        if (records.size > 20) records = records.take(20).toMutableList()
        prefs.edit().putString("records", JSONArray(records).toString()).apply()
    }

    private fun row(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.TOP
        parent.addView(this, LayoutParams(-1, -2))
    }

    private fun card(parent: LinearLayout, attachAsColumn: Boolean = false) = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(dp(18), dp(15), dp(18), dp(15)); background = rounded(Color.WHITE)
        parent.addView(this, if (attachAsColumn) weight(278, 1f) else LayoutParams(-1, -2))
    }

    private fun label(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1.03f)
    }

    private fun button(value: String, key: String, secondary: Boolean = false, action: () -> Unit) = PracticeButton(context).apply {
        text = value; textSize = 17f; isAllCaps = false; contentDescription = key
        background = rounded(if (secondary) soft else teal); setTextColor(if (secondary) teal else Color.WHITE)
        minHeight = dp(48); setPadding(dp(8), dp(5), dp(8), dp(5))
        layoutParams = LayoutParams(-1, dp(50)).apply { topMargin = dp(6) }
        setOnClickListener { action() }
    }

    private fun weight(height: Int = -2, value: Float = 1f) = LayoutParams(0, if (height < 0) height else dp(height), value).apply {
        marginEnd = dp(6); topMargin = dp(3)
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(15).toFloat() }
    private fun gap(parent: LinearLayout, amount: Int) { parent.addView(View(context), LayoutParams(1, dp(amount))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun date(value: Long) = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(value))
    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }
}
