package kr.drivestep.tutor.simulator

import kr.drivestep.tutor.coach.CoachCue
import kr.drivestep.tutor.contextcoach.SituationCoachEngine
import kr.drivestep.tutor.contextcoach.SituationGear
import kr.drivestep.tutor.contextcoach.SituationInput
import kr.drivestep.tutor.contextcoach.SituationKind
import kr.drivestep.tutor.contextcoach.SituationRoad
import kr.drivestep.tutor.contextcoach.SituationSignal
import kr.drivestep.tutor.contextcoach.SituationSource
import kr.drivestep.tutor.contextcoach.SituationWarning
import kotlin.math.abs
import kotlin.math.min

enum class SimulationScenario { LEFT_LANE, RIGHT_LANE, BLIND_SPOT }
enum class SimulationSignal { OFF, LEFT, RIGHT }
enum class SimulationMirror { LEFT, RIGHT }
enum class SimulationPhase { SIGNAL, MIRROR, WAIT_FOR_GAP, STEER, CENTER, COMPLETE, FAILED }
enum class SimulationInterventionKind {
    WRONG_SIGNAL, NO_SIGNAL, MIRROR_FIRST, NEARBY_CAR, SPEED_DROP, SPEED_RISE,
    WRONG_MIRROR, ROAD_EDGE, COLLISION,
}

/** Lane centres are -1 (left), 0 (middle), +1 (right); distance is relative to the learner. */
data class SimulationCar(val id: String, val lanePosition: Double, val distanceM: Double, val speedKmh: Double)

data class SimulationIntervention(
    val kind: SimulationInterventionKind,
    val atElapsedSeconds: Double,
    val cue: CoachCue,
)

data class DrivingSimulationSnapshot(
    val scenario: SimulationScenario,
    val phase: SimulationPhase,
    val speedKmh: Double,
    val lanePosition: Double,
    /** Normalized virtual input, never a physical steering-wheel or front-wheel angle. */
    val steering: Double,
    val signal: SimulationSignal,
    val gas: Double,
    val brake: Double,
    val elapsedSeconds: Double,
    val distanceM: Double,
    val traffic: List<SimulationCar>,
    val blindSpotLeft: Boolean,
    val blindSpotRight: Boolean,
    val targetLane: Double,
    /** A button acknowledgement, not a measurement of gaze or attention. */
    val mirrorChecked: Boolean,
    val coachCue: CoachCue,
    val coachRevision: Long,
    val completed: Boolean,
    val failed: Boolean,
    val paused: Boolean,
    val reflections: List<String>,
    val interventions: List<SimulationIntervention>,
) {
    val isTerminal: Boolean get() = completed || failed
}

/** Reviewed fixed clips. All instructions refer to this on-screen exercise. */
object SimulationCues {
    val START_LEFT = CoachCue("simulation_start_left", "왼쪽 차로로 가볼게요. 먼저 왼쪽 신호를 켜요.")
    val START_RIGHT = CoachCue("simulation_start_right", "오른쪽 차로로 가볼게요. 먼저 오른쪽 신호를 켜요.")
    val MIRROR_LEFT = CoachCue("simulation_mirror_left", "왼쪽 가상 미러를 확인해요.")
    val MIRROR_RIGHT = CoachCue("simulation_mirror_right", "오른쪽 가상 미러를 확인해요.")
    val WAIT = CoachCue("simulation_wait", "옆에 가상 차량이 있어요. 핸들을 중앙에 두고 기다려요.")
    val STEER_LEFT = CoachCue("simulation_steer_left", "화면의 주변을 보며, 왼쪽으로 조금씩 조작해요.")
    val STEER_RIGHT = CoachCue("simulation_steer_right", "화면의 주변을 보며, 오른쪽으로 조금씩 조작해요.")
    val CENTER = CoachCue("simulation_center", "목표 차로예요. 핸들을 중앙에 두고 신호를 꺼요.")
    val COMPLETE = CoachCue("simulation_complete", "직접 조작해 마쳤어요. 방금 연습을 함께 돌아봐요.")
    val WRONG_SIGNAL = CoachCue("simulation_wrong_signal", "신호가 반대쪽이에요. 목표 방향으로 바꿔봐요.")
    val NO_SIGNAL = CoachCue("simulation_no_signal", "옆으로 움직이기 전에, 방향지시등부터 켜요.")
    val MIRROR_FIRST = CoachCue("simulation_mirror_first", "움직이기 전에, 가상 미러부터 확인해요.")
    val WRONG_MIRROR = CoachCue("simulation_wrong_mirror", "이동할 방향의 가상 미러를 확인해요.")
    val NEARBY = CoachCue("simulation_nearby", "옆차가 가까워요. 핸들을 중앙에 두고 기다려요.")
    val SPEED_DROP = CoachCue("simulation_speed_drop", "속도가 빠르게 줄었어요. 조작을 조금 부드럽게 해봐요.")
    val SPEED_RISE = CoachCue("simulation_speed_rise", "속도가 빠르게 늘었어요. 가속을 조금 줄여봐요.")
    val ROAD_EDGE = CoachCue("simulation_road_edge", "가상 도로를 벗어나 연습을 멈췄어요. 다시 시작해요.")
    val COLLISION = CoachCue("simulation_collision", "가상 차량이 겹쳐 연습을 멈췄어요. 다시 시작해요.")
    val catalog = listOf(START_LEFT, START_RIGHT, MIRROR_LEFT, MIRROR_RIGHT, WAIT, STEER_LEFT,
        STEER_RIGHT, CENTER, COMPLETE, WRONG_SIGNAL, NO_SIGNAL, MIRROR_FIRST, WRONG_MIRROR,
        NEARBY, SPEED_DROP, SPEED_RISE, ROAD_EDGE, COLLISION)
}

