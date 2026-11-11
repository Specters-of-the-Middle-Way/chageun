package kr.drivestep.tutor.journey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kr.drivestep.tutor.R
import kr.drivestep.tutor.design.PleosUi
import kotlin.math.roundToInt

/**
 * Interactive visual guide for the 9-step posture, mirror, and seatbelt setup flow.
 *
 * Displays a body-proportioned reference band (derived from [DriverProfile.heightCm] and
 * [DriverProfile.weightKg]) alongside the user's current manual calibration or mirror/belt
 * alignment diagram. All drawing primitives are pre-allocated to keep [onDraw] allocation-free.
 */
class PostureGuideView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardRect = RectF()
    private val innerRect = RectF()
    private val barRect = RectF()
    private val bandRect = RectF()
    private val path = Path()
    private val dashEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)

    private val regularTypeface = runCatching {
        resources.getFont(R.font.asta_sans)
    }.getOrDefault(Typeface.DEFAULT)
    private val boldTypeface = Typeface.create(regularTypeface, Typeface.BOLD)
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = boldTypeface; color = PleosUi.TEXT }
    private val rangePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regularTypeface; color = PleosUi.SECONDARY }
    private var captionLayout: StaticLayout? = null
    private var rangeLayout: StaticLayout? = null
    private var footerHeight = dp(64f)

    private val guideTint = Color.rgb(34, 139, 126)
    private val guideBandFill = Color.argb(46, 34, 139, 126)
    private val skyTint = Color.rgb(225, 234, 242)
    private val roadTint = Color.rgb(212, 216, 222)
    private val bodyZoneFill = Color.argb(58, 68, 70, 78)

    private var stepIndex = 0
    private var profile = DriverProfile()
    private var calibration = VirtualSeatCalibration()
    private var recommended = profile.recommendedCalibration()
    private var headBand = profile.guideBand(CalibrationAxis.HEIGHT)
    private var activeBand = profile.guideBand(CalibrationAxis.FORE_AFT)
    private var activeValue = 50
    private var captionText = ""
    private var rangeText = ""

    init {
        minimumHeight = dp(180f).roundToInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        update(0, profile, calibration)
    }

    fun update(step: Int, driverProfile: DriverProfile, currentCalibration: VirtualSeatCalibration) {
        stepIndex = step.coerceIn(0, JourneyState.SETUP_COUNT - 1)
        profile = driverProfile.validated()
        calibration = currentCalibration
        recommended = profile.recommendedCalibration()
        val headCenter = (50 + ((profile.heightCm - 175) * 0.52).roundToInt()).coerceIn(25, 80)
        headBand = PostureGuideBand(headCenter, (headCenter - 8).coerceAtLeast(0), (headCenter + 8).coerceAtMost(100))

        val axis = when (stepIndex) {
            0 -> CalibrationAxis.FORE_AFT
            1 -> CalibrationAxis.HEIGHT
            2 -> CalibrationAxis.BACKREST
            4 -> CalibrationAxis.STEERING
            else -> null
        }
        if (axis != null) {
            activeBand = profile.guideBand(axis)
            activeValue = calibration.value(axis)
            captionText = if (activeBand.contains(activeValue)) {
                "체격 참고 구간 안 · 직접 착좌감 확인"
            } else {
                "참고 시작 구간 ${activeBand.minRecommended}~${activeBand.maxRecommended} (현재 $activeValue)"
            }
            rangeText = "권장 참고 중심 ${activeBand.recommendedCenter} / 현재 $activeValue"
        } else {
            activeBand = headBand
            activeValue = headBand.recommendedCenter
            captionText = when (stepIndex) {
                3 -> "귀 높이와 헤드레스트 중심 일치 확인"
                5 -> "룸미러 중앙에 뒷유리 전체가 들어오도록 조절"
                6 -> "좌측 미러: 지평선 1/2 · 우측 안쪽 1/4 내 차체"
                7 -> "우측 미러: 지평선 1/2 · 좌측 안쪽 1/4 내 차체"
                else -> "어깨 중앙과 골반 아래로 꼬임 없이 밀착"
            }
            rangeText = "${profile.heightCm}cm · ${profile.weightKg}kg 기준 시각 가이드"
        }
        contentDescription = "운전 자세 및 미러 시각 가이드. $captionText"
        updateCaptionLayouts()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCaptionLayouts()
    }

    private fun updateCaptionLayouts() {
        val textWidth = (width - dp(48f)).roundToInt()
        if (textWidth <= 0) return
        captionPaint.textSize = PleosUi.canvasTextSize(context, dp(18f))
        rangePaint.textSize = PleosUi.canvasTextSize(context, dp(16f))
        captionLayout = StaticLayout.Builder.obtain(captionText, 0, captionText.length, captionPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
        rangeLayout = StaticLayout.Builder.obtain(rangeText, 0, rangeText.length, rangePaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
        footerHeight = (captionLayout?.height ?: 0) + (rangeLayout?.height ?: 0) + dp(12f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val pad = dp(8f)
        cardRect.set(pad, pad, width - pad, height - pad)
        paint.style = Paint.Style.FILL
        paint.color = PleosUi.SURFACE
        canvas.drawRoundRect(cardRect, dp(14f), dp(14f), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = PleosUi.LINE
        canvas.drawRoundRect(cardRect, dp(14f), dp(14f), paint)

        when (stepIndex) {
            in 0..4 -> drawPostureDiagram(canvas)
            5 -> drawRoomMirrorDiagram(canvas)
            6 -> drawSideMirrorDiagram(canvas, isLeft = true)
            7 -> drawSideMirrorDiagram(canvas, isLeft = false)
            else -> drawSeatbeltDiagram(canvas)
        }
        drawFooterCaption(canvas)
    }

    private fun drawPostureDiagram(canvas: Canvas) {
        val left = cardRect.left + dp(14f)
        val right = cardRect.right - dp(14f)
        val top = cardRect.top + dp(12f)
        val diagramBottom = cardRect.bottom - footerHeight - dp(26f)
        innerRect.set(left, top, right, diagramBottom)

        paint.style = Paint.Style.FILL
        paint.color = PleosUi.BACKGROUND
        canvas.drawRoundRect(innerRect, dp(10f), dp(10f), paint)

        // Scale the whole scene uniformly, including calibration offsets and line widths.
        val sceneScale = minOf(innerRect.width() / dp(200f), innerRect.height() / dp(128f))
        val sceneSave = canvas.save()
        canvas.translate(innerRect.centerX(), innerRect.centerY())
        canvas.scale(sceneScale, sceneScale)
        val floorY = dp(50f)

        // Floor reference line and brake pedal on the left
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = PleosUi.LINE
        canvas.drawLine(-dp(92f), floorY, dp(92f), floorY, paint)

        val pedalX = -dp(80f)
        paint.strokeWidth = dp(4f)
        paint.color = PleosUi.SELECTED
        canvas.drawLine(pedalX, floorY - dp(24f), pedalX + dp(10f), floorY - dp(4f), paint)

        // Draw recommended silhouette (dashed teal) first, then current calibration (solid dark)
        drawSeatAndWheelSilhouette(
            canvas = canvas,
            centerX = 0f,
            floorY = floorY,
            cal = recommended,
            color = guideTint,
            dashed = true,
        )
        drawSeatAndWheelSilhouette(
            canvas = canvas,
            centerX = 0f,
            floorY = floorY,
            cal = calibration,
            color = PleosUi.TEXT,
            dashed = false,
        )
        canvas.restoreToCount(sceneSave)

        // Draw target gauge bar below diagram
        val barTop = diagramBottom + dp(10f)
        val barBottom = barTop + dp(12f)
        barRect.set(left, barTop, right, barBottom)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = PleosUi.RAISED
        canvas.drawRoundRect(barRect, dp(6f), dp(6f), paint)

        val barWidth = barRect.width()
        val bandLeft = barRect.left + (activeBand.minRecommended / 100f) * barWidth
        val bandRight = barRect.left + (activeBand.maxRecommended / 100f) * barWidth
        bandRect.set(bandLeft, barTop, bandRight, barBottom)
        paint.color = guideBandFill
        canvas.drawRoundRect(bandRect, dp(6f), dp(6f), paint)

        val recX = barRect.left + (activeBand.recommendedCenter / 100f) * barWidth
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = guideTint
        canvas.drawLine(recX, barTop - dp(3f), recX, barBottom + dp(3f), paint)

        val curX = barRect.left + (activeValue / 100f) * barWidth
        paint.style = Paint.Style.FILL
        paint.color = if (activeBand.contains(activeValue)) guideTint else PleosUi.SELECTED
        canvas.drawCircle(curX, (barTop + barBottom) * 0.5f, dp(7f), paint)
    }

    private fun drawSeatAndWheelSilhouette(
        canvas: Canvas,
        centerX: Float,
        floorY: Float,
        cal: VirtualSeatCalibration,
        color: Int,
        dashed: Boolean,
    ) {
        val seatX = centerX + ((cal.foreAft - 50) / 50f) * dp(22f)
        val seatY = floorY - dp(18f) - ((cal.height - 50) / 50f) * dp(12f)
        val tiltX = ((cal.backrest - 50) / 50f) * dp(14f)
        val backTopX = seatX + dp(18f) + tiltX
        val backTopY = seatY - dp(48f)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (dashed) dp(3f) else dp(4.5f)
        paint.color = color
        paint.pathEffect = if (dashed) dashEffect else null

        // Seat cushion + backrest
        path.rewind()
        path.moveTo(seatX - dp(22f), seatY)
        path.lineTo(seatX + dp(14f), seatY)
        path.lineTo(backTopX, backTopY)
        canvas.drawPath(path, paint)

        // Headrest
        val headOffset = ((headBand.recommendedCenter - 50) / 50f) * dp(6f)
        canvas.drawCircle(backTopX + dp(2f), backTopY - dp(12f) - headOffset, dp(8f), paint)

        // Steering wheel column & rim on the left
        val wheelX = centerX - dp(42f) + ((cal.steering - 50) / 50f) * dp(12f)
        val wheelY = floorY - dp(54f) - ((cal.steering - 50) / 50f) * dp(6f)
        canvas.drawLine(wheelX - dp(16f), wheelY - dp(4f), wheelX, wheelY, paint)
        canvas.drawLine(wheelX - dp(4f), wheelY - dp(14f), wheelX + dp(4f), wheelY + dp(14f), paint)
        paint.pathEffect = null
    }

    private fun drawRoomMirrorDiagram(canvas: Canvas) {
        fitMirrorBounds(2.4f)

        paint.style = Paint.Style.FILL
        paint.color = skyTint
        canvas.drawRoundRect(innerRect, dp(14f), dp(14f), paint)

        val midY = innerRect.centerY()
        barRect.set(innerRect.left, midY, innerRect.right, innerRect.bottom)
        paint.color = roadTint
        canvas.drawRoundRect(barRect, dp(14f), dp(14f), paint)

        // Horizon line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.8f)
        paint.color = guideTint
        paint.pathEffect = dashEffect
        canvas.drawLine(innerRect.left + dp(10f), midY, innerRect.right - dp(10f), midY, paint)

        // Rear window target alignment box
        val insetX = innerRect.width() * 0.16f
        val insetY = innerRect.height() * 0.18f
        bandRect.set(
            innerRect.left + insetX,
            innerRect.top + insetY,
            innerRect.right - insetX,
            innerRect.bottom - insetY,
        )
        paint.strokeWidth = dp(2.5f)
        canvas.drawRoundRect(bandRect, dp(8f), dp(8f), paint)
        paint.pathEffect = null

        // Outer mirror rim
        paint.strokeWidth = dp(3f)
        paint.color = PleosUi.SELECTED
        canvas.drawRoundRect(innerRect, dp(14f), dp(14f), paint)
    }

    private fun drawSideMirrorDiagram(canvas: Canvas, isLeft: Boolean) {
        fitMirrorBounds(1.8f)

        // Sky (top 1/2) & road (bottom 1/2)
        paint.style = Paint.Style.FILL
        paint.color = skyTint
        canvas.drawRoundRect(innerRect, dp(14f), dp(14f), paint)

        val midY = innerRect.centerY()
        barRect.set(innerRect.left, midY, innerRect.right, innerRect.bottom)
        paint.color = roadTint
        canvas.drawRoundRect(barRect, dp(14f), dp(14f), paint)

        // Inner 1/4 vehicle body reference zone
        val quarterW = innerRect.width() * 0.25f
        if (isLeft) {
            bandRect.set(innerRect.right - quarterW, innerRect.top, innerRect.right, innerRect.bottom)
        } else {
            bandRect.set(innerRect.left, innerRect.top, innerRect.left + quarterW, innerRect.bottom)
        }
        paint.color = bodyZoneFill
        canvas.drawRoundRect(bandRect, dp(8f), dp(8f), paint)

        // Horizon 1/2 line & 1/4 vertical split line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = guideTint
        paint.pathEffect = dashEffect
        canvas.drawLine(innerRect.left + dp(6f), midY, innerRect.right - dp(6f), midY, paint)
        val splitX = if (isLeft) innerRect.right - quarterW else innerRect.left + quarterW
        canvas.drawLine(splitX, innerRect.top + dp(6f), splitX, innerRect.bottom - dp(6f), paint)
        paint.pathEffect = null

        // Mirror border
        paint.strokeWidth = dp(3f)
        paint.color = PleosUi.SELECTED
        canvas.drawRoundRect(innerRect, dp(14f), dp(14f), paint)

        // Labels inside mirror zones
        paint.style = Paint.Style.FILL
        paint.typeface = boldTypeface
        paint.textSize = PleosUi.canvasTextSize(context, dp(18f))
        paint.color = PleosUi.TEXT
        paint.textAlign = Paint.Align.CENTER
        val bodyCenterX = bandRect.centerX()
        val roadCenterX = if (isLeft) {
            (innerRect.left + bandRect.left) * 0.5f
        } else {
            (bandRect.right + innerRect.right) * 0.5f
        }
        canvas.drawText("1/4 차체", bodyCenterX, midY + dp(28f), paint)
        canvas.drawText("3/4 옆 차로 시야", roadCenterX, midY + dp(28f), paint)
    }

    private fun fitMirrorBounds(aspectRatio: Float) {
        val availableWidth = cardRect.width() - dp(32f)
        val availableHeight = cardRect.height() - footerHeight - dp(16f)
        val mirrorWidth = minOf(availableWidth, availableHeight * aspectRatio)
        val mirrorHeight = mirrorWidth / aspectRatio
        val centerY = cardRect.top + dp(16f) + availableHeight * 0.5f
        innerRect.set(cardRect.centerX() - mirrorWidth * 0.5f, centerY - mirrorHeight * 0.5f,
            cardRect.centerX() + mirrorWidth * 0.5f, centerY + mirrorHeight * 0.5f)
    }

    private fun drawSeatbeltDiagram(canvas: Canvas) {
        val left = cardRect.left + dp(16f)
        val right = cardRect.right - dp(16f)
        val top = cardRect.top + dp(14f)
        val bottom = cardRect.bottom - footerHeight - dp(8f)
        innerRect.set(left, top, right, bottom)

        paint.style = Paint.Style.FILL
        paint.color = PleosUi.BACKGROUND
        canvas.drawRoundRect(innerRect, dp(12f), dp(12f), paint)

        val sceneScale = minOf(innerRect.width() / dp(120f), innerRect.height() / dp(156f))
        val sceneSave = canvas.save()
        canvas.translate(innerRect.centerX(), innerRect.centerY())
        canvas.scale(sceneScale, sceneScale)
        val cx = 0f
        val headY = -dp(60f)
        val torsoTopY = headY + dp(16f)
        val pelvisY = dp(50f)

        // Torso outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = PleosUi.SECONDARY
        canvas.drawCircle(cx, headY, dp(11f), paint)
        bandRect.set(cx - dp(26f), torsoTopY, cx + dp(26f), pelvisY)
        canvas.drawRoundRect(bandRect, dp(12f), dp(12f), paint)

        // 3-point belt routing (shoulder center -> hip buckle + low pelvis lap belt)
        paint.strokeWidth = dp(5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = guideTint
        canvas.drawLine(cx - dp(22f), torsoTopY + dp(4f), cx + dp(22f), pelvisY - dp(8f), paint)
        canvas.drawLine(cx - dp(24f), pelvisY - dp(8f), cx + dp(22f), pelvisY - dp(8f), paint)
        canvas.restoreToCount(sceneSave)
    }

    private fun drawFooterCaption(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(dp(24f), cardRect.bottom - footerHeight + dp(4f))
        captionLayout?.draw(canvas)
        canvas.translate(0f, (captionLayout?.height ?: 0) + dp(4f))
        rangeLayout?.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
