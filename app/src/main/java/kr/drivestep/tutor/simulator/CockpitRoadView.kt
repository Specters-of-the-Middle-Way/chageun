package kr.drivestep.tutor.simulator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A virtual cockpit. The host owns time and the world; this view only projects its snapshot. */
class CockpitRoadView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var snapshot: DrivingSimulationSnapshot? = null
    private var paused = false
    private var accessibilityKey = ""

    private val pale = Color.rgb(225, 235, 241)
    private val amber = Color.rgb(255, 190, 78)
    private val ink = Color.rgb(16, 24, 32)
    private val laneWidth = 3.5f
    private val viewWidth = 1200f
    private val viewHeight = 430f

    init {
        minimumHeight = (270 * resources.displayMetrics.density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        contentDescription = "1인칭 가상 주행 화면. 좌우 가상 미러와 가상 속도를 표시합니다."
    }

    fun update(snapshot: DrivingSimulationSnapshot, paused: Boolean) {
        this.snapshot = snapshot
        this.paused = paused || snapshot.paused
        updateAccessibility(snapshot)
        invalidate()
    }

    private fun updateAccessibility(s: DrivingSimulationSnapshot) {
        val lane = when {
            s.lanePosition < -.5 -> "왼쪽 차로"
            s.lanePosition > .5 -> "오른쪽 차로"
            else -> "가운데 차로"
        }
        // Speed and animation frames deliberately do not generate accessibility changes.
        val key = "$lane:${s.signal}:${s.blindSpotLeft}:${s.blindSpotRight}:$paused:${s.phase}"
        if (key == accessibilityKey) return
        accessibilityKey = key
        val signal = when (s.signal) {
            SimulationSignal.LEFT -> "왼쪽 가상 방향지시등이 켜져 있습니다. "
            SimulationSignal.RIGHT -> "오른쪽 가상 방향지시등이 켜져 있습니다. "
            SimulationSignal.OFF -> ""
        }
        val warning = buildString {
            if (s.blindSpotLeft) append("왼쪽 가까이에 가상 차량이 있습니다. ")
            if (s.blindSpotRight) append("오른쪽 가까이에 가상 차량이 있습니다. ")
        }
        contentDescription = "1인칭 가상 주행 화면. $lane. $signal$warning" +
            if (paused) "일시정지 상태입니다." else "좌우 가상 미러와 가상 속도를 표시합니다."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.save()
        canvas.scale(width / viewWidth, height / viewHeight)
        canvas.clipRect(0f, 0f, viewWidth, viewHeight)
        val s = snapshot
        val cameraX = (s?.lanePosition?.toFloat() ?: 0f) * laneWidth
        val projection = Projection(600f, 117f, 750f, 1.52f, cameraX)
        val distance = s?.distanceM ?: 0.0

        canvas.save()
        // A small roll accompanies steering, while lateral translation comes from the world position.
        canvas.rotate((s?.steering?.toFloat() ?: 0f) * -1.25f, 600f, 300f)
        drawLandscape(canvas, projection, cameraX)
        drawRoad(canvas, projection, distance, s)
        drawRoadside(canvas, projection, distance)
        s?.traffic?.asSequence()
            ?.filter { it.distanceM > 2.3 && it.distanceM < 190.0 }
            ?.sortedByDescending { it.distanceM }
            ?.forEach { car ->
                drawVehicle(canvas, projection, car.lanePosition.toFloat() * laneWidth,
                    car.distanceM.toFloat(), vehicleColor(car.id), frontFacing = false)
            }
        canvas.restore()

        drawHood(canvas)
        drawMirror(canvas, RectF(30f, 223f, 249f, 338f), -1, s)
        drawMirror(canvas, RectF(951f, 223f, 1170f, 338f), 1, s)
        drawDashboard(canvas, s)
        drawSceneLabel(canvas)
        if (paused) drawPause(canvas)
        canvas.restore()
    }

    private data class Projection(
        val centerX: Float,
        val horizon: Float,
        val focal: Float,
        val eyeHeight: Float,
        val cameraX: Float,
        val horizontalDirection: Float = 1f
    ) {
        fun point(x: Float, y: Float, z: Float): PointF {
            val depth = max(z, .65f)
            return PointF(centerX + (x - cameraX) * focal / depth * horizontalDirection,
                horizon + (eyeHeight - y) * focal / depth)
        }
    }

    private fun drawLandscape(c: Canvas, p: Projection, cameraX: Float) {
        gradientRect(c, -20f, -20f, 1220f, 180f,
            Color.rgb(119, 169, 207), Color.rgb(220, 232, 234))
        paint.shader = RadialGradient(985f, 39f, 270f,
            intArrayOf(Color.argb(90, 255, 250, 226), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        c.drawRect(700f, -20f, 1220f, 180f, paint)
        paint.shader = null

        // Low-contrast cloud banks and atmospheric hills keep the vanishing point legible.
        cloud(c, 165f - cameraX * 2f, 57f, 130f, 9f, Color.argb(55, 255, 255, 250))
        cloud(c, 737f - cameraX * 2f, 31f, 155f, 7f, Color.argb(45, 255, 255, 250))
        cloud(c, 1100f - cameraX * 2f, 85f, 105f, 5f, Color.argb(65, 255, 255, 250))

        path.reset()
        path.moveTo(-30f, 131f)
        path.lineTo(-30f, 103f)
        path.cubicTo(70f, 106f, 103f, 72f, 177f, 84f)
        path.cubicTo(260f, 60f, 290f, 105f, 367f, 102f)
        path.cubicTo(443f, 77f, 473f, 99f, 556f, 111f)
        path.cubicTo(631f, 98f, 674f, 108f, 730f, 111f)
        path.cubicTo(870f, 96f, 898f, 85f, 986f, 105f)
        path.cubicTo(1080f, 78f, 1140f, 87f, 1230f, 91f)
        path.lineTo(1230f, 135f)
        path.close()
        fillPath(c, Color.rgb(154, 178, 185))

        for (i in 0..27) {
            val x = 714f + i * 12f - cameraX * 2.6f
            val buildingHeight = 9f + ((i * 17) % 21)
            rect(c, x, p.horizon - buildingHeight, x + 6f + i % 4, p.horizon + 4f,
                Color.rgb(166, 183, 188))
            if (i % 7 == 0) line(c, x + 3f, p.horizon - buildingHeight - 7f,
                x + 3f, p.horizon - buildingHeight, .8f, Color.rgb(166, 183, 188))
        }
        path.reset()
        path.moveTo(-30f, 125f)
        path.cubicTo(190f, 107f, 282f, 113f, 455f, 119f)
        path.cubicTo(689f, 110f, 781f, 120f, 950f, 118f)
        path.cubicTo(1060f, 108f, 1120f, 111f, 1230f, 119f)
        path.lineTo(1230f, 155f)
        path.lineTo(-30f, 155f)
        path.close()
        fillPath(c, Color.rgb(126, 151, 142))
        gradientRect(c, -30f, p.horizon + 5f, 1230f, 430f,
            Color.rgb(144, 159, 133), Color.rgb(94, 117, 91))
    }

    private fun drawRoad(c: Canvas, p: Projection, distance: Double, s: DrivingSimulationSnapshot?) {
        groundStrip(c, p, -5.9f, 5.9f, .7f, 270f, Color.rgb(160, 165, 157))
        groundStrip(c, p, -5.5f, 5.5f, .7f, 270f, Color.rgb(91, 103, 107))
        val surface = arrayOf(p.point(-5.25f, 0f, 270f), p.point(5.25f, 0f, 270f),
            p.point(5.25f, 0f, .7f), p.point(-5.25f, 0f, .7f))
        paint.shader = LinearGradient(0f, p.horizon, 0f, 390f,
            Color.rgb(96, 111, 119), Color.rgb(47, 57, 65), Shader.TileMode.CLAMP)
        polygon(c, surface, Color.WHITE, preserveShader = true)
        paint.shader = null

        // These neutral blue guides identify the exercise lane; they do not indicate clearance.
        if (s != null && !s.completed && !s.failed && abs(s.targetLane - s.lanePosition) > .14) {
            val targetX = s.targetLane.toFloat() * laneWidth
            groundStrip(c, p, targetX - 1.50f, targetX + 1.50f, 12f, 85f,
                Color.argb(10, 191, 212, 233))
            groundStrip(c, p, targetX - 1.51f, targetX - 1.46f, 12f, 85f,
                Color.argb(95, 190, 207, 228))
            groundStrip(c, p, targetX + 1.46f, targetX + 1.51f, 12f, 85f,
                Color.argb(95, 190, 207, 228))
        }

        val phase = positiveModulo(distance, 9.0).toFloat()
        for (laneX in floatArrayOf(-1.75f, 1.75f)) {
            for (i in 0..30) {
                val near = i * 9f - phase
                val far = near + 3.5f
                if (far > .8f) groundStrip(c, p, laneX - .065f, laneX + .065f,
                    max(.8f, near), far, Color.rgb(222, 229, 229))
            }
        }
        groundStrip(c, p, -5.17f, -5.06f, .7f, 270f, Color.rgb(218, 225, 221))
        groundStrip(c, p, 5.06f, 5.17f, .7f, 270f, Color.rgb(218, 225, 221))

        // Fine asphalt streaks move with the same travelled distance as the lane markings.
        val texturePhase = positiveModulo(distance, 5.0).toFloat()
        for (i in 1..25) {
            val z = i * 5f - texturePhase
            if (z < 3f) continue
            for (j in -3..3) {
                val wx = j * 1.37f + (i % 3) * .21f
                val a = p.point(wx, 0f, z)
                val b = p.point(wx + .06f, 0f, z + .65f)
                line(c, a.x, a.y, b.x, b.y, .7f, Color.argb(13, 229, 238, 242))
            }
        }
    }

    private fun drawRoadside(c: Canvas, p: Projection, distance: Double) {
        for (side in intArrayOf(-1, 1)) {
            val railX = side * 5.9f
            for (railHeight in floatArrayOf(.57f, .84f)) {
                polygon(c, arrayOf(p.point(railX, railHeight, 230f),
                    p.point(railX, railHeight + .055f, 230f),
                    p.point(railX, railHeight + .055f, 1f), p.point(railX, railHeight, 1f)),
                    Color.rgb(119, 135, 137))
                polygon(c, arrayOf(p.point(railX, railHeight + .044f, 230f),
                    p.point(railX, railHeight + .055f, 230f),
                    p.point(railX, railHeight + .055f, 1f), p.point(railX, railHeight + .044f, 1f)),
                    Color.rgb(208, 216, 211))
            }
            val postPhase = positiveModulo(distance, 8.0).toFloat()
            for (i in 28 downTo 0) {
                val z = i * 8f - postPhase
                if (z < 2.4f) continue
                val a = p.point(railX, 0f, z)
                val b = p.point(railX, .89f, z)
                line(c, a.x, a.y, b.x, b.y, max(.8f, 23f / z), Color.rgb(117, 130, 128))
                if (i % 3 == 0) {
                    val reflector = p.point(railX, .78f, z)
                    dot(c, reflector.x, reflector.y, max(.7f, 13f / z), Color.rgb(235, 218, 165))
                }
            }
            val treePhase = positiveModulo(distance, 34.0).toFloat()
            for (i in 6 downTo 1) {
                val z = i * 34f - treePhase + 16f
                val x = side * (11f + (i % 3) * 1.5f)
                val base = p.point(x, 0f, z)
                val top = p.point(x, 4.4f, z)
                val size = 1.75f * p.focal / z
                line(c, base.x, base.y, top.x, top.y + size * .5f, max(1f, 18f / z),
                    Color.rgb(95, 111, 97))
                oval(c, top.x - size * .86f, top.y, top.x + size * .67f, top.y + size * 1.25f,
                    Color.rgb(107, 132, 113))
                oval(c, top.x - size * .28f, top.y - size * .18f, top.x + size * .85f,
                    top.y + size * .94f, Color.rgb(121, 143, 120))
            }
        }
    }

    /** Projects the body, cabin and visible side at their actual virtual depth. */
    private fun drawVehicle(c: Canvas, p: Projection, x: Float, distance: Float,
                            body: Int, frontFacing: Boolean) {
        val near = max(.8f, distance - 2.1f)
        val far = max(near + .1f, distance + 2.1f)
        if (near > 190f) return
        val side = if (p.cameraX >= x) 1f else -1f
        val bodySide = x + side * .94f
        val roofSide = x + side * .72f
        val scale = p.focal / near
        val bumperL = p.point(x - 1.02f, .13f, near)
        val bumperR = p.point(x + 1.02f, .13f, near)
        val shadowRear = p.point(x, 0f, near - .25f)
        oval(c, min(bumperL.x, bumperR.x) - scale * .12f, shadowRear.y - scale * .12f,
            max(bumperL.x, bumperR.x) + scale * .12f, shadowRear.y + scale * .10f,
            Color.argb(76, 18, 24, 28))

        // The two visible tires remain tucked below the body instead of floating on the road.
        for (wheelX in floatArrayOf(x - .79f, x + .79f)) {
            projectedQuad(c, p, wheelX - .12f, wheelX + .12f, .08f, .47f, near + .15f,
                Color.rgb(22, 29, 33))
        }
        polygon(c, arrayOf(p.point(bodySide, .28f, near), p.point(bodySide, .88f, near),
            p.point(bodySide, .86f, far - .35f), p.point(bodySide, .27f, far - .35f)),
            shade(body, .67f))
        polygon(c, arrayOf(p.point(bodySide, .88f, near), p.point(roofSide, 1.44f, near + .85f),
            p.point(roofSide, 1.44f, far - 1.1f), p.point(bodySide, .86f, far - .35f)),
            shade(body, .86f))
        polygon(c, arrayOf(p.point(bodySide - side * .06f, .94f, near + .28f),
            p.point(roofSide, 1.34f, near + .98f), p.point(roofSide, 1.34f, far - 1.24f),
            p.point(bodySide - side * .06f, .94f, far - .62f)), Color.rgb(54, 78, 92))
        polygon(c, arrayOf(p.point(x - .72f, 1.44f, near + .85f),
            p.point(x + .72f, 1.44f, near + .85f), p.point(x + .72f, 1.44f, far - 1.1f),
            p.point(x - .72f, 1.44f, far - 1.1f)), shade(body, 1.15f))

        polygon(c, arrayOf(p.point(x - .91f, .84f, near),
            p.point(x - .72f, 1.44f, near + .85f), p.point(x + .72f, 1.44f, near + .85f),
            p.point(x + .91f, .84f, near)), shade(body, .98f))
        polygon(c, arrayOf(p.point(x - .74f, .95f, near + .12f),
            p.point(x - .64f, 1.33f, near + .79f), p.point(x + .64f, 1.33f, near + .79f),
            p.point(x + .74f, .95f, near + .12f)), Color.rgb(56, 81, 98))
        val glassA = p.point(x - .59f, 1.27f, near + .73f)
        val glassB = p.point(x + .60f, 1.27f, near + .73f)
        line(c, glassA.x, glassA.y, glassB.x, glassB.y, max(.6f, scale * .025f),
            Color.argb(115, 177, 205, 216))

        polygon(c, arrayOf(p.point(x - .94f, .34f, near), p.point(x - .94f, .86f, near),
            p.point(x + .94f, .86f, near), p.point(x + .94f, .34f, near),
            p.point(x + .80f, .23f, near), p.point(x - .80f, .23f, near)), body)
        projectedQuad(c, p, x - .81f, x + .81f, .27f, .37f, near - .01f, shade(body, .57f))
        val seamL = p.point(x - .81f, .72f, near - .01f)
        val seamR = p.point(x + .81f, .72f, near - .01f)
        line(c, seamL.x, seamL.y, seamR.x, seamR.y, max(.6f, scale * .015f), shade(body, .77f))
        val lightColor = if (frontFacing) Color.rgb(234, 243, 240) else Color.rgb(204, 67, 64)
        projectedQuad(c, p, x - .86f, x - .47f, .63f, .75f, near - .025f, lightColor)
        projectedQuad(c, p, x + .47f, x + .86f, .63f, .75f, near - .025f, lightColor)
        if (frontFacing) {
            projectedQuad(c, p, x - .35f, x + .35f, .37f, .56f, near - .03f,
                Color.rgb(34, 43, 50))
        } else {
            projectedQuad(c, p, x - .22f, x + .22f, .43f, .54f, near - .03f,
                Color.rgb(202, 210, 210))
        }
    }

    private fun drawMirror(c: Canvas, bounds: RectF, side: Int, s: DrivingSimulationSnapshot?) {
        val warned = if (side < 0) s?.blindSpotLeft == true else s?.blindSpotRight == true
        val armX = if (side < 0) bounds.right - 23f else bounds.left + 23f
        line(c, armX, bounds.bottom - 5f, armX + side * 17f, 376f, 15f, Color.rgb(32, 43, 51))
        round(c, bounds.left - 5f, bounds.top - 5f, bounds.right + 5f, bounds.bottom + 5f,
            24f, Color.rgb(20, 29, 38))
        c.save()
        path.reset()
        path.addRoundRect(bounds, 20f, 20f, Path.Direction.CW)
        c.clipPath(path)
        gradientRect(c, bounds.left, bounds.top, bounds.right, bounds.bottom,
            Color.rgb(141, 179, 199), Color.rgb(196, 211, 210))
        val horizon = bounds.top + 38f
        rect(c, bounds.left, horizon, bounds.right, bounds.bottom, Color.rgb(113, 138, 116))
        val egoX = (s?.lanePosition?.toFloat() ?: 0f) * laneWidth
        val mirrorProjection = Projection(bounds.centerX() + side * 20f, horizon, 113f,
            1.37f, egoX + side * .95f, -1f)
        groundStrip(c, mirrorProjection, -5.25f, 5.25f, .8f, 180f, Color.rgb(71, 86, 96))
        groundStrip(c, mirrorProjection, -5.13f, -5.04f, .8f, 180f, Color.rgb(194, 210, 209))
        groundStrip(c, mirrorProjection, 5.04f, 5.13f, .8f, 180f, Color.rgb(194, 210, 209))
        val phase = positiveModulo(s?.distanceM ?: 0.0, 9.0).toFloat()
        for (laneX in floatArrayOf(-1.75f, 1.75f)) {
            for (i in 0..16) {
                // Looking backward, travelled road markings recede away from the mirror.
                val near = i * 9f + phase
                groundStrip(c, mirrorProjection, laneX - .07f, laneX + .07f,
                    max(.8f, near), near + 3.5f, Color.rgb(200, 214, 213))
            }
        }
        s?.traffic?.asSequence()
            ?.filter { it.distanceM < 4.0 && it.distanceM > -100.0 &&
                (it.lanePosition - s.lanePosition) * side > .12 }
            ?.sortedBy { it.distanceM }
            ?.forEach { car ->
                // A convex virtual mirror retains nearby alongside traffic at its outside edge.
                val rearDistance = max(5.6f, -car.distanceM.toFloat() + 3.1f)
                drawVehicle(c, mirrorProjection, car.lanePosition.toFloat() * laneWidth,
                    rearDistance, vehicleColor(car.id), frontFacing = true)
            }
        // The narrow body reflection anchors each mirror to the driver's virtual vehicle.
        val bodyEdge = if (side < 0) bounds.right else bounds.left
        polygon(c, arrayOf(PointF(bodyEdge, bounds.top + 18f),
            PointF(bodyEdge - side * 2f, bounds.top + 40f),
            PointF(bodyEdge + side * 24f, bounds.bottom), PointF(bodyEdge, bounds.bottom)),
            Color.rgb(68, 85, 97))
        gradientRect(c, bounds.left, bounds.top, bounds.right, bounds.top + 27f,
            Color.argb(160, 18, 29, 39), Color.TRANSPARENT)
        text(c, if (side < 0) "가상 좌측 미러" else "가상 우측 미러", bounds.left + 13f,
            bounds.top + 18f, 11f, Color.rgb(232, 241, 244))
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (warned) 3.5f else 1.2f
        paint.color = if (warned) amber else Color.rgb(91, 108, 120)
        c.drawRoundRect(bounds, 20f, 20f, paint)
        paint.style = Paint.Style.FILL
        if (warned) {
            val indicatorX = if (side < 0) bounds.right - 20f else bounds.left + 20f
            val indicatorY = bounds.bottom - 20f
            polygon(c, arrayOf(PointF(indicatorX, indicatorY - 8f),
                PointF(indicatorX - 8f, indicatorY + 6f),
                PointF(indicatorX + 8f, indicatorY + 6f)), amber)
            text(c, "!", indicatorX, indicatorY + 3f, 10f, ink, Paint.Align.CENTER, true)
        }
    }

    private fun drawHood(c: Canvas) {
        path.reset()
        path.moveTo(-20f, 406f)
        path.cubicTo(167f, 365f, 302f, 338f, 600f, 335f)
        path.cubicTo(898f, 338f, 1033f, 365f, 1220f, 406f)
        path.lineTo(1220f, 450f)
        path.lineTo(-20f, 450f)
        path.close()
        paint.shader = LinearGradient(0f, 335f, 0f, 420f,
            Color.rgb(91, 111, 125), Color.rgb(34, 48, 61), Shader.TileMode.CLAMP)
        fillPath(c, Color.WHITE, preserveShader = true)
        paint.shader = null
        path.reset()
        path.moveTo(78f, 395f)
        path.cubicTo(283f, 354f, 384f, 339f, 600f, 338f)
        path.cubicTo(816f, 339f, 917f, 354f, 1122f, 395f)
        strokePath(c, Color.argb(88, 195, 210, 219), 1.1f)
        path.reset()
        path.moveTo(357f, 356f)
        path.lineTo(313f, 403f)
        path.moveTo(843f, 356f)
        path.lineTo(887f, 403f)
        strokePath(c, Color.argb(60, 196, 213, 225), 1f)
    }

    private fun drawDashboard(c: Canvas, s: DrivingSimulationSnapshot?) {
        path.reset()
        path.moveTo(-20f, 397f)
        path.cubicTo(210f, 376f, 376f, 361f, 600f, 361f)
        path.cubicTo(824f, 361f, 990f, 376f, 1220f, 397f)
        path.lineTo(1220f, 450f)
        path.lineTo(-20f, 450f)
        path.close()
        paint.shader = LinearGradient(0f, 361f, 0f, 430f,
            Color.rgb(29, 41, 52), Color.rgb(14, 22, 30), Shader.TileMode.CLAMP)
        fillPath(c, Color.WHITE, preserveShader = true)
        paint.shader = null
        line(c, 42f, 412f, 298f, 393f, 1.2f, Color.rgb(65, 83, 95))
        line(c, 902f, 393f, 1158f, 412f, 1.2f, Color.rgb(65, 83, 95))
        val speed = (s?.speedKmh ?: 0.0).toInt().coerceAtLeast(0)
        text(c, speed.toString(), 600f, 397f, 35f, Color.rgb(241, 247, 249), Paint.Align.CENTER, true)
        text(c, "가상 속도 · km/h", 600f, 417f, 11f, Color.rgb(155, 177, 192), Paint.Align.CENTER)
        val blinkOn = ((s?.elapsedSeconds ?: 0.0) * 1.7).toInt() % 2 == 0
        turnArrow(c, 514f, 388f, -1, s?.signal == SimulationSignal.LEFT && blinkOn)
        turnArrow(c, 686f, 388f, 1, s?.signal == SimulationSignal.RIGHT && blinkOn)
    }

    private fun drawSceneLabel(c: Canvas) {
        round(c, 24f, 20f, 126f, 49f, 14f, Color.argb(100, 30, 56, 72))
        dot(c, 40f, 34f, 3f, Color.rgb(211, 229, 241))
        text(c, "가상 주행", 52f, 39f, 13f, Color.rgb(244, 249, 251))
    }

    private fun drawPause(c: Canvas) {
        round(c, 526f, 200f, 674f, 248f, 23f, Color.argb(225, 22, 35, 46))
        rect(c, 548f, 216f, 552f, 232f, pale)
        rect(c, 556f, 216f, 560f, 232f, pale)
        text(c, "일시정지", 612f, 231f, 17f, pale, Paint.Align.CENTER)
    }

    private fun turnArrow(c: Canvas, x: Float, y: Float, direction: Int, active: Boolean) {
        val color = if (active) Color.rgb(121, 218, 175) else Color.rgb(62, 81, 93)
        c.save()
        c.translate(x, y)
        c.scale(direction.toFloat(), 1f)
        polygon(c, arrayOf(PointF(12f, 0f), PointF(1f, -8f), PointF(1f, -3.5f),
            PointF(-11f, -3.5f), PointF(-11f, 3.5f), PointF(1f, 3.5f), PointF(1f, 8f)), color)
        c.restore()
    }

    private fun vehicleColor(id: String): Int = when ((id.hashCode() and Int.MAX_VALUE) % 5) {
        0 -> Color.rgb(193, 201, 204)
        1 -> Color.rgb(68, 103, 127)
        2 -> Color.rgb(115, 130, 140)
        3 -> Color.rgb(207, 204, 191)
        else -> Color.rgb(91, 112, 126)
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255))

    private fun positiveModulo(value: Double, modulus: Double): Double =
        ((value % modulus) + modulus) % modulus

    private fun groundStrip(c: Canvas, p: Projection, left: Float, right: Float,
                            near: Float, far: Float, color: Int) {
        polygon(c, arrayOf(p.point(left, 0f, far), p.point(right, 0f, far),
            p.point(right, 0f, near), p.point(left, 0f, near)), color)
    }

    private fun projectedQuad(c: Canvas, p: Projection, left: Float, right: Float,
                              bottom: Float, top: Float, z: Float, color: Int) {
        polygon(c, arrayOf(p.point(left, top, z), p.point(right, top, z),
            p.point(right, bottom, z), p.point(left, bottom, z)), color)
    }

    private fun polygon(c: Canvas, points: Array<PointF>, color: Int, preserveShader: Boolean = false) {
        if (points.isEmpty()) return
        path.reset()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()
        fillPath(c, color, preserveShader)
    }

    private fun fillPath(c: Canvas, color: Int, preserveShader: Boolean = false) {
        if (!preserveShader) paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawPath(path, paint)
    }

    private fun strokePath(c: Canvas, color: Int, width: Float) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun gradientRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        paint.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        c.drawRect(l, t, r, b, paint)
        paint.shader = null
    }

    private fun cloud(c: Canvas, x: Float, y: Float, width: Float, height: Float, color: Int) {
        path.reset()
        path.moveTo(x - width, y)
        path.cubicTo(x - width * .6f, y - height * .2f, x - width * .55f, y - height, x - width * .15f, y - height * .8f)
        path.cubicTo(x + width * .05f, y - height * 1.5f, x + width * .4f, y - height * .5f, x + width, y)
        path.cubicTo(x + width * .3f, y + height * .4f, x - width * .4f, y + height * .4f, x - width, y)
        fillPath(c, color)
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
                     align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(value, x, y, paint)
    }

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawRect(l, t, r, b, paint)
    }

    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawRoundRect(l, t, r, b, radius, radius, paint)
    }

    private fun oval(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawOval(l, t, r, b, paint)
    }

    private fun dot(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y, radius, paint)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x1, y1, x2, y2, paint)
        paint.style = Paint.Style.FILL
    }
}
