package kr.drivestep.tutor.growth

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import kr.drivestep.tutor.coach.CoachCue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Entirely parked, fictional scenario training; isolated from the real Vehicle SDK. */
// Created in code with required host/session arguments; never inflated from XML.
@SuppressLint("ViewConstructor")
class GrowthTutorView(
    context: Context,
    private val onCue: (CoachCue) -> Unit,
    private val onSdk: () -> Unit,
    private val stopVoice: () -> Unit,
    private val onSetupComplete: (() -> Unit)? = null,
) : LinearLayout(context) {
    private enum class Page { HUB, PROFILE, SETUP, LESSON, PROGRESS }
    private var page = Page.HUB
    private val progress = GrowthProgress(context)
    private var lesson: GrowthLesson? = null
    private var session: GrowthSession? = null
    private var recorded = false
    private var preview = false
    private var currentPreview = false
    private var setupStep = 0
    private var height = progress.heightGroup
    private var body = progress.proportion
    private var active = false
    private var setupOnly = false
    private var scene: DrivingSceneView? = null
    private val ink = Color.rgb(24, 45, 50)
    private val teal = Color.rgb(8, 127, 113)
    private val muted = Color.rgb(89, 111, 113)
    private val mint = Color.rgb(225, 242, 235)
    private val amber = Color.rgb(160, 88, 24)
    init { orientation = VERTICAL; render() }

    fun setActive(value: Boolean) {
        active = value
        if (value) scene?.resume() else scene?.pause()
    }
    fun openSetup() { setupOnly = true; openProfile() }
    fun openLibrary() { setupOnly = false; page = Page.HUB; session = null; lesson = null; render() }
    fun goBack(): Boolean {
        if (setupOnly) { stopVoice(); onSetupComplete?.invoke(); return true }
        if (page == Page.HUB) return false
        stopVoice(); session = null; lesson = null; page = Page.HUB; render(); return true
    }
    private fun cue(value: CoachCue) { if (active) onCue(value) }
    private fun render() {
        scene?.pause(); scene = null; removeAllViews()
        addView(label(if (setupOnly) "옆자리 코치   ·   출발 전 내 자리 맞추기" else "참고 학습 자료   ·   가상 상황 학습", 16, teal, true))
        gap(this, 8)
        when (page) {
            Page.HUB -> hub()
            Page.PROFILE -> profile()
            Page.SETUP -> setup()
            Page.LESSON -> exercise()
            Page.PROGRESS -> results()
        }
        gap(this, 12)
        post {
            var ancestor = parent
            while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
            (ancestor as? ScrollView)?.scrollTo(0, 0)
        }
        addView(label(if (setupOnly) "직접 자세를 확인하는 가이드예요. 실제 시트·미러 센서 확인이나 자동 조절은 하지 않습니다." else "시뮬레이션 학습 기록입니다. 실제 도로 감지·차량 자동 제어·운전 자격 평가를 하지 않습니다.", 13, muted))
    }
    private fun hub() {
        val hero = card(this, ink)
        hero.addView(label("장롱면허부터, 차근차근 자신 있게", 17, Color.rgb(155, 224, 201)))
        hero.addView(label("내 차에 익숙해지는\n오늘의 한 걸음", 31, Color.WHITE, true))
        hero.addView(label("내 자리 맞추기 → 기본 조작 → 규칙 익히기 → 상황 판단", 17, Color.rgb(221, 234, 232)))
        gap(hero, 10)
        hero.addView(label("${progress.completedCount}/${GrowthCurriculum.lessons.size} 과정 완료     ${progress.points} 학습 포인트", 20, Color.WHITE, true))
        val next = GrowthCurriculum.lessons.firstOrNull { !progress.best.containsKey(it.id) }
        hero.addView(button(if (!progress.setupConfirmed) "처음 시작 · 내 자리 맞추기" else if (next != null) "이어서 연습 · ${next.title}" else "완료했어요 · 성장 기록 보기", "growth_continue") {
            if (!progress.setupConfirmed) openProfile() else if (next != null) startLesson(next) else { page = Page.PROGRESS; render() }
        })
        val links = row(this)
        links.addView(button("체격·운전자세 다시 확인", "growth_profile", secondary = true) { openProfile() }, weight())
        links.addView(button("성장 기록 · 연습 순위", "growth_progress", secondary = true) { page = Page.PROGRESS; stopVoice(); render() }, weight())
        links.addView(button("차량 조작 연습", "growth_sdk", secondary = true) { stopVoice(); onSdk() }, weight())
        addView(button(if (preview) "미리보기 종료 · 순서대로 학습" else "모든 상황 미리보기 · 점수 미반영", "growth_preview", secondary = true) { preview = !preview; render() })
        if (preview) addView(label("미리보기 모드 · 기본 세팅과 과정 순서에 관계없이 체험합니다. 학습 기록과 포인트는 바뀌지 않아요.", 15, amber, true))
        gap(this, 16)
        addView(label("나의 연습 지도", 23, ink, true))
        addView(label("앞 과정을 마치면 다음 장이 열려요. 미완료 과정을 다시 시작하면 처음부터 연습합니다.", 14, muted))
        GrowthCurriculum.lessons.chunked(2).forEach { pair ->
            val line = row(this)
            pair.forEach { item ->
                val column = LinearLayout(context).apply { orientation = VERTICAL }
                line.addView(column, weight())
                val panel = card(column)
                val open = available(item)
                val score = progress.best[item.id]
                panel.addView(label("CH ${item.chapter} · ${competency(item.competency)}", 14, teal, true))
                panel.addView(label(item.title, 21, ink, true))
                panel.addView(label(item.subtitle, 15, muted))
                panel.addView(label(if (score != null) "완료 · 최고 $score/100  ${if (score < 80) "한 번 더 복습해요" else "잘 익혔어요"}" else if (open) "지금 연습할 수 있어요" else "앞 장과 기본 세팅을 먼저 확인해요", 14, if (open) teal else muted))
                panel.addView(button(if (score != null) "다시 연습" else "상황 시작", "growth_lesson_${item.id}", open, secondary = true) { startLesson(item) })
            }
        }
    }
    private fun available(item: GrowthLesson): Boolean = preview || (progress.setupConfirmed &&
        GrowthCurriculum.lessons.filter { it.chapter < item.chapter }.all { progress.best.containsKey(it.id) })

    private fun openProfile() { page = Page.PROFILE; height = progress.heightGroup; body = progress.proportion; render(); cue(SetupGuides.intro) }
    private fun profile() {
        addView(label("먼저, 내 몸에 맞는 운전자세", 28, ink, true))
        addView(label("체격을 참고해 확인할 곳을 알려드려요. 키만으로 안전한 시트 위치나 각도를 계산할 수는 없습니다.", 17, muted))
        val cols = columns(this)
        val left = card(cols.first)
        left.addView(label("키 범위", 20, ink, true))
        listOf("155cm 미만", "155–174cm", "175cm 이상").forEachIndexed { i, text ->
            left.addView(button("${if (height == i) "● " else "○ "}$text", "growth_height_$i", secondary = true) { height = i; render() })
        }
        left.addView(label("몸의 비율 · 직접 선택", 18, ink, true))
        listOf("잘 모르겠어요 / 보통", "다리가 긴 편", "상체가 긴 편").forEachIndexed { i, text ->
            left.addView(button("${if (body == i) "● " else "○ "}$text", "growth_body_$i", secondary = true) { body = i; render() })
        }
        addScene(cols.second, "setup", "SEAT")
        val note = card(cols.second, mint)
        note.addView(label("나에게 먼저 확인할 점", 19, teal, true))
        val tip = when(height) {
            0 -> "전방 시야를 확보하면서 페달에 충분히 닿는지, 핸들과 너무 가까워지지 않는지 함께 확인해요."
            2 -> "머리 위 공간과 무릎 공간, 계기판 시야를 함께 확인해요. 시트를 뒤로 밀기만 하면 페달·핸들이 멀어질 수 있어요."
            else -> "같은 키여도 팔·다리 길이가 달라요. 페달, 핸들, 시야를 각각 직접 확인해요."
        } + when(body) { 1 -> " 다리 공간을 맞춘 뒤에도 등을 붙이고 핸들에 편하게 닿는지 살펴요."; 2 -> " 시트 높이를 맞출 때 머리 위 공간과 전방 시야를 함께 살펴요."; else -> "" }
        note.addView(label(tip, 17, ink))
        note.addView(label("영상 대신 재생되는 도식 가이드입니다. 차량·시트 자동 조절 기능은 없습니다.", 14, muted))
        addView(button("이 체격으로 자세 확인 시작", "growth_setup_start") {
            progress.profile(height, body); setupStep = 0; page = Page.SETUP; render(); cue(SetupGuides.steps[0].cue)
        })
        backButton()
    }
    private fun setup() {
        val item = SetupGuides.steps[setupStep]
        addView(label("기본 세팅  ${setupStep + 1}/${SetupGuides.steps.size}  ·  ${item.title}", 26, ink, true))
        val cols = columns(this)
        addScene(cols.first, "setup", item.animation)
        val panel = card(cols.second)
        panel.addView(label(item.title, 23, teal, true))
        panel.addView(label(item.text, 20, ink))
        gap(panel, 12)
        panel.addView(label("직접 확인 체크입니다. 앱이 실제 시트·미러 위치를 측정하지 않아요.", 15, muted))
        panel.addView(button(if (setupStep == SetupGuides.steps.lastIndex) "직접 확인했어요 · 기본 세팅 완료" else "직접 확인했어요 · 다음", "growth_setup_next") {
            if (setupStep < SetupGuides.steps.lastIndex) { setupStep++; render(); cue(SetupGuides.steps[setupStep].cue) }
            else {
                progress.confirmSetup()
                if (setupOnly && onSetupComplete != null) { onSetupComplete.invoke() }
                else { page = Page.HUB; render(); cue(SetupGuides.complete) }
            }
        })
        panel.addView(button("도식 다시 보기", "growth_guide_replay", secondary = true) { scene?.show("setup", item.animation); cue(item.cue) })
        if (setupStep > 0) panel.addView(button("이전 단계", "growth_setup_previous", secondary = true) { setupStep--; render(); cue(SetupGuides.steps[setupStep].cue) })
        backButton()
    }
    private fun startLesson(item: GrowthLesson) {
        if (!available(item)) return
        lesson = item; session = GrowthSession(item); recorded = false; currentPreview = preview; page = Page.LESSON
        render(); cue(item.steps.first().cue)
    }
    private fun exercise() {
        val item = lesson ?: return
        val run = session ?: return
        val value = run.snapshot()
        val step = item.steps[value.stepIndex]
        addView(label(item.title, 27, ink, true))
        if (currentPreview) addView(label("미리보기 · 학습 기록과 포인트에 반영하지 않아요", 15, amber, true))
        addView(label("${competency(item.competency)}  ·  ${value.completedSteps}/${value.totalSteps} 단계 확인  ·  가상 연습", 16, teal, true))
        val cols = columns(this)
        addScene(cols.first, item.scene, value.animation ?: "idle")
        scene?.setLessonStep(step.id)
        if (item.scene in setOf("alley", "hill", "departure")) {
            val tools = card(cols.first, mint)
            tools.addView(label("가상 핸들 방향을 바꿔보세요", 16, teal, true))
            val readout = label("중앙 · 가상 도식", 15, muted)
            tools.addView(readout)
            tools.addView(SeekBar(context).apply {
                max = 100; progress = 50; contentDescription = "growth_virtual_steering"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, position: Int, fromUser: Boolean) {
                        if (fromUser && active) { scene?.setPracticeSteering((position - 50) / 50f); readout.text = when { position < 45 -> "왼쪽 · 가상 도식"; position > 55 -> "오른쪽 · 가상 도식"; else -> "중앙 · 가상 도식" } }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
            tools.addView(label("조향 방향을 보는 체험입니다. 실제 핸들 각도·회전 수·통과 가능 공간은 계산하지 않습니다.", 13, muted))
        }
        val panel = card(cols.second)
        if (value.completed) {
            panel.addView(label("차근차근 한 과정을 마쳤어요", 24, teal, true))
            panel.addView(label("${value.score}/100  ·  이번 가상 연습", 28, ink, true))
            panel.addView(label("다시 안내받은 선택 ${value.mistakes}회. 같은 과정을 반복해도 포인트는 최고 점수만 반영해요.", 16, muted))
            value.feedback?.let { panel.addView(label(it.text, 19, ink)) }
            panel.addView(button(if (currentPreview) "미리보기 완료 · 성장 지도로" else "성장 지도에 반영됐어요 · 돌아가기", "growth_finish") { stopVoice(); page = Page.HUB; session = null; lesson = null; render() })
            panel.addView(button("한 번 더 연습", "growth_retry", secondary = true) { startLesson(item) })
            panel.addView(label("이 점수는 선택한 가상 상황의 학습 결과이며 실제 운전 실력 점수가 아닙니다.", 14, muted))
        } else {
            panel.addView(label("지금 어떻게 할까요?", 18, teal, true))
            panel.addView(label(step.prompt, 22, ink, true))
            gap(panel, 10)
            step.choices.forEach { choice ->
                panel.addView(button(choice.label, "growth_choice_${choice.id}", !value.answeredCorrectly, secondary = true) {
                    if (!active) return@button
                    val result = run.choose(choice.id)
                    if (result.completed && !recorded && !currentPreview) { progress.record(result); recorded = true }
                    render(); result.feedback?.let(::cue)
                })
            }
            value.feedback?.let {
                gap(panel, 8)
                panel.addView(label(it.text, 18, if (value.answeredCorrectly) teal else amber, true))
            }
            if (value.answeredCorrectly) panel.addView(button("다음 상황으로", "growth_next") {
                val result = run.next(); render(); if (!result.completed) cue(item.steps[result.stepIndex].cue)
            })
        }
        if (item.scene == "courtesy") {
            cols.first.addView(button("감사 비상등 표시 시연", "growth_thanks_demo", secondary = true) { scene?.show("courtesy", "THANKS_DEMO") })
            cols.first.addView(label("관행의 가상 시연이며 의무 신호가 아닙니다. 점수에 반영하지 않고 실제 비상등도 켜지 않습니다.", 13, muted))
        }
        gap(this, 10)
        addView(label(item.sourceNote, 13, muted))
        backButton()
    }
    private fun results() {
        addView(label("조금씩 익숙해지고 있어요", 28, ink, true))
        addView(label("실제 주행을 분석한 점수가 아니라, 이 앱에서 완료한 가상 연습 기록이에요.", 16, muted))
        val panel = card(this, mint)
        panel.addView(label("${progress.points} 포인트  ·  ${progress.completedCount}/${GrowthCurriculum.lessons.size} 과정 완료", 26, teal, true))
        panel.addView(label("첫 완료와 최고 점수 갱신만 포인트에 반영합니다. 반복해서 누르는 횟수로 순위를 올리지 않아요.", 15, muted))
        Competency.entries.forEach { comp ->
            val items = GrowthCurriculum.lessons.filter { it.competency == comp }
            val done = items.mapNotNull { progress.best[it.id] }
            val value = if (done.isEmpty()) "미연습" else "완료 ${done.size}/${items.size} · 완료한 과정 평균 ${done.average().toInt()}점"
            panel.addView(label("${competency(comp)}   $value", 18, ink))
        }
        gap(this, 14)
        val ranking = card(this)
        ranking.addView(label("예시 순위 · 가상 참가자", 22, ink, true))
        ranking.addView(label("순위 화면 체험입니다. 다른 사용자와 연결되지 않았어요. 내 점수만 이 기기에 저장된 학습 점수입니다.", 15, muted))
        val rows = (listOf("연습 친구 A (가상)" to 750, "연습 친구 B (가상)" to 450, "연습 친구 C (가상)" to 200) + ("나 · 이 기기의 학습 기록" to progress.points)).sortedByDescending { it.second }
        rows.forEachIndexed { i, (name, points) -> ranking.addView(label("${i + 1}   $name     $points P", 19, if (name.startsWith("나")) teal else muted, name.startsWith("나"))) }
        gap(this, 14)
        addView(label("최근 연습 · 다시 안내받은 내용을 복습해요", 21, ink, true))
        progress.recent().take(10).forEach { record ->
            val item = GrowthCurriculum.find(record.optString("lesson")) ?: return@forEach
            val row = card(this)
            val time = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(record.optLong("time")))
            row.addView(label("${item.title} · ${record.optInt("score")}점 · 다시 안내 ${record.optInt("mistakes")}회", 18, ink, true))
            row.addView(label("$time  ·  가상 연습", 14, muted))
            row.addView(button("이 상황 다시 연습", "growth_review_${item.id}", secondary = true) { startLesson(item) })
        }
        backButton()
        addView(button("성장 기록 초기화", "growth_reset", secondary = true) {
            AlertDialog.Builder(context).setTitle("성장 연습 기록을 초기화할까요?").setMessage("체격 선택, 기본 설정 확인, 가상 연습 점수와 기록을 삭제해요. 차량 조작 연습 기록은 유지돼요.")
                .setNegativeButton("취소", null).setPositiveButton("초기화") { _, _ -> progress.reset(); page = Page.HUB; render() }.show()
        })
    }
    private fun competency(value: Competency) = when(value) {
        Competency.SETUP -> "출발 준비"
        Competency.CONTROL -> "기본 조작"
        Competency.RULES -> "교통 규칙"
        Competency.JUDGMENT -> "상황 판단"
        Competency.COURTESY -> "안전·배려"
    }
    private fun backButton() { addView(button(if (setupOnly) "코치 홈으로" else "성장 지도로", "growth_back", secondary = true) { goBack() }) }
    private fun addScene(parent: LinearLayout, name: String, animation: String) {
        val view = DrivingSceneView(context)
        scene = view
        parent.addView(view, LayoutParams(-1, dp(285)).apply { bottomMargin = dp(8) })
        view.show(name, animation)
        if (!active) view.pause()
    }
    private fun columns(parent: LinearLayout): Pair<LinearLayout, LinearLayout> {
        val wide = resources.configuration.screenWidthDp >= 900
        val row = LinearLayout(context).apply { orientation = if (wide) HORIZONTAL else VERTICAL }
        val a = LinearLayout(context).apply { orientation = VERTICAL }; val b = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(a, if (wide) weight() else LayoutParams(-1, -2))
        row.addView(b, if (wide) weight() else LayoutParams(-1, -2))
        parent.addView(row)
        return a to b
    }
    private fun row(parent: LinearLayout) = LinearLayout(context).apply { orientation = HORIZONTAL; parent.addView(this, LayoutParams(-1,-2)) }
    private fun weight() = LayoutParams(0, -2, 1f).apply { rightMargin = dp(8); topMargin = dp(8) }
    private fun card(parent: LinearLayout, color: Int = Color.WHITE) = LinearLayout(context).apply {
        orientation = VERTICAL; background = rounded(color); setPadding(dp(18), dp(16), dp(18), dp(16))
        parent.addView(this, LayoutParams(-1,-2).apply { topMargin = dp(8) })
    }
    private fun label(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1.02f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(value: String, key: String, enabled: Boolean = true, secondary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 16f; isAllCaps = false; isEnabled = enabled; contentDescription = key
        setTextColor(if (!enabled) muted else if (secondary) teal else Color.WHITE)
        background = rounded(if (!enabled) Color.rgb(226,232,230) else if (secondary) mint else teal)
        minHeight = dp(52); setPadding(dp(12),dp(8),dp(12),dp(8))
        layoutParams = LayoutParams(-1, -2).apply { topMargin = dp(7) }
        setOnClickListener { if (active) action() }
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(15).toFloat() }
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), LayoutParams(1,dp(height))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