/**
 * Single-threaded, pure Kotlin driving exercise, entirely independent of Vehicle SDK.
 * The host keeps the real vehicle parked and controls availability; this class knows no
 * real vehicle state and never permits physical driving. The three-lane geometry,
 * speed response, proximity bands and overlap checks are teaching models only, not
 * calibrated vehicle dynamics, actual collision detection or recommended road limits.
 * No input can trigger an automatic lane change: lateral movement requires steering.
 * Start a fresh instance to retry a completed/failed exercise.
 */
class DrivingSimulationSession(val scenario: SimulationScenario) {
    private val targetLane = if (scenario == SimulationScenario.RIGHT_LANE) 1.0 else -1.0
    private val targetSignal = if (targetLane < 0) SimulationSignal.LEFT else SimulationSignal.RIGHT
    private val targetMirror = if (targetLane < 0) SimulationMirror.LEFT else SimulationMirror.RIGHT
    private var phase = SimulationPhase.SIGNAL
    private var speedKmh = 40.0
    private var lanePosition = 0.0
    private var steering = 0.0
    private var signal = SimulationSignal.OFF
    private var gas = 0.0
    private var brake = 0.0
    private var elapsedSeconds = 0.0
    private var distanceM = 0.0
    private var paused = false
    private var mirrorChecked = false
    private var preparedPosition = 0.0
    private var movedAfterPreparation = false
    private var centeredSeconds = 0.0
    private var waitedForCar = false
    private var passingReleasedAt: Double? = null
    private var coachCue = if (targetLane < 0) SimulationCues.START_LEFT else SimulationCues.START_RIGHT
    private var coachRevision = 1L
    private val interventions = mutableListOf<SimulationIntervention>()
    private val warnedKinds = mutableSetOf<SimulationInterventionKind>()
    private val cueTimes = mutableMapOf(coachCue.id to 0.0)
    private val speedCoach = SituationCoachEngine()
    private var lastSpeedSampleSeconds = -1.0
    private var traffic = listOf(
        SimulationCar("front", 0.0, 64.0, 42.0),
        SimulationCar("left-front", -1.0, 48.0, 44.0),
        SimulationCar("left-rear", -1.0, -38.0, 37.0),
        SimulationCar("right-front", 1.0, 60.0, 43.0),
        SimulationCar("right-rear", 1.0, -42.0, 38.0),
    ) + if (scenario == SimulationScenario.BLIND_SPOT)
        listOf(SimulationCar("left-passing", -1.0, -2.0, 40.0)) else emptyList()

    init { observeSpeed() }

    /** Values are normalized; invalid/non-finite input safely releases the affected pedal. */
    fun setPedals(gas: Double, brake: Double): DrivingSimulationSnapshot {
        if (locked()) return snapshot()
        this.gas = normalized(gas, 0.0, 1.0)
        this.brake = normalized(brake, 0.0, 1.0)
        return snapshot()
    }

    /** Negative is left, positive is right. The host's centre action sends zero. */
    fun setSteering(normalized: Double): DrivingSimulationSnapshot {
        if (locked()) return snapshot()
        steering = normalized(normalized, -1.0, 1.0)
        return snapshot()
    }

