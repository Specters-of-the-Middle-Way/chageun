package kr.drivestep.tutor.tutor

import kr.drivestep.tutor.design.PleosUi
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.drivestep.tutor.coach.CoachCue

/** A parked-only explanation/quiz view. The host applies ParkedGate and owns voice playback. */
// Created in code with required host/session arguments; never inflated from XML.
@SuppressLint("ViewConstructor")
class ParkingLessonView(
    context: Context,
    private val session: ParkingLessonSession,
    private val onCue: (CoachCue) -> Unit,
    private val onFinish: () -> Unit,
    private val onHome: () -> Unit,
) : LinearLayout(context) {
    private val ink = Color.rgb(24, 45, 50)
    private val muted = Color.rgb(89, 111, 113)
    private val teal = Color.rgb(8, 127, 113)
    private val soft = Color.rgb(225, 242, 235)

    init {
        orientation = VERTICAL
        render()
    }

    private fun render(resetScroll: Boolean = false) {
        val ancestorScroll = ancestorScrollView()
        val scrollY = if (resetScroll) 0 else ancestorScroll?.scrollY ?: 0
        removeAllViews()
        val state = session.snapshot()
        addView(label("후진주차 상황 튜터", 28, ink, true))
        gap(this, 8)
        addView(label("P 정차 중 예습·복습 · 실제 차량 조작 없음", 16, teal, true))
        gap(this, 12)
        if (state.completed) {
            renderComplete()
            restoreScroll(ancestorScroll, scrollY)
            return
        }
        val step = ParkingLesson.steps[state.stepIndex]
        val explanationParent: LinearLayout
        val questionParent: LinearLayout
        val wide = resources.configuration.screenWidthDp >= 900
        if (wide) {
            val columns = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
            }
            explanationParent = LinearLayout(context).apply { orientation = VERTICAL }
            questionParent = LinearLayout(context).apply { orientation = VERTICAL }
            columns.addView(explanationParent, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            columns.addView(View(context), LayoutParams(dp(18), 1))
            columns.addView(questionParent, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.25f))
            addView(columns, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            explanationParent = this
            questionParent = this
        }
        val panel = card(explanationParent)
        panel.addView(label("${state.stepIndex + 1} / ${ParkingLesson.steps.size}  ·  ${step.title}", 23, ink, true))
        gap(panel, 10)
        panel.addView(label(step.description, 19, ink))
        gap(panel, 10)
        panel.addView(ParkingOverview(context, state.stepIndex), LayoutParams(LayoutParams.MATCH_PARENT, dp(170)))
        panel.addView(label("설명용 그림입니다. 실제 위치·거리·이동 경로를 안내하지 않아요.", 14, muted))
        if (!wide) gap(this, 18)
        questionParent.addView(label(step.question, 21, ink, true))
        gap(questionParent, 10)
        step.choices.forEachIndexed { index, choice ->
            val selected = state.selectedChoiceIndex == index
            val backgroundColor = when {
                selected && state.answerConfirmed -> soft
                selected -> Color.rgb(255, 242, 224)
                else -> Color.WHITE
            }
            questionParent.addView(choiceButton(choice, "parking_choice_$index", !state.answerConfirmed, backgroundColor) {
                session.answer(index)?.let { cue ->
                    render()
                    onCue(cue)
                }
            })
            gap(questionParent, 8)
        }
        if (state.selectedChoiceIndex != null) {
            val feedback = card(questionParent, if (state.answerConfirmed) soft else Color.rgb(255, 248, 236))
            feedback.addView(label(if (state.answerConfirmed) "이해했어요" else "한 번 더 생각해봐요", 17, teal, true))
            gap(feedback, 5)
            feedback.addView(label(state.latestCue.text, 18, ink))
            gap(questionParent, 10)
        }
        questionParent.addView(choiceButton(
            if (state.stepIndex == ParkingLesson.steps.lastIndex) "확인 결과 보기" else "다음 상황으로",
            "parking_next",
            state.answerConfirmed,
            if (state.answerConfirmed) teal else Color.rgb(223, 230, 227),
            if (state.answerConfirmed) Color.WHITE else muted,
        ) {
            if (session.next()) {
                render(resetScroll = true)
                onCue(session.snapshot().latestCue)
            }
        })
        gap(questionParent, 8)
        questionParent.addView(choiceButton("홈으로", "parking_home", true, Color.TRANSPARENT) { onHome() })
        restoreScroll(ancestorScroll, scrollY)
    }

    private fun renderComplete() {
        val panel = card(this, soft)
        panel.addView(label("다섯 가지 상황을 확인했어요", 25, ink, true))
        gap(panel, 10)
        panel.addView(label("주변 확인 → 후진 준비 → 계속 관찰 → 정렬 전 확인 → 주차 마무리", 19, ink))
        gap(panel, 12)
        panel.addView(label("설명에 대한 이해 확인 결과예요. 실제 주차 성공이나 운전 숙련도를 평가한 결과는 아닙니다.", 18, muted))
        gap(this, 16)
        addView(choiceButton("처음부터 다시 살펴보기", "parking_restart", true, Color.WHITE) {
            session.restart()
            render(resetScroll = true)
            onCue(session.snapshot().latestCue)
        })
        gap(this, 8)
        addView(choiceButton("이해 확인 마치기", "parking_finish", true, teal, Color.WHITE) { onFinish() })
    }

    private fun choiceButton(
        title: String,
        viewTag: String,
        enabled: Boolean,
        fill: Int,
        foreground: Int = ink,
        action: () -> Unit,
    ) = Button(context).apply {
        text = title
        textSize = 18f
        isAllCaps = false
        isEnabled = enabled
        tag = viewTag
        contentDescription = viewTag
        setTextColor(foreground)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(18), dp(14), dp(18), dp(14))
        minHeight = dp(66)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            if (fill == Color.WHITE) setStroke(dp(1), Color.rgb(213, 224, 220))
        }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
    }

    private fun card(parent: LinearLayout, fill: Int = Color.WHITE): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(fill) }
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.coerceAtLeast(18).toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1.08f)
    }

    private fun gap(parent: LinearLayout, height: Int) = parent.addView(View(context), LayoutParams(1, dp(height)))

    private fun ancestorScrollView(): ScrollView? {
        var ancestor = parent
        while (ancestor != null) {
            if (ancestor is ScrollView) return ancestor
            ancestor = ancestor.parent
        }
        return null
    }

    private fun restoreScroll(scroll: ScrollView?, position: Int) {
        scroll ?: return
        // Set immediately so a host re-render triggered by onCue also sees the new position.
        scroll.scrollTo(0, position)
        scroll.post {
            if (scroll.isAttachedToWindow) scroll.scrollTo(0, position)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Static concept illustration: no moving car, path, distance or success prediction. */
// Created in code with required host/session arguments; never inflated from XML.
@SuppressLint("ViewConstructor")
private class ParkingOverview(context: Context, private val step: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val teal = Color.rgb(8, 127, 113)
    private val ink = Color.rgb(24, 45, 50)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "주차 공간과 차량의 정적인 설명용 그림. 실제 주차 위치나 이동 경로가 아닙니다."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = resources.displayMetrics.density
        val inset = 8f * scale
        val bayTop = 14f * scale
        val bayBottom = 80f * scale
        val bayWidth = (width - inset * 2) / 3f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * scale
        paint.color = Color.rgb(176, 196, 189)
        for (i in 0..3) {
            val x = inset + bayWidth * i
            canvas.drawLine(x, bayTop, x, bayBottom, paint)
        }
        canvas.drawLine(inset, bayTop, width - inset, bayTop, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(225, 242, 235)
        bounds.set(inset + bayWidth + 5f * scale, bayTop + 5f * scale,
            inset + bayWidth * 2 - 5f * scale, bayBottom - 5f * scale)
        canvas.drawRoundRect(bounds, 7f * scale, 7f * scale, paint)
        val cx = width / 2f
        val cy = 118f * scale
        paint.color = teal
        bounds.set(cx - 19f * scale, cy - 29f * scale, cx + 19f * scale, cy + 29f * scale)
        canvas.drawRoundRect(bounds, 8f * scale, 8f * scale, paint)
        paint.color = Color.rgb(204, 234, 224)
        bounds.set(cx - 13f * scale, cy + 4f * scale, cx + 13f * scale, cy + 17f * scale)
        canvas.drawRoundRect(bounds, 3f * scale, 3f * scale, paint)
        paint.color = ink
        paint.textSize = PleosUi.canvasTextSize(context, 11f * scale)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("뒤", cx - 27f * scale, cy - 15f * scale, paint)
        canvas.drawText("앞", cx - 27f * scale, cy + 25f * scale, paint)
        paint.textSize = PleosUi.canvasTextSize(context, 13f * scale)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("주차 공간 예시", cx, 52f * scale, paint)
        val focus = FOCUS_LABELS[step]
        paint.textAlign = Paint.Align.LEFT
        val labelX = if (width > 400f * scale) cx + 38f * scale else inset
        val labelY = if (width > 400f * scale) cy + 4f * scale else 166f * scale
        canvas.drawText(focus, labelX, labelY, paint)
    }

    private companion object {
        val FOCUS_LABELS = arrayOf("주변 확인", "완전히 멈춘 뒤 준비", "앞·뒤·양옆 관찰", "불확실하면 멈춤", "마무리 상태 확인")
    }
}
