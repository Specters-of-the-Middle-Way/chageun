package kr.drivestep.tutor.parking

import kr.drivestep.tutor.design.PleosUi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kr.drivestep.tutor.R
import kr.drivestep.tutor.vehicle.TrainingVehicle
import kotlin.math.min

/** Draws one educational world. There is no camera feed, vehicle API, or separate animation state. */
class ParkingSceneView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var state: ParkingSnapshot? = null
    private var paused = false
    private var accessibilityKey = ""
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f
    private val mint = Color.rgb(247, 248, 250)
    private val pale = Color.rgb(222, 229, 233)
    private val amber = Color.rgb(255, 186, 105)
    private val regularTypeface = runCatching {
        resources.getFont(R.font.asta_sans)
    }.getOrDefault(Typeface.DEFAULT)
    private val boldTypeface = Typeface.create(regularTypeface, Typeface.BOLD)

    init {
        minimumHeight = dp(260)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        contentDescription = "가상 주차장. 화면 속 차량과 주차 구역을 표시합니다."
    }

    fun update(value: ParkingSnapshot, paused: Boolean = value.paused) {
        state = value
        this.paused = paused || value.paused
        val key = "${value.gear}:${value.phase}:${value.insideTarget}:$paused"
        if (key != accessibilityKey) {
            accessibilityKey = key
            contentDescription = "가상 주차장. ${value.course.title}, " +
                "가상 기어 ${value.gear}, ${if (value.insideTarget) "화면의 목표 구역 안" else "목표 구역 밖"}. " +
                if (this.paused) "일시정지 상태입니다." else "실제 카메라 영상이 아닙니다."
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        if (width <= 0 || height <= 0) return
        val inset = dp(18).toFloat()
        val top = dp(54).toFloat()
        scale = min((width - inset * 2) / s.worldWidthM.toFloat(), (height - top - dp(44)) / s.worldHeightM.toFloat())
        originX = (width - s.worldWidthM.toFloat() * scale) / 2
        originY = top + s.worldHeightM.toFloat() * scale
        canvas.drawColor(Color.rgb(26, 29, 33))
        drawFloor(canvas, s)
        drawTarget(canvas, s)
        drawTrail(canvas, s)
        s.obstacles.forEachIndexed { index, obstacle ->
            if (obstacle.widthM < 1.2 || obstacle.lengthM < 2.0) drawObstacle(canvas, obstacle)
            else drawCar(canvas, obstacle.x, obstacle.y, obstacle.headingRadians,
                obstacle.widthM, obstacle.lengthM,
                if (index % 2 == 0) Color.rgb(91, 110, 125) else Color.rgb(145, 149, 150), false, 0.0, false)
        }
        drawTrajectory(canvas, s)
        drawCar(canvas, s.pose.x, s.pose.y, s.pose.headingRadians, s.vehicle.widthM,
            s.vehicle.lengthM, Color.rgb(205, 216, 213), true, s.steering,
            s.brake > .1 || s.gear == ParkingGear.P)
        drawOverlay(canvas, s)
        if (paused) drawPause(canvas)
    }

    private fun drawFloor(c: Canvas, s: ParkingSnapshot) {
        val left = sx(0.0); val top = sy(s.worldHeightM)
        val right = sx(s.worldWidthM); val bottom = sy(0.0)
        paint.shader = LinearGradient(left, top, right, bottom,
            Color.rgb(45, 49, 53), Color.rgb(32, 36, 40), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        c.drawRoundRect(left, top, right, bottom, dp(8).toFloat(), dp(8).toFloat(), paint)
        paint.shader = null
        // Concrete expansion seams, kept subtle so trajectory and the target remain dominant.
        for (x in 0..s.worldWidthM.toInt() step 4) line(c, sx(x.toDouble()), top, sx(x.toDouble()), bottom, .6f, Color.rgb(53, 57, 61))
        for (y in 0..s.worldHeightM.toInt() step 4) line(c, left, sy(y.toDouble()), right, sy(y.toDouble()), .6f, Color.rgb(53, 57, 61))
        paint.color = Color.rgb(81, 86, 91)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
        c.drawRoundRect(left, top, right, bottom, dp(8).toFloat(), dp(8).toFloat(), paint)
        paint.style = Paint.Style.FILL
        // The parking bay markings share the same dimensions and orientation as the engine.
        if (s.course != ParkingCourse.PARALLEL) {
            for (x in doubleArrayOf(2.0, 5.0, 8.0, 11.0, 14.0)) {
                val box = ParkingRect("line", x, s.targetBay.y, 3.0, s.targetBay.lengthM, 0.0)
                bayOutline(c, box, Color.rgb(116, 121, 124), dp(1.5f), false)
            }
            laneArrow(c, 8.0, 15.0, 0f)
            label(c, "", sx(8.0), sy(18.5), dp(9f), Color.rgb(114, 121, 126), Paint.Align.CENTER)
        } else {
            line(c, sx(13.7), top + dp(12), sx(13.7), bottom - dp(12), dp(5f), Color.rgb(125, 126, 122))
            for (y in 1..19 step 2) {
                line(c, sx(13.6), sy(y.toDouble()), sx(13.6), sy((y + 1).toDouble()), dp(4f), Color.rgb(180, 174, 148))
            }
            laneArrow(c, 5.0, 7.0, 0f)
            label(c, "", sx(5.0), sy(18.5), dp(9f), Color.rgb(114, 121, 126), Paint.Align.CENTER)
        }
    }

    private fun drawTarget(c: Canvas, s: ParkingSnapshot) {
        val bay = s.targetBay
        c.save(); c.translate(sx(bay.x), sy(bay.y)); c.rotate(Math.toDegrees(bay.headingRadians).toFloat())
        val w = (bay.widthM * scale).toFloat(); val l = (bay.lengthM * scale).toFloat()
        paint.color = if (s.insideTarget) Color.argb(26, 119, 244, 193) else Color.argb(16, 119, 244, 193)
        paint.style = Paint.Style.FILL
        c.drawRect(-w/2, -l/2, w/2, l/2, paint)
        paint.color = mint; paint.strokeWidth = dp(2f); paint.style = Paint.Style.STROKE
        c.drawRect(-w/2, -l/2, w/2, l/2, paint)
        paint.style = Paint.Style.FILL
        val mark = min(w * .23f, dp(19f))
        for (xs in intArrayOf(-1, 1)) for (ys in intArrayOf(-1, 1)) {
            line(c, xs*w/2, ys*l/2, xs*(w/2-mark), ys*l/2, dp(4f), mint)
            line(c, xs*w/2, ys*l/2, xs*w/2, ys*(l/2-mark), dp(4f), mint)
        }
        label(c, "목표", 0f, -l/2 - dp(8), dp(14f), mint, Paint.Align.CENTER)
        label(c, "P", 0f, dp(7f), dp(23f), Color.argb(110, 119, 244, 193), Paint.Align.CENTER, true)
        c.restore()
    }

    private fun drawTrail(c: Canvas, s: ParkingSnapshot) {
        if (s.trail.size < 2) return
        path.reset()
        s.trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y)) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
        paint.color = Color.argb(80, 157, 180, 178); paint.pathEffect = null
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }

    private fun drawTrajectory(c: Canvas, s: ParkingSnapshot) {
        if (s.gear == ParkingGear.P || s.trajectory.size < 2) return
        path.reset()
        s.trajectory.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y)) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
        paint.color = if (s.clearanceM < .5) amber else Color.rgb(190, 218, 208)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(7f)), 0f)
        c.drawPath(path, paint); paint.pathEffect = null; paint.style = Paint.Style.FILL
        s.trajectory.lastOrNull()?.let { p ->
            paint.color = Color.argb(60, 200, 231, 218)
            c.drawCircle(sx(p.x), sy(p.y), dp(5f), paint)
        }
    }

    private fun drawObstacle(c: Canvas, o: ParkingRect) {
        c.save(); c.translate(sx(o.x), sy(o.y)); c.rotate(Math.toDegrees(o.headingRadians).toFloat())
        val w = (o.widthM * scale).toFloat(); val l = (o.lengthM * scale).toFloat()
        paint.color = Color.rgb(103, 106, 102)
        c.drawRoundRect(-w/2, -l/2, w/2, l/2, dp(3f), dp(3f), paint)
        c.restore()
    }

    private fun drawCar(c: Canvas, x: Double, y: Double, heading: Double, widthM: Double,
        lengthM: Double, body: Int, ego: Boolean, steering: Double, braking: Boolean) {
        c.save(); c.translate(sx(x), sy(y)); c.rotate(Math.toDegrees(heading).toFloat())
        val w = (widthM * scale).toFloat(); val l = (lengthM * scale).toFloat()
        // A consistent vehicle footprint is used for body, tyre spacing, and collision geometry.
        paint.color = Color.argb(if (ego) 130 else 85, 0, 0, 0)
        c.drawRoundRect(-w/2-dp(3), -l/2+dp(4), w/2+dp(3), l/2+dp(8), w*.23f, w*.23f, paint)
        val axle = l * .3225f
        for (sign in intArrayOf(-1, 1)) {
            c.save(); c.translate(sign*w*.47f, -axle)
            c.rotate((steering*32).toFloat())
            paint.color = Color.rgb(9, 11, 13)
            c.drawRoundRect(-w*.065f, -l*.068f, w*.065f, l*.068f, 2f, 2f, paint); c.restore()
            paint.color = Color.rgb(9, 11, 13)
            c.drawRoundRect(sign*w*.47f-w*.065f, axle-l*.068f, sign*w*.47f+w*.065f, axle+l*.068f, 2f, 2f, paint)
        }
        path.reset(); path.moveTo(-w*.38f, -l*.5f); path.lineTo(w*.38f, -l*.5f)
        path.quadTo(w*.50f, -l*.48f, w*.50f, -l*.34f)
        path.lineTo(w*.49f, l*.34f); path.quadTo(w*.47f, l*.49f, w*.33f, l*.5f)
        path.lineTo(-w*.33f, l*.5f); path.quadTo(-w*.47f, l*.49f, -w*.49f, l*.34f)
        path.lineTo(-w*.50f, -l*.34f); path.quadTo(-w*.50f, -l*.48f, -w*.38f, -l*.5f); path.close()
        paint.shader = LinearGradient(-w/2, 0f, w/2, 0f,
            intArrayOf(shade(body,.70f), shade(body,1.1f), body, shade(body,.68f)), null, Shader.TileMode.CLAMP)
        c.drawPath(path, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = if (ego) dp(1.2f) else dp(.6f)
        paint.color = if (ego) Color.rgb(236, 244, 238) else shade(body,.65f)
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
        // Hood, panoramic windscreen and short hatch are indicative graphics, not OEM CAD.
        paint.color = shade(body, .87f)
        c.drawRoundRect(-w*.38f, -l*.43f, w*.38f, -l*.22f, w*.08f, w*.08f, paint)
        path.reset(); path.moveTo(-w*.36f,-l*.24f); path.lineTo(w*.36f,-l*.24f)
        path.lineTo(w*.31f,-l*.07f); path.lineTo(-w*.31f,-l*.07f); path.close()
        paint.color = Color.rgb(31, 48, 58); c.drawPath(path, paint)
        paint.color = Color.rgb(44, 61, 68)
        c.drawRoundRect(-w*.31f,-l*.055f,w*.31f,l*.22f,w*.07f,w*.07f,paint)
        paint.color = Color.rgb(23, 39, 48)
        c.drawRoundRect(-w*.33f,l*.24f,w*.33f,l*.36f,w*.06f,w*.06f,paint)
        line(c,-w*.28f,-l*.19f,w*.27f,-l*.19f,dp(.7f),Color.argb(90,203,224,229))
        for (sign in intArrayOf(-1,1)) {
            line(c,sign*w*.40f,-l*.13f,sign*w*.40f,l*.30f,dp(.6f),shade(body,.54f))
            paint.color = shade(body,.65f)
            c.drawRoundRect(sign*w*.51f-w*.07f,-l*.14f,sign*w*.51f+w*.07f,-l*.09f,2f,2f,paint)
            paint.color = Color.rgb(237,245,233)
            c.drawRect(sign*w*.29f-w*.11f,-l*.453f,sign*w*.29f+w*.11f,-l*.434f,paint)
            paint.color = if (braking) Color.rgb(255,81,77) else Color.rgb(148,57,55)
            c.drawRect(sign*w*.29f-w*.13f,l*.442f,sign*w*.29f+w*.13f,l*.46f,paint)
        }
        if (ego) {
            line(c,-w*.14f,-l*.39f,w*.14f,-l*.39f,dp(1f),Color.rgb(37,50,49))
            paint.color = mint
            path.reset(); path.moveTo(0f,-l*.63f); path.lineTo(-w*.10f,-l*.55f); path.lineTo(w*.10f,-l*.55f); path.close(); c.drawPath(path,paint)
        }
        c.restore()
    }

    private fun bayOutline(c: Canvas, b: ParkingRect, color: Int, stroke: Float, fill: Boolean) {
        c.save(); c.translate(sx(b.x),sy(b.y)); c.rotate(Math.toDegrees(b.headingRadians).toFloat())
        paint.color = color; paint.strokeWidth = stroke; paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        val w = (b.widthM*scale).toFloat(); val l=(b.lengthM*scale).toFloat()
        c.drawRect(-w/2,-l/2,w/2,l/2,paint); paint.style=Paint.Style.FILL; c.restore()
    }

    private fun drawOverlay(c: Canvas, s: ParkingSnapshot) {
        val pad=dp(16f)
        label(c,"가상 주차장",pad,dp(32f),dp(18f),pale)
        label(c,TrainingVehicle.NAME,width-pad,dp(32f),dp(18f),pale,Paint.Align.RIGHT,true)
        val legendY=height-dp(16f)
        line(c,pad,legendY-dp(4),pad+dp(18),legendY-dp(4),dp(2f),mint)
        label(c,"목표 구역",pad+dp(25),legendY,dp(15f),pale)
        label(c,"가상 경로",width-pad,legendY,dp(15f),pale,Paint.Align.RIGHT)
        if (s.clearanceM < .55 && !s.failed) {
            val text="가상 장애물에 가까워요"
            paint.textSize=PleosUi.canvasTextSize(context, dp(17f)); val tw=paint.measureText(text)
            paint.color=Color.argb(230,54,43,30)
            c.drawRoundRect(width/2-tw/2-dp(12),dp(50f),width/2+tw/2+dp(12),dp(96f),dp(8f),dp(8f),paint)
            label(c,text,width/2f,dp(81f),dp(17f),amber,Paint.Align.CENTER)
        }
    }

    private fun drawPause(c: Canvas) {
        paint.color=Color.argb(130,8,11,13); c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        label(c,"연습이 잠시 멈췄어요",width/2f,height/2f-dp(8f),dp(22f),Color.WHITE,Paint.Align.CENTER,true)
        label(c,"이어하기를 눌러 계속해요",width/2f,height/2f+dp(30f),dp(18f),pale,Paint.Align.CENTER)
    }

    private fun laneArrow(c: Canvas,x:Double,y:Double,degrees:Float) {
        c.save(); c.translate(sx(x),sy(y)); c.rotate(degrees)
        paint.color=Color.rgb(97,104,109)
        val size=scale*.6f
        path.reset(); path.moveTo(-size*.23f,size); path.lineTo(size*.23f,size); path.lineTo(size*.23f,-size*.1f)
        path.lineTo(size*.65f,-size*.1f); path.lineTo(0f,-size); path.lineTo(-size*.65f,-size*.1f)
        path.lineTo(-size*.23f,-size*.1f); path.close(); c.drawPath(path,paint); c.restore()
    }

    private fun label(c:Canvas,t:String,x:Float,y:Float,size:Float,color:Int,align:Paint.Align=Paint.Align.LEFT,bold:Boolean=false) {
        paint.color=color;paint.style=Paint.Style.FILL;paint.textSize=PleosUi.canvasTextSize(context, size);paint.textAlign=align
        paint.typeface=if(bold)boldTypeface else regularTypeface
        c.drawText(t,x,y,paint);paint.textAlign=Paint.Align.LEFT
    }
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,stroke:Float,color:Int) {
        paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.color=color;c.drawLine(x1,y1,x2,y2,paint);paint.style=Paint.Style.FILL
    }
    private fun shade(c:Int,f:Float)=Color.rgb((Color.red(c)*f).toInt().coerceIn(0,255),(Color.green(c)*f).toInt().coerceIn(0,255),(Color.blue(c)*f).toInt().coerceIn(0,255))
    private fun sx(x:Double)=originX+(x*scale).toFloat()
    private fun sy(y:Double)=originY-(y*scale).toFloat()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun dp(value:Float)=value*resources.displayMetrics.density
}
