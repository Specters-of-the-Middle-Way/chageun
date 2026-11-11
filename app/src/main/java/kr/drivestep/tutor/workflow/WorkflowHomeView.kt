package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton

enum class WorkflowHomeAction(val key: String) {
    PREPARE("workflow_prepare"), WEATHER("workflow_weather"), VEHICLE("workflow_connected"),
    MANUAL("workflow_manual"), GUIDES("workflow_guides"), COURSES("workflow_courses"),
    DRIVE("workflow_tasks"), PARKING("workflow_parking"), LANE_CHANGE("workflow_lane_change"),
    HISTORY("workflow_history"), RECOVER("workflow_recover")
}

/** Presentation only. Readiness, consent, recovery, and navigation remain owned by WorkflowView. */
@SuppressLint("ViewConstructor")
class WorkflowHomeView(context: Context, private val prepared: Boolean,
    private val resumeTitle: String?, private val resumeDescription: String?,
    private val actionsEnabled: Boolean, private val onAction: (WorkflowHomeAction) -> Unit
) : LinearLayout(context) {
    private val accent = Color.rgb(16, 87, 74)
    private val softAccent = Color.rgb(233, 244, 239)
    private var dashboardMode = -1

    init { orientation = VERTICAL; contentDescription = "workflow_dashboard" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density
        val nextMode = when {
            available < 680 || resources.configuration.fontScale > 1.6f -> 0
            available < 1_000 -> 1
            else -> 2
        }
        if (dashboardMode != nextMode) { dashboardMode = nextMode; build(nextMode == 2) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun build(twoColumns: Boolean) {
        removeAllViews()
        addView(label("출발 전 확인부터, 나에게 필요한 연습까지", 18, PleosUi.SECONDARY), full().apply { bottomMargin = dp(24) })
        if (resumeTitle != null) recovery()
        if (twoColumns) {
            val columns = LinearLayout(context).apply { orientation = HORIZONTAL; isBaselineAligned = false }
            addView(columns, full())
            columns.addView(preparation(), LayoutParams(0, -2, 1f).apply { marginEnd = dp(24) })
            columns.addView(practice(), LayoutParams(0, -2, 2f))
        } else {
            addView(compactPreparation(), full().apply { bottomMargin = dp(24) })
            addView(practice(), full())
            addView(tile("차량 기능 배우기", "내 차의 기능과 사용법", "info", WorkflowHomeAction.MANUAL, 112), full().apply { topMargin = dp(16) })
        }
        addView(label("가상 연습은 차량을 P에 놓고 멈춘 상태에서 이용해요.", 15, PleosUi.SECONDARY), full().apply { topMargin = dp(20) })
    }

    private fun preparation() = panel().apply {
        addView(label("출발 전 확인", 25, PleosUi.TEXT, true), full())
        addView(label("내 차를 살펴보고 출발을 준비해요.", 16, PleosUi.SECONDARY), full().apply { topMargin = dp(8) })
        addView(label(if (prepared) "준비 항목을 모두 확인했어요" else "준비 확인부터 시작해요", 15, accent).apply {
            background = shape(softAccent, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, full().apply { topMargin = dp(24); bottomMargin = dp(12) })
        addView(primary(if (prepared) "준비 항목 다시 보기" else "출발 준비하기", WorkflowHomeAction.PREPARE), full())
        addView(View(context).apply { setBackgroundColor(PleosUi.LINE) }, LayoutParams(-1, dp(1)).apply {
            topMargin = dp(24); bottomMargin = dp(12)
        })
        addView(tile("오늘 날씨", "날씨에 맞는 출발 조언", "info", WorkflowHomeAction.WEATHER, 104, quiet = true), full())
        addView(tile("차량 상태", "시트·안전벨트·위치와 내비", "vehicle", WorkflowHomeAction.VEHICLE, 104, quiet = true), full().apply { topMargin = dp(8) })
        addView(tile("차량 기능 배우기", "내 차의 기능과 사용법", "info", WorkflowHomeAction.MANUAL, 104, quiet = true), full().apply { topMargin = dp(8) })
    }

    private fun compactPreparation() = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(label("출발 전 확인", 24, PleosUi.TEXT, true), full().apply { bottomMargin = dp(12) })
        addView(primary(if (prepared) "준비 항목 다시 보기" else "출발 준비하기", WorkflowHomeAction.PREPARE), full().apply { bottomMargin = dp(12) })
        addView(pair(
            tile("오늘 날씨", "출발 전 날씨 조언", "info", WorkflowHomeAction.WEATHER, 112),
            tile("차량 상태", "시트·벨트·위치와 내비", "vehicle", WorkflowHomeAction.VEHICLE, 112)
        ), full())
    }

    private fun practice() = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(label("운전 연습", 25, PleosUi.TEXT, true), full())
        addView(label("화면 속 상황으로 차근차근 익혀요", 16, PleosUi.SECONDARY), full().apply { topMargin = dp(8); bottomMargin = dp(20) })
        addView(pair(
            tile("주행 연습", "출발·간격·교차로 등 18개 상황", "steering", WorkflowHomeAction.DRIVE, 164, featured = true),
            tile("주차 연습", "후진·T자·평행·전면 주차", "vehicle", WorkflowHomeAction.PARKING, 164)
        ), full().apply { bottomMargin = dp(16) })
        addView(pair(
            tile("차로 변경", "운전자 시점으로 순서 익히기", "navigation", WorkflowHomeAction.LANE_CHANGE, 164),
            tile("맞춤 코스", "연습 목표에 맞는 코스 고르기", "driving", WorkflowHomeAction.COURSES, 164)
        ), full())
        addView(label("배우고 돌아보기", 21, PleosUi.TEXT, true), full().apply { topMargin = dp(28); bottomMargin = dp(14) })
        addView(pair(
            tile("운전의 기본", "시선·거리·노면·교차로", "eye", WorkflowHomeAction.GUIDES, 112),
            tile("연습 기록", "복습하고 다음 연습 고르기", "profile", WorkflowHomeAction.HISTORY, 112)
        ), full())
    }

    private fun recovery() {
        val stacked = dashboardMode == 0
        val banner = panel(softAccent).apply {
            orientation = if (stacked) VERTICAL else HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val words = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label("중단한 연습 · $resumeTitle", 21, PleosUi.TEXT, true), full())
            addView(label(resumeDescription.orEmpty(), 15, PleosUi.SECONDARY), full().apply { topMargin = dp(8) })
        }
        banner.addView(words, if (stacked) full().apply { bottomMargin = dp(16) }
            else LayoutParams(0, -2, 1f).apply { marginEnd = dp(20) })
        banner.addView(primary("준비하고 시작", WorkflowHomeAction.RECOVER),
            if (stacked) full() else LayoutParams(dp(190), -2))
        addView(banner, full().apply { bottomMargin = dp(24) })
    }

    private fun tile(title: String, subtitle: String, icon: String, action: WorkflowHomeAction,
        height: Int, featured: Boolean = false, quiet: Boolean = false): PracticeButton {
        val body = "$title\n$subtitle"
        return PracticeButton(context).apply {
            text = SpannableString(body).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(.72f), title.length + 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(if (featured) Color.rgb(225, 242, 234) else PleosUi.SECONDARY),
                    title.length + 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = if (quiet) 20f else 22f
            setTextColor(if (featured) Color.WHITE else PleosUi.TEXT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = true; isAllCaps = false
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(dp(if (quiet) 12 else 22), dp(22), dp(if (quiet) 12 else 22), dp(22))
            minHeight = dp(height); minimumHeight = dp(height); minWidth = 0; minimumWidth = 0
            backgroundTintList = null
            background = ripple(if (featured) accent else if (quiet) Color.TRANSPARENT else PleosUi.SURFACE, !featured && !quiet)
            elevation = 0f; stateListAnimator = null
            setCompoundDrawablesRelative(PleosUi.icon(context, icon, if (quiet) 28 else 36,
                if (featured) Color.WHITE else accent), null, null, null)
            compoundDrawablePadding = dp(16)
            contentDescription = action.key; isEnabled = actionsEnabled
            setOnClickListener { onAction(action) }
        }
    }

    private fun primary(title: String, action: WorkflowHomeAction) = PracticeButton(context).apply {
        text = title; textSize = 19f; isAllCaps = false; includeFontPadding = false
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        minWidth = 0; minimumWidth = 0; minHeight = dp(64); minimumHeight = dp(64)
        backgroundTintList = null; background = ripple(accent, false); elevation = 0f; stateListAnimator = null
        contentDescription = action.key; isEnabled = actionsEnabled
        setOnClickListener { onAction(action) }
    }
    private fun pair(first: View, second: View) = LinearLayout(context).apply {
        isBaselineAligned = false
        if (dashboardMode == 0) {
            orientation = VERTICAL
            addView(first, full().apply { bottomMargin = dp(16) })
            addView(second, full())
        } else {
            orientation = HORIZONTAL
            addView(first, LayoutParams(0, -1, 1f).apply { marginEnd = dp(16) })
            addView(second, LayoutParams(0, -1, 1f))
        }
    }
    private fun panel(fill: Int = PleosUi.SURFACE) = LinearLayout(context).apply {
        orientation = VERTICAL; background = shape(fill, 22)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun shape(fill: Int, radius: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (border) setStroke(dp(1), PleosUi.LINE)
    }
    private fun ripple(fill: Int, border: Boolean) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(35, 110, 145, 134)), shape(fill, 18, border), shape(Color.WHITE, 18))
    private fun full() = LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
