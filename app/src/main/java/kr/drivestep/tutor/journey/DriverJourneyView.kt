package kr.drivestep.tutor.journey

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kr.drivestep.tutor.R
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PleosSwitch
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
) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
    private val bg = PleosUi.BACKGROUND
    private val panel = PleosUi.SURFACE
    private val raised = PleosUi.RAISED
    private val white = PleosUi.TEXT
    private val grey = PleosUi.SECONDARY
    private val line = PleosUi.LINE
    private val mint = PleosUi.TEXT
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
    private var lastPage: JourneyStep? = null

    val recordEnabled: Boolean get() = state.recordConsent
    val vehicleDataEnabled: Boolean get() = state.vehicleConsent
    val canPractice: Boolean get() = state.canPractice

    init {
        orientation = HORIZONTAL
        setBackgroundColor(bg)
        render()
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (!value) { dialog?.dismiss(); dialog = null }
        updateEnabled()
    }

    fun beginVisit() {
        state.newVisit()
        render()
    }

    fun close() { closed = true; active = false; dialog?.dismiss(); dialog = null }

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
        removeAllViews(); enabledControls.clear()
        val side = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(bg)
        }
        addView(side, LayoutParams(dp(204), LayoutParams.MATCH_PARENT))
        side.addView(label("운전 튜터", 24, white, true), full(48))
        side.addView(label("IONIQ 3", 18, grey), full(34))
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
                setCompoundDrawables(PleosUi.icon(context, icon, 30, white), null, null, null)
                compoundDrawablePadding = dp(16)
                background = shape(if (selected) raised else bg, 16)
                setTextColor(white)
            }, full(64).apply { bottomMargin = dp(8) })
        }
        side.addView(View(context), LayoutParams(1, 0, 1f))
        side.addView(button("정차 교육", "journey_education") { state.enterEducation(); render() }, full(64))
        gap(side, 12)
        side.addView(label("P · 정차 중 사용", 16, grey), full(32))
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
    }

    private fun profilePage(parent: LinearLayout) {
        heading(parent, "", "운전자 설정", "체격과 운전 경험을 확인하고 운전 환경을 준비하세요.")
        val row = horizontal(parent, 0, 1f)
        val car = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(18)); background = shape(panel, 16)
        }
        row.addView(car, LayoutParams(0, -1, .85f).apply { marginEnd = dp(28) })
        val model = horizontal(car, 80)
        model.addView(PleosUi.image(context, "vehicle", 56), LayoutParams(dp(64), dp(64)))
        model.addView(label("IONIQ 3", 30, white, true), LayoutParams(0, dp(64), 1f).apply { marginStart = dp(16) })
        car.addView(label("유럽형 일반 차체", 18, grey), full(34))
        gap(car, 14)
        specRow(car, "전장", "4,155 mm")
        specRow(car, "전폭", "1,800 mm")
        specRow(car, "전고", "1,505 mm")
        specRow(car, "축거", "2,680 mm")
        gap(car, 14)
        car.addView(label("대표 데모 180 cm · 75~80 kg\n남성 · 동아시아인", 16, grey), full(50))
        car.addView(label("성별·인종을 보정 계수로 사용하지 않아요.", 14, grey), full(36))
        car.addView(View(context), LayoutParams(1, 0, 1f))
        divider(car)
        consentRow(car, "프로필·연습 기록", "이 기기에 저장", "journey_consent_records", state.recordConsent) {
            state.recordConsent = it; persist(); onSettingsChanged()
        }
        consentRow(car, "차량 조작 신호", "지원되는 SDK 실습에 연결", "journey_consent_vehicle", state.vehicleConsent) {
            state.vehicleConsent = it; persist(); onSettingsChanged()
        }
        if (prefs.getBoolean("has_profile", false)) car.addView(button("준비 정보 삭제", "journey_delete") { confirmDelete() }.apply { textSize = 17f }, full(64))

        val right = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(right, LayoutParams(0, -1, 1.15f))
        val scroll = ScrollView(context).apply { isFillViewport = true }
        right.addView(scroll, LayoutParams(-1, 0, 1f))
        val form = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(form)
        addSlider(form, "키", state.profile.heightCm, 150, 200, "cm", "journey_height") {
            state.updateProfile(state.profile.copy(heightCm = it)); persist()
        }
        addSlider(form, "체중", state.profile.weightKg, 50, 110, "kg", "journey_weight") {
            state.updateProfile(state.profile.copy(weightKg = it)); persist()
        }
        choiceRow(form, "운전 경험", listOf("처음 운전", "장롱면허", "종종 운전"), state.profile.experience, "journey_experience") {
            state.updateProfile(state.profile.copy(experience = it)); persist(); render()
        }
        addSlider(form, "내가 느끼는 자신감", state.profile.confidence, 1, 5, "/ 5", "journey_confidence") {
            state.updateProfile(state.profile.copy(confidence = it)); persist()
        }
        choiceRow(form, "안내 선호 · 기록용", listOf("차근차근", "짧게"), state.profile.guidance, "journey_guidance") {
            state.updateProfile(state.profile.copy(guidance = it)); persist(); render()
        }
        form.addView(label("현재 음성은 공통 고정 안내입니다.", 16, grey), full(36))
        gap(right, 16)
        right.addView(button("운전 환경 설정", "journey_profile_continue", selected = true) {
            state.confirmProfile(); persist(); render()
        }, full(64))
    }

    private fun revisitPage(parent: LinearLayout) {
        heading(parent, "", "운전자와 차량 확인", "운전자가 바뀌었거나 자세를 조절했다면 운전 환경을 다시 확인하세요.")
        val summary = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        parent.addView(summary, full(190))
        summary.addView(PleosUi.image(context, "profile", 88), LayoutParams(dp(120), dp(120)))
        val info = LinearLayout(context).apply { orientation = VERTICAL }
        summary.addView(info, LayoutParams(0, -2, 1f).apply { marginStart = dp(28) })
        info.addView(label("IONIQ 3", 32, white, true), full(50))
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
        heading(parent, "", step.first, "운전 환경 ${index + 1}/9  ·  ${state.setupCompletedCount}개 확인")
        val row = horizontal(parent, 0, 1f)
        val reference = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), dp(14), dp(12), dp(14)) }
        row.addView(reference, LayoutParams(dp(300), -1).apply { marginEnd = dp(24) })
        val icon = when (index) { in 0..3 -> "seat"; 4 -> "steering"; in 5..7 -> "eye"; else -> "check" }
        reference.addView(PleosUi.image(context, icon, 48), LayoutParams(dp(56), dp(56)))
        gap(reference, 8)
        reference.addView(label(step.first, 20, white, true).apply { gravity = Gravity.CENTER }, full(32))
        reference.addView(label("${state.profile.heightCm} cm · ${state.profile.weightKg} kg", 16, grey).apply { gravity = Gravity.CENTER }, full(26))
        val guideView = PostureGuideView(context).apply { update(index, state.profile, state.calibration) }
        reference.addView(guideView, LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        val info = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(info, LayoutParams(0, -1, 1f))
        info.addView(label(step.second, 23, white), full(132))
        divider(info)
        gap(info, 20)
        val axis = when (index) { 0 -> CalibrationAxis.FORE_AFT; 1 -> CalibrationAxis.HEIGHT; 2 -> CalibrationAxis.BACKREST; 4 -> CalibrationAxis.STEERING; else -> null }
        if (axis != null) {
            addSlider(info, "가상 조절값", state.calibration.value(axis), 0, 100, "/ 100", "journey_calibration_${axis.name.lowercase()}") {
                state.setCalibration(axis, it); guideView.update(index, state.profile, state.calibration); persist()
            }
            val band = state.guideBand(axis)
            val presetRow = horizontal(info, 56)
            presetRow.addView(label("체격 참고 시작 구간 ${band.minRecommended}~${band.maxRecommended} (중심 ${band.recommendedCenter})\n50은 중립 시작값이며 차량에 전송하지 않습니다.", 16, grey), LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })
            presetRow.addView(button("체격 참고 시작값 적용", "journey_apply_recommended_preset") {
                state.applyRecommendedPreset(); persist(); render()
            }, LayoutParams(dp(210), dp(56)))
        } else info.addView(label(if (index in 5..7) "직접 후방과 옆쪽 시야를 확인하세요.\n앱에서 미러의 각도나 시야를 측정하지 않습니다." else "차량의 조절 장치와 사용설명서를 확인하세요.\n앱에서 올바른 상태를 자동 판정하지 않습니다.", 18, grey), full(100))
        info.addView(View(context), LayoutParams(1, 0, 1f))
        info.addView(button("설명 다시 확인", "journey_setup_skip_help") {
            state.leaveSetupUnresolved(); persist()
            showMessage(step.first, step.second + "\n\n확인하지 못한 항목은 완료로 바꾸지 않아요. 불편한 부분을 다시 조절한 뒤 확인하거나 정차 교육을 선택하세요.")
        }, full(64))
        info.addView(button("조절 및 확인 완료", "journey_setup_confirm", selected = true) {
            state.confirmSetupStep(); persist(); render()
        }, full(64).apply { topMargin = dp(12) })
        gap(parent, 24)
        val steps = horizontal(parent, 64)
        state.setupChecks().forEachIndexed { i, done ->
            steps.addView(button(if (done) "${i + 1} ✓" else "${i + 1}", "journey_setup_step_$i", selected = i == index) {
                state.enterSetup(i); render()
            }, LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(6) })
        }
    }

    private fun preflightPage(parent: LinearLayout) {
        heading(parent, "", "출발 전 확인", "${state.preflightCompletedCount}/7 확인  ·  각 항목을 직접 살펴본 뒤 표시하세요.")
        val scroll = ScrollView(context)
        parent.addView(scroll, LayoutParams(-1, 0, 1f))
        val checks = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(checks)
        preflightItems.forEachIndexed { index, item ->
            val done = state.preflightChecks()[index]
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            checks.addView(row, full(76))
            row.addView(ImageView(context).apply {
                setImageDrawable(PleosUi.checkIcon(context, done, 40))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(44), dp(48)))
            val words = LinearLayout(context).apply { orientation = VERTICAL }
            words.addView(label(item.first, 21, white), full(30))
            words.addView(label(item.second, 16, grey), full(26))
            row.addView(words, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            row.addView(button(if (done) "확인 ✓" else "확인", "journey_preflight_$index", selected = done) {
                state.setPreflight(index, !done); render()
            }, LayoutParams(dp(118), dp(64)))
            divider(checks)
        }
        gap(parent, 16)
        val actions = horizontal(parent, 64)
        actions.addView(button("교육 보기", "journey_preflight_hold") { state.enterEducation(); render() }, LayoutParams(dp(180), dp(64)).apply { marginEnd = dp(16) })
        actions.addView(button("연습 선택", "journey_preflight_continue", selected = true, condition = { state.canPractice }) {
            state.enterPractice(); render()
        }, LayoutParams(0, dp(64), 1f))
    }

    private fun practicePage(parent: LinearLayout) {
        heading(parent, "", "연습 선택", "IONIQ 3  ·  ${if (recordEnabled) "연습 기록 저장 켜짐" else "연습 기록 저장 꺼짐"}")
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        parent.addView(list, LayoutParams(-1, 0, 1f))
        practiceRow(list, "driving", "차로 변경", "방향지시등 · 미러 확인 · 조향", "journey_start_drive") { launch(JourneyDestination.DRIVE) }
        practiceRow(list, "vehicle", "주차", "후진 주차 · 평행 주차", "journey_start_parking") { launch(JourneyDestination.PARKING) }
        practiceRow(list, "info", "정차 중 교육", "전기차 조작 원리 · 차량 신호 실습", "journey_course_education") { state.enterEducation(); render() }
        gap(parent, 16)
        val status = horizontal(parent, 64)
        status.addView(label("내비 경로 연습은 제공 예정입니다.", 18, grey), LayoutParams(0, dp(64), 1f))
        status.addView(button("운전 환경 설정", "journey_adjust") { state.enterSetup(); render() }, LayoutParams(dp(210), dp(64)))
    }

    private fun practiceRow(parent: LinearLayout, icon: String, title: String, subtitle: String, key: String, action: () -> Unit) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(24), dp(16), dp(24)); background = shape(panel, 16) }
        parent.addView(row, LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(16) })
        row.addView(PleosUi.image(context, icon, 64), LayoutParams(dp(84), dp(84)))
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(words, LayoutParams(0, -2, 1f).apply { marginStart = dp(24) })
        words.addView(label(title, 28, white, true), full(48))
        words.addView(label(subtitle, 18, grey), full(36))
        row.addView(button("시작", key, selected = true, condition = { state.canPractice }) { action() }.apply {
            setCompoundDrawables(null, null, PleosUi.icon(context, "next", 24, Color.WHITE), null)
            compoundDrawablePadding = dp(12)
        }, LayoutParams(dp(132), dp(64)))
    }

    private fun educationPage(parent: LinearLayout) {
        heading(parent, "", "정차 중 교육", "일반 교육은 차량 신호 연결 없이 이용할 수 있습니다.")
        val scroll = ScrollView(context)
        parent.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(list)
        educationTile(list, "전기차의 감속", "회생제동과 브레이크를 구분해요", "journey_ev_braking") {
            showMessage("회생제동과 브레이크", "회생제동은 감속 과정의 에너지를 회수하는 기능이에요. 모드와 배터리 상태 등에 따라 감속 느낌이 달라질 수 있어요.\n\n원페달 기능이 있더라도 모든 상황의 정지·정차 유지를 대신한다고 가정하지 않아요. 현재 차량의 사용설명서와 계기판 표시를 먼저 확인하고, 필요한 제동은 운전자가 직접 수행해요.\n\n이 앱은 실제 회생제동 모드를 변경하지 않아요.", educationId = "ev_braking")
        }
        educationTile(list, "주차를 시작하기 전", "기어와 조향, 주변 확인의 순서를 알아봐요", "journey_parking_guide") {
            showMessage("주차 연습 준비", "연습할 장소와 주변 사람·차량을 먼저 직접 살펴요. 기어를 바꾸기 전에는 멈추고 브레이크를 유지해요.\n\n가상 주차에서는 화면 속 기어·핸들·페달을 사용해요. 실제 차량은 P·정차 상태를 유지합니다. 화면의 예상선은 가상 모델이 그린 경로예요.\n\n실제 주차선·장애물과의 거리를 자동으로 인식하는 기능은 아니에요.", educationId = "parking_prepare")
        }
        educationTile(list, "방향지시등 조작", "차량 SDK 신호로 직접 확인하는 기초 실습", "journey_sdk_signal") { requestVehicleLesson(JourneyDestination.SIGNAL) }
        educationTile(list, "핸들 조작", "P·정차 상태에서 조향 신호를 확인해요", "journey_sdk_steering") { requestVehicleLesson(JourneyDestination.STEERING) }
        educationTile(list, "기존 학습 자료", "교통상식·주차 순서를 다시 확인해요", "journey_legacy") { launch(JourneyDestination.LEGACY, requireReady = false) }
        educationTile(list, "상황 감지 변수", "개발용 가상 입력으로 반응 조건을 확인해요", "journey_context") { launch(JourneyDestination.CONTEXT, requireReady = false) }
        gap(parent, 12)
        parent.addView(button(if (state.canPractice) "연습 선택으로" else "준비하던 단계로", "journey_education_back", selected = true) {
            if (state.canPractice) state.enterPractice() else state.enterPreflight(); render()
        }, full(64))
    }

    private fun reviewPage(parent: LinearLayout) {
        heading(parent, "", "연습 기록", "가상 조작 기록과 본인의 느낌을 구분해 확인합니다.")
        val row = horizontal(parent, 108)
        val learned = prefs.getStringSet("education", emptySet())?.size ?: 0
        metric(row, "학습 완료", "$learned 개", "정차 중 교육 확인")
        metric(row, "내 자신감", "${state.profile.confidence} / 5", "내가 응답한 값")
        metric(row, "운전 숙련도", "평가 중", "검증된 수행 근거 필요")
        gap(parent, 20)
        val panelView = LinearLayout(context).apply { orientation = VERTICAL; background = shape(panel, 14); setPadding(dp(20), dp(16), dp(20), dp(16)) }
        parent.addView(panelView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        panelView.addView(label(if (lastPractice == null) "최근 준비와 연습" else "방금 연습은 어땠나요?", 23, white, true), full(43))
        choiceRow(panelView, "내 느낌", listOf("한결 편해졌어요", "차근차근 해봤어요", "아직 어려워요"), selfFeeling, "journey_feeling") { selfFeeling = it; render() }
        choiceRow(panelView, "다시 연습하고 싶은 것", listOf("핸들 조작", "기어 조작", "주변 확인"), selfDifficulty, "journey_difficulty") { selfDifficulty = it; render() }
        gap(panelView, 9)
        val history = readReports()
        val last = history.firstOrNull()
        panelView.addView(label(last?.let {
            "최근 자기평가  ${date(it.optLong("at"))}\n${it.optString("feeling")} · ${it.optString("difficulty")}" }
            ?: "저장한 자기평가가 아직 없어요. 이전 주행 경로와의 자동 비교는 제공 예정입니다.", 14, grey), full(64))
        panelView.addView(View(context), LayoutParams(1, 0, 1f))
        panelView.addView(label(storageMessage ?: if (recordEnabled) "응답은 이 기기에만 저장해요." else "기록 저장이 꺼져 있어요. 이번 응답은 저장하지 않아요.", 13, grey), full(30))
        val actions = horizontal(parent, 66)
        actions.addView(button(if (recordEnabled) "자기평가 저장 · 연습 선택" else "저장 없이 연습 선택", "journey_review_save", selected = true) {
            if (recordEnabled) saveSelfReport()
            if (state.canPractice) state.enterPractice() else state.enterPreflight()
            render()
        }, LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(12) })
        actions.addView(button("저장 정보 관리", "journey_review_settings") { state.enterProfile(); render() }, LayoutParams(dp(180), dp(64)))
    }

    private fun launch(destination: JourneyDestination, requireReady: Boolean = true) {
        if (!active || closed) return
        if (requireReady && !state.canPractice) { state.enterPreflight(); render(); return }
        if (destination in setOf(JourneyDestination.LEGACY, JourneyDestination.CONTEXT) && !recordEnabled) {
            showMessage("기록 저장이 꺼져 있어요", "기존 학습·변수 검증 기능은 별도 연습 기록을 저장해요. 운전자 화면에서 기록 저장을 켠 뒤 이용해 주세요. 이 화면의 일반 교육과 가상 연습은 저장 없이 이용할 수 있어요.")
            return
        }
        lastPractice = destination
        Log.i("DriverJourney", "launch=$destination records=$recordEnabled vehicle=$vehicleDataEnabled")
        onDestination(destination)
    }

    private fun requestVehicleLesson(destination: JourneyDestination) {
        if (!state.vehicleConsent || !state.recordConsent) {
            showMessage("차량 조작 실습의 연결을 확인해요", "이 실습은 차량 신호를 읽고 별도 조작 기록을 저장해요. 운전자 화면에서 ‘차량 조작 신호 연결’과 ‘프로필·연습 기록 저장’을 모두 켠 뒤 이용해 주세요. 일반 정차 교육과 가상 연습은 연결 없이 이용할 수 있어요.")
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
            .setMessage("이 화면의 프로필·가상 보정값·준비 확인·자기평가를 삭제합니다. 주행·주차 시뮬레이터와 SDK 실습의 별도 기록은 이 작업에서 삭제하지 않습니다.")
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

    private fun heading(parent: LinearLayout, eyebrow: String, title: String, subtitle: String) {
        parent.addView(label(title, 32, white, true), full(54))
        parent.addView(label(subtitle, 18, grey), full(40))
        gap(parent, 16)
    }
    private fun specRow(parent: LinearLayout, title: String, value: String) {
        val row = horizontal(parent, 36)
        row.addView(label(title, 18, grey), LayoutParams(0, dp(36), 1f))
        row.addView(label(value, 19, white), LayoutParams(dp(126), dp(36)))
    }
    private fun metric(parent: LinearLayout, title: String, value: String, subtitle: String) {
        val block = LinearLayout(context).apply { orientation = VERTICAL; background = shape(panel, 16); setPadding(dp(20), dp(10), dp(20), dp(10)) }
        parent.addView(block, LayoutParams(0, dp(108), 1f).apply { marginEnd = dp(12) })
        block.addView(label(title, 16, grey), full(24)); block.addView(label(value, 28, white, true), full(43)); block.addView(label(subtitle, 14, grey), full(24))
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
        parent.addView(label(title, 18, grey), full(30))
        val row = horizontal(parent, 64)
        choices.forEachIndexed { i, choice -> row.addView(button(choice, "${key}_$i", selected = choice == selected) { onSelect(choice) }, LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(7) }) }
    }
    private fun addSlider(parent: LinearLayout, title: String, value: Int, min: Int, max: Int, unit: String, key: String, onChange: (Int) -> Unit) {
        val top = horizontal(parent, 30)
        top.addView(label(title, 18, grey), LayoutParams(0, dp(30), 1f))
        val number = label(context.getString(R.string.journey_slider_value, value, unit), 22, white, true)
        top.addView(number, LayoutParams(dp(112), dp(30)))
        val slider = SeekBar(context).apply {
            this.max = max - min; progress = value - min; contentDescription = key
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
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
        enabledControls += slider to { true }; parent.addView(slider, full(54))
    }
    private fun consentRow(parent: LinearLayout, title: String, subtitle: String, key: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = horizontal(parent, 76)
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        words.addView(label(title, 18, white), full(28)); words.addView(label(subtitle, 15, grey), full(32))
        row.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val toggle = PleosSwitch(context).apply {
            contentDescription = key; isChecked = checked; minHeight = dp(48)
            setOnCheckedChangeListener { _, selected -> if (active && !closed) onChange(selected) }
        }
        row.addView(toggle, LayoutParams(dp(96), dp(64))); enabledControls += toggle to { true }
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, key: String, selected: Boolean = false, condition: () -> Boolean = { true }, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 20f; isAllCaps = false; gravity = Gravity.CENTER
        minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(9), 0, dp(9), 0); contentDescription = key
        PleosUi.decorate(this, selected)
        setTypeface(typeface, Typeface.NORMAL)
        setOnClickListener { if (active && !closed && condition()) action() }
        enabledControls += this to condition
    }
    private fun updateEnabled() { enabledControls.forEach { (view, condition) -> view.isEnabled = active && !closed && condition(); view.alpha = 1f } }
    private fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(line) }, full(1)) }
    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    private fun horizontal(parent: LinearLayout, height: Int, weight: Float = 0f) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, if (height == 0) 0 else dp(height), weight))
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
        "배터리 잔량" to "차량 계기판의 잔량과 오늘의 이동 계획",
        "오늘의 컨디션" to "피로·불편·운전 부담 확인",
    )

}
