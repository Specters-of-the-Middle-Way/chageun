package kr.drivestep.tutor.practice

import kr.drivestep.tutor.vehicle.ParkingGear
import kr.drivestep.tutor.vehicle.ParkingSignalField
import kr.drivestep.tutor.vehicle.ParkingSignalReading
import kotlin.math.abs

enum class SteeringPracticeStage {
    WAIT_SAFE_INPUTS, WAIT_CENTER_BASELINE, WAIT_LEFT, WAIT_CENTER_AFTER_LEFT,
    WAIT_RIGHT, WAIT_CENTER_AFTER_RIGHT, WAIT_CENTER_RETRY_LEFT, WAIT_CENTER_RETRY_RIGHT,
    COMPLETED, INTERRUPTED,
}

enum class SteeringPracticeAction { WAIT_FOR_SIGNALS, CENTER, LEFT, RIGHT, NONE }

/** Emulator observation bands, not recommended physical steering angles or a safe driving speed. */
data class SteeringPracticeConfig(
    val centerBandDegrees: Double = 2.0,
    val directionThresholdDegrees: Double = 5.0,
    val zeroSpeedToleranceMps: Double = 0.01,
    val freshnessMillis: Long = 5_000L,
    val maxEvents: Int = 128,
) {
    init {
        require(centerBandDegrees.isFinite() && centerBandDegrees >= 0.0)
        require(directionThresholdDegrees.isFinite() && directionThresholdDegrees > centerBandDegrees)
        require(zeroSpeedToleranceMps.isFinite() && zeroSpeedToleranceMps >= 0.0)
        require(freshnessMillis > 0 && maxEvents > 0)
    }
}

data class SteeringPracticeEvent(
    val sequence: Long,
    val atElapsedMillis: Long,
    val observedAtMillis: Long?,
    val field: ParkingSignalField?,
    val angleDegrees: Double?,
    val stageBefore: SteeringPracticeStage,
    val stageAfter: SteeringPracticeStage,
    val feedbackKey: String,
)

data class SteeringPracticeSnapshot(
    val id: String,
    val startedAtMillis: Long,
    val stage: SteeringPracticeStage,
    val outcome: PracticeOutcome,
    val nextAction: SteeringPracticeAction,
    val completedSteps: Int,
    val latestFeedbackKey: String?,
    val feedbackRevision: Long,
    val events: List<SteeringPracticeEvent>,
    val interruptionReason: String?,
    val gear: ParkingGear,
    val speedMps: Double?,
    val angleDegrees: Double?,
) {
    val totalSteps: Int get() = 4
    val isTerminal: Boolean get() = outcome != PracticeOutcome.ACTIVE
}

/**
 * Read-only, stationary preparation exercise. Call from one thread.
 *
 * P, effectively-zero simulated speed and a subscription center baseline prepare the exercise;
 * only subsequent changed subscription samples complete LEFT -> CENTER -> RIGHT -> CENTER.
 * GETs update displayed values/freshness only; they never reset or advance a prepared
 * exercise. First subscription callbacks establish a baseline, never an action.
 * Subscription changes are compared with the previous subscription, not a racing GET.
 * Unknown values
 * may wait during initial connection; after preparation they interrupt the session.
 * Continuous SPEED also requires a positive AAOS measurement timestamp (elapsed
 * realtime nanoseconds). Missing/zero timestamps wait before preparation and stop
 * a prepared session. Re-reading the same measurement never extends its freshness.
 * ON_CHANGE gear timestamps may be old; only their local receipt freshness applies.
 * The caller must also interrupt on UX restriction, source disconnection or screen exit.
 * Never restore a live instance from a stored snapshot or resume after reconnection.
 */