    fun setSignal(next: SimulationSignal): DrivingSimulationSnapshot {
        if (locked() || signal == next) return snapshot()
        signal = next
        if (phase == SimulationPhase.CENTER && next == SimulationSignal.OFF && isAtTarget() && isSteeringCentered()) {
            return snapshot()
        }
        mirrorChecked = false
        movedAfterPreparation = false
        centeredSeconds = 0.0
        if (next == targetSignal) {
            transition(SimulationPhase.MIRROR, if (targetLane < 0) SimulationCues.MIRROR_LEFT else SimulationCues.MIRROR_RIGHT)
        } else {
            transition(SimulationPhase.SIGNAL, if (targetLane < 0) SimulationCues.START_LEFT else SimulationCues.START_RIGHT)
            if (next != SimulationSignal.OFF) warn(SimulationInterventionKind.WRONG_SIGNAL, SimulationCues.WRONG_SIGNAL)
        }
        return snapshot()
    }

    /** This records only the learner's button press; it never claims to track their eyes. */
    fun lookMirror(mirror: SimulationMirror): DrivingSimulationSnapshot {
        if (locked()) return snapshot()
        if (mirror != targetMirror) {
            warn(SimulationInterventionKind.WRONG_MIRROR, SimulationCues.WRONG_MIRROR)
        } else if (signal != targetSignal) {
            warn(SimulationInterventionKind.NO_SIGNAL, SimulationCues.NO_SIGNAL)
        } else if (!mirrorChecked) {
            mirrorChecked = true
            preparedPosition = lanePosition
            movedAfterPreparation = false
            if (nearLane(targetLane)) {
                waitedForCar = true
                if (scenario == SimulationScenario.BLIND_SPOT && passingReleasedAt == null) passingReleasedAt = elapsedSeconds
                transition(SimulationPhase.WAIT_FOR_GAP, SimulationCues.WAIT)
            } else {
                transition(SimulationPhase.STEER, steerCue())
            }
        }
        return snapshot()
    }

    /** Long render gaps advance at most 100 ms; no catch-up motion after returning to the app. */
    fun tick(dtSeconds: Double): DrivingSimulationSnapshot {
        if (locked() || !dtSeconds.isFinite() || dtSeconds <= 0.0) return snapshot()
        var remaining = min(dtSeconds, MAX_TICK_SECONDS)
        // Short integration steps prevent cars from tunnelling through one another.
        while (remaining > 0.000001 && !isTerminal()) {
            val dt = min(remaining, 0.02)
            step(dt)
            remaining -= dt
        }
        return snapshot()
    }

    fun releaseInputs(): DrivingSimulationSnapshot {
        if (isTerminal()) return snapshot()
        gas = 0.0; brake = 0.0; steering = 0.0
        return snapshot()
    }

    fun pause(): DrivingSimulationSnapshot {
        if (isTerminal()) return snapshot()
        releaseInputs()
        paused = true
        return snapshot()
    }

    fun resume(): DrivingSimulationSnapshot {
        if (isTerminal()) return snapshot()
        paused = false
        return snapshot()
    }

    fun snapshot(): DrivingSimulationSnapshot = DrivingSimulationSnapshot(
        scenario, phase, speedKmh, lanePosition, steering, signal, gas, brake,
        elapsedSeconds, distanceM, traffic.toList(), nearLane(-1.0), nearLane(1.0),
        targetLane, mirrorChecked, coachCue, coachRevision,
        phase == SimulationPhase.COMPLETE, phase == SimulationPhase.FAILED, paused,
        reflections(), interventions.toList(),
    )

