package kr.drivestep.tutor.ride

import kr.drivestep.tutor.coach.CoachCue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RideLesson { START_STOP, CROSSWALK }
enum class VirtualGear { P, D, R }
enum class VirtualSignal { NONE, RED, GREEN }
enum class RidePhase {
    READY_BRAKE, READY_DRIVE, DRIVE_TO_LINE, STOP_AND_PARK,
    APPROACH_CROSSWALK, WAIT_FOR_PEDESTRIAN, WAIT_FOR_GREEN, RESUME_AFTER_GREEN, COMPLETED,
}

data class RideSnapshot(
    val lesson: RideLesson,
    val phase: RidePhase,
    /** Virtual display values: deliberately simplified, not calibrated vehicle measurements. */
    val speedKmh: Double,
    val distanceM: Double,
    val stopLineM: Double,
    /** Normalized illustrative input and lateral position. Neither is a real steering angle. */
    val steering: Double,
    val laneOffset: Double,
    val gear: VirtualGear,
    val gasPressed: Boolean,
    val brakePressed: Boolean,
    val completed: Boolean,
    val coachCue: CoachCue,
    val feedbackRevision: Long,
    val interventions: Int,
    val retries: Int,
    val pedestrianPresent: Boolean,
    val signal: VirtualSignal,
    /** Scripted wait progress, not an estimate of a real traffic light. */
    val waitingSeconds: Double,
    val practiceSeconds: Double,
    val reflection: List<String>,
)

/** Fixed coaching cues about this isolated, fictional simulation only. */
object RideCoach {
    val BRAKE = CoachCue("ride_brake", "제가 옆에서 함께할게요. 먼저 화면의 브레이크를 눌러 가상 차량을 멈춘 채로 유지해 주세요.")
    val DRIVE = CoachCue("ride_drive", "좋아요. 브레이크를 누른 상태에서 화면의 D를 선택해 볼까요?")
    val START = CoachCue("ride_start", "D가 선택됐어요. 브레이크를 놓고 가속을 조금 눌러 가상 차량을 출발시켜 보세요.")
    val STOP_LINE = CoachCue("ride_stop_line", "이제 가속을 놓고, 브레이크로 멈춰 볼까요?")
    val EARLY_STOP = CoachCue("ride_early_stop", "브레이크로 멈추는 조작을 확인했어요. 이번에는 가상 목표선 앞까지 조금 더 진행한 뒤 다시 멈춰 볼게요.")
    val PARK = CoachCue("ride_park", "잘 멈췄어요. 브레이크를 유지하며 P를 선택해 마무리해 볼까요?")
    val START_COMPLETE = CoachCue("ride_start_complete", "브레이크, D, 출발, 정지, P까지 직접 조작했어요. 다음에도 서두르지 않고 한 동작씩 해봐요.")
    val CROSS_APPROACH = CoachCue("ride_cross_approach", "이번 가상 장면은 빨간 신호이고 앞에 보행자가 있어요. 브레이크를 눌러 정지선 전에 멈춰 주세요.")
    val PEDESTRIAN_WAIT = CoachCue("ride_pedestrian_wait", "보행자가 지나갈 때까지 기다려요.")
    val RED_WAIT = CoachCue("ride_red_wait", "아직 빨간불이에요.")
    val GREEN = CoachCue("ride_green", "가상 신호가 녹색으로 바뀌었고 보행자도 없어요. 화면의 주변을 확인한 뒤 브레이크를 놓고 가속해 볼까요?")
    val CROSS_COMPLETE = CoachCue("ride_cross_complete", "멈추고, 기다리고, 가상 신호가 바뀐 뒤 출발하는 조작을 확인했어요. 실제 운전에서도 신호와 주변을 따로 확인해요.")
    val BRAKE_FOR_GEAR = CoachCue("ride_brake_for_gear", "괜찮아요. 이 연습에서는 완전히 멈추고 브레이크를 누른 뒤 기어를 바꿔요.")
    val MOVING_GEAR = CoachCue("ride_moving_gear", "아직 가상 차량이 움직이고 있어요. 먼저 브레이크로 완전히 멈춘 뒤 기어를 선택해 주세요.")
    val REVERSE = CoachCue("ride_reverse", "지금은 출발과 정지를 익히는 과정이에요. 후진은 다음 과정에서 배우고, 이번에는 안내한 D와 P를 사용해요.")
    val GAS_IN_P = CoachCue("ride_gas_in_p", "지금은 P예요. 가속을 놓고 브레이크를 누른 뒤 D를 선택하는 순서로 돌아가 볼까요?")
    val BOTH_PEDALS = CoachCue("ride_both_pedals", "두 페달을 함께 누르고 있어요. 가속을 놓고 브레이크 조작부터 차분하게 해봐요.")
    val TOO_EARLY = CoachCue("ride_too_early", "아직 출발할 차례가 아니에요. 다시 브레이크로 멈추고 보행자와 빨간 신호를 확인해요.")
    val KEEP_STOPPED = CoachCue("ride_keep_stopped", "마무리 전에 다시 움직였어요. 가속을 놓고 브레이크로 멈춘 다음 P를 선택해 봐요.")
    val LANE = CoachCue("ride_lane", "가속을 놓고, 중앙 쪽으로 조금 조작해요.")
    val RETRY_LANE = CoachCue("ride_retry_lane", "가상 차로 경계를 벗어나 출발 위치로 돌아왔어요. 괜찮아요. 핸들을 중앙에 두고 브레이크부터 다시 해봐요.")
    val RETRY_LINE = CoachCue("ride_retry_line", "가상 정지선을 지나쳐 시작 위치로 돌아왔어요. 이번에는 조금 일찍 가속을 놓고 브레이크를 눌러봐요.")
    val HOLD_BRAKE = CoachCue("ride_hold_brake", "정지한 동안에도 브레이크를 눌러 유지해 주세요. 이 가상 장면에서는 멈춘 채 기다리는 조작을 함께 익혀요.")
    val catalog = listOf(BRAKE, DRIVE, START, STOP_LINE, EARLY_STOP, PARK, START_COMPLETE,
        CROSS_APPROACH, PEDESTRIAN_WAIT, RED_WAIT, GREEN, CROSS_COMPLETE, BRAKE_FOR_GEAR,
        MOVING_GEAR, REVERSE, GAS_IN_P, BOTH_PEDALS, TOO_EARLY, KEEP_STOPPED, LANE,
        RETRY_LANE, RETRY_LINE, HOLD_BRAKE)
}