class SteeringPracticeSession(
    private val id: String,
    private val startedAtMillis: Long,
    private val config: SteeringPracticeConfig = SteeringPracticeConfig(),
) {
    private enum class Zone { CENTER, LEFT, RIGHT, BETWEEN }

    private var stage = SteeringPracticeStage.WAIT_SAFE_INPUTS
    private var completedSteps = 0
    private var prepared = false
    private var gear = ParkingGear.UNKNOWN
    private var speedMps: Double? = null
    private var angleDegrees: Double? = null
    private var lastSubscriptionAngle: Double? = null
    private var lastSubscriptionZone: Zone? = null
    private val receivedTimes = mutableMapOf<ParkingSignalField, Long>()
    private val sourceTimes = mutableMapOf<ParkingSignalField, Long>()
    private var latestNow = 0L
    private var interruptionReason: String? = null
    private var feedbackKey: String? = "steering_wait_inputs"
    private var feedbackRevision = 0L
    private val events = ArrayDeque<SteeringPracticeEvent>()

    init {
        require(id.isNotBlank())
        require(startedAtMillis >= 0)
    }

    fun accept(reading: ParkingSignalReading, nowElapsedMillis: Long): SteeringPracticeSnapshot {
        if (isTerminal()) return snapshot()
        checkFreshness(nowElapsedMillis)
        if (isTerminal()) return snapshot()
        val at = reading.receivedAtElapsedMillis
        if (at < 0 || at > latestNow) return interrupt("INVALID_TIMESTAMP", latestNow)
        val previousAt = receivedTimes[reading.field]
        val sourceAt = reading.sourceTimestampNanos
        val previousSourceAt = sourceTimes[reading.field]
        if (previousAt != null && at < previousAt) return snapshot()
        if (reading.field == ParkingSignalField.SPEED && (sourceAt == null || sourceAt <= 0)) {
            receivedTimes[ParkingSignalField.SPEED] = at
            sourceTimes.remove(ParkingSignalField.SPEED)
            speedMps = null
            return if (prepared) interrupt("SPEED_TIMESTAMP_UNKNOWN", latestNow) else snapshot()
        }
        if (sourceAt != null && previousSourceAt != null && sourceAt < previousSourceAt) return snapshot()
        if (latestNow - at >= config.freshnessMillis && reading.field in REQUIRED_FIELDS) {
            return interrupt("SIGNAL_STALE_${reading.field.name}", latestNow)
        }
        if (reading.field == ParkingSignalField.SPEED) {
            val measuredAtMillis = sourceAt!! / NANOS_PER_MILLISECOND
            if (measuredAtMillis > latestNow) return interrupt("SPEED_TIMESTAMP_INVALID", latestNow)
            if (latestNow - measuredAtMillis >= config.freshnessMillis) {
                return interrupt("SIGNAL_STALE_SPEED_MEASUREMENT", latestNow)
            }
        }
        val previousSubscriptionAngle = lastSubscriptionAngle
        val previousSubscriptionZone = lastSubscriptionZone
        receivedTimes[reading.field] = at
        if (sourceAt != null) sourceTimes[reading.field] = sourceAt
        when (reading.field) {
            ParkingSignalField.GEAR -> gear = reading.gear
            ParkingSignalField.SPEED -> speedMps = reading.speedMps?.takeIf { it.isFinite() }
            ParkingSignalField.STEERING -> angleDegrees = reading.angleDegrees?.takeIf { it.isFinite() }
            ParkingSignalField.BRAKE, ParkingSignalField.PARKING_BRAKE -> Unit
        }
        val steeringSubscription = reading.field == ParkingSignalField.STEERING && reading.isSubscription
        if (steeringSubscription) {
            lastSubscriptionAngle = angleDegrees
            lastSubscriptionZone = angleDegrees?.let(::zone)
        }

        if (gear != ParkingGear.UNKNOWN && gear != ParkingGear.P) return interrupt("GEAR_NOT_P", latestNow)
        if (speedMps != null && abs(speedMps!!) > config.zeroSpeedToleranceMps) return interrupt("VEHICLE_MOVING", latestNow)
        val valid = gear == ParkingGear.P && speedMps != null && angleDegrees != null
        if (!valid) {
            if (prepared) return interrupt("SIGNAL_UNKNOWN_${reading.field.name}", latestNow)
            return snapshot()
        }
        prepared = true
        if (stage == SteeringPracticeStage.WAIT_SAFE_INPUTS) {
            // GET readiness does not authorize a LEFT prompt: wait until the actual
            // subscription supplies its initial center (or requests center recovery).
            val baselineZone = lastSubscriptionZone ?: return snapshot()
            if (baselineZone == Zone.CENTER) {
                transition(SteeringPracticeStage.WAIT_LEFT, 0, "steering_left", reading)
            } else {
                transition(SteeringPracticeStage.WAIT_CENTER_BASELINE, 0, "steering_center_start", reading)
            }
            return snapshot()
        }
        if (!steeringSubscription) return snapshot()
        val currentZone = lastSubscriptionZone!!

        // Only a subscription can establish this initial center. It is still 0/4.
        if (stage == SteeringPracticeStage.WAIT_CENTER_BASELINE && currentZone == Zone.CENTER) {
            transition(SteeringPracticeStage.WAIT_LEFT, 0, "steering_left", reading)
            return snapshot()
        }
        if (reading.isInitial || previousSubscriptionAngle == null ||
            previousSubscriptionAngle == lastSubscriptionAngle || previousSubscriptionZone == currentZone
        ) return snapshot()

        when (stage) {
            SteeringPracticeStage.WAIT_LEFT -> when (currentZone) {
                Zone.LEFT -> transition(SteeringPracticeStage.WAIT_CENTER_AFTER_LEFT, 1, "steering_left_confirmed", reading)
                Zone.RIGHT -> retry(left = true, reading, "steering_wrong_right")
                else -> Unit
            }
            SteeringPracticeStage.WAIT_CENTER_AFTER_LEFT -> when (currentZone) {
                Zone.CENTER -> transition(SteeringPracticeStage.WAIT_RIGHT, 2, "steering_right", reading)
                Zone.RIGHT -> retry(left = true, reading, "steering_wrong_right")
                else -> Unit
            }
            SteeringPracticeStage.WAIT_RIGHT -> when (currentZone) {
                Zone.RIGHT -> transition(SteeringPracticeStage.WAIT_CENTER_AFTER_RIGHT, 3, "steering_right_confirmed", reading)
                Zone.LEFT -> retry(left = false, reading, "steering_wrong_left")
                else -> Unit
            }
            SteeringPracticeStage.WAIT_CENTER_AFTER_RIGHT -> when (currentZone) {
                Zone.CENTER -> transition(SteeringPracticeStage.COMPLETED, 4, "steering_complete", reading)
                Zone.LEFT -> retry(left = false, reading, "steering_wrong_left")
                else -> Unit
            }
            SteeringPracticeStage.WAIT_CENTER_RETRY_LEFT -> if (currentZone == Zone.CENTER) {
                transition(SteeringPracticeStage.WAIT_LEFT, 0, "steering_retry_left", reading)
            }
            SteeringPracticeStage.WAIT_CENTER_RETRY_RIGHT -> if (currentZone == Zone.CENTER) {
                transition(SteeringPracticeStage.WAIT_RIGHT, 2, "steering_retry_right", reading)
            }
            else -> Unit
        }
        return snapshot()
    }

    /** A timeout may interrupt; elapsed time can never complete an action. */
    fun checkFreshness(nowElapsedMillis: Long): SteeringPracticeSnapshot {
        if (isTerminal()) return snapshot()
        require(nowElapsedMillis >= 0)
        latestNow = maxOf(latestNow, nowElapsedMillis)
        val stale = REQUIRED_FIELDS.firstOrNull { field ->
            receivedTimes[field]?.let { latestNow - it >= config.freshnessMillis } == true
        }
        if (stale != null) return interrupt("SIGNAL_STALE_${stale.name}", latestNow)
        val speedMeasuredAtMillis = sourceTimes[ParkingSignalField.SPEED]?.div(NANOS_PER_MILLISECOND)
        if (speedMeasuredAtMillis != null && latestNow - speedMeasuredAtMillis >= config.freshnessMillis) {
            return interrupt("SIGNAL_STALE_SPEED_MEASUREMENT", latestNow)
        }
        return snapshot()
    }

    fun interrupt(reason: String, nowElapsedMillis: Long): SteeringPracticeSnapshot {
        if (isTerminal()) return snapshot()
        require(reason.isNotBlank() && nowElapsedMillis >= 0)
        latestNow = maxOf(latestNow, nowElapsedMillis)
        interruptionReason = reason
        transition(SteeringPracticeStage.INTERRUPTED, completedSteps, "steering_interrupted", null)
        return snapshot()
    }

    fun snapshot(): SteeringPracticeSnapshot = SteeringPracticeSnapshot(
        id = id, startedAtMillis = startedAtMillis, stage = stage,
        outcome = when (stage) {
            SteeringPracticeStage.COMPLETED -> PracticeOutcome.COMPLETED
            SteeringPracticeStage.INTERRUPTED -> PracticeOutcome.INTERRUPTED
            else -> PracticeOutcome.ACTIVE
        },
        nextAction = when (stage) {
            SteeringPracticeStage.WAIT_SAFE_INPUTS -> SteeringPracticeAction.WAIT_FOR_SIGNALS
            SteeringPracticeStage.WAIT_LEFT -> SteeringPracticeAction.LEFT
            SteeringPracticeStage.WAIT_RIGHT -> SteeringPracticeAction.RIGHT
            SteeringPracticeStage.COMPLETED, SteeringPracticeStage.INTERRUPTED -> SteeringPracticeAction.NONE
            else -> SteeringPracticeAction.CENTER
        },
        completedSteps = completedSteps, latestFeedbackKey = feedbackKey,
        feedbackRevision = feedbackRevision, events = events.toList(), interruptionReason = interruptionReason,
        gear = gear, speedMps = speedMps, angleDegrees = angleDegrees,
    )

    private fun retry(left: Boolean, reading: ParkingSignalReading, key: String) {
        transition(
            if (left) SteeringPracticeStage.WAIT_CENTER_RETRY_LEFT else SteeringPracticeStage.WAIT_CENTER_RETRY_RIGHT,
            if (left) 0 else 2, key, reading,
        )
    }

    private fun transition(next: SteeringPracticeStage, steps: Int, key: String, reading: ParkingSignalReading?) {
        if (stage == next && completedSteps == steps && feedbackKey == key) return
        val before = stage
        stage = next
        completedSteps = steps
        feedbackKey = key
        feedbackRevision++
        if (events.size == config.maxEvents) events.removeFirst()
        events.addLast(SteeringPracticeEvent(
            feedbackRevision, latestNow, reading?.observedAtMillis, reading?.field,
            angleDegrees, before, next, key,
        ))
    }

    private fun zone(angle: Double): Zone = when {
        abs(angle) <= config.centerBandDegrees -> Zone.CENTER
        angle <= -config.directionThresholdDegrees -> Zone.LEFT
        angle >= config.directionThresholdDegrees -> Zone.RIGHT
        else -> Zone.BETWEEN
    }

    private fun isTerminal() = stage == SteeringPracticeStage.COMPLETED || stage == SteeringPracticeStage.INTERRUPTED

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val REQUIRED_FIELDS = listOf(ParkingSignalField.GEAR, ParkingSignalField.SPEED, ParkingSignalField.STEERING)
    }
}
