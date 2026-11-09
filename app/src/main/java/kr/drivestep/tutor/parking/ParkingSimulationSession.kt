package kr.drivestep.tutor.parking

import kr.drivestep.tutor.coach.CoachCue
import kotlin.math.*

enum class ParkingCourse { REVERSE_BAY, PARALLEL }
enum class ParkingGear { P, R, D }
enum class ParkingPhase { PREPARE, MANEUVER, ALIGNED, COMPLETE, FAILED }
enum class ParkingInterventionKind { BRAKE_FOR_GEAR, STOP_FOR_GEAR, SELECT_GEAR, FORWARD_FIRST, PROXIMITY, STOP_AND_P, ADJUST_POSITION, COLLISION, ROAD_EDGE }

data class ParkingPoint(val x: Double, val y: Double)
/** Centre of body; zero heading points north (+y), positive heading rotates clockwise. */
data class ParkingPose(val x: Double, val y: Double, val headingRadians: Double)
data class ParkingRect(val id: String, val x: Double, val y: Double, val widthM: Double, val lengthM: Double, val headingRadians: Double = 0.0)

/**
 * Body dimensions/wheelbase: Hyundai Europe IONIQ 3 base specification, 2026.
 * 32° maximum road-wheel angle and equal front/rear body-overhang split are EDUCATIONAL
 * ASSUMPTIONS, not Hyundai calibration. Mirror width, tire contact patches, steering ratio,
 * suspension, tyre slip and actuator limits are not reproduced by this planar model.
 */
data class ParkingVehicleSpec(
    val name: String = "IONIQ 3 · Base",
    val lengthM: Double = 4.155,
    val widthM: Double = 1.800,
    val wheelbaseM: Double = 2.680,
    val heightM: Double = 1.505,
    val maxRoadWheelAngleDegrees: Double = 32.0,
)

data class ParkingIntervention(val kind: ParkingInterventionKind, val atElapsedSeconds: Double, val cue: CoachCue)
data class ParkingSnapshot(
    val course: ParkingCourse,
    val pose: ParkingPose,
    val gear: ParkingGear,
    val speedKmh: Double,
    val signedSpeedMps: Double,
    val steering: Double,
    val gas: Double,
    val brake: Double,
    val elapsedSeconds: Double,
    val distanceM: Double,
    val phase: ParkingPhase,
    val vehicle: ParkingVehicleSpec,
    val worldWidthM: Double,
    val worldHeightM: Double,
    val targetBay: ParkingRect,
    val obstacles: List<ParkingRect>,
    val corners: List<ParkingPoint>,
    /** Constant-input prediction in the virtual bicycle model; not a camera/sensor result. */
    val trajectory: List<ParkingPoint>,
    val trail: List<ParkingPoint>,
    val clearanceM: Double,
    val insideTarget: Boolean,
    val angleErrorDegrees: Double,
    val coachCue: CoachCue,
    val coachRevision: Long,
    val paused: Boolean,
    val completed: Boolean,
    val failed: Boolean,
    val reflections: List<String>,
    val interventions: List<ParkingIntervention>,
) { val isTerminal: Boolean get() = completed || failed }

