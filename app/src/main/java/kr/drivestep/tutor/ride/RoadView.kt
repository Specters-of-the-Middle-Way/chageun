package kr.drivestep.tutor.ride

import kr.drivestep.tutor.design.PleosUi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Input-responsive schematic. No camera, route, real braking-distance or steering model. */
class RoadView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state: RideSnapshot? = null
    private var paused = false
    private var elapsed = 0.0
    private var phaseEnteredAt = 0.0
    private val teal = Color.rgb(8, 127, 113)
    private val ink = Color.rgb(24, 45, 50)

    fun update(snapshot: RideSnapshot, isPaused: Boolean, elapsedSeconds: Double) {
        if (state?.phase != snapshot.phase) phaseEnteredAt = elapsedSeconds
        state = snapshot
        paused = isPaused
        elapsed = elapsedSeconds
        contentDescription = "가상 도로. ${if (isPaused) "일시정지. " else ""}속도 ${snapshot.speedKmh.toInt()} 킬로미터. " +
            "가상 기어 ${snapshot.gear.name}. " +
            (if (snapshot.brakePressed) "가상 브레이크 눌림. " else "가상 브레이크 해제. ") +
            (if (abs(snapshot.laneOffset) < .15) "차로 가운데. " else if (snapshot.laneOffset < 0) "차로 가운데보다 왼쪽. " else "차로 가운데보다 오른쪽. ") +
            (if (snapshot.pedestrianPresent) "횡단보도에 보행자가 있어요." else "보행자 없음.")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 600f, height / 320f)
        if (scale <= 0f) return
        canvas.drawColor(Color.rgb(235, 242, 237))
        canvas.save()
        canvas.translate((width - 600 * scale) / 2, (height - 320 * scale) / 2)
        canvas.scale(scale, scale)
        label(canvas, "가상 주행 · 실제 제동거리/조향비 미반영", 18f, 24f, 15f, Color.rgb(81, 104, 104))
        val s = state
        rect(canvas, 170f, 37f, 430f, 320f, Color.rgb(197, 210, 204))
        rect(canvas, 184f, 37f, 416f, 320f, Color.rgb(86, 108, 110))
        val laneColor = if (s != null && abs(s.laneOffset) >= .35) Color.rgb(249, 192, 90) else Color.rgb(238, 245, 237)
        line(canvas, 193f, 37f, 193f, 320f, 4f, laneColor)
        line(canvas, 407f, 37f, 407f, 320f, 4f, laneColor)
        // The painted roadside markers move toward the driver as virtual distance increases.
        val scroll = (((s?.distanceM ?: 0.0) * 6.0) % 54.0).toFloat()
        for (i in -1..6) {
            val y = 40f + 54f * i + scroll
            rect(canvas, 176f, y, 181f, y + 23f, Color.WHITE)
            rect(canvas, 419f, y, 424f, y + 23f, Color.WHITE)
        }
        if (s != null) {
            val frontY = 232f
            val stopY = frontY - ((s.stopLineM - s.distanceM) * 6.0).toFloat()
            if (stopY in 45f..310f) {
                if (s.lesson == RideLesson.CROSSWALK) {
                    for (i in 0..8) rect(canvas, 201f + i * 22f, stopY - 35f, 211f + i * 22f, stopY - 14f, Color.WHITE)
                    if (s.pedestrianPresent) {
                        val walking = if (s.phase == RidePhase.WAIT_FOR_PEDESTRIAN) (s.waitingSeconds / 6.0).coerceIn(0.0, 1.0).toFloat() else 0f
                        person(canvas, 200f + 188f * walking, stopY - 25f)
                        label(canvas, "보행자", 447f, stopY - 20f, 16f, Color.rgb(124, 72, 159))
                    }
                }
                line(canvas, 201f, stopY, 399f, stopY, 5f, Color.WHITE)
                label(canvas, "정지선", 106f, stopY + 5f, 16f, ink)
            }
            if (s.lesson == RideLesson.CROSSWALK) {
                round(canvas, 449f, 54f, 492f, 137f, 10f, ink)
                dot(canvas, 470f, 76f, 12f, if (s.signal == VirtualSignal.RED) Color.rgb(223, 75, 59) else Color.rgb(68, 83, 84))
                dot(canvas, 470f, 116f, 12f, if (s.signal == VirtualSignal.GREEN) Color.rgb(54, 189, 126) else Color.rgb(68, 83, 84))
                label(canvas, if (s.signal == VirtualSignal.GREEN) "가상 녹색" else "가상 적색", 443f, 159f, 15f, ink)
            }
            val x = 300f + 89f * s.laneOffset.toFloat()
            car(canvas, x, frontY + 31f, s.steering.toFloat(), s.brakePressed)
            label(canvas, "내 가상 차량", 18f, 279f, 16f, teal)
            label(canvas, if (abs(s.laneOffset) < .15) "차로 가운데" else if (s.laneOffset < 0) "가운데보다 왼쪽" else "가운데보다 오른쪽", 18f, 302f, 14f, ink)
            if (paused) {
                round(canvas, 220f, 145f, 380f, 191f, 16f, Color.WHITE)
                label(canvas, "잠시 쉬는 중", 300f, 175f, 20f, teal, Paint.Align.CENTER)
            }
        }
        canvas.restore()
    }

    private fun car(c: Canvas, x: Float, y: Float, steering: Float, brake: Boolean) {
        c.save(); c.translate(x, y)
        round(c, -24f, -31f, 24f, 34f, 9f, teal)
        round(c, -17f, -21f, 17f, -7f, 4f, Color.rgb(218, 241, 236))
        round(c, -16f, 12f, 16f, 24f, 3f, Color.rgb(173, 214, 206))
        for (xWheel in listOf(-24f, 24f)) {
            c.save(); c.translate(xWheel, -17f); c.rotate(steering * 28f)
            round(c, -3f, -9f, 3f, 9f, 2f, ink); c.restore()
        }
        if (brake) {
            rect(c, -23f, 26f, -11f, 32f, Color.rgb(252, 112, 90))
            rect(c, 11f, 26f, 23f, 32f, Color.rgb(252, 112, 90))
        }
        c.restore()
    }

    private fun person(c: Canvas, x: Float, y: Float) {
        val color = Color.rgb(124, 72, 159)
        dot(c, x, y - 12f, 7f, color)
        line(c, x, y - 3f, x, y + 11f, 6f, color)
        line(c, x - 8f, y + 1f, x + 8f, y + 1f, 4f, color)
        line(c, x, y + 9f, x - 7f, y + 20f, 4f, color)
        line(c, x, y + 9f, x + 7f, y + 20f, 4f, color)
    }

    private fun label(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        paint.color = color; paint.style = Paint.Style.FILL; paint.textSize = PleosUi.canvasTextSize(context, size); paint.textAlign = align
        c.drawText(value, x, y, paint)
    }
    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL; c.drawRect(l, t, r, b, paint)
    }
    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL; c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }
    private fun dot(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL; c.drawCircle(x, y, radius, paint)
    }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x1, y1, x2, y2, paint)
    }
}