/**
 * A slow, deliberately simplified classroom simulation. No Android API, camera,
 * navigation, vehicle signal, legal-compliance score or actual vehicle control.
 *
 * Virtual acceleration, braking and lateral motion are arbitrary teaching values;
 * do not reuse them for braking distance, steering ratio or real-road guidance.
 * A tick is capped at 0.25s so background time cannot silently complete a lesson.
 * Host pause = stop ticking and release virtual pedals, then explicitly resume.
 */
class DriveLessonSession(val lesson: RideLesson) {
    private var phase = if (lesson == RideLesson.START_STOP) RidePhase.READY_BRAKE else RidePhase.APPROACH_CROSSWALK
    private var speed = if (lesson == RideLesson.START_STOP) 0.0 else 6.0
    private var distance = 0.0
    private var steering = 0.0
    private var laneOffset = 0.0
    private var gear = if (lesson == RideLesson.START_STOP) VirtualGear.P else VirtualGear.D
    private var gas = false
    private var brake = false
    private var cue = if (lesson == RideLesson.START_STOP) RideCoach.BRAKE else RideCoach.CROSS_APPROACH
    private var revision = 0L
    private var interventions = 0
    private var retries = 0
    private var pedestrian = lesson == RideLesson.CROSSWALK
    private var signal = if (lesson == RideLesson.CROSSWALK) VirtualSignal.RED else VirtualSignal.NONE
    private var waiting = 0.0
    private var elapsed = 0.0
    private var greenStartDistance = 0.0
    private var stopPrompted = false
    private var earlyStopPrompted = false
    private var laneWarned = false
    private var lastIntervention: String? = null
    private val observed = linkedSetOf<String>()
    private val stopLine = if (lesson == RideLesson.START_STOP) 32.0 else 24.0

    fun snapshot() = RideSnapshot(
        lesson, phase, speed, distance, stopLine, steering, laneOffset, gear, gas, brake,
        phase == RidePhase.COMPLETED, cue, revision, interventions, retries, pedestrian, signal,
        waiting, elapsed, observed.toList(),
    )

