package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Original educational diagrams; no camera, vehicle or map input. */
@SuppressLint("ViewConstructor")
class RoadConceptView(context: Context, private val illustration: RoadIllustration) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    init { contentDescription = "교육 그림 · ${illustration.caption}"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL; paint.color = 0xff252c35.toInt()
        rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
        paint.color = 0xff464e58.toInt(); rect.set(w * .32f, dp(42f), w * .68f, h - dp(12f)); canvas.drawRect(rect, paint)
        paint.color = 0xfff7f8fa.toInt(); paint.strokeWidth = dp(3f)
        canvas.drawLine(w * .32f, dp(42f), w * .32f, h - dp(12f), paint)
        canvas.drawLine(w * .68f, dp(42f), w * .68f, h - dp(12f), paint)
        when (illustration) {
            RoadIllustration.WHITE_DASH -> for (i in 0..3) canvas.drawLine(w * .5f, h * (.25f + i * .16f), w * .5f, h * (.33f + i * .16f), paint)
            RoadIllustration.WHITE_SOLID -> canvas.drawLine(w * .5f, dp(42f), w * .5f, h - dp(12f), paint)
            RoadIllustration.YELLOW_CENTER -> {
                paint.color = 0xffffcf57.toInt()
                canvas.drawLine(w * .495f, dp(42f), w * .495f, h - dp(12f), paint)
                canvas.drawLine(w * .505f, dp(42f), w * .505f, h - dp(12f), paint)
                arrow(canvas, w * .41f, h * .45f, false); arrow(canvas, w * .59f, h * .65f, true)
            }
            RoadIllustration.STOP_LINE -> {
                for (i in 0..6) {
                    rect.set(w * (.34f + i * .048f), h * .31f, w * (.36f + i * .048f), h * .51f)
                    canvas.drawRect(rect, paint)
                }
                paint.strokeWidth = dp(7f); canvas.drawLine(w * .33f, h * .6f, w * .67f, h * .6f, paint)
            }
            else -> {
                for (i in 0..3) canvas.drawLine(w * .5f, h * (.24f + i * .16f), w * .5f, h * (.31f + i * .16f), paint)
                paint.color = 0xff73d7b8.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f)
                when (illustration) {
                    RoadIllustration.LOOK_AHEAD -> { rect.set(w * .35f,h * .2f,w * .65f,h * .4f); canvas.drawOval(rect,paint) }
                    RoadIllustration.NEAR_FIELD -> { rect.set(w * .32f,h * .48f,w * .68f,h * .9f); canvas.drawOval(rect,paint) }
                    RoadIllustration.MIRRORS -> {
                        canvas.drawCircle(w * .36f,h * .78f,dp(14f),paint); canvas.drawCircle(w * .64f,h * .78f,dp(14f),paint)
                        arrow(canvas,w * .59f,h * .39f,true)
                    }
                    RoadIllustration.LANE_CENTER -> { canvas.drawLine(w * .59f,h * .75f,w * .59f,h * .25f,paint); arrow(canvas,w * .59f,h * .3f,true) }
                    else -> Unit
                }
                paint.style = Paint.Style.FILL
            }
        }
        // Right-hand traffic example, independent of the foreign source's road layout.
        paint.style = Paint.Style.FILL; paint.color = 0xff73d7b8.toInt()
        rect.set(w * .565f,h * .74f,w * .615f,h * .94f); canvas.drawRoundRect(rect,dp(4f),dp(4f),paint)
        // Captions are rendered by the parent as wrapping, scalable TextViews.
    }
    private fun arrow(canvas: Canvas, x: Float, y: Float, up: Boolean) {
        val direction = if (up) -1f else 1f
        path.rewind(); path.moveTo(x - dp(8f),y - direction * dp(8f)); path.lineTo(x,y + direction * dp(8f)); path.lineTo(x + dp(8f),y - direction * dp(8f))
        canvas.drawPath(path,paint)
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
