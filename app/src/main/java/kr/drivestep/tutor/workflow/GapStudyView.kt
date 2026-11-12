package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kr.drivestep.tutor.R
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import kr.drivestep.tutor.journey.PreparationStyle
import java.util.Locale

@SuppressLint("ViewConstructor")
class GapStudyView(context: Context, private val state: GapStudyState, private val interactive: () -> Boolean,
    private val stopSpeech: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL; background = PreparationStyle.surface(context)
        setPadding(dp(20), dp(18), dp(20), dp(18)); render()
    }
    private fun render() {
        removeAllViews()
        label("속도와 거리 바꿔 보기", 24, bold = true)
        label("가상 값을 골라 시간 간격이 어떻게 달라지는지 확인해요.", 19)
        options("예시 속도", "speed", listOf(0.0, 30.0, 60.0, 90.0), state.speedKmh, "km/h", state::speed)
        options("예시 거리", "gap", listOf(10.0, 30.0, 60.0, 90.0), state.gapMeters, "m", state::gap)
        options("비교할 시간", "target", listOf(3.0, 6.0), state.targetSeconds, "초", state::target)
        val seconds = state.headwaySeconds
        val result = if (seconds == null) "정차 예시 · 시간 간격을 계산하지 않아요."
        else String.format(Locale.KOREA, "시간 간격 %.1f초 · 선택한 목표 %.0f초의 거리 %.1fm\n%s", seconds,
            state.targetSeconds, state.targetMeters, if (state.reachesExample == true) "선택한 예시 목표 이상이에요." else "선택한 예시 목표보다 작아요.")
        label(result, 23, "gap_study_result", bold = true)
        addView(GapDiagramView(context, state), LayoutParams(-1, dp(140)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        label("3초·6초는 계산 비교용 목표이며 국내 법정 기준이나 실제 안전 판정이 아니에요. 제동거리·상대 속도·노면 상태는 계산하지 않아요.", 18)
    }
    private fun options(title: String, key: String, values: List<Double>, selected: Double, unit: String, change: (Double) -> Unit) {
        label(title, 20, bold = true)
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; isBaselineAligned = false }
        values.forEach { value -> row.addView(PracticeButton(context).apply {
            text = context.getString(R.string.road_gap_choice, if (value == selected) "✓ " else "", value.toInt(), unit)
            contentDescription = "gap_study_${key}_${value.toInt()}"; textSize = 20f; isAllCaps = false; gravity = Gravity.CENTER
            minWidth = 0; minimumWidth = 0; minHeight = dp(64); minimumHeight = dp(64)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isEnabled = interactive(); isSelected = value == selected; PreparationStyle.button(this, isSelected)
            setOnClickListener { if (interactive()) { stopSpeech(); change(value); render() } }
        }, LayoutParams(0,-2,1f).apply { marginEnd = dp(6) }) }
        addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun label(value: String, size: Int, key: String? = null, bold: Boolean = false) = addView(TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (bold) PleosUi.TEXT else PleosUi.SECONDARY); contentDescription = key
        includeFontPadding = false; if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0,dp(6),0,dp(6))
    },LayoutParams(-1,-2))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

@SuppressLint("ViewConstructor")
private class GapDiagramView(context: Context, private val state: GapStudyState) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    init { contentDescription = "교육용 차량 간격 그림 · ${state.gapMeters.toInt()}미터" }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = 0xff252c35.toInt(); rect.set(0f,0f,w,h); canvas.drawRoundRect(rect,12f,12f,paint)
        val start = w * .16f; val end = start + w * (.14f + .62f * (state.gapMeters / 100).coerceIn(0.0,1.0).toFloat())
        paint.color = 0xffd4d9df.toInt(); paint.strokeWidth = 2f; canvas.drawLine(start,h*.5f,end,h*.5f,paint)
        paint.color = 0xff73d7b8.toInt(); rect.set(start-w*.035f,h*.34f,start+w*.035f,h*.68f); canvas.drawRoundRect(rect,6f,6f,paint)
        paint.color = 0xffd4d9df.toInt(); rect.set(end-w*.035f,h*.34f,end+w*.035f,h*.68f); canvas.drawRoundRect(rect,6f,6f,paint)
        paint.textSize = PleosUi.canvasTextSize(context, 20f * resources.displayMetrics.density); paint.textAlign = Paint.Align.CENTER
        canvas.drawText("내 차",start,h*.88f,paint); canvas.drawText("앞차",end,h*.88f,paint)
    }
}
