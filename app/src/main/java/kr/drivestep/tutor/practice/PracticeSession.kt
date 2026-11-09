package kr.drivestep.tutor.practice

/** A logical lamp state, not an individual flash of the indicator bulb. */
enum class SignalPattern { OFF, LEFT, RIGHT, BOTH, UNKNOWN }

enum class PracticeStage {
    WAIT_BASELINE_OFF,
    WAIT_LEFT_ON,
    WAIT_LEFT_OFF,
    WAIT_RIGHT_ON,
    WAIT_RIGHT_OFF,
    COMPLETED,
    INTERRUPTED,
}

enum class PracticeOutcome { ACTIVE, COMPLETED, INTERRUPTED }

enum class PracticeAction {
    TURN_OFF_ALL,
    TURN_LEFT_ON,
    TURN_LEFT_OFF,
    TURN_RIGHT_ON,
    TURN_RIGHT_OFF,
    NONE,
}

enum class ObservationDisposition {
    ADVANCED,
    DUPLICATE,
    WAITING,
    CORRECTION_REQUIRED,
    OUT_OF_ORDER,
    INVALID_SIGNAL,
}

data class SignalObservation(
    val pattern: SignalPattern,
    val observedAtMillis: Long,
    val stageBefore: PracticeStage,
    val stageAfter: PracticeStage,
    val disposition: ObservationDisposition,
)

data class PracticeSnapshot(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val stage: PracticeStage,
    val outcome: PracticeOutcome,
    val nextAction: PracticeAction,
    val completedStepCount: Int,
    val lastSignal: SignalPattern?,
    val events: List<SignalObservation>,
    val interruptionReason: String?,
) {
    val isTerminal: Boolean get() = outcome != PracticeOutcome.ACTIVE
    val totalStepCount: Int get() = 5
}

/**
 * One stationary indicator exercise. Call methods from a single thread (the UI thread).
 *
 * Completion requires five observed states: OFF -> LEFT -> OFF -> RIGHT -> OFF.
 * Existing ON states, elapsed time, duplicate callbacks and unknown values never
 * complete a step. If a wrong direction is used, a fresh OFF baseline is required
 * before the requested direction can count again.
 *
 * Signal loss, source reconnection, screen leaving the safe parked mode, and process
 * recreation must interrupt the session in the app layer. Start a new session after
 * reconnection; do not restore a live session from an old snapshot.
 */
