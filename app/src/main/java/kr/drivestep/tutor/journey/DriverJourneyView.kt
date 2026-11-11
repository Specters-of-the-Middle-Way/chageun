package kr.drivestep.tutor.journey

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kr.drivestep.tutor.speech.TutorSpeechController
import kr.drivestep.tutor.speech.SpeechStatusText
import kr.drivestep.tutor.connected.ReadinessSnapshot
import kr.drivestep.tutor.workflow.InputValidity
import kr.drivestep.tutor.R
import kr.drivestep.tutor.vehicle.TrainingVehicle
import kr.drivestep.tutor.vehicle.VehicleEducation
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PleosSwitch
import kr.drivestep.tutor.design.PracticeButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The parked-only service journey. SDK subscriptions and vehicle UX gating belong to the host. */
// Created in code with required host/session arguments; never inflated from XML.
@SuppressLint("ViewConstructor")
class DriverJourneyView(
    context: Context,
    private val onDestination: (JourneyDestination) -> Unit,
    private val onSettingsChanged: () -> Unit,
    private val vehicleEducation: VehicleEducation = TrainingVehicle.education,
) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
    private val bg = PleosUi.BACKGROUND
    private val panel = PleosUi.SURFACE
    private val raised = PleosUi.RAISED
    private val white = PleosUi.TEXT
    private val grey = PleosUi.SECONDARY
    private val line = PleosUi.LINE
    private val mint = PreparationStyle.accent
    private val amber = PleosUi.SECONDARY
    private var state = restoreState()
    private var active = false
    private var closed = false
    private var lastPractice: JourneyDestination? = null
    private var selfFeeling = "차근차근 해봤어요"
    private var selfDifficulty = "핸들 조작"
    private var storageMessage: String? = null
    private val enabledControls = mutableListOf<Pair<View, () -> Boolean>>()
    private var dialog: AlertDialog? = null
    private var content: LinearLayout? = null
    private var pageScroll: ScrollView? = null
    private var lastPage: JourneyStep? = null
    private var speechContext: String? = null
    private val vehicleToast = PreparationToast(this)
    private var readiness: ReadinessSnapshot? = null
    private var setupSpeechLabel: TextView? = null
    private var setupSpeechStatus = "음성으로 듣기를 누르면 현재 단계의 안내를 읽어요."
    private val setupSpeech = TutorSpeechController(context, onTranscript = {}, onStatus = {
        setupSpeechStatus = SpeechStatusText.forDisplay(if (it.contains("대화 시작")) "음성 안내를 껐어요. 화면에서 설명을 확인할 수 있어요." else it.replace("음성 입력과 안내", "음성 안내"))
        setupSpeechLabel?.let { label -> label.text = setupSpeechStatus; label.visibility = if (setupSpeechStatus.isBlank()) GONE else VISIBLE }
    })
    fun setSpeechEnabled(value: Boolean) { setupSpeech.setEnabled(value) }

    val recordEnabled: Boolean get() = state.recordConsent
    val vehicleDataEnabled: Boolean get() = state.vehicleConsent
    val canPractice: Boolean get() = state.canPractice
    fun preparationForGleo() = kr.drivestep.tutor.gleo.GleoActionPolicy.preparation(
        state.setupCompletedCount, state.preflightCompletedCount, state.canPractice,
        state.vehicleConsent, readiness, SystemClock.elapsedRealtime())
    val isGuidanceSpeaking: Boolean get() = setupSpeech.isSpeaking

    /** Shared consent choice for the profile switch and the lesson's explicit consent dialog. */
    fun setVehicleDataEnabled(value: Boolean) {
        if (closed || state.vehicleConsent == value) return
        state.vehicleConsent = value
        persist()
        onSettingsChanged()
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(bg)
        render()
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        setupSpeech.setActive(value)
        if (!value) { dialog?.dismiss(); dialog = null; vehicleToast.cancel() }
        updateEnabled()
    }

    fun openProfile() { state.enterProfile(); render() }
    fun acceptVehicle(value: ReadinessSnapshot, changed: Set<Int>): Boolean {
        readiness = value
        val invalidated = state.invalidateVehiclePreparation(changed)
        if (invalidated) { persist(); render() }
        return invalidated
    }

    fun requestPreparation() { state.enterPreflight(); render() }

    fun beginVisit() {
        state.newVisit()
        render()
    }

    fun close() { setupSpeech.close(); closed = true; active = false; dialog?.dismiss(); dialog = null; vehicleToast.cancel() }

    fun onReturnFromPractice(destination: JourneyDestination? = null) {
        lastPractice = destination ?: lastPractice
        state.enterReview()
        render()
    }

    fun goBack(): Boolean {
        when (state.page) {
            JourneyStep.PROFILE, JourneyStep.REVISIT -> return false
            JourneyStep.SETUP -> state.enterProfile()
            JourneyStep.PREFLIGHT -> state.enterSetup()
            JourneyStep.PRACTICE -> state.enterPreflight()
            JourneyStep.EDUCATION, JourneyStep.REVIEW -> if (state.canPractice) state.enterPractice() else state.enterPreflight()
        }
        render()
        return true
    }

    private fun render() {
        val nextSpeechContext = "${state.page}:${state.setupIndex}"
        val previousScroll = if (speechContext == nextSpeechContext) pageScroll?.scrollY ?: 0 else 0
        pageScroll = null
        if (speechContext != nextSpeechContext) { setupSpeech.stop(); speechContext = nextSpeechContext }
        setupSpeechLabel = null; vehicleToast.cancel()
        removeAllViews(); enabledControls.clear()
        val side = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(bg)
        }
        addView(side, LayoutParams(dp(224), LayoutParams.MATCH_PARENT))
        side.addView(label("운전 튜터", 24, white, true), full(48))
        side.addView(label(TrainingVehicle.NAME, 18, grey), full(34))
        gap(side, 24)
        listOf(
            Triple("profile", "운전자", JourneyStep.PROFILE),
            Triple("seat", "운전 환경", JourneyStep.SETUP),
            Triple("check", "출발 준비", JourneyStep.PREFLIGHT),
            Triple("steering", "연습", JourneyStep.PRACTICE),
            Triple("menu", "기록", JourneyStep.REVIEW),
        ).forEach { (icon, title, page) ->
            val selected = page == state.page || (page == JourneyStep.PROFILE && state.page == JourneyStep.REVISIT)
            side.addView(button(title, "journey_nav_${page.name.lowercase()}") {
                when (page) {
                    JourneyStep.PROFILE -> state.enterProfile()
                    JourneyStep.SETUP -> state.enterSetup()
                    JourneyStep.PREFLIGHT -> state.enterPreflight()
                    JourneyStep.PRACTICE -> state.enterPractice()
                    JourneyStep.REVIEW -> state.enterReview()
                    else -> Unit
                }
                render()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                setCompoundDrawables(PleosUi.icon(context, icon, 28, if (selected) mint else white), null, null, null)
                compoundDrawablePadding = dp(12)
                background = shape(if (selected) PreparationStyle.softAccent else bg, 16)
                setTextColor(if (selected) mint else white)
                if (selected) setTypeface(typeface, Typeface.BOLD)
            }, full(64).apply { bottomMargin = dp(8) })
        }
        side.addView(View(context), LayoutParams(1, 0, 1f))
        side.addView(button("정차 교육", "journey_education") { state.enterEducation(); render() }, full(64))
        addView(View(context).apply { setBackgroundColor(line) }, LayoutParams(dp(1), -1))
        val body = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18))
        }
        addView(body, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        content = body
        when (state.page) {
            JourneyStep.PROFILE -> profilePage(body)
            JourneyStep.REVISIT -> revisitPage(body)
            JourneyStep.SETUP -> setupPage(body)
            JourneyStep.PREFLIGHT -> preflightPage(body)
            JourneyStep.PRACTICE -> practicePage(body)
            JourneyStep.EDUCATION -> educationPage(body)
            JourneyStep.REVIEW -> reviewPage(body)
        }
        if (lastPage != state.page) {
            Log.i("DriverJourney", "page=${state.page} setup=${state.setupCompletedCount}/9 preflight=${state.preflightCompletedCount}/7 records=$recordEnabled vehicle=$vehicleDataEnabled")
            lastPage = state.page
        }
        updateEnabled()
        pageScroll?.let { scroll -> scroll.post { scroll.scrollTo(0, previousScroll) } }
    }

    private fun profilePage(parent: LinearLayout) {
        heading(parent, "", "운전자 설정", "나에게 맞는 준비를 위해 체격과 운전 경험을 알려주세요.")
        state.profileInputError?.let { parent.addView(label(it, 19, grey), full(-2)) }
        val page = scrollBody(parent)
        val columns = PreparationColumns(context, 1f, 1.7f)
        page.addView(columns, full(-2))
        val overview = LinearLayout(context).apply { orientation = VERTICAL }
        columns.addView(overview)
        val car = card()
        overview.addView(car, full(-2))
        val model = horizontal(car, 64)
        model.addView(PleosUi.image(context, "vehicle", 44, mint), LayoutParams(dp(52), dp(52)))
        model.addView(label(TrainingVehicle.NAME, 28, white, true), LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        car.addView(label(TrainingVehicle.VARIANT, 19, grey), full(-2).apply { bottomMargin = dp(18) })
        specRow(car, "전장", context.getString(R.string.vehicle_dimension_mm, TrainingVehicle.LENGTH_MM))
        specRow(car, "전폭", context.getString(R.string.vehicle_dimension_mm, TrainingVehicle.WIDTH_MM))
        specRow(car, "전고", context.getString(R.string.vehicle_dimension_mm, TrainingVehicle.HEIGHT_MM))
        specRow(car, "축거", context.getString(R.string.vehicle_dimension_mm, TrainingVehicle.WHEELBASE_MM))
        val privacy = card()
        overview.addView(privacy, full(-2).apply { topMargin = dp(20) })
        privacy.addView(label("저장과 차량 연결", 23, white, true), full(-2))
        privacy.addView(label("필요한 항목만 선택해 주세요.", 18, grey), full(-2).apply { topMargin = dp(8); bottomMargin = dp(12) })
        consentRow(privacy, "프로필·연습 기록", "이 기기에 저장", "journey_consent_records", state.recordConsent) {
            state.recordConsent = it; persist(); onSettingsChanged()
        }
        divider(privacy)
        consentRow(privacy, "차량 정보 연결", "차량 조작·시트·안전벨트 상태 읽기", "journey_consent_vehicle", state.vehicleConsent) {
            setVehicleDataEnabled(it)
        }
        if (prefs.getBoolean("has_profile", false)) privacy.addView(button("준비 정보 삭제", "journey_delete") { confirmDelete() }, full(-2))

        val form = card()
        columns.addView(form)
        form.addView(label("나에게 맞는 안내", 25, white, true), full(-2).apply { bottomMargin = dp(22) })
        val bodyInputs = PreparationColumns(context, breakpoint = 640)
        form.addView(bodyInputs, full(-2))
        val heightInput = LinearLayout(context).apply { orientation = VERTICAL }
        val weightInput = LinearLayout(context).apply { orientation = VERTICAL }
        bodyInputs.addView(heightInput); bodyInputs.addView(weightInput)
        addSlider(heightInput, "키", state.profile.heightCm, 150, 200, "cm", "journey_height") {
            state.updateProfile(state.profile.copy(heightCm = it)); persist()
        }
        addSlider(weightInput, "체중", state.profile.weightKg, 50, 110, "kg", "journey_weight") {
            state.updateProfile(state.profile.copy(weightKg = it)); persist()
        }
        gap(form, 14); divider(form); gap(form, 20)
        choiceRow(form, "운전 경험", listOf("처음 운전", "장롱면허", "종종 운전"), state.profile.experience, "journey_experience") {
            state.updateProfile(state.profile.copy(experience = it)); persist(); render()
        }
        gap(form, 20)
        addSlider(form, "내가 느끼는 자신감", state.profile.confidence, 1, 5, "/ 5", "journey_confidence") {
            state.updateProfile(state.profile.copy(confidence = it)); persist()
        }
        gap(form, 20)
        choiceRow(form, "내가 편한 안내 방식", listOf("차근차근", "짧게"), state.profile.guidance, "journey_guidance") {
            state.updateProfile(state.profile.copy(guidance = it)); persist(); render()
        }
        form.addView(label("선호하는 방식을 기록해요. 현재 음성 안내 길이는 같아요.", 18, grey), full(-2).apply { topMargin = dp(14) })
        parent.addView(button("이 설정으로 운전 환경 확인", "journey_profile_continue", selected = true) {
            state.confirmProfile(); persist(); render()
        }, full(-2).apply { topMargin = dp(18) })
    }

    private fun revisitPage(parent: LinearLayout) {
        heading(parent, "", "운전자와 차량 확인", "운전자가 바뀌었거나 자세를 조절했다면 운전 환경을 다시 확인하세요.")
        val summary = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        parent.addView(summary, full(190))
        summary.addView(PleosUi.image(context, "profile", 88), LayoutParams(dp(120), dp(120)))
        val info = LinearLayout(context).apply { orientation = VERTICAL }
        summary.addView(info, LayoutParams(0, -2, 1f).apply { marginStart = dp(28) })
        info.addView(label(TrainingVehicle.NAME, 32, white, true), full(50))
        info.addView(label("${state.profile.heightCm} cm  ·  ${state.profile.weightKg} kg  ·  ${state.profile.experience}", 22, white), full(40))
        info.addView(label("운전 환경 ${state.setupCompletedCount}/9 항목 확인", 18, grey), full(36))
        divider(parent)
        parent.addView(label("출발 전 확인은 이용할 때마다 다시 진행합니다.", 20, grey), full(76))
        parent.addView(button("같은 운전자 · 출발 준비 확인", "journey_revisit", selected = true) { state.revisitUnchanged(); render() }, full(72))
        gap(parent, 16)
        parent.addView(button("운전자 또는 자세 변경", "journey_revisit_changed") { state.revisitChanged(); persist(); render() }, full(72))
    }

    private fun setupPage(parent: LinearLayout) {
        val index = state.setupIndex
        val step = setupSteps[index]
        val progress = heading(parent, "", "운전 환경",
            context.getString(R.string.journey_setup_progress, index + 1, state.setupCompletedCount))
        val stepButtons = mutableListOf<Button>()
        fun refreshChecks() {
            progress.text = context.getString(R.string.journey_setup_progress, index + 1, state.setupCompletedCount)
            state.setupChecks().forEachIndexed { i, done -> stepButtons[i].text = setupStepLabel(i, done) }
        }
        val page = scrollBody(parent)
        val columns = PreparationColumns(context, 1f, 3f)
        page.addView(columns, full(-2))
        val order = card(18)
        columns.addView(order)
        order.addView(label("확인 순서", 23, white, true), full(-2).apply { bottomMargin = dp(16) })
        state.setupChecks().forEachIndexed { i, done ->
            val next = button(setupStepLabel(i, done), "journey_setup_step_$i", selected = i == index) {
                state.enterSetup(i); render()
            }.apply {
                textSize = 18f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14)); minHeight = dp(56); minimumHeight = dp(56)
                PreparationStyle.button(this, i == index, soft = true)
            }
            stepButtons += next
            order.addView(next, full(-2).apply { bottomMargin = dp(8) })
        }
        val info = card()
        columns.addView(info)
        info.addView(label(step.first, 28, white, true), full(-2))
        info.addView(label(step.second, 22, white), full(-2).apply { topMargin = dp(14); bottomMargin = dp(14) })
        info.addView(label("차량에서 직접 조절해 주세요. 화면의 조절값은 가상 가이드예요.", 18, mint).apply {
            background = PreparationStyle.surface(context, PreparationStyle.softAccent)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }, full(-2))
        val guideView = PostureGuideView(context).apply { update(index, state.profile, state.calibration) }
        info.addView(guideView, full(300).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val axis = when (index) { 0 -> CalibrationAxis.FORE_AFT; 1 -> CalibrationAxis.HEIGHT; 2 -> CalibrationAxis.BACKREST; 4 -> CalibrationAxis.STEERING; else -> null }
        if (axis != null) {
            addSlider(info, "가상 조절값", state.calibration.value(axis), 0, 100, "/ 100", "journey_calibration_${axis.name.lowercase()}") {
                state.setCalibration(axis, it); guideView.update(index, state.profile, state.calibration)
                refreshChecks(); persist()
            }
            val band = state.guideBand(axis)
            val preset = PreparationColumns(context, 1.4f, 1f, 700)
            info.addView(preset, full(-2))
            preset.addView(label("체격 참고 구간 ${band.minRecommended}~${band.maxRecommended}\n50은 중립 시작값이에요.", 18, grey))
            preset.addView(button("체격 참고 시작값 적용", "journey_apply_recommended_preset") {
                state.applyRecommendedPreset(); persist(); render()
            })
        } else info.addView(label(if (index in 5..7) "뒤와 옆의 시야를 직접 확인해요. 그림은 실제 미러 영상이나 각도 측정 결과가 아니에요."
            else "차량의 조절 장치와 사용설명서를 확인해요. 올바른 착용·조절 상태는 직접 살펴주세요.", 19, grey), full(-2))
        val support = PreparationColumns(context, breakpoint = 700)
        info.addView(support, full(-2).apply { topMargin = dp(16) })
        support.addView(button("음성으로 듣기", "journey_setup_speak") { setupSpeech.speak(step.first + ". " + step.second) })
        support.addView(button("차량 상태 보기", "journey_setup_vehicle_details") {
            vehicleToast.show(PreparationVehicleStatus.text(index, readiness, state.vehicleConsent, SystemClock.elapsedRealtime()))
        })
        info.addView(label("시트 설정값을 보여드려요. 자세와 벨트 꼬임, 미러 시야는 직접 확인해 주세요.", 18, grey), full(-2).apply { topMargin = dp(12) })
        setupSpeechLabel = label(setupSpeechStatus, 17, grey).also {
            it.contentDescription = "journey_setup_speech_status"
            it.visibility = if (setupSpeechStatus.isBlank()) GONE else VISIBLE
            info.addView(it, full(-2).apply { topMargin = dp(12) })
        }
        val actions = PreparationColumns(context, 1f, 2f, 760)
        parent.addView(actions, full(-2).apply { topMargin = dp(18) })
        actions.addView(button("설명 다시 확인", "journey_setup_skip_help") {
            state.leaveSetupUnresolved(); refreshChecks(); persist()
            showMessage(step.first, step.second + "\n\n확인하지 못한 항목은 완료로 바꾸지 않아요. 불편한 부분을 다시 조절한 뒤 확인하거나 정차 교육을 선택하세요.")
        })
        actions.addView(button("조절 및 확인 완료", "journey_setup_confirm", selected = true) {
            if (index == 8 && readiness?.belt?.quality == InputValidity.VALID && readiness?.belt?.value == false) {
                showMessage("안전벨트가 채워지지 않았어요", "안전벨트를 채워 주세요. 벨트가 꼬이지 않았는지, 몸에 잘 맞는지도 확인해 주세요.")
            } else { state.confirmSetupStep(); persist(); render() }
        })
    }

    private fun preflightPage(parent: LinearLayout) {
        heading(parent, "", "출발 준비", "차량과 주변을 직접 살펴본 뒤, 확인한 항목을 눌러 주세요.")
        val page = scrollBody(parent)
        val status = card(20, PreparationStyle.softAccent)
        page.addView(status, full(-2).apply { bottomMargin = dp(20) })
        status.addView(label("${state.preflightCompletedCount}/7 항목을 확인했어요", 24, mint, true), full(-2))
        status.addView(label(if (state.canPractice) "준비 확인을 마쳤어요. 연습할 항목을 골라 보세요."
            else "모든 항목을 확인하면 연습을 선택할 수 있어요.", 18, grey), full(-2).apply { topMargin = dp(8) })
        val columns = PreparationColumns(context)
        page.addView(columns, full(-2))
        listOf("차량과 주변" to listOf(1, 2, 3, 5), "운전자 확인" to listOf(0, 4, 6)).forEach { (title, indices) ->
            val checks = LinearLayout(context).apply { orientation = VERTICAL }
            columns.addView(checks)
            checks.addView(label(title, 23, white, true), full(-2).apply { bottomMargin = dp(14) })
            indices.forEach { index ->
                val item = preflightItems[index]
                val done = state.preflightChecks()[index]
                val titleText = item.first + if (done) "  ✓" else ""
                val check = button("", "journey_preflight_$index", selected = done) { state.setPreflight(index, !done); render() }.apply {
                    text = SpannableString("$titleText\n${item.second}").apply {
                        setSpan(StyleSpan(Typeface.BOLD), 0, titleText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(.8f), titleText.length + 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    textSize = 22f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setLineSpacing(dp(8).toFloat(), 1f)
                    setPadding(dp(20), dp(22), dp(20), dp(22)); minHeight = dp(116); minimumHeight = dp(116)
                    PreparationStyle.button(this, done, soft = true)
                    setCompoundDrawablesRelative(PleosUi.checkIcon(context, done, 32), null, null, null)
                    compoundDrawablePadding = dp(16)
                }
                checks.addView(check, full(-2).apply { bottomMargin = dp(12) })
            }
        }
        val actions = PreparationColumns(context, 1f, 2f, 760)
        parent.addView(actions, full(-2).apply { topMargin = dp(18) })
        actions.addView(button("교육 보기", "journey_preflight_hold") { state.enterEducation(); render() })
        actions.addView(button("연습 선택", "journey_preflight_continue", selected = true, condition = { state.canPractice }) {
            state.enterPractice(); render()
        })
    }

    private fun practicePage(parent: LinearLayout) {
        heading(parent, "", "연습 선택", "지금 연습하고 싶은 항목을 골라 차근차근 익혀 보세요.")
        val page = scrollBody(parent)
        val status = card(20, PreparationStyle.softAccent)
        page.addView(status, full(-2).apply { bottomMargin = dp(24) })
        status.addView(label("준비 확인을 마쳤어요", 24, mint, true), full(-2))
        status.addView(label("${TrainingVehicle.NAME} · ${if (recordEnabled) "연습 기록 저장 켜짐" else "저장 없이 연습해요"}", 18, grey), full(-2).apply { topMargin = dp(8) })
        val firstRow = PreparationColumns(context, breakpoint = 900)
        page.addView(firstRow, full(-2).apply { bottomMargin = dp(20) })
        firstRow.addView(practiceCard("menu", "준비와 연습", "날씨·차량 기능·목표별 코스와 상황 연습", "journey_start_workflow", true) { launch(JourneyDestination.WORKFLOW) })
        firstRow.addView(practiceCard("driving", "차로 변경", "방향지시등·미러 확인·조향 순서 익히기", "journey_start_drive") { launch(JourneyDestination.DRIVE) })
        val secondRow = PreparationColumns(context, breakpoint = 900)
        page.addView(secondRow, full(-2))
        secondRow.addView(practiceCard("vehicle", "주차", "후진·T자·평행·전면 주차 연습", "journey_start_parking") { launch(JourneyDestination.PARKING) })
        secondRow.addView(practiceCard("sound", "함께하는 연수", "짧은 대화로 다음 상황을 준비하고 복습하기", "journey_start_lesson") { launch(JourneyDestination.LESSON) })
        page.addView(label("화면 속 연습은 실제 운전과 구분해요. 차량은 P에 놓고 멈춘 상태로 이용해 주세요.", 18, grey), full(-2).apply { topMargin = dp(20) })
        val actions = PreparationColumns(context, breakpoint = 760)
        parent.addView(actions, full(-2).apply { topMargin = dp(18) })
        actions.addView(button("정차 중 교육", "journey_course_education") { state.enterEducation(); render() })
        actions.addView(button("운전 환경 다시 확인", "journey_adjust") { state.enterSetup(); render() })
    }

    private fun practiceCard(icon: String, title: String, subtitle: String, key: String,
        featured: Boolean = false, action: () -> Unit): Button = button("", key, selected = featured, condition = { state.canPractice }, action = action).apply {
        val words = "$title\n$subtitle\n시작하기 →"
        text = SpannableString(words).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(.75f), title.length + 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = 25f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setLineSpacing(dp(10).toFloat(), 1f)
        setPadding(dp(26), dp(26), dp(26), dp(26)); minHeight = dp(196); minimumHeight = dp(196)
        setCompoundDrawablesRelative(PleosUi.icon(context, icon, 42, if (featured) Color.WHITE else mint), null, null, null)
        compoundDrawablePadding = dp(22)
    }

    private fun educationPage(parent: LinearLayout) {
        heading(parent, "", "정차 중 교육", "차량을 연결하지 않아도 운전의 기본을 배울 수 있어요.")
        val scroll = ScrollView(context)
        parent.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(list)
        if (vehicleEducation.showEvEducation) educationTile(list, "전기차의 감속", "회생제동과 브레이크를 구분해요", "journey_ev_braking") {
            showMessage("회생제동과 브레이크", "회생제동은 감속 과정의 에너지를 회수하는 기능이에요. 모드와 배터리 상태 등에 따라 감속 느낌이 달라질 수 있어요.\n\n원페달 기능이 있더라도 모든 상황의 정지·정차 유지를 대신한다고 가정하지 않아요. 현재 차량의 사용설명서와 계기판 표시를 먼저 확인하고, 필요한 제동은 운전자가 직접 수행해요.\n\n이 앱은 실제 회생제동 모드를 변경하지 않아요.", educationId = "ev_braking")
        }
        educationTile(list, "주차를 시작하기 전", "기어와 조향, 주변 확인의 순서를 알아봐요", "journey_parking_guide") {
            showMessage("주차 연습 준비", "연습할 장소와 주변 사람·차량을 먼저 직접 살펴요. 기어를 바꾸기 전에는 멈추고 브레이크를 유지해요.\n\n가상 주차에서는 화면 속 기어·핸들·페달을 사용해요. 실제 차량은 P·정차 상태를 유지합니다. 화면의 예상선은 가상 모델이 그린 경로예요.\n\n실제 주차선·장애물과의 거리를 자동으로 인식하는 기능은 아니에요.", educationId = "parking_prepare")
        }
        educationTile(list, "방향지시등 조작", "차량에서 방향지시등을 켜고 끄며 익혀요", "journey_sdk_signal") { requestVehicleLesson(JourneyDestination.SIGNAL) }
        educationTile(list, "핸들 조작", "P에 놓고 멈춘 상태에서 핸들 조작을 익혀요", "journey_sdk_steering") { requestVehicleLesson(JourneyDestination.STEERING) }
        educationTile(list, "날씨·차량 기능·가상 코스", "준비와 학습, 목표별 조작 연습을 연결해요", "journey_workflow") { launch(JourneyDestination.WORKFLOW, requireReady = false) }
        educationTile(list, "함께하는 연수", "대화와 준비 안내를 연결하고 조작 기록을 복습해요", "journey_lesson") { launch(JourneyDestination.LESSON, requireReady = false) }
        gap(parent, 12)
        parent.addView(button(if (state.canPractice) "연습 선택으로" else "준비하던 단계로", "journey_education_back", selected = true) {
            if (state.canPractice) state.enterPractice() else state.enterPreflight(); render()
        }, full(64))
    }

    private fun reviewPage(parent: LinearLayout) {
        heading(parent, "", "연습 기록", "배운 내용과 내 느낌을 돌아보고, 다음 연습을 준비해요.")
        val page = scrollBody(parent)
        val columns = PreparationColumns(context, 1f, 1.35f)
        page.addView(columns, full(-2))
        val overview = LinearLayout(context).apply { orientation = VERTICAL }
        columns.addView(overview)
        val summary = card()
        overview.addView(summary, full(-2))
        summary.addView(label("나의 연습 현황", 25, white, true), full(-2).apply { bottomMargin = dp(20) })
        val metrics = PreparationColumns(context, breakpoint = 440)
        summary.addView(metrics, full(-2))
        val learned = prefs.getStringSet("education", emptySet())?.size ?: 0
        metric(metrics, "학습 완료", "$learned 개", "정차 중 교육 확인")
        metric(metrics, "내 자신감", "${state.profile.confidence} / 5", "내가 응답한 값")
        summary.addView(label("연습 기록과 자신감은 실제 운전 실력 평가가 아니에요.", 18, grey), full(-2).apply { topMargin = dp(18) })

        val history = card()
        overview.addView(history, full(-2).apply { topMargin = dp(20) })
        history.addView(label("최근 남긴 소감", 23, white, true), full(-2).apply { bottomMargin = dp(16) })
        val reports = readReports().take(3)
        if (reports.isEmpty()) {
            history.addView(label("아직 남긴 소감이 없어요", 21, white, true), full(-2))
            history.addView(label("연습 후 느낌과 다시 해보고 싶은 것을 기록해 보세요.", 19, grey), full(-2).apply { topMargin = dp(10) })
        } else reports.forEachIndexed { index, report ->
            if (index > 0) { gap(history, 16); divider(history); gap(history, 16) }
            history.addView(label(date(report.optLong("at")), 17, grey), full(-2))
            history.addView(label(report.optString("feeling"), 21, white, true), full(-2).apply { topMargin = dp(8) })
            history.addView(label("다시 연습 · ${report.optString("difficulty")}", 18, grey), full(-2).apply { topMargin = dp(8) })
        }

        val reflection = card()
        columns.addView(reflection)
        reflection.addView(label(if (lastPractice == null) "오늘의 준비와 연습" else "방금 연습은 어땠나요?", 25, white, true), full(-2))
        reflection.addView(label("느낌과 다시 해볼 것을 하나씩 골라 주세요.", 19, grey), full(-2).apply { topMargin = dp(10); bottomMargin = dp(24) })
        val choices = PreparationColumns(context, breakpoint = 650)
        reflection.addView(choices, full(-2))
        choices.addView(reviewChoices("내 느낌", listOf("한결 편해졌어요", "차근차근 해봤어요", "아직 어려워요"), selfFeeling, "journey_feeling") { selfFeeling = it; render() })
        choices.addView(reviewChoices("다시 해볼 것", listOf("핸들 조작", "기어 조작", "주변 확인"), selfDifficulty, "journey_difficulty") { selfDifficulty = it; render() })
        reflection.addView(label(storageMessage ?: if (recordEnabled) "선택한 소감은 이 기기에만 저장해요."
            else "기록 저장이 꺼져 있어요. 이번 응답은 저장하지 않아요.", 18, grey).apply {
            background = PreparationStyle.surface(context, PreparationStyle.softAccent)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }, full(-2).apply { topMargin = dp(24) })
        val actions = PreparationColumns(context, 1f, 2f, 760)
        parent.addView(actions, full(-2).apply { topMargin = dp(18) })
        actions.addView(button("저장 정보 관리", "journey_review_settings") { state.enterProfile(); render() })
        actions.addView(button(if (recordEnabled) "소감 저장 · 연습 선택" else "저장 없이 연습 선택", "journey_review_save", selected = true) {
            if (recordEnabled) saveSelfReport()
            if (state.canPractice) state.enterPractice() else state.enterPreflight()
            render()
        })
    }

    private fun reviewChoices(title: String, choices: List<String>, selected: String, key: String,
        onSelect: (String) -> Unit) = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(label(title, 21, white, true), full(-2).apply { bottomMargin = dp(14) })
        choices.forEachIndexed { index, value ->
            addView(button(value, "${key}_$index", selected = value == selected) { onSelect(value) }.apply {
                PreparationStyle.button(this, value == selected, soft = true)
                minHeight = dp(72); minimumHeight = dp(72)
            }, full(-2).apply { if (index != choices.lastIndex) bottomMargin = dp(12) })
        }
    }

    private fun launch(destination: JourneyDestination, requireReady: Boolean = true) {
        if (!active || closed) return
        if (requireReady && !state.canPractice) { state.enterPreflight(); render(); return }
        if (destination in setOf(JourneyDestination.LEGACY, JourneyDestination.CONTEXT) && !recordEnabled) {
            showMessage("기록 저장이 꺼져 있어요", "이 연습은 기록 저장을 켠 뒤 이용할 수 있어요. 운전자 설정에서 켜 주세요. 기본 교육과 가상 연습은 저장 없이도 이용할 수 있어요.")
            return
        }
        lastPractice = destination
        Log.i("DriverJourney", "launch=$destination records=$recordEnabled vehicle=$vehicleDataEnabled")
        onDestination(destination)
    }

    private fun requestVehicleLesson(destination: JourneyDestination) {
        if (!state.vehicleConsent || !state.recordConsent) {
            showMessage("차량 조작 실습의 연결을 확인해요", "차량 조작을 확인하려면 운전자 설정에서 ‘차량 정보 연결’과 ‘프로필·연습 기록’을 켜 주세요. 기본 교육과 가상 연습은 차량 연결 없이도 이용할 수 있어요.")
            return
        }
        launch(destination, requireReady = false)
    }

    private fun persist() {
        val editor = prefs.edit().putBoolean("record_consent", state.recordConsent).putBoolean("vehicle_consent", state.vehicleConsent)
        if (state.recordConsent) {
            editor.putBoolean("has_profile", state.profileConfirmed)
                .putInt("height", state.profile.heightCm).putInt("weight", state.profile.weightKg)
                .putString("experience", state.profile.experience).putInt("confidence", state.profile.confidence)
                .putString("guidance", state.profile.guidance).putString("difficulty", state.profile.difficulty)
                .putInt("seat_fore_aft", state.calibration.foreAft).putInt("seat_height", state.calibration.height)
                .putInt("seat_backrest", state.calibration.backrest).putInt("seat_steering", state.calibration.steering)
                .putString("setup", state.setupChecks().joinToString("") { if (it) "1" else "0" })
        }
        editor.apply()
    }

    private fun restoreState(): JourneyState {
        val profile = DriverProfile(prefs.getInt("height", 180), prefs.getInt("weight", 78),
            prefs.getString("experience", "장롱면허") ?: "장롱면허", prefs.getInt("confidence", 3),
            prefs.getString("guidance", "차근차근") ?: "차근차근", prefs.getString("difficulty", "주차") ?: "주차")
        val calibration = VirtualSeatCalibration(prefs.getInt("seat_fore_aft", 50).coerceIn(0, 100),
            prefs.getInt("seat_height", 50).coerceIn(0, 100), prefs.getInt("seat_backrest", 50).coerceIn(0, 100),
            prefs.getInt("seat_steering", 50).coerceIn(0, 100))
        val checks = prefs.getString("setup", "")?.map { it == '1' }.orEmpty()
        return JourneyState(profile, calibration, checks, prefs.getBoolean("has_profile", false),
            prefs.getBoolean("record_consent", false), prefs.getBoolean("vehicle_consent", false))
    }

    private fun confirmDelete() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(context).setTitle("운전 준비 정보를 삭제할까요?")
            .setMessage("프로필, 화면 속 자세 설정, 준비 확인과 연습 소감을 삭제해요. 주행·주차 연습과 차량 조작 연습 기록은 각각의 기록 화면에서 관리할 수 있어요.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                if (!active || closed) return@setPositiveButton
                prefs.edit().clear().apply(); state = JourneyState(); lastPractice = null; storageMessage = null
                onSettingsChanged(); render()
            }.show()
    }

    private fun showMessage(title: String, body: String, educationId: String? = null) {
        if (!active || closed) return
        dialog?.dismiss()
        val builder = AlertDialog.Builder(context).setTitle(title).setMessage(body)
        if (educationId == null) builder.setPositiveButton("확인", null)
        else builder.setNegativeButton("닫기", null).setPositiveButton("교육 내용을 확인했어요") { _, _ ->
            if (!active || closed) return@setPositiveButton
            if (recordEnabled) {
                val saved = prefs.getStringSet("education", emptySet()).orEmpty().toMutableSet()
                saved.add(educationId); prefs.edit().putStringSet("education", saved).apply()
            }
        }
        dialog = builder.show()
    }

    private fun saveSelfReport() {
        if (!recordEnabled) return
        val row = JSONObject().put("at", System.currentTimeMillis()).put("source", "SELF_REPORT")
            .put("practice", lastPractice?.name ?: "PREPARATION").put("feeling", selfFeeling)
            .put("difficulty", selfDifficulty).put("confidence", state.profile.confidence)
        val rows = (listOf(row) + readReports()).take(20)
        if (!prefs.edit().putString("self_reports", JSONArray(rows).toString()).commit())
            storageMessage = "이번 자기평가를 저장하지 못했어요."
    }

    private fun readReports(): List<JSONObject> = runCatching {
        val rows = JSONArray(prefs.getString("self_reports", "[]"))
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.take(20)
    }.getOrDefault(emptyList())

    private fun setupStepLabel(index: Int, done: Boolean): String =
        context.getString(R.string.journey_setup_step, index + 1, if (done) " ✓" else "") + "  " +
            listOf("시트 앞뒤", "시트 높이", "등받이·허리", "헤드레스트", "핸들 위치", "룸미러", "왼쪽 미러", "오른쪽 미러", "안전벨트")[index]

    private fun card(padding: Int = 24, fill: Int = panel) = LinearLayout(context).apply {
        orientation = VERTICAL; background = PreparationStyle.surface(context, fill)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun scrollBody(parent: LinearLayout): LinearLayout {
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        parent.addView(scroll, LayoutParams(-1, 0, 1f)); pageScroll = scroll
        return LinearLayout(context).apply { orientation = VERTICAL; scroll.addView(this) }
    }

    private fun heading(parent: LinearLayout, eyebrow: String, title: String, subtitle: String): TextView {
        parent.addView(label(title, 32, white, true), full(-2))
        val progress = label(subtitle, 20, grey)
        parent.addView(progress, full(-2).apply { topMargin = dp(10); bottomMargin = dp(22) })
        return progress
    }
    private fun specRow(parent: LinearLayout, title: String, value: String) {
        val row = horizontal(parent, -2).apply { minimumHeight = dp(40) }
        row.addView(label(title, 19, grey), LayoutParams(0, -2, 1f))
        row.addView(label(value, 20, white), LayoutParams(-2, -2))
    }
    private fun metric(parent: LinearLayout, title: String, value: String, subtitle: String) {
        val block = card(18, PreparationStyle.softAccent)
        parent.addView(block)
        block.addView(label(title, 19, grey), full(-2))
        block.addView(label(value, 30, mint, true), full(-2).apply { topMargin = dp(10); bottomMargin = dp(8) })
        block.addView(label(subtitle, 17, grey), full(-2))
    }

    private fun educationTile(parent: LinearLayout, title: String, subtitle: String, key: String, action: () -> Unit) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(12)) }
        parent.addView(row, full(88))
        row.addView(PleosUi.image(context, if (key.contains("steering")) "steering" else if (key.contains("parking")) "vehicle" else "info", 32), LayoutParams(dp(48), dp(48)))
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        words.addView(label(title, 22, white), full(34)); words.addView(label(subtitle, 16, grey), full(29))
        row.addView(words, LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        row.addView(button("열기", key, action = action), LayoutParams(dp(100), dp(64)))
        divider(parent)
    }
    private fun choiceRow(parent: LinearLayout, title: String, choices: List<String>, selected: String, key: String, onSelect: (String) -> Unit) {
        parent.addView(label(title, 21, white, true), full(-2).apply { bottomMargin = dp(12) })
        val row = horizontal(parent, -2)
        choices.forEachIndexed { i, choice -> row.addView(button(choice, "${key}_$i", selected = choice == selected) { onSelect(choice) }.apply {
            PreparationStyle.button(this, choice == selected, soft = true)
        }, LayoutParams(0, -2, 1f).apply { if (i != choices.lastIndex) marginEnd = dp(10) }) }
    }
    private fun addSlider(parent: LinearLayout, title: String, value: Int, min: Int, max: Int, unit: String, key: String, onChange: (Int) -> Unit) {
        val top = horizontal(parent, -2)
        top.addView(label(title, 21, white, true), LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        val number = label(context.getString(R.string.journey_slider_value, value, unit), 24, mint, true)
        top.addView(number, LayoutParams(-2, -2))
        val slider = SeekBar(context).apply {
            this.max = max - min; progress = value - min; contentDescription = key
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
            thumbTintList = android.content.res.ColorStateList.valueOf(mint)
            setPadding(dp(12), 0, dp(12), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && active && !closed) {
                        number.text = context.getString(R.string.journey_slider_value, progress + min, unit)
                        onChange(progress + min)
                    }
                }
            })
        }
        enabledControls += slider to { true }; parent.addView(slider, full(64))
    }
    private fun consentRow(parent: LinearLayout, title: String, subtitle: String, key: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = horizontal(parent, -2).apply { minimumHeight = dp(100); setPadding(0, dp(14), 0, dp(14)) }
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        words.addView(label(title, 20, white, true), full(-2)); words.addView(label(subtitle, 18, grey), full(-2).apply { topMargin = dp(8) })
        row.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val toggle = PleosSwitch(context).apply {
            contentDescription = key; isChecked = checked; minHeight = dp(48)
            setOnCheckedChangeListener { _, selected -> if (active && !closed) onChange(selected) }
        }
        row.addView(toggle, LayoutParams(dp(80), dp(64)).apply { marginStart = dp(12) }); enabledControls += toggle to { true }
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, key: String, selected: Boolean = false, condition: () -> Boolean = { true }, action: () -> Unit) = PracticeButton(context).apply {
        text = value; textSize = 20f; isAllCaps = false; gravity = Gravity.CENTER
        minWidth = 0; minimumWidth = 0; minHeight = dp(64); minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12)); contentDescription = key
        PreparationStyle.button(this, selected)
        setOnClickListener { if (active && !closed && condition()) action() }
        enabledControls += this to condition
    }
    private fun updateEnabled() { enabledControls.forEach { (view, condition) -> view.isEnabled = active && !closed && condition(); view.alpha = if (view.isEnabled) 1f else .45f } }
    private fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(line) }, full(1)) }
    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    private fun horizontal(parent: LinearLayout, height: Int, weight: Float = 0f) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, if (height <= 0) height else dp(height), weight))
    }
    private fun full(height: Int) = LayoutParams(LayoutParams.MATCH_PARENT, if (height < 0) height else dp(height))
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), full(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun date(value: Long) = SimpleDateFormat("M.d HH:mm", Locale.KOREA).format(Date(value))

    private val setupSteps get() = listOf(
        "시트 앞뒤" to "페달을 끝까지 조작할 수 있는지 확인해요. 등을 기대고도 다리가 무리 없이 움직이는 위치를 직접 찾으세요.",
        "시트 높이" to "전방과 계기판이 가려지지 않는지 확인해요. 머리 위 여유와 조작 편의도 함께 살펴요.",
        "등받이와 허리 지지" to "등이 지지되는 자세에서 페달과 핸들을 편하게 조작할 수 있는지 확인해요.",
        "헤드레스트" to "현재 차량의 사용설명서를 보며 머리를 지지하는 위치로 직접 조절해요.",
        "핸들 높이와 앞뒤" to "팔의 움직임과 계기판 시야를 확인해요. 조절한 뒤 고정 상태도 직접 살펴요.",
        "룸미러" to "운전 자세를 유지하고 뒤쪽 시야를 확인해요. 자세를 바꿨다면 미러를 다시 맞춰요.",
        "왼쪽 사이드미러" to "차량 안내를 보며 왼쪽 후방 시야를 맞춰요. 미러만으로 보이지 않는 영역도 직접 확인해야 해요.",
        "오른쪽 사이드미러" to "오른쪽 후방 시야를 확인해요. 화면의 그림은 실제 미러 영상이나 각도 측정 결과가 아니에요.",
        "안전벨트" to "체결과 꼬임, 몸에 닿는 착용 상태를 직접 확인해요. 동승자의 착용 상태도 살펴요.",
    )
    private val preflightItems get() = listOf(
        "안전벨트" to "본인과 동승자의 체결·착용 상태",
        "문과 경고 표시" to "문 닫힘과 계기판의 경고 확인",
        "페달 주변과 짐" to "발밑 방해물과 움직일 수 있는 물건",
        "유리와 미러의 시야" to "가림·김서림과 주변 직접 확인",
        "조작 위치" to "등화·와이퍼 등 필요한 조작부",
        vehicleEducation.energyCheck,
        "오늘의 컨디션" to "피로·불편·운전 부담 확인",
    )

}
