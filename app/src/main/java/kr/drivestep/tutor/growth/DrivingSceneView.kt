package kr.drivestep.tutor.growth

import kr.drivestep.tutor.design.PleosUi
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A schematic, parked-only teaching animation. It never reads/writes a vehicle,
 * predicts a free path, converts a steering input into a real angle or scores a driver.
 * The host supplies the lesson action and owns the parked/foreground gate.
 */
class DrivingSceneView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scene = "setup"
    private var action = "IDLE"
    private var lessonStep = ""
    private var progress = 0f
    private var steering = 0f
    private var animator: ValueAnimator? = null
    private var hostPaused = false
    private var completed = false

    private val ink = Color.rgb(24, 45, 50)
    private val muted = Color.rgb(93, 113, 114)
    private val teal = Color.rgb(8, 127, 113)
    private val amber = Color.rgb(214, 139, 42)
    private val purple = Color.rgb(139, 91, 176)
    private val road = Color.rgb(222, 230, 228)
    private val line = Color.rgb(250, 252, 251)
    private val darkRoad = Color.rgb(79, 98, 103)

    init {
        minimumHeight = (260 * resources.displayMetrics.density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun show(scene: String, animation: String = "idle") {
        this.scene = scene.lowercase(Locale.ROOT)
        action = animation.uppercase(Locale.ROOT)
        animator?.cancel()
        animator = null
        progress = 0f
        completed = false
        updateDescription()
        invalidate()
        startAnimationIfAllowed()
    }

    /** Scene conditions come from the authored step, never inferred from the actual road. */
    fun setLessonStep(stepId: String) {
        lessonStep = stepId.lowercase(Locale.ROOT)
        updateDescription()
        invalidate()
    }

    /** Normalized illustrative steering input, not degrees or a recommended real-car angle. */
    fun setPracticeSteering(value: Float) {
        steering = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        updateDescription()
        invalidate()
    }

    fun pause() {
        hostPaused = true
        animator?.pause()
    }

    fun resume() {
        hostPaused = false
        startAnimationIfAllowed()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimationIfAllowed()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnimationIfAllowed() else animator?.pause()
    }

    private fun startAnimationIfAllowed() {
        if (hostPaused || !isAttachedToWindow || windowVisibility != VISIBLE || completed) return
        animator?.let {
            if (it.isPaused) it.resume()
            return
        }
        // Intro has a gentle observation pulse. A choice demonstration runs once.
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = if (action == "IDLE") 2800L else 2600L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = if (action == "IDLE") ValueAnimator.INFINITE else 0
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                progress = it.animatedValue as Float
                if (action != "IDLE" && progress >= 1f) completed = true
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = maxOf(minimumHeight, (width * 0.49f).toInt())
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(240, 246, 243))
        val scale = min(width / 720f, height / 350f)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate((width - 720f * scale) / 2f, (height - 350f * scale) / 2f)
        canvas.scale(scale, scale)
        text(canvas, "가상 상황 · 축척/조향비 미반영", 18f, 27f, 15f, muted)
        dot(canvas, 551f, 22f, 5f, teal)
        text(canvas, "내 차", 563f, 27f, 13f, ink)
        dot(canvas, 626f, 22f, 5f, amber)
        text(canvas, "상대 차", 638f, 27f, 13f, ink)
        when (scene) {
            "setup" -> drawSetup(canvas)
            "departure" -> drawDeparture(canvas)
            "solid_line" -> drawSolidLine(canvas)
            "right_turn" -> drawRightTurn(canvas)
            "highway" -> drawHighway(canvas)
            "alley", "hill" -> drawNarrow(canvas, scene == "hill")
            "tunnel" -> drawTunnel(canvas)
            "courtesy" -> drawCourtesy(canvas)
            else -> drawDeparture(canvas)
        }
        rect(canvas, 0f, 304f, 720f, 350f, Color.rgb(228, 239, 234))
        text(canvas, actionLabel(), 18f, 333f, 17f, ink, true)
        drawSteering(canvas, 624f, 327f)
        canvas.restore()
    }

    private fun drawSetup(c: Canvas) {
        round(c, 98f, 67f, 621f, 278f, 28f, Color.WHITE)
        // Side profile: seat support, bent arms/knees, seat belt and a centered mirror.
        val emphasis = if (action in setOf("OBSERVE", "SEAT", "MIRRORS", "WHEEL", "BELT")) pulse() else 0.25f
        round(c, 225f, 126f, 245f, 221f, 10f, muted)
        round(c, 226f, 215f, 330f, 235f, 8f, muted)
        dot(c, 290f, 108f, 22f, amber)
        stroke(c, 279f, 137f, 270f, 197f, 16f, teal)
        stroke(c, 275f, 194f, 337f, 209f, 14f, teal)
        stroke(c, 337f, 209f, 378f, 251f, 13f, teal)
        stroke(c, 377f, 251f, 413f, 251f, 10f, ink)
        stroke(c, 282f, 149f, 338f, 160f, 11f, teal)
        stroke(c, 338f, 160f, 385f, 143f, 10f, teal)
        stroke(c, 259f, 137f, 312f, 220f, 7f, Color.rgb(56, 72, 77))
        ring(c, 402f, 139f, 24f, 6f, ink)
        stroke(c, 421f, 150f, 450f, 184f, 8f, muted)
        round(c, 433f, 243f, 451f, 263f, 3f, ink)
        round(c, 473f, 79f, 523f, 96f, 5f, Color.rgb(177, 210, 225))
        if (action in setOf("IDLE", "OBSERVE", "MIRRORS")) ring(c, 498f, 87f, 34f + emphasis * 8f, 3f, purple)
        if (action == "SEAT") {
            ring(c, 265f, 188f, 47f + 8f * emphasis, 3f, purple)
            ring(c, 339f, 211f, 32f + 6f * emphasis, 3f, purple)
        }
        if (action == "WHEEL") ring(c, 402f, 139f, 34f + emphasis * 8f, 3f, purple)
        if (action == "BELT") stroke(c, 259f, 137f, 312f, 220f, 5f + 3f * emphasis, amber)
        text(c, "어깨는 등받이에", 105f, 105f, 16f, ink)
        text(c, "무릎·팔꿈치는 여유 있게", 326f, 282f, 16f, ink)
        text(c, "미러는 앉은 자세에서", 458f, 119f, 15f, purple)
        // Eye-line illustration is not a computed mirror or seat setting.
        dashed(c, 306f, 108f, 484f, 90f, 2f, purple)
    }

    private fun drawDeparture(c: Canvas) {
        verticalRoad(c, 246f, 474f)
        dashed(c, 360f, 54f, 360f, 290f, 3f, line)
        round(c, 474f, 154f, 542f, 279f, 8f, Color.rgb(206, 224, 215))
        text(c, "출발 전 주변 확인", 32f, 94f, 19f, ink, true)
        text(c, "신호 → 양보 → 합류", 32f, 120f, 16f, muted)
        val merge = if (action == "PROCEED") progress else 0f
        val x = lerp(503f, 414f, merge)
        val y = lerp(230f, 165f, merge)
        car(c, x, y, teal, -22f * sin(merge * Math.PI.toFloat()), action == "SIGNAL")
        car(c, 413f, 83f + if (action == "WAIT" || action == "YIELD") 80f * progress else 0f, amber)
        if (action == "OBSERVE" || action == "IDLE") observation(c, 500f, 220f)
        if (action in setOf("WAIT", "STOP", "YIELD")) stopBadge(c, x, y - 49f)
        arrow(c, 301f, 220f, 301f, 100f, muted)
    }

    private fun drawSolidLine(c: Canvas) {
        val dotted = lessonStep == "solid_line_dotted"
        verticalRoad(c, 239f, 481f)
        if (dotted) dashed(c, 360f, 54f, 360f, 290f, 5f, line)
        else stroke(c, 360f, 54f, 360f, 290f, 5f, line)
        text(c, if (dotted) "백색 점선 구간" else "백색 실선 구간", 29f, 93f, 20f, ink, true)
        text(c, if (dotted) "신호와 간격도 확인" else "차로를 유지해요", 29f, 121f, 17f, muted)
        val forward = if (action in setOf("PROCEED", "WAIT")) 65f * progress else 0f
        car(c, 415f, 227f - forward, teal)
        car(c, 300f, 105f, amber)
        if (action == "OBSERVE" || action == "IDLE") ring(c, 360f, 172f, 20f + 12f * pulse(), 3f, purple)
        if (action == "STOP") stopBadge(c, 416f, 177f)
        text(c, if (dotted) "점선도 바로 진입은 금물" else "선 표시를 먼저 확인", 499f, 174f, 14f, purple)
    }

    private fun drawRightTurn(c: Canvas) {
        val dedicated = lessonStep == "right_turn_arrow"
        val withPeople = lessonStep == "right_turn_people" || lessonStep.isEmpty()
        rect(c, 260f, 51f, 420f, 294f, road)
        rect(c, 260f, 80f, 681f, 190f, road)
        for (i in 0..6) rect(c, 269f + i * 22f, 160f, 280f + i * 22f, 181f, line)
        stroke(c, 268f, 197f, 411f, 197f, 4f, Color.WHITE)
        round(c, 216f, 78f, 251f, 150f, 8f, ink)
        dot(c, 233f, 92f, 8f, if (!withPeople) Color.rgb(208, 70, 56) else Color.rgb(75, 95, 89))
        if (dedicated) {
            // A dedicated red right-turn signal, with its green arrow currently unlit.
            text(c, "→", 233f, 141f, 25f, Color.rgb(75, 95, 89), true, Paint.Align.CENTER)
            text(c, "우회전 전용", 153f, 67f, 16f, ink, true)
            text(c, "전용 적색 신호", 29f, 100f, 19f, ink, true)
            text(c, "길이 비어도 대기", 29f, 129f, 16f, muted)
        } else {
            dot(c, 233f, 134f, 8f, if (withPeople) teal else Color.rgb(75, 95, 89))
            text(c, if (withPeople) "차량 녹색 신호" else "차량 적색 신호", 29f, 100f, 19f, ink, true)
            text(c, if (withPeople) "사람이 건너려는 중" else "먼저 완전히 정지", 29f, 129f, 16f, muted)
        }
        text(c, "신호와 보행자 모두", 29f, 245f, 16f, muted)
        val go = if (action == "PROCEED" && !withPeople && !dedicated) progress else 0f
        val x = 364f + 221f * go * go
        val y = 241f - 117f * min(1f, go * 2f)
        car(c, x, y, teal, 90f * go, action == "SIGNAL", rightSignal = true)
        if (withPeople) {
            val walking = if (action == "WAIT" || action == "OBSERVE") progress else 0f
            person(c, 248f + 182f * walking, 171f, purple)
            text(c, "보행자 통행 보호", 455f, 215f, 16f, purple)
            text(c, "녹색만 보고 출발하지 않아요", 455f, 238f, 14f, muted)
        } else if (dedicated) {
            text(c, "횡단보도가 비어 있어도", 455f, 215f, 15f, purple)
            text(c, "전용 적색 신호에서는 정지", 455f, 239f, 14f, muted)
        }
        if (action in setOf("STOP", "WAIT", "OBSERVE") || dedicated) stopBadge(c, 364f, 211f)
        if (action == "OBSERVE" || action == "IDLE") observation(c, 364f, 210f)
    }

    private fun drawHighway(c: Canvas) {
        val congested = lessonStep == "highway_exception"
        verticalRoad(c, 235f, 493f)
        dashed(c, 364f, 52f, 364f, 292f, 4f, line)
        text(c, "고속도로", 34f, 94f, 20f, ink, true)
        text(c, if (congested) "통행량 증가로 정체" else "추월 후 주행차로로", 34f, 123f, 16f, muted)
        if (congested) {
            val drift = if (action == "STOP") 0f else 7f * progress
            for (y in listOf(90f, 174f)) car(c, 300f, y - drift, amber)
            for (y in listOf(90f, 174f, 258f)) car(c, 431f, y - drift, amber)
            car(c, 300f, 258f - drift, teal)
            if (action == "OBSERVE" || action == "IDLE") observation(c, 364f, 171f)
            text(c, "여러 차가 함께", 515f, 153f, 16f, muted)
            text(c, "느리게 움직여요", 515f, 178f, 16f, muted)
            text(c, "내 속도만의 문제가 아님", 515f, 216f, 14f, purple)
        } else {
            text(c, "추월차로", 259f, 76f, 14f, muted)
            text(c, "주행차로", 390f, 76f, 14f, muted)
            val change = if (action == "RIGHT_LANE") progress else 0f
            car(c, lerp(300f, 431f, change), 225f - 47f * change, teal,
                26f * sin(change * Math.PI.toFloat()), action == "SIGNAL" || action == "RIGHT_LANE", rightSignal = true)
            car(c, 431f, 105f, amber)
            if (action == "OBSERVE" || action == "IDLE") observation(c, 344f, 212f)
            text(c, "뒤·옆을 확인한", 515f, 185f, 15f, muted)
            text(c, "가상 장면입니다", 515f, 208f, 15f, muted)
            text(c, "실제 빈 공간 감지 아님", 515f, 240f, 14f, muted)
        }
    }

    private fun drawNarrow(c: Canvas, hill: Boolean) {
        verticalRoad(c, 307f, 431f)
        round(c, 430f, 172f, 510f, 292f, 7f, Color.rgb(205, 226, 215))
        dashed(c, 440f, 183f, 497f, 183f, 3f, teal)
        text(c, if (hill) "좁은 비탈길 교행" else "좁은 골목 교행", 27f, 88f, 20f, ink, true)
        text(c, if (hill) "그림 속 내 차는 올라가는 중" else "멈추고 공간부터 확인해요", 27f, 116f, 15f, muted)
        text(c, "그림 속 대피 공간", 516f, 225f, 15f, teal)
        text(c, "후방·가장자리 확인 필요", 516f, 247f, 13f, muted)
        if (hill) {
            arrow(c, 255f, 245f, 255f, 154f, muted)
            text(c, "오르막", 206f, 275f, 15f, ink)
            val slope = Path().apply { moveTo(46f, 250f); lineTo(171f, 177f); lineTo(171f, 250f); close() }
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(209, 222, 215); c.drawPath(slope, paint)
        }
        val yield = action == "YIELD" || action == "REVERSE_SAFE"
        val p = if (yield) min(1f, progress * 1.75f) else 0f
        val y = if (action == "REVERSE_SAFE") lerp(210f, 258f, p) else lerp(223f, 221f, p)
        val x = lerp(369f, 472f, p)
        car(c, x, y, teal, if (yield) 24f * sin(p * Math.PI.toFloat()) else 0f)
        val otherProgress = if (yield) ((progress - 0.60f) / 0.40f).coerceIn(0f, 1f) else 0f
        car(c, 369f, lerp(98f, 273f, otherProgress), amber, 180f)
        if (action in setOf("STOP", "WAIT", "OBSERVE")) stopBadge(c, x, y - 47f)
        if (action == "OBSERVE" || action == "IDLE") observation(c, 472f, 231f)
    }

    private fun drawTunnel(c: Canvas) {
        round(c, 228f, 56f, 496f, 293f, 46f, darkRoad)
        rect(c, 244f, 91f, 480f, 294f, Color.rgb(105, 123, 126))
        dashed(c, 362f, 74f, 362f, 293f, 3f, line)
        for (i in 0..3) {
            round(c, 237f, 97f + i * 48f, 245f, 119f + i * 48f, 3f, Color.rgb(245, 219, 129))
            round(c, 479f, 97f + i * 48f, 487f, 119f + i * 48f, 3f, Color.rgb(245, 219, 129))
        }
        car(c, 418f, 235f - if (action == "PROCEED") 80f * progress else 0f, teal)
        car(c, 418f, 104f, amber)
        text(c, "터널 진입 전", 28f, 91f, 20f, ink, true)
        text(c, "창문·환기 설정 확인", 28f, 120f, 16f, muted)
        text(c, "차간거리와 전방 주시", 28f, 147f, 16f, muted)
        round(c, 539f, 124f, 630f, 193f, 8f, Color.WHITE)
        val closed = action == "OBSERVE" || action == "WAIT"
        rect(c, 550f, 137f, 619f, if (closed) 181f else 154f, Color.rgb(176, 212, 223))
        stroke(c, 550f, 182f, 619f, 182f, 3f, ink)
        text(c, "창문 확인 예시", 532f, 220f, 15f, muted)
        text(c, "자동 제어하지 않아요", 515f, 244f, 14f, muted)
    }

    private fun drawCourtesy(c: Canvas) {
        verticalRoad(c, 242f, 482f)
        dashed(c, 362f, 54f, 362f, 290f, 3f, line)
        text(c, "배려하는 운전", 28f, 89f, 20f, ink, true)
        text(c, "감사보다 안정이 먼저", 28f, 116f, 16f, muted)
        val preparingMerge = lessonStep == "courtesy_thanks" && action != "THANKS_DEMO"
        val merge = if (action == "SIGNAL") progress else 0f
        val ownX = if (preparingMerge) lerp(300f, 420f, merge) else 420f
        val ownY = if (action == "PROCEED") 150f - 55f * progress else 150f - 15f * merge
        // The amber driver has left room behind our intended right-lane merge.
        car(c, 420f, 249f, amber)
        car(c, ownX, ownY, teal,
            rotation = if (preparingMerge) 24f * sin(merge * Math.PI.toFloat()) else 0f,
            signal = action == "SIGNAL", rightSignal = true, hazard = action == "THANKS_DEMO")
        if (action == "OBSERVE" || action == "IDLE") observation(c, 380f, 191f)
        if (action == "STOP") stopBadge(c, ownX, ownY - 48f)
        if (action == "THANKS_DEMO") {
            text(c, "감사 표시 두 번", 510f, 188f, 16f, teal)
            text(c, "가상 관습 예시", 510f, 213f, 15f, muted)
            text(c, "의무·안전 규칙 아님", 510f, 238f, 14f, muted)
        } else if (preparingMerge) {
            text(c, "오른쪽 방향지시", 510f, 188f, 16f, teal)
            text(c, "확인한 간격으로 합류", 510f, 213f, 15f, muted)
            text(c, "실제 도로 판단 아님", 510f, 238f, 14f, muted)
        } else {
            text(c, "실차 비상등", 510f, 188f, 16f, teal)
            text(c, "자동 점등 없음", 510f, 213f, 16f, teal)
            text(c, "무리한 끼어들기 금물", 510f, 238f, 14f, muted)
        }
    }

    private fun verticalRoad(c: Canvas, left: Float, right: Float) {
        round(c, left - 8f, 50f, right + 8f, 294f, 10f, Color.rgb(198, 213, 207))
        rect(c, left, 50f, right, 294f, road)
        stroke(c, left + 6f, 50f, left + 6f, 294f, 3f, line)
        stroke(c, right - 6f, 50f, right - 6f, 294f, 3f, line)
    }

    private fun car(c: Canvas, x: Float, y: Float, color: Int, rotation: Float = 0f,
                    signal: Boolean = false, rightSignal: Boolean = false, hazard: Boolean = false) {
        c.save(); c.translate(x, y); c.rotate(rotation)
        round(c, -23f, -35f, 23f, 35f, 9f, color)
        round(c, -16f, -20f, 16f, -6f, 3f, Color.rgb(221, 239, 238))
        round(c, -16f, 13f, 16f, 24f, 3f, Color.rgb(182, 213, 211))
        // A small wheel marker reflects the virtual slider; it is not a vehicle model.
        c.save(); c.translate(-23f, -18f); c.rotate(if (color == teal) steering * 25f else 0f)
        round(c, -3f, -9f, 3f, 9f, 2f, ink); c.restore()
        c.save(); c.translate(23f, -18f); c.rotate(if (color == teal) steering * 25f else 0f)
        round(c, -3f, -9f, 3f, 9f, 2f, ink); c.restore()
        if (signal && (progress * 10f).toInt() % 2 == 0) {
            val sx = if (rightSignal) 24f else -24f
            dot(c, sx, -29f, 5f, Color.rgb(255, 198, 48))
            dot(c, sx, 28f, 5f, Color.rgb(255, 198, 48))
        }
        // A requested, explicit courtesy demonstration: exactly two on intervals.
        // It cannot reach vehicle APIs and does not imply that drivers should do this.
        if (hazard && (progress in 0.06f..0.25f || progress in 0.48f..0.67f)) {
            for (sx in listOf(-24f, 24f)) {
                dot(c, sx, -29f, 5f, Color.rgb(255, 198, 48))
                dot(c, sx, 28f, 5f, Color.rgb(255, 198, 48))
            }
        }
        c.restore()
    }

    private fun person(c: Canvas, x: Float, y: Float, color: Int) {
        dot(c, x, y - 12f, 7f, color)
        stroke(c, x, y - 3f, x, y + 10f, 6f, color)
        stroke(c, x - 8f, y + 1f, x + 8f, y + 1f, 4f, color)
        stroke(c, x, y + 8f, x - 7f, y + 19f, 4f, color)
        stroke(c, x, y + 8f, x + 7f, y + 19f, 4f, color)
    }

    private fun observation(c: Canvas, x: Float, y: Float) {
        ring(c, x, y, 33f + 18f * pulse(), 2f, Color.argb(110, 139, 91, 176))
    }

    private fun stopBadge(c: Canvas, x: Float, y: Float) {
        round(c, x - 22f, y - 12f, x + 22f, y + 12f, 9f, Color.WHITE)
        text(c, "정지", x, y + 5f, 13f, teal, true, Paint.Align.CENTER)
    }

    private fun drawSteering(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); c.rotate(steering * 90f)
        ring(c, 0f, 0f, 15f, 3f, teal)
        stroke(c, -14f, 0f, 14f, 0f, 3f, teal)
        stroke(c, 0f, 0f, 0f, 14f, 3f, teal)
        c.restore()
        text(c, "가상 조향", x + 26f, y + 5f, 13f, muted)
    }

    private fun actionLabel() = when (action) {
        "SEAT" -> "발이 닿아도 무릎·팔꿈치에는 여유를"
        "MIRRORS" -> "앉은 자세에서 뒤·옆 시야를 확인해요"
        "WHEEL" -> "계기판 시야와 조작 여유를 확인해요"
        "BELT" -> "벨트가 목·배를 지나지 않게 확인해요"
        "THANKS_DEMO" -> "감사 표시 관습의 가상 예시 · 의무 아님"
        "STOP" -> "멈추고 확인해요"
        "OBSERVE" -> "주변·표시·내 자세를 살펴요"
        "WAIT" -> if (scene == "solid_line") "차로를 유지하며 다음 경로를 찾아요" else "서두르지 않고 기다려요"
        "PROCEED" -> "확인한 순서대로 진행하는 예시"
        "YIELD" -> "먼저 공간을 확보하고 양보해요"
        "RIGHT_LANE" -> "확인 후 주행차로로 복귀하는 예시"
        "SIGNAL" -> "내 진행 방향을 미리 알려요"
        "REVERSE_SAFE" -> "후방 확인이 전제된 가상 후진 예시"
        else -> "안내를 듣고 행동을 선택해요"
    }

    private fun updateDescription() {
        val title = when (scene) {
            "setup" -> "운전 자세와 미러 확인"
            "departure" -> "정차 위치에서 출발 준비"
            "solid_line" -> if (lessonStep == "solid_line_dotted") "백색 점선과 변경 전 주변 확인" else "백색 실선 차로 유지"
            "right_turn" -> when (lessonStep) {
                "right_turn_arrow" -> "우회전 전용 적색 신호에서 정지"
                "right_turn_people" -> "차량 녹색 신호에서도 보행자 통행 보호"
                else -> "차량 적색 신호에서 우회전 전 일시정지"
            }
            "highway" -> if (lessonStep == "highway_exception") "통행량 증가로 여러 차가 느리게 움직이는 고속도로 정체" else "고속도로 추월 후 복귀"
            "alley" -> "좁은 골목에서 마주친 차와 양보"
            "hill" -> "좁은 비탈길에서 올라가는 내 차의 양보"
            "tunnel" -> "터널 진입 전 창문과 전방 확인"
            "courtesy" -> "안정적인 합류와 배려"
            else -> "가상 운전 상황"
        }
        contentDescription = "$title. ${actionLabel()}. 축척과 실제 조향비를 반영하지 않은 설명용 애니메이션. 가상 조향 입력 ${if (abs(steering) < 0.03f) "중앙" else if (steering < 0f) "왼쪽" else "오른쪽"}."
    }

    private fun pulse() = (0.5f - cos(progress * Math.PI.toFloat() * 2f) * 0.5f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                     bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = PleosUi.canvasTextSize(context, size)
        paint.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        paint.textAlign = align; paint.pathEffect = null
        c.drawText(value, x, y, paint)
    }

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.pathEffect = null
        c.drawRect(l, t, r, b, paint)
    }

    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.pathEffect = null
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    private fun dot(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.pathEffect = null
        c.drawCircle(x, y, radius, paint)
    }

    private fun ring(c: Canvas, x: Float, y: Float, radius: Float, width: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.color = color; paint.pathEffect = null
        c.drawCircle(x, y, radius, paint)
    }

    private fun stroke(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        paint.color = color; paint.pathEffect = null; c.drawLine(x1, y1, x2, y2, paint)
    }

    private fun dashed(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.BUTT
        paint.color = color; paint.pathEffect = DashPathEffect(floatArrayOf(13f, 12f), 0f)
        c.drawLine(x1, y1, x2, y2, paint); paint.pathEffect = null
    }

    private fun arrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        stroke(c, x1, y1, x2, y2, 3f, color)
        val sign = if (y2 < y1) 1f else -1f
        stroke(c, x2, y2, x2 - 7f, y2 + 11f * sign, 3f, color)
        stroke(c, x2, y2, x2 + 7f, y2 + 11f * sign, 3f, color)
    }
}