object ParkingSimulationCues {
    val START_REVERSE = CoachCue("parking_sim_start_reverse", "가상 후진 주차예요. 브레이크를 누른 채 R을 선택해요.")
    val START_PARALLEL = CoachCue("parking_sim_start_parallel", "가상 평행 주차예요. 브레이크를 누른 채 R을 선택해요.")
    val BRAKE = CoachCue("parking_sim_brake", "기어를 바꾸기 전에 브레이크부터 눌러요.")
    val STOP = CoachCue("parking_sim_stop", "아직 움직이고 있어요. 완전히 멈춘 뒤 기어를 바꿔요.")
    val SELECT = CoachCue("parking_sim_select", "지금은 P예요. 브레이크를 누르고 기어를 선택해요.")
    val REVERSE = CoachCue("parking_sim_reverse", "화면 속 R이 선택됐어요. 브레이크를 놓고 조금씩 후진해요.")
    val PARALLEL = CoachCue("parking_sim_parallel", "화면의 궤적을 보며 오른쪽으로 돌려 후진해요. 공간 안에서 반대로 풀어봐요.")
    val FORWARD = CoachCue("parking_sim_forward", "D가 선택됐어요. 위치를 고칠 때 조금씩 전진해요.")
    val FORWARD_FIRST = CoachCue("parking_sim_forward_first", "이번 연습은 후진부터 시작해요. 멈춘 뒤 R을 선택해봐요.")
    val NEAR = CoachCue("parking_sim_near", "가상 차체가 가까워졌어요. 멈추고 화면의 공간을 다시 살펴요.")
    val ALIGNED = CoachCue("parking_sim_aligned", "가상 주차 구역 안이에요. 멈추고 P로 바꾼 뒤 완료를 눌러요.")
    val PARK = CoachCue("parking_sim_park", "완료하기 전에 브레이크로 멈추고 P를 선택해요.")
    val ADJUST = CoachCue("parking_sim_adjust", "가상 차체의 위치나 방향을 조금 더 맞춰봐요.")
    val COMPLETE = CoachCue("parking_sim_complete", "가상 주차를 마쳤어요. 직접 조작한 과정을 돌아봐요.")
    val COLLISION = CoachCue("parking_sim_collision", "가상 차량이 겹쳐 연습을 멈췄어요. 다시 시도해봐요.")
    val EDGE = CoachCue("parking_sim_edge", "가상 연습장을 벗어나 멈췄어요. 다시 시작해요.")
    val catalog = listOf(START_REVERSE, START_PARALLEL, BRAKE, STOP, SELECT, REVERSE, PARALLEL,
        FORWARD, FORWARD_FIRST, NEAR, ALIGNED, PARK, ADJUST, COMPLETE, COLLISION, EDGE)
}

