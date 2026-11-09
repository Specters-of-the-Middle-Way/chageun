package kr.drivestep.tutor.lessoncoach

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import kr.drivestep.tutor.speech.TutorSpeechController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Parked-only lesson surface. Speech, driver reports, route events and own-vehicle
 * observations retain their separate provenance; this view never observes adjacent traffic.
 */
// Constructed in code with required host/lifecycle/consent callbacks.
@SuppressLint("ViewConstructor")
class LessonCoachView(
    context: Context,
    private val onExit: () -> Unit,
    private val recordEnabled: () -> Boolean,
    private val vehicleEnabled: () -> Boolean,
    private val onInputModeChanged: (Boolean) -> Unit = {},
) : LinearLayout(context) {
    private enum class Page { MENU, LESSON, REVIEW }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store = LessonCoachStore(context)
    private var session = LessonCoachSession()
    private var snapshot = session.snapshot()
    private var page = Page.MENU
    private var active = false
    private var closed = false
    private var speechEnabled = true
    private var liveInputs = false
    private var notifiedLive = false
    private var selectedTopic = LessonTopic.LANE_CHANGE
    private var startedAtMillis = 0L
    private var sessionId = ""
    private var spokenRevision = -1L
    private var connection = "차량 연결을 선택하면 신호를 확인해요."
    private var speechStatus = "듣기를 누르면 Pleos 음성 인식을 시작해요."
    private var storageStatus = ""
    private var records = emptyList<StoredLesson>()
    private var reviewRecord: StoredLesson? = null
    private var reviewIndex = 0
    private var replaying = false
    private var demoSignal = LessonSignal.OFF
    private var demoSpeed = 20.0
    private var demoBrake = false
    private var demoSteering = 0.0
    private var demoNav: NavigationObservation? = null
    private var demoSequence = 0
    private var demoInitial = true
    private var inputText = ""
    private var activeDialog: AlertDialog? = null

    private var replyLabel: TextView? = null
    private var directionLabel: TextView? = null
    private var reportLabel: TextView? = null
    private var vehicleLabel: TextView? = null
    private var navigationLabel: TextView? = null
    private var speechLabel: TextView? = null
    private var inputSourceLabel: TextView? = null
    private var connectionLabel: TextView? = null
    private var recordLabel: TextView? = null
    private var liveCountLabel: TextView? = null
    private var speedLabel: TextView? = null
    private var textInput: EditText? = null
    private var pauseButton: Button? = null
    private var resumeButton: Button? = null
    private var listenButton: Button? = null
    private var stopListeningButton: Button? = null
    private var brakeButton: Button? = null
    private val inputControls = mutableListOf<View>()
    private val signalButtons = mutableMapOf<LessonSignal, Button>()
    private var reviewTitle: TextView? = null
    private var reviewDetail: TextView? = null
    private var reviewVehicle: TextView? = null
    private var reviewPosition: TextView? = null
    private var replayButton: Button? = null
    private var reviewSlider: SeekBar? = null
    private var refreshingSlider = false

    private val speech = TutorSpeechController(context,
        onTranscript = { text -> if (canInput()) acceptText(text, SpeechInputSource.PLEOS_STT) },
        onStatus = { text -> speechStatus = text; refreshLive() },
    )

    val wantsLiveInputs: Boolean
        get() = active && !closed && page == Page.LESSON &&
            snapshot.phase == LessonPhase.ACTIVE && liveInputs && vehicleEnabled()

    private val ticker = object : Runnable {
        override fun run() {
            if (!active || closed || page != Page.LESSON) return
            val now = SystemClock.elapsedRealtime()
            if (snapshot.phase == LessonPhase.ACTIVE && !liveInputs) {
                applySnapshot(session.acceptVehicle(demoObservation(now), now))
                demoInitial = false
                demoNav?.let { applySnapshot(session.acceptNavigation(it.copy(atMillis = now), now)) }
            }
            applySnapshot(session.tick(now))
            notifyInputMode()
            main.postDelayed(this, 500)
        }
    }

    private val replayTick = object : Runnable {
        override fun run() {
            val record = reviewRecord ?: return
            if (!replaying || !active || closed || page != Page.REVIEW) return
            if (reviewIndex + 1 < record.events.size) {
                reviewIndex++
                updateReview()
                main.postDelayed(this, 900)
            } else {
                replaying = false
                updateReview()
            }
        }
    }

    init {
        orientation = VERTICAL
        render()
        io.execute {
            val result = runCatching { store.read() }
            main.post {
                if (!closed) {
                    result.onSuccess { records = it }.onFailure { storageStatus = "이전 기록을 읽지 못했어요. 기존 파일은 그대로 두었어요." }
                    if (page == Page.MENU) render()
                }
            }
        }
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        speech.setActive(value)
        if (!value) {
            dismissDialog()
            main.removeCallbacks(ticker)
            stopReplay()
            snapshot = session.pause(SystemClock.elapsedRealtime(), "화면을 떠나거나 정차 사용 조건이 바뀜")
            spokenRevision = snapshot.cueRevision
        } else if (page == Page.LESSON) {
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
        notifyInputMode()
        refreshLive()
    }

    fun setSpeechEnabled(value: Boolean) {
        speechEnabled = value
        speech.setEnabled(value)
    }

    fun setConnectionStatus(value: String) {
        connection = value
        connectionLabel?.show(value)
    }

    fun acceptVehicle(value: VehicleObservation) {
        if (wantsLiveInputs && value.source == VehicleInputSource.SDK) {
            applySnapshot(session.acceptVehicle(value, SystemClock.elapsedRealtime()))
        }
    }

    fun acceptNavigation(value: NavigationObservation) {
        if (wantsLiveInputs && value.source == NavigationInputSource.NAVI) {
            applySnapshot(session.acceptNavigation(value, SystemClock.elapsedRealtime()))
        }
    }

    fun goBack(): Boolean = when (page) {
        Page.MENU -> false
        Page.LESSON -> { if (active) finishDialog(); true }
        Page.REVIEW -> { showMenu(); true }
    }

    fun close() {
        if (closed) return
        setActive(false)
        dismissDialog()
        closed = true
        main.removeCallbacksAndMessages(null)
        speech.close()
        io.shutdown()
    }

    override fun onDetachedFromWindow() {
        setActive(false)
        super.onDetachedFromWindow()
    }

    private fun render() {
        removeAllViews()
        inputControls.clear(); signalButtons.clear()
        replyLabel = null; directionLabel = null; reportLabel = null; vehicleLabel = null
        navigationLabel = null; speechLabel = null; inputSourceLabel = null; connectionLabel = null
        recordLabel = null; liveCountLabel = null; speedLabel = null; textInput = null
        pauseButton = null; resumeButton = null; listenButton = null; stopListeningButton = null; brakeButton = null
        reviewTitle = null; reviewDetail = null; reviewVehicle = null; reviewPosition = null
        replayButton = null; reviewSlider = null
        when (page) {
            Page.MENU -> menu()
            Page.LESSON -> lesson()
            Page.REVIEW -> review()
        }
        refreshLive()
    }

    private fun menu() {
        addView(label("함께하는 연수", 28, bold = true))
        addView(label("말로 요청하고, 다음 상황을 준비하고, 내 조작을 돌아봐요.", 17, secondary = true))
        space(this, 18)
        addView(label("오늘 익힐 한 가지", 19, bold = true))
        val topics = row(this)
        LessonTopic.entries.forEach { topic ->
            topics.addView(button(topic.title, "lesson_topic_${topic.name.lowercase()}", selectedTopic == topic) {
                selectedTopic = topic; render()
            }, weight())
        }
        space(this, 12)
        val modes = row(this)
        modes.addView(button("시연 데이터", "lesson_mode_demo", !liveInputs) { liveInputs = false; render() }, weight())
        modes.addView(button("차량 신호 연결", "lesson_mode_live", liveInputs) {
            if (vehicleEnabled()) { liveInputs = true; render() }
            else showDialog(AlertDialog.Builder(context).setMessage("차량 데이터 사용 동의를 먼저 켜 주세요. 동의 없이도 시연 데이터로 연습할 수 있어요.")
                .setPositiveButton("확인", null))
        }, weight())
        addView(label(if (liveInputs) "연결된 차량의 조작 신호와 지원되는 내비 정보를 읽어요. 주변 차량은 감지하지 않아요."
            else "실제 차량은 P·정차 상태로 두세요. 화면의 시험 입력은 실제 차량을 움직이지 않아요.", 15, secondary = true))
        space(this, 12)
        addView(button("함께 시작하기", "lesson_start", true) { start(selectedTopic) })
        addView(button("이전 메뉴", "lesson_exit") { speech.stop(); onExit() })
        space(this, 20)
        addView(label("지난 연수 이어보기", 20, bold = true))
        if (records.isEmpty()) addView(label(storageStatus.ifBlank { "마무리와 복습 응답을 남기면 이 차량에서 다시 볼 수 있어요." }, 15, secondary = true))
        records.forEachIndexed { index, record ->
            addView(button("${date(record.startedAtMillis)} · ${record.topic.title}\n${if (record.liveInputs) "차량 신호" else "시연 데이터"} · ${record.reflection.label}", "lesson_history_$index") {
                openReview(record)
            })
        }
        if (records.isNotEmpty() || storageStatus.isNotBlank()) addView(button("연수 기록 삭제", "lesson_delete") { confirmDelete() })
        addView(label("정차 중 검증용 프로토타입 · 음성 원본은 저장하지 않아요. 기록 동의 시 인식 문장과 조작 기록을 최대 20회 보관해요.", 14, secondary = true))
    }

    private fun lesson() {
        addView(label(snapshot.topic.title, 27, bold = true))
        addView(label(if (liveInputs) "차량 연결 · 정차 중 관측" else "시연 데이터 · 정차 중 연수", 15, secondary = true))
        space(this, 12)
        val coach = panel(this)
        coach.addView(label("차근의 안내", 15, secondary = true))
        replyLabel = label(snapshot.reply, 24, bold = true).also { coach.addView(it) }
        speechLabel = label(speechStatus, 14, secondary = true).also { coach.addView(it) }
        val voiceRow = row(coach)
        listenButton = button("말하기", "lesson_listen", true, "sound") { if (canInput()) speech.startListening() }
            .also { voiceRow.addView(it, weight()); inputControls.add(it) }
        stopListeningButton = button("듣기 중지", "lesson_stop_listening") { speech.stopListening(); refreshLive() }
            .also { voiceRow.addView(it, weight()) }
        voiceRow.addView(button("다시 듣기", "lesson_repeat") { if (canInput()) acceptText("다시 설명해 줘", SpeechInputSource.TEXT_DEMO) }, weight())
        inputSourceLabel = label("아직 입력한 말이 없어요.", 15, secondary = true).also { coach.addView(it) }
        val state = panel(this)
        directionLabel = label("", 18, bold = true).also { state.addView(it) }
        reportLabel = label("", 16, secondary = true).also { state.addView(it) }
        navigationLabel = label("", 16, secondary = true).also { state.addView(it) }
        vehicleLabel = label("", 16).also { state.addView(it) }
        if (liveInputs) connectionLabel = label(connection, 14, secondary = true).also { state.addView(it) }
        liveCountLabel = label("", 14, secondary = true).also { state.addView(it) }
        val actions = row(this)
        pauseButton = button("잠깐 쉬기", "lesson_pause") { pause() }.also { actions.addView(it, weight()) }
        resumeButton = button("새 상황부터 다시 시작", "lesson_resume", true) { resume() }.also { actions.addView(it, weight()) }
        actions.addView(button("마무리·복습", "lesson_finish") { finishDialog() }, weight())
        recordLabel = label("", 14, secondary = true).also { addView(it) }
        space(this, 12)
        val test = panel(this)
        test.addView(label("에뮬레이터 시험 입력", 19, bold = true))
        test.addView(label("음성 서비스가 연결되지 않아도 같은 해석 흐름을 확인할 수 있어요. 아래 문장은 실제 음성 인식 결과와 구분해요.", 14, secondary = true))
        textInput = EditText(context).apply {
            contentDescription = "lesson_input"
            hint = "예: 왼쪽으로 가고 싶은데 차가 많아"
            setText(inputText)
            setTextColor(PleosUi.TEXT); setHintTextColor(PleosUi.SECONDARY)
            setTextSize(18f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 2; imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = background(PleosUi.RAISED)
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }.also { test.addView(it); inputControls.add(it) }
        test.addView(button("문장 입력", "lesson_send") { sendTyped() }.also(inputControls::add))
        val examples = row(test)
        listOf("차로 변경 도와줘" to "ask", "오른쪽 말고 왼쪽" to "correct", "왼쪽으로 가고 싶은데 차가 많고 멈춰 있어" to "traffic").forEach { (text, id) ->
            examples.addView(button(text, "lesson_example_$id") {
                if (canInput()) acceptText(text, SpeechInputSource.TEXT_DEMO)
            }.also(inputControls::add), weight(72))
        }
        if (!liveInputs) demoControls(test)
        else test.addView(label("이 모드에서는 시험 차량·내비 신호를 넣지 않아요. 실제 연결된 데이터만 별도 표시해요.", 14, secondary = true))
    }

    private fun demoControls(parent: LinearLayout) {
        space(parent, 12)
        parent.addView(label("다음 회전 시연", 17, bold = true))
        val navigation = row(parent)
        navigation.addView(button("왼쪽 회전 300 m", "lesson_demo_left") { demoNavigation(LessonDirection.LEFT) }.also(inputControls::add), weight())
        navigation.addView(button("오른쪽 회전 300 m", "lesson_demo_right") { demoNavigation(LessonDirection.RIGHT) }.also(inputControls::add), weight())
        val signals = row(parent)
        listOf(LessonSignal.LEFT to "왼쪽 깜빡이", LessonSignal.RIGHT to "오른쪽 깜빡이", LessonSignal.OFF to "깜빡이 끄기").forEach { (signal, text) ->
            signals.addView(button(text, "lesson_signal_${signal.name.lowercase()}") {
                if (canInput()) { demoSignal = signal; observeDemo() }
            }.also { signalButtons[signal] = it; inputControls.add(it) }, weight())
        }
        speedLabel = label("", 16).also { parent.addView(it) }
        val speed = SeekBar(context).apply {
            max = 60; progress = demoSpeed.toInt(); contentDescription = "lesson_speed"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && canInput()) { demoSpeed = progress.toDouble(); observeDemo() }
                }
            })
        }
        parent.addView(speed); inputControls.add(speed)
        val controls = row(parent)
        brakeButton = button("브레이크 누름", "lesson_brake") {
            if (canInput()) { demoBrake = !demoBrake; observeDemo() }
        }.also { controls.addView(it, weight()); inputControls.add(it) }
        controls.addView(button("감속 장면 넣기", "lesson_demo_slow") {
            if (canInput()) { demoSpeed = 8.0; demoBrake = true; speed.progress = 8; observeDemo() }
        }.also(inputControls::add), weight())
        controls.addView(button("전륜 조향 5°", "lesson_demo_steer") {
            if (canInput()) { demoSteering = if (demoSteering == 0.0) -5.0 else 0.0; observeDemo() }
        }.also(inputControls::add), weight())
        parent.addView(label("가상 기어는 D로 입력돼요. 실제 차량의 기어·속도·조향을 제어하지 않아요.", 14, secondary = true))
    }

    private fun start(topic: LessonTopic) {
        if (!active || closed) return
        if (liveInputs && !vehicleEnabled()) {
            showDialog(AlertDialog.Builder(context).setMessage("차량 데이터 사용 동의가 꺼져 있어요. 동의를 켜거나 연수 목록에서 시연 데이터를 선택해 주세요.")
                .setPositiveButton("확인", null))
            return
        }
        speech.stop(); stopReplay()
        selectedTopic = topic; sessionId = UUID.randomUUID().toString(); startedAtMillis = System.currentTimeMillis()
        session = LessonCoachSession(); snapshot = session.start(topic, SystemClock.elapsedRealtime())
        spokenRevision = -1; demoSignal = LessonSignal.OFF; demoSpeed = 20.0; demoBrake = false
        demoSteering = 0.0; demoNav = null; demoInitial = true; inputText = ""
        page = Page.LESSON
        render()
        notifyInputMode()
        applySnapshot(snapshot)
        main.removeCallbacks(ticker); main.post(ticker)
    }

    private fun sendTyped() {
        if (!canInput()) return
        val text = textInput?.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        inputText = text
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(textInput?.windowToken, 0)
        textInput?.clearFocus()
        acceptText(text, SpeechInputSource.TEXT_DEMO)
    }

    private fun acceptText(text: String, source: SpeechInputSource) {
        if (!canInput()) return
        speech.stopListening()
        applySnapshot(session.acceptUtterance(text, source, SystemClock.elapsedRealtime()))
        if (snapshot.phase == LessonPhase.PAUSED) speech.stop()
        notifyInputMode()
    }

    private fun observeDemo() {
        if (!canInput() || liveInputs) return
        val now = SystemClock.elapsedRealtime()
        applySnapshot(session.acceptVehicle(demoObservation(now), now))
        demoInitial = false
    }

    private fun demoObservation(now: Long) = VehicleObservation(now, VehicleInputSource.SIMULATION,
        demoSpeed, demoSignal, LessonGear.D, demoBrake, demoSteering, isInitial = demoInitial)

    private fun demoNavigation(direction: LessonDirection) {
        if (!canInput() || liveInputs) return
        val now = SystemClock.elapsedRealtime()
        val nav = NavigationObservation(now, NavigationInputSource.SIMULATION, "demo-${++demoSequence}", direction, 300.0)
        demoNav = nav
        applySnapshot(session.acceptNavigation(nav, now))
    }

    private fun applySnapshot(value: LessonSnapshot) {
        snapshot = value
        if (value.cueRevision != spokenRevision) {
            spokenRevision = value.cueRevision
            if (active && !closed && speechEnabled && value.phase == LessonPhase.ACTIVE) speech.speak(value.reply)
        }
        refreshLive()
    }

    private fun refreshLive() {
        if (closed) return
        if (page != Page.LESSON) {
            if (page == Page.REVIEW) updateReview()
            return
        }
        replyLabel?.show(snapshot.reply)
        directionLabel?.show(when {
            snapshot.awaitingConfirmation -> "인식한 방향을 확인해 주세요"
            snapshot.awaitingDirection -> "방향을 짧게 확인하고 있어요"
            else -> "요청 방향 · ${snapshot.direction.label}"
        })
        reportLabel?.show("운전자 설명 · ${snapshot.reportedTraffic.description}")
        navigationLabel?.show(snapshot.navigation?.let { "${if (it.source == NavigationInputSource.NAVI) "내비 연결" else "시연 경로"} · 다음 ${it.direction.label} 회전 ${format(it.remainingMeters)} m" }
            ?: "내비 정보 · 아직 확인되지 않았어요")
        vehicleLabel?.show(snapshot.vehicle?.let(::vehicleText) ?: "내 차 상태 · 새 신호를 기다리고 있어요")
        speechLabel?.show(speechStatus)
        inputSourceLabel?.show(if (snapshot.lastUtterance.isBlank()) "아직 입력한 말이 없어요."
            else "${if (snapshot.speechInputSource == SpeechInputSource.PLEOS_STT) "인식한 말" else "시험 입력"} · ${snapshot.lastUtterance}")
        liveCountLabel?.show("조작·대화 기록 ${snapshot.events.size}개 · 주변 차량은 관측하지 않아요")
        recordLabel?.show(if (recordEnabled()) "마무리하면 인식 문장과 조작 기록을 이 차량에 저장해요. 음성 원본은 저장하지 않아요."
            else "기록 동의가 꺼져 있어 이번 연수는 화면에서만 복습해요.")
        speedLabel?.show("가상 속도 · ${format(demoSpeed)} km/h")
        brakeButton?.show(if (demoBrake) "브레이크 놓기" else "브레이크 누름")
        inputControls.forEach { it.isEnabled = canInput() }
        pauseButton?.visibility = if (snapshot.phase == LessonPhase.ACTIVE) VISIBLE else GONE
        resumeButton?.visibility = if (snapshot.phase == LessonPhase.PAUSED) VISIBLE else GONE
        pauseButton?.isEnabled = active; resumeButton?.isEnabled = active
        stopListeningButton?.isEnabled = active && speech.isListening
        listenButton?.show(if (speech.isListening) "듣고 있어요" else "말하기")
        listenButton?.isEnabled = canInput() && !speech.isListening
        signalButtons.forEach { (signal, view) -> PleosUi.decorate(view, demoSignal == signal) }
    }

    private fun pause() {
        if (!active) return
        speech.stop()
        snapshot = session.pause(SystemClock.elapsedRealtime())
        spokenRevision = snapshot.cueRevision
        notifyInputMode(); refreshLive()
    }

    private fun resume() {
        if (!active) return
        demoInitial = true; demoNav = null
        applySnapshot(session.resume(SystemClock.elapsedRealtime()))
        notifyInputMode()
    }

    private fun finishDialog() {
        if (!active || page != Page.LESSON) return
        pause()
        showDialog(AlertDialog.Builder(context).setTitle("오늘 연수는 어땠나요?")
            .setItems(LessonReflection.entries.map { it.label }.toTypedArray()) { _, which -> finish(LessonReflection.entries[which]) }
            .setNegativeButton("더 연습하기") { _, _ -> resume() })
    }

    private fun finish(reflection: LessonReflection) {
        if (!active || page != Page.LESSON) return
        speech.stop()
        snapshot = session.finish(reflection, SystemClock.elapsedRealtime())
        val record = StoredLesson(sessionId, startedAtMillis, snapshot.topic, liveInputs, reflection,
            snapshot.nextLesson ?: snapshot.topic, snapshot.nextLessonReason, snapshot.events)
        openReview(record)
        if (!recordEnabled()) { storageStatus = "기록 동의가 꺼져 있어 저장하지 않았어요."; return }
        storageStatus = "기록을 저장하고 있어요."
        io.execute {
            // Re-check consent at execution time; a queued save must not ignore revocation.
            val result = if (recordEnabled()) runCatching { store.save(record) } else null
            main.post {
                if (!closed) {
                    result?.onSuccess { records = it; storageStatus = "이 차량에 저장했어요." }
                        ?.onFailure { storageStatus = "저장하지 못했어요. 현재 화면에서는 복습할 수 있어요." }
                    if (result == null) storageStatus = "기록 동의가 꺼져 있어 저장하지 않았어요."
                    if (active && page == Page.MENU) render() else recordLabel?.show(storageStatus)
                }
            }
        }
    }

    private fun openReview(record: StoredLesson) {
        speech.stop(); main.removeCallbacks(ticker); stopReplay()
        reviewRecord = record; reviewIndex = 0; page = Page.REVIEW
        notifyInputMode(); render()
    }

    private fun review() {
        val record = reviewRecord ?: return
        addView(label("내 조작 다시보기", 27, bold = true))
        addView(label("${record.topic.title} · ${if (record.liveInputs) "차량 연결 기록" else "시연 기록"}", 17, secondary = true))
        val timeline = panel(this)
        reviewPosition = label("", 14, secondary = true).also { timeline.addView(it) }
        reviewTitle = label("", 23, bold = true).also { timeline.addView(it) }
        reviewDetail = label("", 17).also { timeline.addView(it) }
        reviewVehicle = label("", 16, secondary = true).also { timeline.addView(it) }
        reviewSlider = SeekBar(context).apply {
            max = (record.events.size - 1).coerceAtLeast(0)
            contentDescription = "lesson_review_position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) { stopReplay() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !refreshingSlider) { reviewIndex = progress; updateReview() }
                }
            })
        }.also { timeline.addView(it) }
        val controls = row(timeline)
        controls.addView(button("이전 순간", "lesson_review_previous") {
            stopReplay(); reviewIndex = (reviewIndex - 1).coerceAtLeast(0); updateReview()
        }, weight())
        replayButton = button("순서대로 보기", "lesson_replay", true) {
            if (!active || record.events.isEmpty()) return@button
            if (replaying) stopReplay() else {
                if (reviewIndex >= record.events.lastIndex) reviewIndex = 0
                replaying = true; updateReview(); main.postDelayed(replayTick, 900)
            }
        }.also { controls.addView(it, weight()) }
        controls.addView(button("다음 순간", "lesson_review_next") {
            stopReplay(); reviewIndex = (reviewIndex + 1).coerceAtMost(record.events.lastIndex.coerceAtLeast(0)); updateReview()
        }, weight())
        timeline.addView(label("내 차에서 받은 값과 입력한 설명만 보여요. 주변 차량이나 차로 변경 성공 장면을 만들어내지 않아요.", 14, secondary = true))
        val next = panel(this)
        next.addView(label("다음에도 차근차근", 20, bold = true))
        next.addView(label("운전자 복습 · ${record.reflection.label}", 16))
        next.addView(label(record.nextLessonReason, 15, secondary = true))
        next.addView(button("${record.nextLesson.title} 이어서 연습", "lesson_next", true) {
            liveInputs = record.liveInputs; start(record.nextLesson)
        })
        recordLabel = label(storageStatus, 14, secondary = true).also { addView(it) }
        addView(button("연수 목록으로", "lesson_review_back") { showMenu() })
        updateReview()
    }

    private fun updateReview() {
        val record = reviewRecord ?: return
        val event = record.events.getOrNull(reviewIndex)
        val firstAt = record.events.firstOrNull()?.atMillis ?: 0
        reviewPosition?.show(if (event == null) "기록된 순간이 없어요" else
            "${reviewIndex + 1} / ${record.events.size} · 첫 기록 후 ${format((event.atMillis - firstAt).coerceAtLeast(0) / 1000.0)}초 · ${if (event.kind == LessonEventKind.UTTERANCE) event.title else sourceLabel(event.source)}")
        reviewTitle?.show(event?.title ?: "조작 기록 없음")
        reviewDetail?.show(event?.detail.orEmpty())
        reviewVehicle?.show(event?.vehicle?.let(::vehicleText).orEmpty())
        refreshingSlider = true; reviewSlider?.progress = reviewIndex; refreshingSlider = false
        replayButton?.show(if (replaying) "재생 멈추기" else "순서대로 보기")
        replayButton?.isEnabled = active && record.events.isNotEmpty()
    }

    private fun stopReplay() {
        replaying = false; main.removeCallbacks(replayTick)
        if (page == Page.REVIEW) updateReview()
    }

    private fun showMenu() {
        speech.stop(); stopReplay(); main.removeCallbacks(ticker)
        page = Page.MENU; notifyInputMode(); render()
    }

    private fun confirmDelete() {
        if (!active) return
        showDialog(AlertDialog.Builder(context).setTitle("연수 기록을 삭제할까요?")
            .setMessage("함께하는 연수의 기록만 삭제해요. 다른 실습 기록은 남아 있어요.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                if (!active || closed) return@setPositiveButton
                io.execute {
                    val result = runCatching { store.deleteAll() }
                    main.post {
                        if (!closed) {
                            result.onSuccess { records = emptyList(); reviewRecord = null; storageStatus = "연수 기록을 삭제했어요." }
                                .onFailure { storageStatus = "삭제하지 못했어요. 다시 시도해 주세요." }
                            if (page == Page.MENU) render()
                        }
                    }
                }
            })
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        if (!active || closed) return
        dismissDialog()
        val dialog = builder.create()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    private fun dismissDialog() {
        val dialog = activeDialog
        activeDialog = null
        dialog?.dismiss()
    }

    private fun notifyInputMode() {
        val current = wantsLiveInputs
        if (notifiedLive != current) { notifiedLive = current; onInputModeChanged(current) }
    }

    private fun canInput() = active && !closed && page == Page.LESSON && snapshot.phase == LessonPhase.ACTIVE

    private fun vehicleText(value: VehicleObservation): String {
        val signal = when (value.signal) { LessonSignal.LEFT -> "왼쪽"; LessonSignal.RIGHT -> "오른쪽"; LessonSignal.OFF -> "꺼짐"; LessonSignal.UNKNOWN -> "미확인" }
        val brake = when (value.brakePressed) { true -> "누름"; false -> "놓음"; null -> "미확인" }
        val prefix = if (value.source == VehicleInputSource.SDK) "차량 신호" else "시연 신호"
        return "$prefix · ${value.speedKmh?.let { "${format(it)} km/h" } ?: "속도 미확인"} · 기어 ${value.gear.name}\n" +
            "방향지시등 $signal · 브레이크 $brake · 전륜 ${value.frontWheelAngleDegrees?.let { "${format(it)}°" } ?: "미확인"}"
    }

    private fun sourceLabel(value: LessonEvidence) = when (value) {
        LessonEvidence.DRIVER_REPORTED -> "운전자 설명 / 시험 문장"
        LessonEvidence.SDK -> "차량 SDK"
        LessonEvidence.SIMULATION -> "시연 입력"
        LessonEvidence.NAVI -> "내비 연결"
        LessonEvidence.APP -> "앱 안내"
    }

    private fun label(value: String, size: Int, secondary: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (secondary) PleosUi.SECONDARY else PleosUi.TEXT)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(5), 0, dp(7)); setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun button(value: String, key: String, selected: Boolean = false, icon: String? = null, action: () -> Unit) = PracticeButton(context).apply {
        text = value; contentDescription = key; isAllCaps = false; textSize = 17f
        minHeight = dp(62); minimumHeight = dp(62); gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(4) }
        PleosUi.decorate(this, selected)
        if (icon != null) PleosUi.prefix(this, icon)
        setOnClickListener { if (active && !closed) action() }
    }

    private fun panel(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(14)); background = background(PleosUi.SURFACE)
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(4) })
    }

    private fun row(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun weight(minHeight: Int = 62) = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8); topMargin = dp(6); bottomMargin = dp(4)
        height = dp(minHeight)
    }

    private fun background(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun space(parent: LinearLayout, value: Int) { parent.addView(View(context), LayoutParams(1, dp(value))) }
    private fun TextView.show(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun format(value: Double) = String.format(Locale.KOREA, "%.1f", value)
    private fun date(value: Long) = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(value))
}