    private fun step(dt: Double) {
        val previousLane = lanePosition
        val previousSpeed = speedKmh / 3.6
        // No-pedal input holds the current virtual speed, enabling one-pointer practice.
        // Braking wins if both pedals are held; there is no reverse or autonomous control.
        val acceleration = if (brake > 0.0) -4.5 * brake else 2.4 * gas
        speedKmh = (speedKmh + acceleration * 3.6 * dt).coerceIn(0.0, 100.0)
        val averageSpeed = (previousSpeed + speedKmh / 3.6) / 2.0
        val travelled = averageSpeed * dt
        distanceM += travelled
        elapsedSeconds += dt
        lanePosition += steering * averageSpeed / 22.0 * dt
        traffic = traffic.map {
            if (it.id != "left-passing") it.copy(distanceM = it.distanceM + it.speedKmh / 3.6 * dt - travelled)
            else {
                // Preserve this teaching situation while a novice listens to the opening
                // cues. After their mirror acknowledgement the same car waits one second,
                // then accelerates continuously ahead; it is never teleported or removed.
                val releasedAt = passingReleasedAt
                if (releasedAt == null || elapsedSeconds - releasedAt < 1.0) it.copy(speedKmh = speedKmh)
                else {
                    val nextSpeed = min(50.0, it.speedKmh + 2.0 * 3.6 * dt)
                    it.copy(speedKmh = nextSpeed,
                        distanceM = it.distanceM + (it.speedKmh + nextSpeed) / 7.2 * dt - travelled)
                }
            }
        }

        if (abs(lanePosition) > ROAD_EDGE) {
            fail(SimulationInterventionKind.ROAD_EDGE, SimulationCues.ROAD_EDGE)
            return
        }
        if (traffic.any { abs(it.lanePosition - lanePosition) < 0.47 && abs(it.distanceM) < 4.6 }) {
            fail(SimulationInterventionKind.COLLISION, SimulationCues.COLLISION)
            return
        }

        val moved = abs(lanePosition - previousLane) > 0.00001
        val movingTowardTarget = (lanePosition - previousLane) * targetLane > 0.00001
        val nearbyDuringMove = moved && traffic.any {
            abs(it.distanceM) < NEAR_DISTANCE_M &&
                abs(it.lanePosition - lanePosition) < abs(it.lanePosition - previousLane) &&
                abs(it.lanePosition - lanePosition) < 1.1
        }
        if (phase == SimulationPhase.WAIT_FOR_GAP && !nearLane(targetLane)) {
            preparedPosition = lanePosition
            movedAfterPreparation = false
            transition(SimulationPhase.STEER, steerCue())
        }
        if (phase == SimulationPhase.STEER && nearLane(targetLane) && !isAtTarget()) {
            waitedForCar = true
            movedAfterPreparation = false
            transition(SimulationPhase.WAIT_FOR_GAP, SimulationCues.WAIT)
        }
        if (phase == SimulationPhase.STEER && movingTowardTarget &&
            (lanePosition - preparedPosition) * targetLane >= 0.05) movedAfterPreparation = true
        if (phase == SimulationPhase.STEER && isAtTarget() && movedAfterPreparation) {
            transition(SimulationPhase.CENTER, SimulationCues.CENTER)
        }
        if (phase == SimulationPhase.CENTER) {
            if (!isAtTarget()) {
                centeredSeconds = 0.0
                if (signal == targetSignal) transition(SimulationPhase.STEER, steerCue())
                else {
                    mirrorChecked = false
                    transition(SimulationPhase.SIGNAL, if (targetLane < 0) SimulationCues.START_LEFT else SimulationCues.START_RIGHT)
                }
            } else if (isSteeringCentered() && signal == SimulationSignal.OFF) {
                centeredSeconds += dt
                if (centeredSeconds >= 0.6) {
                    releaseInputs()
                    transition(SimulationPhase.COMPLETE, SimulationCues.COMPLETE)
                    return
                }
            } else centeredSeconds = 0.0
        }

        if (nearbyDuringMove) warn(SimulationInterventionKind.NEARBY_CAR, SimulationCues.NEARBY)
        else if (moved && signal == SimulationSignal.OFF && phase != SimulationPhase.CENTER)
            warn(SimulationInterventionKind.NO_SIGNAL, SimulationCues.NO_SIGNAL)
        else if (moved && signal != targetSignal && signal != SimulationSignal.OFF)
            warn(SimulationInterventionKind.WRONG_SIGNAL, SimulationCues.WRONG_SIGNAL)
        else if (moved && !mirrorChecked)
            warn(SimulationInterventionKind.MIRROR_FIRST, SimulationCues.MIRROR_FIRST)
        if (elapsedSeconds - lastSpeedSampleSeconds >= 0.1 - 0.000001) observeSpeed()
    }