    fun setPedals(gas: Boolean, brake: Boolean): RideSnapshot {
        if (isComplete()) return snapshot()
        val changed = this.gas != gas || this.brake != brake
        this.gas = gas
        this.brake = brake
        if (gas && brake) {
            intervene("both_pedals", RideCoach.BOTH_PEDALS)
            return snapshot()
        }
        if (gas && gear == VirtualGear.P) {
            intervene("gas_in_p", RideCoach.GAS_IN_P)
            return snapshot()
        }
        if (brake && !gas && phase == RidePhase.READY_BRAKE && stopped()) {
            observed += "브레이크를 누르고 기어 선택 준비"
            advance(RidePhase.READY_DRIVE, RideCoach.DRIVE)
        } else if (gas && !brake && phase in WAIT_PHASES) {
            // The user can make the car roll; they must brake again. No automatic answer.
            waiting = 0.0
            phase = RidePhase.APPROACH_CROSSWALK
            intervene("early_departure", RideCoach.TOO_EARLY)
        } else if (changed && !brake && !gas && phase in WAIT_PHASES) {
            inform(RideCoach.HOLD_BRAKE)
        } else if (changed && !gas && brake) {
            lastIntervention = null
        }
        return snapshot()
    }

    fun setSteering(value: Double): RideSnapshot {
        if (!isComplete() && value.isFinite()) steering = value.coerceIn(-1.0, 1.0)
        return snapshot()
    }

    fun setGear(value: VirtualGear): RideSnapshot {
        if (isComplete() || value == gear) return snapshot()
        when {
            !stopped() -> intervene("moving_gear", RideCoach.MOVING_GEAR)
            !brake || gas -> intervene("brake_for_gear", RideCoach.BRAKE_FOR_GEAR)
            value == VirtualGear.R -> intervene("reverse", RideCoach.REVERSE)
            value == VirtualGear.D -> {
                gear = value
                observed += "브레이크를 유지하며 D 선택"
                if (lesson == RideLesson.START_STOP) advance(RidePhase.DRIVE_TO_LINE, RideCoach.START)
                else if (signal == VirtualSignal.GREEN && !pedestrian) {
                    greenStartDistance = distance
                    advance(RidePhase.RESUME_AFTER_GREEN, RideCoach.GREEN)
                } else advance(RidePhase.APPROACH_CROSSWALK, if (pedestrian) RideCoach.CROSS_APPROACH else RideCoach.RED_WAIT)
            }
            value == VirtualGear.P -> {
                gear = value
                if (lesson == RideLesson.START_STOP && phase == RidePhase.STOP_AND_PARK) {
                    observed += "정지 후 브레이크를 유지하며 P 선택"
                    complete(RideCoach.START_COMPLETE)
                } else if (lesson == RideLesson.START_STOP) {
                    advance(RidePhase.READY_DRIVE, RideCoach.DRIVE)
                } else {
                    // Parking early is allowed but does not complete the signal exercise.
                    waiting = 0.0
                    inform(RideCoach.DRIVE)
                }
            }
        }
        return snapshot()
    }

    fun tick(deltaSeconds: Double): RideSnapshot {
        if (isComplete() || !deltaSeconds.isFinite() || deltaSeconds <= 0.0) return snapshot()
        val dt = min(deltaSeconds, 0.25)
        elapsed += dt
        val previousSpeed = speed
        val canMove = gear == VirtualGear.D
        speed = when {
            brake -> max(0.0, speed - 6.0 * dt)
            canMove && gas -> min(8.0, speed + 2.0 * dt)
            else -> max(0.0, speed - 1.4 * dt)
        }
        if (gear == VirtualGear.P) speed = 0.0
        distance += (previousSpeed + speed) * 0.5 / 3.6 * dt
        if (canMove && speed > 0.0) {
            laneOffset += steering * (speed / 8.0) * dt * 0.12
            laneOffset = laneOffset.coerceIn(-1.0, 1.0)
            if (gas && !brake) observed += "가속과 가상 차량의 움직임 확인"
        }
        if (abs(laneOffset) >= 0.95) {
            retry(RideCoach.RETRY_LANE)
            return snapshot()
        }
        if (abs(laneOffset) >= 0.35 && !laneWarned) {
            laneWarned = true
            intervene("lane", RideCoach.LANE)
        } else if (abs(laneOffset) < 0.20) {
            laneWarned = false
            if (lastIntervention == "lane") lastIntervention = null
        }
        if (lesson == RideLesson.START_STOP) updateStartStop() else updateCrosswalk(dt)
        return snapshot()
    }