class PracticeSession(
    private val id: String,
    private val startedAtMillis: Long,
) {
    private var stage = PracticeStage.WAIT_BASELINE_OFF
    private var completedStepCount = 0
    private var lastSignal: SignalPattern? = null
    private var lastAcceptedAtMillis: Long? = null
    private var targetArmed = false
    private var endedAtMillis: Long? = null
    private var interruptionReason: String? = null
    private val events = mutableListOf<SignalObservation>()

    init {
        require(id.isNotBlank()) { "Session id must not be blank" }
        require(startedAtMillis >= 0) { "Start time must not be negative" }
    }

    fun accept(pattern: SignalPattern, observedAtMillis: Long): PracticeSnapshot {
        if (isTerminal()) return snapshot()
        val before = stage
        val lastAt = lastAcceptedAtMillis
        if (observedAtMillis < startedAtMillis || (lastAt != null && observedAtMillis < lastAt)) {
            record(pattern, observedAtMillis, before, ObservationDisposition.OUT_OF_ORDER)
            return snapshot()
        }
        lastAcceptedAtMillis = observedAtMillis

        if (pattern == SignalPattern.UNKNOWN || pattern == SignalPattern.BOTH) {
            lastSignal = pattern
            interrupt(
                if (pattern == SignalPattern.UNKNOWN) "SIGNAL_UNKNOWN" else "BOTH_INDICATORS_ACTIVE",
                observedAtMillis,
            )
            record(pattern, observedAtMillis, before, ObservationDisposition.INVALID_SIGNAL)
            return snapshot()
        }
        if (pattern == lastSignal) {
            record(pattern, observedAtMillis, before, ObservationDisposition.DUPLICATE)
            return snapshot()
        }
        lastSignal = pattern

        val disposition = when (stage) {
            PracticeStage.WAIT_BASELINE_OFF -> {
                if (pattern == SignalPattern.OFF) {
                    advanceTo(PracticeStage.WAIT_LEFT_ON, 1)
                    targetArmed = true
                    ObservationDisposition.ADVANCED
                } else ObservationDisposition.WAITING
            }
            PracticeStage.WAIT_LEFT_ON -> awaitTarget(pattern, SignalPattern.LEFT, PracticeStage.WAIT_LEFT_OFF, 2)
            PracticeStage.WAIT_LEFT_OFF -> awaitOff(pattern, SignalPattern.LEFT, PracticeStage.WAIT_LEFT_ON, 1, PracticeStage.WAIT_RIGHT_ON, 3)
            PracticeStage.WAIT_RIGHT_ON -> awaitTarget(pattern, SignalPattern.RIGHT, PracticeStage.WAIT_RIGHT_OFF, 4)
            PracticeStage.WAIT_RIGHT_OFF -> {
                val result = awaitOff(pattern, SignalPattern.RIGHT, PracticeStage.WAIT_RIGHT_ON, 3, PracticeStage.COMPLETED, 5)
                if (stage == PracticeStage.COMPLETED) endedAtMillis = observedAtMillis
                result
            }
            PracticeStage.COMPLETED, PracticeStage.INTERRUPTED -> ObservationDisposition.WAITING
        }
        record(pattern, observedAtMillis, before, disposition)
        return snapshot()
    }

    fun interrupt(reason: String, atMillis: Long): PracticeSnapshot {
        if (isTerminal()) return snapshot()
        require(reason.isNotBlank()) { "An interruption needs a reason" }
        interruptionReason = reason
        endedAtMillis = maxOf(startedAtMillis, lastAcceptedAtMillis ?: startedAtMillis, atMillis)
        stage = PracticeStage.INTERRUPTED
        return snapshot()
    }

    fun snapshot(): PracticeSnapshot = PracticeSnapshot(
        id = id,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        stage = stage,
        outcome = when (stage) {
            PracticeStage.COMPLETED -> PracticeOutcome.COMPLETED
            PracticeStage.INTERRUPTED -> PracticeOutcome.INTERRUPTED
            else -> PracticeOutcome.ACTIVE
        },
        nextAction = when (stage) {
            PracticeStage.WAIT_BASELINE_OFF -> PracticeAction.TURN_OFF_ALL
            PracticeStage.WAIT_LEFT_ON -> if (targetArmed) PracticeAction.TURN_LEFT_ON else PracticeAction.TURN_OFF_ALL
            PracticeStage.WAIT_LEFT_OFF -> PracticeAction.TURN_LEFT_OFF
            PracticeStage.WAIT_RIGHT_ON -> if (targetArmed) PracticeAction.TURN_RIGHT_ON else PracticeAction.TURN_OFF_ALL
            PracticeStage.WAIT_RIGHT_OFF -> PracticeAction.TURN_RIGHT_OFF
            PracticeStage.COMPLETED, PracticeStage.INTERRUPTED -> PracticeAction.NONE
        },
        completedStepCount = completedStepCount,
        lastSignal = lastSignal,
        events = events.toList(),
        interruptionReason = interruptionReason,
    )

    private fun awaitTarget(pattern: SignalPattern, target: SignalPattern, next: PracticeStage, completed: Int): ObservationDisposition {
        if (pattern == SignalPattern.OFF) {
            targetArmed = true
            return ObservationDisposition.WAITING
        }
        if (pattern == target && targetArmed) {
            targetArmed = false
            advanceTo(next, completed)
            return ObservationDisposition.ADVANCED
        }
        targetArmed = false
        return ObservationDisposition.CORRECTION_REQUIRED
    }

    private fun awaitOff(
        pattern: SignalPattern,
        expectedActive: SignalPattern,
        retryStage: PracticeStage,
        retryCompleted: Int,
        next: PracticeStage,
        completed: Int,
    ): ObservationDisposition {
        if (pattern == SignalPattern.OFF) {
            targetArmed = true
            advanceTo(next, completed)
            return ObservationDisposition.ADVANCED
        }
        if (pattern != expectedActive) {
            targetArmed = false
            advanceTo(retryStage, retryCompleted)
            return ObservationDisposition.CORRECTION_REQUIRED
        }
        return ObservationDisposition.WAITING
    }

    private fun advanceTo(next: PracticeStage, completed: Int) {
        stage = next
        completedStepCount = completed
    }

    private fun record(pattern: SignalPattern, time: Long, before: PracticeStage, disposition: ObservationDisposition) {
        events += SignalObservation(pattern, time, before, stage, disposition)
    }

    private fun isTerminal(): Boolean = stage == PracticeStage.COMPLETED || stage == PracticeStage.INTERRUPTED
}
