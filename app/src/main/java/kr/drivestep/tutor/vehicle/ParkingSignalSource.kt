package kr.drivestep.tutor.vehicle

enum class ParkingGear { P, R, N, D, UNKNOWN }

enum class ParkingSignalField { GEAR, STEERING, SPEED, BRAKE, PARKING_BRAKE }

/**
 * Exactly one field is updated per reading. Ignore values for other fields: they are
 * defaults, not a synchronized vehicle snapshot. Keep freshness separately for each field.
 */
data class ParkingSignalReading(
    val field: ParkingSignalField,
    val gear: ParkingGear = ParkingGear.UNKNOWN,
    /** Front-wheel/bicycle-model angle; degrees, left negative. NOT steering-wheel rotation. */
    val angleDegrees: Double? = null,
    /** Actual vehicle speed in m/s; reverse can be negative. Never a cruise-control target. */
    val speedMps: Double? = null,
    val brakePressed: Boolean? = null,
    val parkingBrakeOn: Boolean? = null,
    /** Local receipt wall clock for display/logging; not a hardware measurement timestamp. */
    val observedAtMillis: Long,
    /** Local elapsedRealtime receipt time for connection freshness, unaffected by wall-clock changes. */
    val receivedAtElapsedMillis: Long,
    /** AAOS measurement timestamp since boot. Pleos SDK callbacks do not expose this. */
    val sourceTimestampNanos: Long? = null,
    /** All GETs and the first callback for each subscription are baselines, never completed actions. */
    val isInitial: Boolean,
    /** Separates GET baselines from the first subscription baseline. */
    val isSubscription: Boolean = !isInitial,
)

interface ParkingSignalSource {
    /** Callbacks run on main. READY means required data was received, not permission to drive. */
    fun start(onState: (ParkingSignalReading) -> Unit, onStatus: (SourceStatus) -> Unit)
    fun stop()
}
