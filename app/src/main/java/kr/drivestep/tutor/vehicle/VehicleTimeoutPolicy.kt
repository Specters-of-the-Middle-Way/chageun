package kr.drivestep.tutor.vehicle

/**
 * Two-tier vehicle I/O timeout policy separated by vehicle operating context.
 *
 * - Tier 1 (Parked / Initial Service Connection): While stationary in P, initial service
 *   binding and capability inspection allow up to [PARKED_CONNECT_TIMEOUT_MS] (10s) to tolerate
 *   cold binder initialization on the emulator or head unit.
 * - Tier 2 (Active Practice / Driving / Parking Maneuver): Once a session is active,
 *   per-read deadlines and signal freshness windows are kept short ([ACTIVE_READ_TIMEOUT_MS] = 2.5s,
 *   [DRIVING_SITUATION_FRESHNESS_MS] = 1.5s) so delayed or stale signals fail-safe immediately
 *   without leaving the driver waiting during an active maneuver.
 */
object VehicleTimeoutPolicy {
    /** Tier 1: Initial Vehicle SDK / Car service connection and capability lookup while parked (P). */
    const val PARKED_CONNECT_TIMEOUT_MS = 10_000L

    /** Tier 2: Active signal snapshot read deadline during practice or parking maneuvers. */
    const val ACTIVE_READ_TIMEOUT_MS = 2_500L

    /** Tier 2: Periodic health-check interval for multi-channel parking/steering signals. */
    const val ACTIVE_HEARTBEAT_MS = 1_000L

    /** Tier 2: Periodic snapshot interval for turn-signal practice health checks. */
    const val TURN_SIGNAL_HEARTBEAT_MS = 3_000L

    /** Tier 2: Recommended signal freshness window during active practice maneuvers (3.0s). */
    const val ACTIVE_PRACTICE_FRESHNESS_MS = 3_000L

    /** Tier 2: Maximum signal age accepted by the real-time driving situation coach (1.5s). */
    const val DRIVING_SITUATION_FRESHNESS_MS = 1_500L
}