/** Geometry helpers operate on the virtual body only, never real sensed objects. */
object ParkingGeometry {
    fun corners(rect: ParkingRect): List<ParkingPoint> {
        val rightX = cos(rect.headingRadians); val rightY = -sin(rect.headingRadians)
        val forwardX = sin(rect.headingRadians); val forwardY = cos(rect.headingRadians)
        return listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0).map { (w, l) ->
            ParkingPoint(rect.x + rightX * w * rect.widthM / 2 + forwardX * l * rect.lengthM / 2,
                rect.y + rightY * w * rect.widthM / 2 + forwardY * l * rect.lengthM / 2)
        }
    }

    /** SAT detects edge crossings even when no corner is inside the other rectangle. */
    fun overlaps(a: ParkingRect, b: ParkingRect): Boolean {
        val ca = corners(a); val cb = corners(b)
        for (heading in listOf(a.headingRadians, b.headingRadians)) {
            for (axis in listOf(ParkingPoint(cos(heading), -sin(heading)), ParkingPoint(sin(heading), cos(heading)))) {
                val pa = ca.map { it.x * axis.x + it.y * axis.y }
                val pb = cb.map { it.x * axis.x + it.y * axis.y }
                if (pa.max() < pb.min() - 0.000001 || pb.max() < pa.min() - 0.000001) return false
            }
        }
        return true
    }

    fun contains(outer: ParkingRect, inner: ParkingRect): Boolean = corners(inner).all { point ->
        val dx = point.x - outer.x; val dy = point.y - outer.y
        val lateral = dx * cos(outer.headingRadians) - dy * sin(outer.headingRadians)
        val longitudinal = dx * sin(outer.headingRadians) + dy * cos(outer.headingRadians)
        abs(lateral) <= outer.widthM / 2 + 0.000001 && abs(longitudinal) <= outer.lengthM / 2 + 0.000001
    }

    fun clearance(a: ParkingRect, b: ParkingRect): Double {
        if (overlaps(a, b)) return 0.0
        val ca = corners(a); val cb = corners(b)
        fun distanceToSegment(p: ParkingPoint, u: ParkingPoint, v: ParkingPoint): Double {
            val dx = v.x-u.x; val dy = v.y-u.y
            val t = (((p.x-u.x)*dx + (p.y-u.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0, 1.0)
            return hypot(p.x-(u.x+t*dx), p.y-(u.y+t*dy))
        }
        var result = Double.POSITIVE_INFINITY
        for (i in 0..3) for (j in 0..3) {
            result = min(result, distanceToSegment(ca[i], cb[j], cb[(j+1)%4]))
            result = min(result, distanceToSegment(cb[i], ca[j], ca[(j+1)%4]))
        }
        return result
    }

    fun wrapAngle(angle: Double): Double = atan2(sin(angle), cos(angle))
}

/**
 * Parked-only educational simulation, independent from Android and all vehicle SDKs.
 * 1.4 m/s² acceleration, 3.5 m/s² braking, 0.8 m/s² coast drag, a 5 km/h virtual speed cap,
 * 8° target tolerance and obstacle clearance cues are scenario assumptions, not road advice.
 * No self-parking, steering assist or automatic success: finish() requires the user's P/stop.
 */
class ParkingSimulationSession(val course: ParkingCourse) {
    private val vehicle = ParkingVehicleSpec()
    private val width = 16.0
    private val height = 20.0
    private var pose = if (course == ParkingCourse.REVERSE_BAY) ParkingPose(8.0, 10.0, 0.0) else ParkingPose(8.2, 14.8, 0.0)
    private val target = if (course == ParkingCourse.REVERSE_BAY) ParkingRect("target", 8.0, 4.0, 3.0, 5.6)
        else ParkingRect("target", 11.5, 8.0, 3.2, 7.4)
    private val obstacles = if (course == ParkingCourse.REVERSE_BAY) listOf(
        ParkingRect("parked-left", 4.4, 4.1, 1.8, 4.3), ParkingRect("parked-right", 11.6, 4.1, 1.8, 4.3),
    ) else listOf(ParkingRect("parked-front", 11.5, 15.0, 1.8, 4.3), ParkingRect("parked-rear", 11.5, 2.1, 1.8, 4.1))
    private var gear = ParkingGear.P
    private var speed = 0.0
    private var steering = 0.0
    private var gas = 0.0
    private var brake = 0.0
    private var elapsed = 0.0
    private var distance = 0.0
    private var phase = ParkingPhase.PREPARE
    private var paused = false
    private var movedReverse = false
    private var gearChanges = 0
    private var coachCue = if (course == ParkingCourse.REVERSE_BAY) ParkingSimulationCues.START_REVERSE else ParkingSimulationCues.START_PARALLEL
    private var revision = 1L
    private val interventions = mutableListOf<ParkingIntervention>()
    private val warned = mutableSetOf<ParkingInterventionKind>()
    private val trail = mutableListOf(ParkingPoint(pose.x, pose.y))

    fun snapshot(): ParkingSnapshot {
        val body = body()
        val corners = ParkingGeometry.corners(body)
        return ParkingSnapshot(course, pose, gear, abs(speed)*3.6, speed, steering, gas, brake,
            elapsed, distance, phase, vehicle, width, height, target, obstacles.toList(), corners,
            predictedPath(), trail.toList(), clearance(body, corners), ParkingGeometry.contains(target, body),
            angleError(), coachCue, revision, paused, phase == ParkingPhase.COMPLETE, phase == ParkingPhase.FAILED,
            reflections(), interventions.toList())
    }

    fun setGear(value: ParkingGear): ParkingSnapshot {
        if (locked() || gear == value) return snapshot()
        if (abs(speed) > STOP_TOLERANCE_MPS) warn(ParkingInterventionKind.STOP_FOR_GEAR, ParkingSimulationCues.STOP)
        else if (brake < 0.5) warn(ParkingInterventionKind.BRAKE_FOR_GEAR, ParkingSimulationCues.BRAKE)
        else {
            speed = 0.0; gear = value; gearChanges++
            if (phase == ParkingPhase.PREPARE) phase = ParkingPhase.MANEUVER
            when (value) {
                ParkingGear.R -> emit(if (course == ParkingCourse.PARALLEL && !movedReverse) ParkingSimulationCues.PARALLEL else ParkingSimulationCues.REVERSE)
                ParkingGear.D -> if (!movedReverse) warn(ParkingInterventionKind.FORWARD_FIRST, ParkingSimulationCues.FORWARD_FIRST) else emit(ParkingSimulationCues.FORWARD)
                ParkingGear.P -> emit(if (fitsTarget()) ParkingSimulationCues.ALIGNED else ParkingSimulationCues.ADJUST)
            }
        }
        return snapshot()
    }

    fun setSteering(value: Double): ParkingSnapshot {
        if (!locked()) steering = normalized(value, -1.0, 1.0)
        return snapshot()
    }

    fun setPedals(gas: Double, brake: Double): ParkingSnapshot {
        if (!locked()) {
            this.gas = normalized(gas, 0.0, 1.0); this.brake = normalized(brake, 0.0, 1.0)
            if (this.gas > 0.0 && this.brake == 0.0 && gear == ParkingGear.P)
                warn(ParkingInterventionKind.SELECT_GEAR, ParkingSimulationCues.SELECT)
        }
        return snapshot()
    }

    fun tick(dtSeconds: Double): ParkingSnapshot {
        if (locked() || !dtSeconds.isFinite() || dtSeconds <= 0.0) return snapshot()
        var remaining = min(dtSeconds, MAX_TICK_SECONDS)
        while (remaining > 0.000001 && !terminal()) {
            val dt = min(remaining, 0.01)
            step(dt); remaining -= dt
        }
        return snapshot()
    }

    fun releaseInputs(): ParkingSnapshot {
        if (!terminal()) { gas = 0.0; brake = 0.0; steering = 0.0 }
        return snapshot()
    }

    fun pause(): ParkingSnapshot {
        if (!terminal()) {
            gas = 0.0; brake = 0.0; steering = 0.0
            // Pause preserves the pose but does not carry momentum into a later resume.
            speed = 0.0; paused = true
        }
        return snapshot()
    }

    fun resume(): ParkingSnapshot { if (!terminal()) paused = false; return snapshot() }

    fun finish(): ParkingSnapshot {
        if (locked()) return snapshot()
        if (gear != ParkingGear.P || abs(speed) > STOP_TOLERANCE_MPS) warn(ParkingInterventionKind.STOP_AND_P, ParkingSimulationCues.PARK)
        else if (!fitsTarget() || distance < 0.5) warn(ParkingInterventionKind.ADJUST_POSITION, ParkingSimulationCues.ADJUST)
        else {
            speed = 0.0; gas = 0.0; brake = 0.0; steering = 0.0
            phase = ParkingPhase.COMPLETE; emit(ParkingSimulationCues.COMPLETE)
        }
        return snapshot()
    }

    private fun step(dt: Double) {
        elapsed += dt
        val previousSpeed = speed
        val magnitude = when {
            gear == ParkingGear.P -> 0.0
            brake > 0.0 -> max(0.0, abs(speed) - 3.5*brake*dt)
            gas > 0.0 -> min(MAX_SPEED_MPS, abs(speed) + 1.4*gas*dt)
            else -> max(0.0, abs(speed) - 0.8*dt)
        }
        speed = magnitude * when (gear) { ParkingGear.R -> -1; ParkingGear.D -> 1; ParkingGear.P -> 0 }
        val ds = (previousSpeed + speed)*0.5*dt
        if (abs(ds) > 0.000001) {
            pose = moved(pose, ds, steering)
            distance += abs(ds)
            if (ds < 0) movedReverse = true
            if (hypot(pose.x-trail.last().x, pose.y-trail.last().y) >= 0.12) {
                trail += ParkingPoint(pose.x, pose.y)
                if (trail.size > 800) trail.removeAt(0)
            }
        }
        val body = body(); val corners = ParkingGeometry.corners(body)
        if (corners.any { it.x < 0 || it.x > width || it.y < 0 || it.y > height }) {
            fail(ParkingInterventionKind.ROAD_EDGE, ParkingSimulationCues.EDGE); return
        }
        if (obstacles.any { ParkingGeometry.overlaps(body, it) }) {
            fail(ParkingInterventionKind.COLLISION, ParkingSimulationCues.COLLISION); return
        }
        if (clearance(body, corners) < 0.55 && abs(ds) > 0.000001)
            warn(ParkingInterventionKind.PROXIMITY, ParkingSimulationCues.NEAR)
        if (fitsTarget() && distance > 0.5) {
            if (phase != ParkingPhase.ALIGNED) { phase = ParkingPhase.ALIGNED; emit(ParkingSimulationCues.ALIGNED) }
        } else if (phase == ParkingPhase.ALIGNED) {
            phase = ParkingPhase.MANEUVER
            emit(ParkingSimulationCues.ADJUST)
        }
    }

    /** Analytic constant-steering rear-axle integration; centre follows the assumed axle offset. */
    private fun moved(from: ParkingPose, ds: Double, input: Double): ParkingPose {
        val axleOffset = vehicle.wheelbaseM / 2.0
        val rearX = from.x - sin(from.headingRadians)*axleOffset
        val rearY = from.y - cos(from.headingRadians)*axleOffset
        val curvature = tan(Math.toRadians(input * vehicle.maxRoadWheelAngleDegrees)) / vehicle.wheelbaseM
        val heading = ParkingGeometry.wrapAngle(from.headingRadians + ds*curvature)
        val x: Double; val y: Double
        if (abs(curvature) < 0.000001) {
            x = rearX + ds*sin(from.headingRadians); y = rearY + ds*cos(from.headingRadians)
        } else {
            x = rearX + (cos(from.headingRadians)-cos(heading))/curvature
            y = rearY + (sin(heading)-sin(from.headingRadians))/curvature
        }
        return ParkingPose(x + sin(heading)*axleOffset, y + cos(heading)*axleOffset, heading)
    }

    private fun predictedPath(): List<ParkingPoint> {
        if (gear == ParkingGear.P) return emptyList()
        var prediction = pose
        val direction = if (gear == ParkingGear.R) -1.0 else 1.0
        return buildList {
            add(ParkingPoint(pose.x, pose.y))
            repeat(18) {
                prediction = moved(prediction, direction*0.2, steering)
                add(ParkingPoint(prediction.x, prediction.y))
            }
        }
    }

    private fun body() = ParkingRect("learner", pose.x, pose.y, vehicle.widthM, vehicle.lengthM, pose.headingRadians)
    private fun angleError() = abs(Math.toDegrees(ParkingGeometry.wrapAngle(pose.headingRadians-target.headingRadians)))
    private fun fitsTarget() = ParkingGeometry.contains(target, body()) && angleError() <= 8.0
    private fun clearance(body: ParkingRect, corners: List<ParkingPoint>): Double {
        val edge = corners.minOf { min(min(it.x, width-it.x), min(it.y, height-it.y)) }.coerceAtLeast(0.0)
        return min(edge, obstacles.minOfOrNull { ParkingGeometry.clearance(body, it) } ?: edge)
    }
    private fun warn(kind: ParkingInterventionKind, cue: CoachCue) {
        if (warned.add(kind)) { interventions += ParkingIntervention(kind, elapsed, cue); emit(cue) }
    }
    private fun fail(kind: ParkingInterventionKind, cue: CoachCue) {
        warn(kind, cue); speed = 0.0; gas = 0.0; brake = 0.0; steering = 0.0
        phase = ParkingPhase.FAILED; emit(cue)
    }
    private fun emit(cue: CoachCue) { if (coachCue.id != cue.id) { coachCue = cue; revision++ } }
    private fun reflections(): List<String> = buildList {
        add("가상 ${if (course == ParkingCourse.REVERSE_BAY) "후진" else "평행"} 주차에서 ${String.format(java.util.Locale.ROOT, "%.1f", distance)}m를 직접 조작했어요.")
        add("정지·브레이크 조건을 확인한 기어 변경은 ${gearChanges}회예요.")
        if (phase == ParkingPhase.COMPLETE) add("화면 속 차체의 위치·방향을 맞추고, P에서 완료를 눌렀어요.")
        else if (phase == ParkingPhase.FAILED) add("가상 차체 겹침 또는 연습장 이탈로 중단했어요. 새 연습에서 다시 시도해요.")
        else add("아직 가상 주차 완료로 처리하지 않았어요. 어려웠던 조작부터 다시 연습해요.")
        if (interventions.any { it.kind == ParkingInterventionKind.PROXIMITY }) add("옆 공간이 좁아진 순간이 있었어요. 다음에는 멈추고 위치를 다시 살펴봐요.")
        add("실제 주차 성공이나 운전 숙련도를 평가한 기록은 아니에요.")
    }
    private fun locked() = paused || terminal()
    private fun terminal() = phase == ParkingPhase.COMPLETE || phase == ParkingPhase.FAILED
    private fun normalized(value: Double, low: Double, high: Double) = if (value.isFinite()) value.coerceIn(low, high) else 0.0
    companion object {
        const val MAX_TICK_SECONDS = 0.1
        const val MAX_SPEED_MPS = 5.0/3.6
        const val STOP_TOLERANCE_MPS = 0.025
    }
}