    private fun observeSpeed() {
        lastSpeedSampleSeconds = elapsedSeconds
        val now = (elapsedSeconds * 1000).toLong()
        // Reuse the established signal + 2–3 second speed-change context. Throttling
        // to 10 Hz keeps its bounded window meaningful even with 60 Hz rendering.
        // Virtual proximity has its own phrases, so it is never labelled an OEM warning.
        val event = speedCoach.accept(SituationInput(
            atElapsedMillis = now, source = SituationSource.SIMULATION,
            signal = when (signal) {
                SimulationSignal.OFF -> SituationSignal.OFF
                SimulationSignal.LEFT -> SituationSignal.LEFT
                SimulationSignal.RIGHT -> SituationSignal.RIGHT
            },
            speedKmh = speedKmh, warning = SituationWarning.CLEAR,
            gear = SituationGear.D, brakePressed = brake > 0.0,
            steering = null, roadContext = SituationRoad.STRAIGHT,
        )).event ?: return
        when (event.kind) {
            SituationKind.SPEED_DROP -> warn(SimulationInterventionKind.SPEED_DROP, SimulationCues.SPEED_DROP)
            SituationKind.SPEED_RISE -> warn(SimulationInterventionKind.SPEED_RISE, SimulationCues.SPEED_RISE)
            SituationKind.LANE_WARNING -> Unit
        }
    }

    private fun fail(kind: SimulationInterventionKind, cue: CoachCue) {
        releaseInputs()
        warn(kind, cue)
        phase = SimulationPhase.FAILED
        emit(cue, terminal = true)
    }

    private fun transition(next: SimulationPhase, cue: CoachCue) {
        if (phase == next) return
        phase = next
        emit(cue, terminal = isTerminal())
    }

    private fun emit(cue: CoachCue, terminal: Boolean = false) {
        if (coachCue.id == cue.id) return
        if (!terminal && cueTimes[cue.id]?.let { elapsedSeconds - it < 5.0 } == true) return
        coachCue = cue
        coachRevision++
        cueTimes[cue.id] = elapsedSeconds
    }

    /** Each correction is spoken and recorded at most once in one attempt. */
    private fun warn(kind: SimulationInterventionKind, cue: CoachCue) {
        if (!warnedKinds.add(kind)) return
        interventions += SimulationIntervention(kind, elapsedSeconds, cue)
        emit(cue)
    }

    private fun reflections(): List<String> = buildList {
        if (mirrorChecked || phase == SimulationPhase.COMPLETE) add("방향지시 뒤 가상 미러 확인 버튼을 직접 눌렀어요. 시선 추적 결과는 아니에요.")
        if (waitedForCar) add("옆 가상 차량이 지나가는 흐름을 연습했어요. 이 화면의 간격은 실제 도로의 안전 기준이 아니에요.")
        if (phase == SimulationPhase.COMPLETE) add("핸들로 목표 차로에 진입하고 중앙 조향과 신호 끄기로 마무리했어요.")
        if (phase == SimulationPhase.FAILED) add("가상 화면의 도로 이탈 또는 차량 겹침으로 멈췄어요. 새 연습에서 다시 시도할 수 있어요.")
        if (interventions.any { it.kind == SimulationInterventionKind.NO_SIGNAL || it.kind == SimulationInterventionKind.WRONG_SIGNAL })
            add("다음에는 이동할 방향의 신호를 먼저 켜봐요.")
        if (interventions.any { it.kind == SimulationInterventionKind.MIRROR_FIRST || it.kind == SimulationInterventionKind.WRONG_MIRROR })
            add("신호 다음에는 이동 방향의 가상 미러를 확인해요.")
        if (interventions.any { it.kind == SimulationInterventionKind.NEARBY_CAR }) add("옆차가 가까울 때는 조향을 멈추고 기다리는 동작을 다시 해봐요.")
        if (interventions.any { it.kind == SimulationInterventionKind.SPEED_DROP || it.kind == SimulationInterventionKind.SPEED_RISE })
            add("급한 속도 변화가 있었어요. 다음에는 페달을 조금씩 눌러봐요.")
    }

    private fun steerCue() = if (targetLane < 0) SimulationCues.STEER_LEFT else SimulationCues.STEER_RIGHT
    private fun nearLane(lane: Double) = traffic.any { abs(it.lanePosition - lane) < 0.25 && abs(it.distanceM) < NEAR_DISTANCE_M }
    private fun isAtTarget() = abs(lanePosition - targetLane) <= 0.12
    private fun isSteeringCentered() = abs(steering) <= 0.04
    private fun locked() = paused || isTerminal()
    private fun isTerminal() = phase == SimulationPhase.COMPLETE || phase == SimulationPhase.FAILED
    private fun normalized(value: Double, min: Double, max: Double) = if (value.isFinite()) value.coerceIn(min, max) else 0.0

    companion object {
        const val MAX_TICK_SECONDS = 0.1
        private const val ROAD_EDGE = 1.28
        private const val NEAR_DISTANCE_M = 14.0
    }
}