    private fun updateStartStop() {
        if (distance > stopLine && phase != RidePhase.COMPLETED) {
            retry(RideCoach.RETRY_LINE)
            return
        }
        if (phase == RidePhase.STOP_AND_PARK && !stopped()) {
            phase = RidePhase.DRIVE_TO_LINE
            intervene("keep_stopped", RideCoach.KEEP_STOPPED)
        }
        if (phase != RidePhase.DRIVE_TO_LINE) return
        if (!stopPrompted && !laneWarned && distance >= stopLine - 16.0) {
            stopPrompted = true
            inform(RideCoach.STOP_LINE)
        }
        if (brake && !gas && stopped()) {
            if (distance >= stopLine - 16.0) {
                observed += "가상 목표선 전에 브레이크로 정지"
                advance(RidePhase.STOP_AND_PARK, RideCoach.PARK)
            } else if (distance >= 1.0 && !earlyStopPrompted) {
                earlyStopPrompted = true
                inform(RideCoach.EARLY_STOP)
            }
        }
    }

    private fun updateCrosswalk(dt: Double) {
        if (signal != VirtualSignal.GREEN && distance > stopLine) {
            retry(RideCoach.RETRY_LINE)
            return
        }
        if (phase == RidePhase.APPROACH_CROSSWALK && stopped() && brake && !gas) {
            observed += "가상 적신호와 보행자 앞에서 브레이크로 정지"
            waiting = 0.0
            advance(if (pedestrian) RidePhase.WAIT_FOR_PEDESTRIAN else RidePhase.WAIT_FOR_GREEN,
                if (pedestrian) RideCoach.PEDESTRIAN_WAIT else RideCoach.RED_WAIT)
        }
        if (phase in WAIT_PHASES) {
            if (stopped() && brake && !gas && gear == VirtualGear.D) {
                waiting += dt
                if (phase == RidePhase.WAIT_FOR_PEDESTRIAN && waiting >= 6.0) {
                    pedestrian = false
                    waiting = 0.0
                    observed += "가상 보행자가 벗어날 때까지 정지 유지"
                    advance(RidePhase.WAIT_FOR_GREEN, RideCoach.RED_WAIT)
                } else if (phase == RidePhase.WAIT_FOR_GREEN && waiting >= 4.0) {
                    signal = VirtualSignal.GREEN
                    waiting = 0.0
                    greenStartDistance = distance
                    observed += "보행자가 없어도 가상 적신호 동안 대기"
                    advance(RidePhase.RESUME_AFTER_GREEN, RideCoach.GREEN)
                }
            }
        } else if (phase == RidePhase.RESUME_AFTER_GREEN && !pedestrian && signal == VirtualSignal.GREEN &&
            gas && !brake && gear == VirtualGear.D && speed >= 2.0 && distance - greenStartDistance >= 3.0) {
            observed += "가상 녹색 신호 이후 직접 가속해 출발"
            complete(RideCoach.CROSS_COMPLETE)
        }
    }

    private fun retry(message: CoachCue) {
        retries++
        interventions++
        distance = 0.0
        speed = 0.0
        laneOffset = 0.0
        steering = 0.0
        // A retry never resumes a pedal held before the intervention; require fresh input.
        gas = false
        brake = false
        gear = if (lesson == RideLesson.START_STOP) VirtualGear.P else VirtualGear.D
        phase = if (lesson == RideLesson.START_STOP) RidePhase.READY_BRAKE else RidePhase.APPROACH_CROSSWALK
        signal = if (lesson == RideLesson.CROSSWALK) VirtualSignal.RED else VirtualSignal.NONE
        pedestrian = lesson == RideLesson.CROSSWALK
        waiting = 0.0
        stopPrompted = false
        earlyStopPrompted = false
        laneWarned = false
        lastIntervention = null
        cue = message
        revision++
    }

    private fun advance(next: RidePhase, message: CoachCue) {
        phase = next
        lastIntervention = null
        inform(message)
    }

    private fun complete(message: CoachCue) {
        phase = RidePhase.COMPLETED
        gas = false
        brake = false
        inform(message)
    }

    private fun intervene(key: String, message: CoachCue) {
        if (lastIntervention == key) return
        lastIntervention = key
        interventions++
        // A recovered-then-repeated incident is a new coaching event even if its
        // words match the previous cue. Continuous identical input stays deduped above.
        if (cue.id == message.id) revision++ else inform(message)
    }

    private fun inform(message: CoachCue) {
        if (cue.id == message.id) return
        cue = message
        revision++
    }

    private fun isComplete() = phase == RidePhase.COMPLETED
    private fun stopped() = speed <= 0.001
    private companion object {
        val WAIT_PHASES = setOf(RidePhase.WAIT_FOR_PEDESTRIAN, RidePhase.WAIT_FOR_GREEN)
    }
}
