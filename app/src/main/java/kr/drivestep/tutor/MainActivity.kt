package kr.drivestep.tutor

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.content.pm.PackageManager
import kr.drivestep.tutor.debug.EmulatorControls
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PleosSwitch
import kr.drivestep.tutor.data.PracticeRepository
import kr.drivestep.tutor.growth.GrowthTutorView
import kr.drivestep.tutor.ride.DriveCoachView
import kr.drivestep.tutor.contextcoach.SituationCoachView
import kr.drivestep.tutor.simulator.DrivingSimulatorView
import kr.drivestep.tutor.parking.ParkingSimulatorView
import kr.drivestep.tutor.journey.DriverJourneyView
import kr.drivestep.tutor.journey.JourneyDestination
import kr.drivestep.tutor.coach.*
import kr.drivestep.tutor.practice.*
import kr.drivestep.tutor.tutor.*
import kr.drivestep.tutor.vehicle.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Screen { COACH, SIMULATOR, PARK_SIM, SITUATION, RIDE, HOME, HOW_TO, PRACTICE, REVIEW, PARKING, STEERING_SETUP, STEERING, GROWTH }
    private lateinit var growth: GrowthTutorView
    private lateinit var ride: DriveCoachView
    private lateinit var situation: SituationCoachView
    private lateinit var simulator: DrivingSimulatorView
    private lateinit var parkingSimulator: ParkingSimulatorView
    private lateinit var journey: DriverJourneyView
    private var returnToCoach = true
    private var voiceStatusView: TextView? = null
    private var voiceRepeatButton: Button? = null
    private lateinit var repository: PracticeRepository
    private lateinit var source: TurnSignalSource
    private lateinit var parkedGate: ParkedGate
    private lateinit var voice: VoiceCoach
    private lateinit var steering: SteeringFeature
    private lateinit var emulatorControls: EmulatorControls
    private val emulatorTest get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && android.os.Build.HARDWARE.lowercase() in setOf("ranchu", "goldfish")
    private var steeringReview: SteeringPracticeSnapshot? = null
    private var voiceEnabled = true
    private var voiceStatus = "음성 준비 중"
    private var currentCue: CoachCue? = null
    private var parkingSession: ParkingLessonSession? = null
    private val io = Executors.newSingleThreadExecutor()
    private var screen = Screen.COACH
    private var session: PracticeSession? = null
    private var review: PracticeSnapshot? = null
    private var history = emptyList<PracticeSnapshot>()
    private var reading: SignalReading? = null
    private var sourceStatus = SourceStatus(SourceState.CONNECTING, "차량 신호를 확인하고 있어요.")
    private var parked = false
    private var gateMessage = "정차 상태 확인 중"
    private var databaseReady = false
    private var saveMessage = "기록은 이 차량에만 저장됩니다."
    private var lastWriteError = false
    private var latestSaveRevision = 0L
    private var foreground = false
    private var resumed = false
    private var revisitPending = false
    private var rootScroll: ScrollView? = null
    private var renderedScreen: Screen? = null
    private var rendering = false
    private val ink = PleosUi.TEXT
    private val muted = PleosUi.SECONDARY
    private val teal = PleosUi.SELECTED
    private val canvas = PleosUi.BACKGROUND
    private val soft = PleosUi.RAISED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = PracticeRepository(this)
        source = PleosTurnSignalSource(this)
        parkedGate = ParkedGate(this)
        voiceEnabled = getSharedPreferences("coach", MODE_PRIVATE).getBoolean("voice_enabled", true)
        voice = VoiceCoach(this) { status ->
            voiceStatus = status
            if (!isDestroyed) render()
        }
        voice.setEnabled(voiceEnabled)
        emulatorControls = EmulatorControls(this) { /* The panel updates its own status. */ }
        steering = SteeringFeature(this, onChange = {
            if (!isDestroyed) {
                if (steering.storageError != null && screen in setOf(Screen.STEERING, Screen.STEERING_SETUP)) { voice.stop(); currentCue = null }
                render()
            }
        }, onCue = { cue ->
            if (screen == Screen.STEERING && foreground && parked) offerCue(cue)
        })
        growth = GrowthTutorView(this, onCue = { offerCue(it) }, onSdk = {
            voice.stop(); currentCue = null; returnToCoach = false; screen = Screen.HOME; render()
        }, stopVoice = { voice.stop(); currentCue = null }, onSetupComplete = {
            voice.stop(); currentCue = null; screen = Screen.COACH; render()
        })
        ride = DriveCoachView(this, onCue = { offerCue(it) }, stopVoice = {
            voice.stop(); currentCue = null
        }, onExit = { voice.stop(); currentCue = null; screen = Screen.COACH; render() })
        situation = SituationCoachView(this, onCue = { offerCue(it) }, stopVoice = {
            voice.stop(); currentCue = null
        }, onExit = { voice.stop(); currentCue = null; screen = Screen.COACH; render() })
        journey = DriverJourneyView(this, onDestination = { destination -> openDestination(destination) },
            onSettingsChanged = {
                if (::journey.isInitialized) {
                    if (journey.vehicleDataEnabled) {
                        if (foreground) { connectSource(); if (hasSpeedPermission() && journey.vehicleDataEnabled) steering.connect() }
                    } else {
                        source.stop(); reading = null; steering.stop("CONSENT_WITHDRAWN")
                        sourceStatus = SourceStatus(SourceState.STOPPED, "차량 조작 신호는 선택 동의 후 연결해요.")
                    }
                    render()
                }
            })
        simulator = DrivingSimulatorView(this, onCue = { offerCue(it) }, stopVoice = {
            voice.stop(); currentCue = null
        }, onExit = { returnFromPractice(JourneyDestination.DRIVE) }, recordEnabled = { journey.recordEnabled })
        parkingSimulator = ParkingSimulatorView(this, onCue = { offerCue(it) }, stopVoice = {
            voice.stop(); currentCue = null
        }, onExit = { returnFromPractice(JourneyDestination.PARKING) }, recordEnabled = { journey.recordEnabled })
        render()
        io.execute {
            try {
                repository.recoverInterrupted()
                val records = repository.listRecent()
                runOnUiThread {
                    if (!isDestroyed) { history = records; databaseReady = true; render() }
                }
            } catch (_: Exception) {
                runOnUiThread { saveMessage = "기록 저장소를 열지 못했습니다. 앱을 다시 시작해 주세요."; lastWriteError = true; render() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (revisitPending) {
            revisitPending = false
            journey.beginVisit()
            if (screen in setOf(Screen.COACH, Screen.SIMULATOR, Screen.PARK_SIM)) screen = Screen.COACH
        }
        parkedGate.start { allowed, message ->
            parked = allowed
            gateMessage = message
            voice.setActive(allowed && foreground && (screen !in setOf(Screen.SIMULATOR, Screen.PARK_SIM) || resumed))
            if (!allowed) {
                interrupt("DRIVING_RESTRICTION")
                steering.interrupt("DRIVING_RESTRICTION", speak = false)
                emulatorControls.stop()
                growth.setActive(false)
                ride.setActive(false)
                situation.setActive(false)
                simulator.setActive(false)
                parkingSimulator.setActive(false)
                journey.setActive(false)
                currentCue = null
            }
            render()
        }
        connectSource()
        if (hasSpeedPermission() && journey.vehicleDataEnabled) steering.connect()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        voice.setActive(foreground && parked)
        render()
    }

    override fun onPause() {
        resumed = false
        simulator.setActive(false)
        parkingSimulator.setActive(false)
        journey.setActive(false)
        // EVS side-camera overlays also pause the Activity during SDK signal practice.
        // Only this interactive road simulation requires silence on a partial pause.
        if (screen in setOf(Screen.SIMULATOR, Screen.PARK_SIM)) voice.setActive(false)
        super.onPause()
    }

    override fun onStop() {
        foreground = false
        growth.setActive(false)
        ride.setActive(false)
        situation.setActive(false)
        simulator.setActive(false)
        parkingSimulator.setActive(false)
        journey.setActive(false)
        voice.setActive(false)
        interrupt("SCREEN_LEFT")
        steering.stop("SCREEN_LEFT")
        emulatorControls.stop()
        source.stop()
        parkedGate.stop()
        parked = false
        revisitPending = true
        super.onStop()
    }

    override fun onDestroy() {
        ride.close()
        situation.close()
        simulator.close()
        parkingSimulator.close()
        journey.close()
        emulatorControls.close()
        steering.close()
        voice.close()
        io.execute { repository.close() }
        io.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    @Deprecated("Legacy Activity back callback")
    override fun onBackPressed() {
        if (screen == Screen.COACH) { if (!journey.goBack()) super.onBackPressed() }
        else if (screen == Screen.PARK_SIM) { if (!parkingSimulator.goBack()) returnFromPractice(JourneyDestination.PARKING) }
        else if (screen == Screen.SIMULATOR) { if (!simulator.goBack()) returnFromPractice(JourneyDestination.DRIVE) }
        else if (screen == Screen.SITUATION) { if (!situation.goBack()) { screen = Screen.COACH; render() } }
        else if (screen == Screen.RIDE) { if (!ride.goBack()) { screen = Screen.COACH; render() } }
        else if (screen == Screen.GROWTH) {
            if (!growth.goBack()) { screen = Screen.COACH; render() }
        } else if (screen == Screen.HOME) { screen = Screen.COACH; render() }
        else {
            interrupt("USER_STOPPED")
            steering.interrupt("USER_STOPPED", speak = false)
            goHome()
        }
    }

    private fun connectSource() {
        if (!::journey.isInitialized || !journey.vehicleDataEnabled) return
        reading = null
        source.start(
            onState = { value ->
                var changed = reading?.left != value.left || reading?.right != value.right
                reading = value
                val running = session
                if (running != null && !running.snapshot().isTerminal && parked && foreground) {
                    val pattern = when {
                        !value.isKnown -> SignalPattern.UNKNOWN
                        value.left == SignalLamp.ACTIVE && value.right == SignalLamp.ACTIVE -> SignalPattern.BOTH
                        value.left == SignalLamp.ACTIVE -> SignalPattern.LEFT
                        value.right == SignalLamp.ACTIVE -> SignalPattern.RIGHT
                        else -> SignalPattern.OFF
                    }
                    if (value.isInitial && changed &&
                        running.snapshot().stage == PracticeStage.WAIT_BASELINE_OFF &&
                        pattern in setOf(SignalPattern.LEFT, SignalPattern.RIGHT)) {
                        // A fresh GET can explain the baseline; it cannot count as an action.
                        offerCue(SignalCoach.START)
                    }
                    // GETs can establish an OFF baseline, but cannot complete an action.
                    if ((!value.isInitial || pattern == SignalPattern.UNKNOWN ||
                        (pattern == SignalPattern.OFF && running.snapshot().stage == PracticeStage.WAIT_BASELINE_OFF)) &&
                        pattern != running.snapshot().lastSignal) {
                        val result = running.accept(pattern, value.observedAtMillis)
                        SignalCoach.onEvent(result)?.let(::offerCue)
                        persist(result)
                        changed = true
                        if (result.isTerminal) { review = result; screen = Screen.REVIEW }
                    }
                }
                if (changed) render()
            },
            onStatus = { status ->
                val changed = sourceStatus.state != status.state
                sourceStatus = status
                if (status.state in setOf(SourceState.ERROR, SourceState.UNSUPPORTED, SourceState.STOPPED)) {
                    interrupt("SIGNAL_CONNECTION_LOST")
                }
                if (changed) render()
            },
        )
    }

    private fun begin() {
        if (!parked || !databaseReady || lastWriteError || sourceStatus.state != SourceState.READY) return
        session = PracticeSession(UUID.randomUUID().toString(), System.currentTimeMillis())
        review = null
        screen = Screen.PRACTICE
        voice.stop()
        currentCue = SignalCoach.onStart(session!!.snapshot())
        // A fresh OFF callback will announce the first situation. If already ON,
        // explain how to return to the baseline while waiting for a real change.
        if (reading?.isKnown == true && (reading?.left == SignalLamp.ACTIVE || reading?.right == SignalLamp.ACTIVE)) {
            currentCue?.let(voice::speak)
        }
        persist(session!!.snapshot())
        // Reconnect to request a fresh baseline; cached UI state never starts a lesson.
        connectSource()
        render()
    }

    private fun interrupt(reason: String) {
        val active = session ?: return
        if (active.snapshot().isTerminal) return
        val result = active.interrupt(reason, System.currentTimeMillis())
        review = result
        screen = Screen.REVIEW
        voice.stop()
        currentCue = SignalCoach.onStart(result)
        persist(result)
    }

    private fun persist(snapshot: PracticeSnapshot) {
        if (!journey.recordEnabled) { saveMessage = "저장 동의가 꺼져 있어 이번 조작 기록을 저장하지 않아요."; return }
        val revision = ++latestSaveRevision
        if (!lastWriteError) saveMessage = "기록을 저장하고 있어요…"
        io.execute {
            try {
                repository.save(snapshot)
                val records = repository.listRecent()
                runOnUiThread {
                    if (!isDestroyed) {
                        history = records
                        // An older queued save cannot confirm the latest snapshot or
                        // clear a failure that has already stopped further practice.
                        if (revision == latestSaveRevision && !lastWriteError) {
                            saveMessage = "차량 안에 기록을 저장했어요."
                        }
                        render()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        lastWriteError = true
                        voice.stop()
                        currentCue = null
                        saveMessage = "저장하지 못했어요. 실습을 중단하고 앱을 다시 시작해 주세요."
                        session?.takeUnless { it.snapshot().isTerminal }?.let {
                            review = it.interrupt("STORAGE_ERROR", System.currentTimeMillis())
                            screen = Screen.REVIEW
                        }
                        render()
                    }
                }
            }
        }
    }

    private fun render() {
        if (rendering || isDestroyed) return
        rendering = true
        try { renderContent() } finally { rendering = false }
    }

    private fun renderContent() {
        if (isDestroyed) return
        if (!::emulatorControls.isInitialized || !::steering.isInitialized || !::growth.isInitialized || !::ride.isInitialized || !::situation.isInitialized || !::simulator.isInitialized || !::parkingSimulator.isInitialized || !::journey.isInitialized) return
        growth.setActive(foreground && parked && screen == Screen.GROWTH)
        ride.setActive(foreground && parked && screen == Screen.RIDE)
        situation.setActive(foreground && parked && screen == Screen.SITUATION)
        simulator.setActive(foreground && resumed && parked && screen == Screen.SIMULATOR)
        parkingSimulator.setActive(foreground && resumed && parked && screen == Screen.PARK_SIM)
        journey.setActive(foreground && resumed && parked && screen == Screen.COACH)
        // Keep the direct-manipulation surface mounted during speech/sensor updates.
        // Replacing it would cancel a held pedal or restart its simulation ticker.
        if (foreground && parked && renderedScreen == screen &&
            ((screen == Screen.RIDE && ride.isAttachedToWindow) || (screen == Screen.SITUATION && situation.isAttachedToWindow) ||
                (screen == Screen.SIMULATOR && simulator.isAttachedToWindow) ||
                (screen == Screen.PARK_SIM && parkingSimulator.isAttachedToWindow) ||
                (screen == Screen.COACH && journey.isAttachedToWindow))) {
            voiceStatusView?.text = voiceStatus
            voiceRepeatButton?.isEnabled = voiceEnabled && currentCue != null
            return
        }
        if (!foreground || !parked || screen !in setOf(Screen.HOW_TO, Screen.PRACTICE, Screen.STEERING_SETUP, Screen.STEERING) ||
            (screen == Screen.STEERING && (steeringReview != null || steering.snapshot?.isTerminal == true))) emulatorControls.stop()
        if (parked && screen in setOf(Screen.COACH, Screen.SIMULATOR, Screen.PARK_SIM)) {
            renderSimulator()
            return
        }
        val savedScroll = if (renderedScreen == screen) rootScroll?.scrollY ?: 0 else 0
        val scroll = ScrollView(this).apply { setBackgroundColor(canvas); isFillViewport = true }
        rootScroll = scroll
        renderedScreen = screen
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(24))
        }
        scroll.addView(content)
        content.addView(text("DRIVESTEP   /   내 차 안의 옆자리 코치", 15, teal, true))
        gap(content, 16)
        if (!parked) {
            val panel = card(content)
            panel.addView(text("안전하게 정차한 뒤 만나요", 27, ink, true))
            gap(panel, 12)
            panel.addView(text(gateMessage, 19))
            gap(panel, 10)
            if (emulatorTest) panel.addView(text("에뮬레이터에서 R·N·D로 바꾸면 제한될 수 있어요. PC 조작 패널에서 P로 복귀한 뒤 다시 시작해 주세요.", 17, teal))
            gap(panel, 10)
            panel.addView(text("이 실습은 정차 중에만 사용할 수 있어요. 주행 상태로 바뀌면 진행 중인 실습은 중단 기록으로 남습니다.", 17, muted))
            setContentView(scroll)
            return
        }
        voiceControls(content)
        gap(content, 12)
        when (screen) {
            Screen.COACH -> coachHome(content)
            Screen.SIMULATOR, Screen.PARK_SIM -> Unit // Mounted without a ScrollView above.
            Screen.SITUATION -> {
                (situation.parent as? android.view.ViewGroup)?.removeView(situation)
                content.addView(situation)
            }
            Screen.RIDE -> {
                (ride.parent as? android.view.ViewGroup)?.removeView(ride)
                content.addView(ride)
            }
            Screen.GROWTH -> {
                (growth.parent as? android.view.ViewGroup)?.removeView(growth)
                content.addView(growth)
            }
            Screen.HOME -> home(content)
            Screen.HOW_TO -> operationGuide(content)
            Screen.PRACTICE -> practice(content)
            Screen.REVIEW -> reviewScreen(content)
            Screen.PARKING -> parking(content)
            Screen.STEERING_SETUP -> steeringSetup(content)
            Screen.STEERING -> steeringPractice(content)
        }
        gap(content, 16)
        val footer = when (screen) {
            Screen.COACH -> "현대자동차 기본 탑재를 제안하는 개발 프로토타입입니다. 현재 공식 기본 앱은 아닙니다."
            Screen.SIMULATOR, Screen.PARK_SIM -> "정차 중 가상 연습"
            Screen.SITUATION -> "상황 감지 검증용 가상 입력 · 실제 주행 중 화면 실행과 주변 인식은 구현되지 않았습니다."
            Screen.RIDE -> "정차 중 가상 주행 체험 · 실제 도로 인식이나 차량 제어를 하지 않습니다."
            Screen.GROWTH -> "완료한 가상 연습만 학습 점수로 저장해요. 실제 SDK 실습 기록과 구분됩니다."
            Screen.PARKING -> "이해 확인은 현재 과정에서만 유지돼요. 실제 주차 기록은 수집하지 않습니다."
            Screen.STEERING, Screen.STEERING_SETUP -> steering.storageError ?: "기어·조향 실습 기록은 이 차량 안에 저장돼요. 실제 주차 성공을 판정하지 않습니다."
            Screen.HOW_TO -> "레버형 예시이며, 해당 차량의 사용설명서가 우선입니다."
            else -> saveMessage
        }
        content.addView(text(footer, 14, if (lastWriteError && screen != Screen.PARKING && screen != Screen.HOW_TO) Color.rgb(160, 52, 35) else muted))
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, savedScroll) }
    }

    private fun coachHome(parent: LinearLayout) {
        val hero = card(parent, ink)
        hero.addView(text("혼자 운전하기 전, 옆자리에서 함께", 17, Color.rgb(155, 224, 201)))
        gap(hero, 6)
        hero.addView(text("제가 알려드릴게요.\n직접 해 보면서 익혀요.", 32, Color.WHITE, true))
        gap(hero, 8)
        hero.addView(text("조작법을 듣고 → 직접 조작하고 → 코치의 피드백을 받아요.\n서두르지 않아도 괜찮아요. 익숙해질 때까지 함께 연습해요.", 18, Color.rgb(221, 234, 232)))
        hero.addView(button("도로를 보며 직접 연습 · 차로 변경", "coach_simulator") {
            voice.stop(); currentCue = null; screen = Screen.SIMULATOR; render()
        })
        hero.addView(text("운전자 시점 · 움직이는 주변 차량 · 미러 확인 · 핸들 조작 · 옆자리 음성 코치", 15, Color.rgb(221, 234, 232)))
        gap(parent, 14)
        val wide = resources.configuration.screenWidthDp >= 900
        val row = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        parent.addView(row)
        val actual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val virtual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(actual, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        row.addView(virtual, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) } else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val a = card(actual)
        a.addView(text("01  내 차의 조작부터", 23, ink, true))
        a.addView(text("P·정차 상태의 조작 연수\n레버·핸들 신호를 받고, 다음 조작을 음성으로 안내해요.", 16, muted))
        a.addView(button("방향지시등 · 코치와 시작", "coach_signal", databaseReady && !lastWriteError && sourceStatus.state == SourceState.READY) {
            returnToCoach = true; screen = Screen.HOW_TO; offerCue(OperationGuide.introduction); render()
        })
        a.addView(button("핸들 · 코치와 시작", "coach_steering", secondary = true) {
            returnToCoach = true; voice.stop(); currentCue = null; steeringReview = null
            screen = Screen.STEERING_SETUP; if (hasSpeedPermission() && journey.vehicleDataEnabled) steering.connect(); render()
        })
        a.addView(button("처음이라면 · 시트와 미러 맞추기", "coach_setup", secondary = true) {
            voice.stop(); currentCue = null; screen = Screen.GROWTH; render(); growth.openSetup()
        })
        a.addView(text(if (sourceStatus.state == SourceState.READY) "● 차량 신호 연결됨 · 조작하면 코치가 반응해요" else sourceStatus.message, 14, teal))
        val b = card(virtual, soft)
        b.addView(text("02  움직이며 연수하는 느낌", 23, ink, true))
        b.addView(text("정차 중 가상 주행 체험\n가속·제동·핸들을 직접 움직이면 상황에 맞춰 코치가 반응해요.", 16, muted))
        b.addView(button("조작형 연수 체험 열기", "coach_ride") {
            voice.stop(); currentCue = null; screen = Screen.RIDE; render()
        })
        gap(b, 8)
        b.addView(text("실제 차는 움직이지 않아요. 화면 속 페달·기어·핸들로 출발과 정지, 횡단보도 앞 대기를 체험합니다.", 16, ink))
        gap(b, 8)
        b.addView(text("앞으로: 실제 주행 상황을 읽는 음성 코치\n주변 인식 데이터와 주행 중 사용 범위를 먼저 확인해야 합니다.", 14, muted))
        gap(parent, 14)
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(links)
        links.addView(button("내 조작 기록 · 복습", "coach_sdk", secondary = true) {
            returnToCoach = false; voice.stop(); currentCue = null; screen = Screen.HOME; render()
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(10) })
        links.addView(button("기존 교통상식 학습 자료", "coach_reference", secondary = true) {
            voice.stop(); currentCue = null; growth.openLibrary(); screen = Screen.GROWTH; render()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        parent.addView(button("상황 감지 변수 확인 · 개발용", "coach_situation", secondary = true) {
            voice.stop(); currentCue = null; screen = Screen.SITUATION; render()
        })
    }

    /** Preserve the platform's system bars; dedicate the app viewport to the road. */
    private fun renderSimulator() {
        rootScroll = null
        renderedScreen = screen
        voiceStatusView = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PleosUi.BACKGROUND)
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply {
            setImageDrawable(PleosUi.icon(this@MainActivity, "back", 32))
            background = null; contentDescription = "app_back"
            setOnClickListener { onBackPressed() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(64), dp(64)))
        bar.addView(text("드라이브스텝", 24, PleosUi.TEXT, true), LinearLayout.LayoutParams(0, dp(64), 1f))
        bar.addView(text("IONIQ 3", 18, PleosUi.SECONDARY), LinearLayout.LayoutParams(dp(110), dp(64)))
        val toggle = PleosSwitch(this).apply {
            text = "음성 안내  "
            textSize = 15f
            setTextColor(PleosUi.TEXT)
            contentDescription = "voice_toggle"
            isChecked = voiceEnabled
            setOnCheckedChangeListener { _, checked ->
                voiceEnabled = checked
                getSharedPreferences("coach", MODE_PRIVATE).edit().putBoolean("voice_enabled", checked).apply()
                voice.setEnabled(checked)
                render()
            }
        }
        bar.addView(toggle, LinearLayout.LayoutParams(dp(196), dp(64)))
        val repeat = button("다시 듣기", "replay_voice", voiceEnabled && currentCue != null, secondary = true) {
            voice.stop(); currentCue?.let(voice::speak)
        }
        PleosUi.decorate(repeat)
        PleosUi.prefix(repeat, "sound", 24)
        voiceRepeatButton = repeat
        bar.addView(repeat, LinearLayout.LayoutParams(dp(164), dp(64)))
        content.addView(bar)
        val surface = when (screen) { Screen.COACH -> journey; Screen.PARK_SIM -> parkingSimulator; else -> simulator }
        (surface.parent as? android.view.ViewGroup)?.removeView(surface)
        content.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(content)
    }

    private fun returnFromPractice(destination: JourneyDestination) {
        voice.stop(); currentCue = null
        journey.onReturnFromPractice(destination)
        screen = Screen.COACH
        render()
    }

    private fun openDestination(destination: JourneyDestination) {
        if (!parked) return
        val sdk = destination in setOf(JourneyDestination.SIGNAL, JourneyDestination.STEERING)
        if ((sdk && (!journey.vehicleDataEnabled || !journey.recordEnabled)) ||
            (destination in setOf(JourneyDestination.LEGACY, JourneyDestination.CONTEXT) && !journey.recordEnabled)) {
            AlertDialog.Builder(this).setTitle("설정 확인")
                .setMessage("기존 SDK 실습은 조작 신호와 실습 기록 동의가 필요해요. 프로필에서 설정하거나 화면 속 가상 연습을 이용해 주세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        if (destination in setOf(JourneyDestination.DRIVE, JourneyDestination.PARKING) && !journey.canPractice) return
        voice.stop(); currentCue = null; returnToCoach = true
        screen = when (destination) {
            JourneyDestination.DRIVE -> Screen.SIMULATOR
            JourneyDestination.PARKING -> Screen.PARK_SIM
            JourneyDestination.SIGNAL -> { connectSource(); Screen.HOW_TO }
            JourneyDestination.STEERING -> { steeringReview = null; if (hasSpeedPermission()) steering.connect(); Screen.STEERING_SETUP }
            JourneyDestination.CONTEXT -> Screen.SITUATION
            JourneyDestination.LEGACY -> { growth.openLibrary(); Screen.GROWTH }
        }
        render()
    }

    private fun home(parent: LinearLayout) {
        parent.addView(button("옆자리 코치로 돌아가기", "coach_home", secondary = true) {
            voice.stop(); currentCue = null; screen = Screen.COACH; render()
        })
        parent.addView(button("성장 튜터로 돌아가기", "growth_home", secondary = true) {
            voice.stop(); currentCue = null; growth.openLibrary(); screen = Screen.GROWTH; render()
        })
        gap(parent, 12)
        val wide = resources.configuration.screenWidthDp >= 900
        val lessons = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val signalColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val parkingColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lessons.addView(signalColumn, if (wide) LinearLayout.LayoutParams(0, -2, 1.2f) else LinearLayout.LayoutParams(-1, -2))
        lessons.addView(parkingColumn, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) } else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        parent.addView(lessons)
        val hero = card(signalColumn, ink)
        hero.addView(text("첫 번째 연습 · 방향지시등", 16, Color.rgb(155, 224, 201)))
        gap(hero, 10)
        hero.addView(text("왼쪽, 오른쪽.\n하나씩 익혀봐요.", 32, Color.WHITE, true))
        gap(hero, 12)
        hero.addView(text("차량이 정차한 상태에서 실제 방향지시등을 조작해요.\n끄기 → 왼쪽 → 끄기 → 오른쪽 → 끄기", 18, Color.rgb(221, 234, 232)))
        gap(hero, 18)
        hero.addView(button("조작법 보고 실습 시작", "start_practice", databaseReady && !lastWriteError && sourceStatus.state == SourceState.READY) {
            screen = Screen.HOW_TO; offerCue(OperationGuide.introduction); render()
        })
        hero.addView(button("조작법 먼저 보기", "operation_guide", secondary = true) {
            screen = Screen.HOW_TO
            offerCue(OperationGuide.introduction)
            render()
        })
        gap(parent, 14)
        val parkingCard = card(parkingColumn)
        parkingCard.addView(text("주차를 위한 첫 조작", 23, ink, true))
        gap(parkingCard, 8)
        parkingCard.addView(text("먼저 조작법을 알아보고, P·정지 상태에서\n왼쪽 → 중앙 → 오른쪽 → 중앙 신호를 확인해요.", 17, muted))
        parkingCard.addView(button("기어·조향 조작 확인", "steering_setup") {
            voice.stop(); currentCue = null; steeringReview = null
            screen = Screen.STEERING_SETUP
            if (hasSpeedPermission() && journey.vehicleDataEnabled) steering.connect()
            render()
        })
        parkingCard.addView(button("주차 순서 배우기", "parking_lesson", secondary = true) {
            voice.stop()
            parkingSession = ParkingLessonSession()
            screen = Screen.PARKING
            offerCue(ParkingLesson.steps.first().intro)
            render()
        })
        gap(parent, 14)
        val connection = card(parent)
        connection.addView(text(if (sourceStatus.state == SourceState.READY) "●  차량 신호 연결됨" else "○  차량 신호 확인", 18, teal, true))
        connection.addView(text(sourceStatus.message, 16, muted))
        gap(connection, 8)
        connection.addView(text("현재 방향지시등: ${readingLabel()}", 17, ink))
        if (sourceStatus.state in setOf(SourceState.ERROR, SourceState.UNSUPPORTED, SourceState.STOPPED)) {
            connection.addView(button("다시 연결", "reconnect") { connectSource() })
        }
        gap(parent, 14)
        gap(parent, 20)
        parent.addView(text("나의 연습 기록", 23, ink, true))
        steering.history.take(5).forEach { record ->
            val row = card(parent)
            row.addView(text("기어·조향 · ${if (record.outcome == PracticeOutcome.COMPLETED) "완료" else "중단"} · ${record.completedSteps}/4", 19, ink, true))
            row.addView(text(date(record.startedAtMillis), 15, muted))
            row.addView(button("조향 기록 복습", "steering_review_${record.id}", secondary = true) {
                voice.stop(); currentCue = null; steeringReview = record; screen = Screen.STEERING; render()
            })
            gap(parent, 8)
        }
        gap(parent, 10)
        if (history.isEmpty()) parent.addView(text("첫 실습을 마치면 이곳에서 다시 볼 수 있어요.", 17, muted))
        history.take(10).forEach { record ->
            val row = card(parent)
            row.addView(text("${outcomeLabel(record)} · ${record.completedStepCount}/5 확인", 19, ink, true))
            row.addView(text(date(record.startedAtMillis), 15, muted))
            row.addView(button("기록 복습", "review_${record.id}", secondary = true) {
                voice.stop(); currentCue = null
                review = record; screen = Screen.REVIEW; render()
            })
            gap(parent, 8)
        }
        gap(parent, 8)
        parent.addView(text("조작 실습 기록이며 운전 능력이나 안전을 평가하는 점수가 아닙니다.", 14, muted))
    }

    private fun practice(parent: LinearLayout) {
        val current = session?.snapshot() ?: return
        parent.addView(text("옆자리 코치 · 방향지시등", 28, ink, true))
        gap(parent, 14)
        val columns = practiceColumns(parent)
        val lesson = card(columns)
        lesson.addView(text("직접 조작하면 다음 안내로 이어져요", 17, teal, true))
        gap(lesson, 14)
        val arrow = when (current.nextAction) {
            PracticeAction.TURN_LEFT_ON -> "←"
            PracticeAction.TURN_RIGHT_ON -> "→"
            else -> "○"
        }
        lesson.addView(text(arrow, 58, teal, true).apply { gravity = Gravity.CENTER })
        lesson.addView(text(actionLabel(current.nextAction), 27, ink, true).apply { gravity = Gravity.CENTER })
        gap(lesson, 12)
        lesson.addView(text(currentCue?.text ?: "서두르지 않아도 괜찮아요. 방향지시등 조작부터 함께 익혀봐요.", 18, ink))
        gap(lesson, 8)
        lesson.addView(text("차를 움직이지 않는 상황 연습입니다. 실제 도로의 빈 공간이나 보행자를 앱이 확인하지는 않아요.", 14, muted))
        gap(lesson, 12)
        lesson.addView(text("지금 받은 신호: ${readingLabel()}", 19, teal))
        gap(lesson, 10)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 5; progress = current.completedStepCount
            progressTintList = android.content.res.ColorStateList.valueOf(teal)
        }
        lesson.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        gap(parent, 12)
        parent.addView(text("준비(꺼짐)  ›  왼쪽  ›  끄기  ›  오른쪽  ›  끄기", 16, muted))
        gap(parent, 14)
        parent.addView(button("이번 실습 그만하기", "stop_practice", secondary = true) { interrupt("USER_STOPPED"); render() })
    }

    private fun reviewScreen(parent: LinearLayout) {
        val record = review ?: run { screen = Screen.HOME; home(parent); return }
        val completed = record.outcome == PracticeOutcome.COMPLETED
        val panel = card(parent, if (completed) soft else Color.WHITE)
        panel.addView(text(if (completed) "한 번 더 익숙해졌어요" else "이번에는 여기까지 했어요", 28, ink, true))
        gap(panel, 10)
        panel.addView(text("${outcomeLabel(record)} · ${record.completedStepCount}/5 확인", 20, teal, true))
        panel.addView(text(date(record.startedAtMillis), 15, muted))
        if (completed && session?.snapshot()?.id == record.id && currentCue != null) {
            gap(panel, 10)
            panel.addView(text(currentCue!!.text, 17, ink))
        }
        if (completed && returnToCoach) {
            panel.addView(button("이어서 핸들 익히기", "coach_continue_steering") {
                voice.stop(); currentCue = null; steeringReview = null; screen = Screen.STEERING_SETUP
                if (hasSpeedPermission() && journey.vehicleDataEnabled) steering.connect(); render()
            })
        }
        if (!completed) {
            gap(panel, 10)
            panel.addView(text(reasonLabel(record.interruptionReason), 17, muted))
        }
        gap(parent, 16)
        parent.addView(text("차량에서 확인한 순서", 23, ink, true))
        gap(parent, 8)
        val significant = record.events.filter { it.disposition != ObservationDisposition.DUPLICATE }.takeLast(30)
        if (significant.isEmpty()) parent.addView(text("아직 확인된 조작이 없어요.", 17, muted))
        significant.forEach { event ->
            val mark = when (event.disposition) {
                ObservationDisposition.ADVANCED -> "✓"
                ObservationDisposition.CORRECTION_REQUIRED -> "↻"
                else -> "·"
            }
            val desc = when (event.disposition) {
                ObservationDisposition.ADVANCED -> "확인"
                ObservationDisposition.CORRECTION_REQUIRED -> "끄고 다시 연습"
                ObservationDisposition.OUT_OF_ORDER -> "오래된 신호 제외"
                ObservationDisposition.INVALID_SIGNAL -> "신호 확인 불가"
                else -> "대기"
            }
            parent.addView(text("$mark  ${clock(event.observedAtMillis)}   ${patternLabel(event.pattern)} · $desc", 17, if (event.disposition == ObservationDisposition.ADVANCED) teal else muted).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        gap(parent, 14)
        parent.addView(text("이 기록은 Pleos SDK가 알려준 조작 상태예요. 거울 확인·양보 판단·실제 운전 숙련도를 평가한 결과는 아닙니다.", 15, muted))
        gap(parent, 16)
        parent.addView(button("홈으로", "home") { goHome() })
        gap(parent, 8)
        parent.addView(button("이 기록 삭제", "delete_record", secondary = true) {
            AlertDialog.Builder(this).setTitle("이 연습 기록을 삭제할까요?")
                .setMessage("이 차량에 저장된 해당 기록만 삭제합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제") { _, _ -> delete(record.id) }.show()
        })
    }

    private fun offerCue(cue: CoachCue) {
        currentCue = cue
        if (foreground && parked) voice.speak(cue)
    }

    private fun goHome() {
        voice.stop()
        currentCue = null
        session = null
        steering.interrupt("USER_STOPPED", speak = false)
        steering.clearSession()
        steeringReview = null
        parkingSession = null
        screen = if (returnToCoach) Screen.COACH else Screen.HOME
        render()
    }

    private fun voiceControls(parent: LinearLayout) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val toggle = PleosSwitch(this).apply {
            text = "음성 안내"
            textSize = 17f
            setTextColor(ink)
            contentDescription = "voice_toggle"
            isChecked = voiceEnabled
            setOnCheckedChangeListener { _, checked ->
                voiceEnabled = checked
                getSharedPreferences("coach", MODE_PRIVATE).edit().putBoolean("voice_enabled", checked).apply()
                voice.setEnabled(checked)
                render()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(0, dp(50), 1f))
        val repeat = button("다시 듣기", "replay_voice", voiceEnabled && currentCue != null, secondary = true) {
            voice.stop()
            currentCue?.let(voice::speak)
        }
        voiceRepeatButton = repeat
        row.addView(repeat, LinearLayout.LayoutParams(dp(155), dp(50)))
        parent.addView(row)
        voiceStatusView = text(voiceStatus, 13, muted)
        parent.addView(voiceStatusView)
    }

    private fun operationGuide(parent: LinearLayout) {
        parent.addView(text("먼저, 어떻게 조작하나요?", 28, ink, true))
        gap(parent, 12)
        val column = practiceColumns(parent)
        val panel = card(column)
        panel.addView(text("실제 차 · 방향지시등 레버 예시", 20, teal, true))
        panel.addView(text("① 표시가 있는 레버를 찾아요.\n② 아래는 왼쪽, 위는 오른쪽이에요.\n③ 계기판 화살표를 확인해요.\n④ 레버를 중앙으로 돌려 꺼요.", 20, ink))
        gap(panel, 8)
        panel.addView(text("차종마다 조작부가 달라요. 내 차의 표시와 설명서를 먼저 확인하세요.", 15, muted))
        gap(panel, 8)
        if (emulatorTest) panel.addView(text("에뮬레이터에서는 옆의 ‘← 왼쪽 / 끄기 / 오른쪽 →’ 버튼을 누르세요. 실습을 시작한 다음 안내 순서에 맞춰 조작하면 돼요.", 17, teal))
        column.addView(button("이제 방향지시등 실습하기", "guide_start", databaseReady && !lastWriteError && sourceStatus.state == SourceState.READY) { begin() })
        column.addView(button("홈으로", "home", secondary = true) { goHome() })
    }

    /** Emulator controls sit beside the prompt, so the next input is visible without scrolling. */
    private fun practiceColumns(parent: LinearLayout): LinearLayout {
        val control = emulatorControls.createView() ?: return parent
        val wide = resources.configuration.screenWidthDp >= 900
        val row = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val lesson = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(lesson, if (wide) LinearLayout.LayoutParams(0, -2, 1.15f) else LinearLayout.LayoutParams(-1, -2))
        row.addView(control, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) } else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        parent.addView(row)
        return lesson
    }

    private fun hasSpeedPermission() = checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 30) {
            if (hasSpeedPermission() && foreground && journey.vehicleDataEnabled) steering.connect()
            render()
        }
    }

    private fun steeringSetup(parent: LinearLayout) {
        parent.addView(text("주차 전 · 기어와 조향 익히기", 27, ink, true))
        gap(parent, 12)
        val column = practiceColumns(parent)
        val panel = card(column)
        panel.addView(text("어떻게 조작하나요?", 21, teal, true))
        panel.addView(text("① P단과 정지를 확인해요. 주차 브레이크와 주변 안전은 직접 확인하세요.\n② 먼저 중앙 상태에서 시작해요.\n③ 안내에 따라 왼쪽 → 중앙 → 오른쪽 → 중앙으로 조작해요.", 19, ink))
        gap(panel, 10)
        if (emulatorTest) panel.addView(text("에뮬레이터: 옆의 −8°(왼쪽), 0°(중앙), +8°(오른쪽) 버튼을 눌러요. 이 숫자는 시험 입력값이며 실차 권장 각도가 아닙니다.", 16, teal))
        panel.addView(text("실차에서는 핸들을 손으로 조작합니다. 현재 버전은 에뮬레이터 검증용이며, 차종별 신호·조향 조건 확인이 먼저 필요해요.", 15, muted))
        gap(panel, 8)
        panel.addView(text("R·D 조작과 차량 이동은 이번 실습에 포함하지 않아요. 기어가 P를 벗어나거나 정지 상태를 확인하지 못하면 멈춥니다.", 15, muted))
        gap(column, 10)
        column.addView(text(steering.status.message, 16, muted))
        column.addView(text(steeringSignalLabel(), 16, teal))
        if (!hasSpeedPermission()) {
            column.addView(button("차량 속도 읽기 허용", "grant_car_speed") { requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 30) })
            column.addView(text("정지 상태를 확인하는 데 필요해요. 허용하지 않아도 방향지시등 실습은 사용할 수 있어요.", 14, muted))
        } else {
            column.addView(button("신호 다시 연결", "steering_reconnect", secondary = true) { steering.stop("USER_RECONNECTED"); steering.connect() })
        }
        column.addView(button("안내 확인 · 조작 실습 시작", "steering_start", hasSpeedPermission() && steering.storageReady && steering.storageError == null && steering.status.state == SourceState.READY) {
            if (!parked || !foreground) return@button
            voice.stop(); currentCue = null; steeringReview = null
            screen = Screen.STEERING; steering.begin(); render()
        })
        column.addView(button("홈으로", "home", secondary = true) { goHome() })
    }

    private fun steeringSignalLabel(): String {
        val values = steering.readings
        val gear = values[ParkingSignalField.GEAR]?.gear?.takeUnless { it == ParkingGear.UNKNOWN }?.name ?: "미확인"
        fun format(value: Double?) = value?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "미확인"
        return "기어 $gear  ·  속도 ${format(values[ParkingSignalField.SPEED]?.speedMps?.times(3.6))} km/h\nSDK 전륜 조향각 ${format(values[ParkingSignalField.STEERING]?.angleDegrees)}° (핸들 회전각 아님)"
    }

    private fun steeringPractice(parent: LinearLayout) {
        val record = steeringReview ?: steering.snapshot ?: run { steeringSetup(parent); return }
        parent.addView(text(if (record.isTerminal) "기어·조향 실습 복습" else "안내대로 조작해 봐요", 27, ink, true))
        gap(parent, 12)
        val column = if (!record.isTerminal) practiceColumns(parent) else parent
        val panel = card(column, if (record.outcome == PracticeOutcome.COMPLETED) soft else Color.WHITE)
        panel.addView(text(if (record.isTerminal) "${record.completedSteps}/4 조작 확인" else "옆자리 코치 · 조작을 기다리고 있어요", 19, teal, true))
        val action = when(record.nextAction) {
            SteeringPracticeAction.LEFT -> "← 왼쪽 조작"
            SteeringPracticeAction.RIGHT -> "오른쪽 조작 →"
            SteeringPracticeAction.CENTER -> "○ 중앙으로 돌아오기"
            SteeringPracticeAction.WAIT_FOR_SIGNALS -> "P·정지·조향 신호 확인 중"
            SteeringPracticeAction.NONE -> if (record.outcome == PracticeOutcome.COMPLETED) "차근차근 잘했어요" else "이번 실습을 멈췄어요"
        }
        panel.addView(text(action, 28, ink, true))
        gap(panel, 10)
        val cue = SteeringCoach.catalog.firstOrNull { it.id == record.latestFeedbackKey }
        panel.addView(text(cue?.text ?: "신호를 기다리고 있어요.", 18, ink))
        gap(panel, 12)
        panel.addView(text(if (record.isTerminal) "확인한 기어 ${record.gear.name} · 조향 ${record.angleDegrees ?: "미확인"}°" else steeringSignalLabel(), 17, teal))
        if (record.isTerminal) {
            if (record.outcome != PracticeOutcome.COMPLETED) panel.addView(text(steeringReason(record.interruptionReason), 18, muted))
            gap(parent, 12)
            parent.addView(text("수신 신호로 확인한 과정", 21, ink, true))
            record.events.takeLast(20).forEach { event ->
                val label = SteeringCoach.catalog.firstOrNull { it.id == event.feedbackKey }?.text ?: event.feedbackKey
                parent.addView(text("${event.sequence}. $label", 15, muted).apply { setPadding(0, dp(5), 0, dp(5)) })
            }
            parent.addView(button("홈으로", "home") { goHome() })
            parent.addView(button("이 조향 기록 삭제", "delete_steering", secondary = true) {
                AlertDialog.Builder(this).setTitle("이 조향 기록을 삭제할까요?").setNegativeButton("취소", null)
                    .setPositiveButton("삭제") { _, _ -> steering.delete(record.id); goHome() }.show()
            })
        } else {
            column.addView(text("중앙 준비는 0/4예요. 이후 변경 신호 네 번을 확인합니다.", 14, muted))
            column.addView(button("이번 실습 그만하기", "stop_steering", secondary = true) { steering.interrupt("USER_STOPPED"); render() })
        }
    }

    private fun steeringReason(reason: String?) = when(reason) {
        "GEAR_NOT_P" -> "기어가 P가 아니어서 중단했어요. P로 복귀한 뒤 새로 시작해요."
        "VEHICLE_MOVING" -> "정지 상태가 아닌 속도 신호를 받아 중단했어요."
        else -> if (reason?.startsWith("SIGNAL_STALE") == true) "필요한 신호가 제때 갱신되지 않아 중단했어요. 다시 연결해 주세요." else reasonLabel(reason)
    }

    private fun parking(parent: LinearLayout) {
        val lesson = parkingSession ?: ParkingLessonSession().also { parkingSession = it }
        parent.addView(ParkingLessonView(this, lesson,
            onCue = { cue -> offerCue(cue) },
            onFinish = { goHome() },
            onHome = { goHome() },
        ))
    }

    private fun delete(id: String) {
        io.execute {
            try {
                repository.delete(id)
                val records = repository.listRecent()
                runOnUiThread {
                    if (!isDestroyed) { history = records; review = null; goHome() }
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "기록을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1.03f)
    }

    private fun button(label: String, key: String, enabled: Boolean = true, secondary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; isEnabled = enabled
        contentDescription = key
        PleosUi.decorate(this, !secondary)
        minHeight = dp(56)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) }
        setOnClickListener { action() }
    }

    private fun card(parent: LinearLayout, color: Int = Color.WHITE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 20)
        setPadding(dp(22), dp(20), dp(22), dp(20))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun date(time: Long) = SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.KOREA).format(Date(time))
    private fun clock(time: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(time))
    private fun outcomeLabel(record: PracticeSnapshot) = if (record.outcome == PracticeOutcome.COMPLETED) "실습 완료" else "실습 중단"
    private fun readingLabel(): String = reading?.let {
        if (!it.isKnown) "확인 불가" else if (it.left == SignalLamp.ACTIVE) "← 왼쪽 켜짐" else if (it.right == SignalLamp.ACTIVE) "오른쪽 켜짐 →" else "꺼짐"
    } ?: "수신 대기"
    private fun patternLabel(pattern: SignalPattern) = when (pattern) {
        SignalPattern.OFF -> "꺼짐"
        SignalPattern.LEFT -> "왼쪽 켜짐"
        SignalPattern.RIGHT -> "오른쪽 켜짐"
        SignalPattern.BOTH -> "양쪽 신호"
        SignalPattern.UNKNOWN -> "알 수 없는 신호"
    }
    private fun actionLabel(action: PracticeAction) = when (action) {
        PracticeAction.TURN_OFF_ALL -> "방향지시등을 모두 꺼 주세요"
        PracticeAction.TURN_LEFT_ON -> "왼쪽 방향지시등을 켜 주세요"
        PracticeAction.TURN_LEFT_OFF -> "왼쪽 방향지시등을 꺼 주세요"
        PracticeAction.TURN_RIGHT_ON -> "오른쪽 방향지시등을 켜 주세요"
        PracticeAction.TURN_RIGHT_OFF -> "오른쪽 방향지시등을 꺼 주세요"
        PracticeAction.NONE -> "실습을 마쳤어요"
    }
    private fun reasonLabel(reason: String?) = when (reason) {
        "USER_STOPPED" -> "직접 실습을 종료했어요. 다음에 새로 시작할 수 있어요."
        "SCREEN_LEFT" -> "앱 화면을 벗어나 실습을 중단했어요."
        "PROCESS_RESTART" -> "앱이 종료되어 진행 중이던 실습을 중단 기록으로 복구했어요."
        "DRIVING_RESTRICTION" -> "정차 사용 가능 상태가 바뀌어 실습을 중단했어요."
        "STORAGE_ERROR" -> "기록을 저장하지 못해 실습을 중단했어요."
        else -> "차량 신호를 계속 확인할 수 없어 실습을 중단했어요. 다시 연결한 뒤 시작해 주세요."
    }
}
