package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kr.drivestep.tutor.design.PleosUi
import kotlin.math.roundToInt

/** Authored top-down training scene; not a vehicle camera or surrounding-object detector. */
class ScenarioSurfaceView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var snapshot: ScenarioSnapshot? = null
    init { minimumHeight = dp(200f).roundToInt() }
    fun update(value: ScenarioSnapshot) { snapshot = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot ?: return
        val roadLeft = width * .18f
        val roadRight = width * .82f
        paint.color = PleosUi.RAISED
        rect.set(roadLeft, 0f, roadRight, height.toFloat())
        canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
        paint.color = PleosUi.SECONDARY; paint.strokeWidth = dp(2f)
        for (i in 1..2) {
            val x = roadLeft + (roadRight - roadLeft) * i / 3
            for (j in 0..8) {
                val y = (j * height / 7f + (s.distance * 8 % (height / 7f))).toFloat()
                canvas.drawLine(x, y, x, y + height / 16f, paint)
            }
        }
        val laneWidth = (roadRight - roadLeft) / 3
        val carX = width / 2f + (s.lateral.coerceIn(-4.0, 4.0) / 3.6 * laneWidth).toFloat()
        val carSave = canvas.save()
        if ((s.task.id == "D06" || s.task.id == "D07" || s.task.id == "D08")) canvas.rotate(s.rotation.toFloat(), carX, height * .75f)
        paint.color = PleosUi.TEXT
        rect.set(carX - laneWidth * .22f, height * .62f, carX + laneWidth * .22f, height * .88f)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
        paint.color = PleosUi.SURFACE
        rect.set(carX - laneWidth * .15f, height * .64f, carX + laneWidth * .15f, height * .68f)
        canvas.drawRect(rect, paint)
        canvas.restoreToCount(carSave)
        if ((s.task.id == "D02" || s.task.id == "D10" || s.task.id == "D11" || s.task.id == "D13")) {
            paint.color = if (s.waiting < 6) 0xffbd794d.toInt() else 0xff228b7e.toInt()
            val x = if (s.task.id == "D02") width / 2f else width / 2f - laneWidth
            rect.set(x - laneWidth * .22f, height * .18f, x + laneWidth * .22f, height * .4f)
            canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
        }
        if ((s.task.id == "D04" || s.task.id == "D05" || s.task.id == "D17" || s.task.id == "D18")) {
            paint.color = if (s.warning || (s.task.id == "D05" && s.phase < 2)) 0xffbd4d4d.toInt() else 0xff228b7e.toInt()
            rect.set(roadLeft + dp(8f), height * .42f, roadRight - dp(8f), height * .47f)
            canvas.drawRect(rect, paint)
        }
        paint.color = PleosUi.TEXT; paint.textSize = PleosUi.canvasTextSize(context, dp(19f)); paint.textAlign = Paint.Align.LEFT
        canvas.drawText("가상 주행", dp(12f), dp(30f), paint)
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
