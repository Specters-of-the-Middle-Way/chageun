package kr.drivestep.tutor.practice

import kr.drivestep.tutor.coach.SteeringCoach
import kr.drivestep.tutor.vehicle.ParkingGear
import kr.drivestep.tutor.vehicle.ParkingSignalField
import kr.drivestep.tutor.vehicle.ParkingSignalReading
import org.junit.Assert.*
import org.junit.Test

class SteeringPracticeSessionTest {
    private var clock = 10L
    private fun session(config: SteeringPracticeConfig = SteeringPracticeConfig()) = SteeringPracticeSession("test", 100_000L, config)

    private fun reading(
        field: ParkingSignalField,
        initial: Boolean = false,
        angle: Double? = null,
        gear: ParkingGear = ParkingGear.UNKNOWN,
        speed: Double? = null,
        at: Long = ++clock,
        source: Long? = if (field == ParkingSignalField.SPEED) at * 1_000_000L else null,
        subscription: Boolean = !initial,
    ) = ParkingSignalReading(
        field, gear = gear, angleDegrees = angle, speedMps = speed,
        observedAtMillis = 100_000L + at, receivedAtElapsedMillis = at,
        sourceTimestampNanos = source, isInitial = initial, isSubscription = subscription,
    )

    private fun feed(session: SteeringPracticeSession, reading: ParkingSignalReading) =
        session.accept(reading, maxOf(clock, reading.receivedAtElapsedMillis))

    private fun prepare(session: SteeringPracticeSession, angle: Double = 0.0): SteeringPracticeSnapshot {
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
        feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0))
        steer(session, angle, initial = true)
        return steer(session, angle, initial = true, subscription = true)
    }

    private fun steer(session: SteeringPracticeSession, angle: Double?, initial: Boolean = false, subscription: Boolean = !initial) =
        feed(session, reading(ParkingSignalField.STEERING, initial = initial, angle = angle, subscription = subscription))

    @Test fun `fresh P zero and center prepare but four actual changes complete`() {
        val session = session()
        val baseline = prepare(session)
        assertEquals(0, baseline.completedSteps)
        assertEquals(SteeringPracticeAction.LEFT, baseline.nextAction)
        assertEquals(SteeringCoach.LEFT, SteeringCoach.onEvent(baseline))
        assertEquals(1, steer(session, -5.0).completedSteps)
        assertEquals(2, steer(session, 0.0).completedSteps)
        assertEquals(3, steer(session, 5.0).completedSteps)
        val result = steer(session, 0.0)
        assertEquals(PracticeOutcome.COMPLETED, result.outcome)
        assertEquals(4, result.completedSteps)
        assertEquals(SteeringCoach.COMPLETE, SteeringCoach.onEvent(result))
        assertEquals(result, steer(session, -10.0))
    }

    @Test fun `initial left is not an action and requires a center baseline`() {
        val session = session()
        val initial = prepare(session, -8.0)
        assertEquals(SteeringPracticeStage.WAIT_CENTER_BASELINE, initial.stage)
        assertEquals(0, steer(session, -9.0).completedSteps)
        assertEquals(SteeringPracticeStage.WAIT_CENTER_BASELINE, steer(session, 0.0, initial = true).stage)
        assertEquals(SteeringPracticeStage.WAIT_LEFT, steer(session, 0.0).stage)
        assertEquals(1, steer(session, -5.0).completedSteps)
    }

    @Test fun `initial right must also return to center before left`() {
        val session = session()
        prepare(session, 8.0)
        assertEquals(0, steer(session, -8.0).completedSteps)
        assertEquals(SteeringPracticeStage.WAIT_CENTER_BASELINE, session.snapshot().stage)
        steer(session, 0.0)
        assertEquals(1, steer(session, -6.0).completedSteps)
    }

    @Test fun `GET only sequence cannot complete or reset a prepared exercise`() {
        val session = session()
        prepare(session)
        val revision = session.snapshot().feedbackRevision
        listOf(-5.0, 0.0, 5.0, 0.0).forEach { steer(session, it, initial = true) }
        assertEquals(0, session.snapshot().completedSteps)
        assertEquals(PracticeOutcome.ACTIVE, session.snapshot().outcome)
        assertEquals(SteeringPracticeStage.WAIT_LEFT, session.snapshot().stage)
        assertEquals(revision, session.snapshot().feedbackRevision)
    }

    @Test fun `GET before each genuine callback cannot swallow the subscription transition`() {
        val session = session()
        prepare(session)
        listOf(-5.0, 0.0, 5.0, 0.0).forEachIndexed { index, angle ->
            val before = session.snapshot()
            val queried = steer(session, angle, initial = true)
            assertEquals(angle, queried.angleDegrees!!, 0.0)
            assertEquals(before.stage, queried.stage)
            assertEquals(before.feedbackRevision, queried.feedbackRevision)
            val changed = steer(session, angle)
            assertEquals(index + 1, changed.completedSteps)
            assertEquals(changed, steer(session, angle))
        }
        assertEquals(PracticeOutcome.COMPLETED, session.snapshot().outcome)
    }

    @Test fun `genuine callback before GET advances exactly once in either arrival order`() {
        val session = session()
        prepare(session)
        listOf(-5.0, 0.0, 5.0, 0.0).forEachIndexed { index, angle ->
            val changed = steer(session, angle)
            assertEquals(index + 1, changed.completedSteps)
            assertEquals(changed, steer(session, angle, initial = true))
            assertEquals(changed, steer(session, angle))
        }
        assertEquals(PracticeOutcome.COMPLETED, session.snapshot().outcome)
    }

    @Test fun `GET center cannot turn a duplicate left callback into a return or reset`() {
        val session = session()
        prepare(session)
        val left = steer(session, -5.0)
        val queried = steer(session, 0.0, initial = true)
        assertEquals(left.stage, queried.stage)
        assertEquals(left.feedbackRevision, queried.feedbackRevision)
        val duplicate = steer(session, -5.0)
        assertEquals(1, duplicate.completedSteps)
        assertEquals(left.feedbackRevision, duplicate.feedbackRevision)
        assertEquals(2, steer(session, 0.0).completedSteps)
    }

    @Test fun `GET readiness does not prompt left until subscription establishes center`() {
        val session = session()
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
        feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0))
        val queried = steer(session, 0.0, initial = true)
        assertEquals(SteeringPracticeStage.WAIT_SAFE_INPUTS, queried.stage)
        assertEquals(SteeringPracticeAction.WAIT_FOR_SIGNALS, queried.nextAction)
        assertNotEquals(SteeringCoach.LEFT.id, queried.latestFeedbackKey)
        val initial = steer(session, -5.0, initial = true, subscription = true)
        assertEquals(SteeringPracticeStage.WAIT_CENTER_BASELINE, initial.stage)
        assertEquals(0, initial.completedSteps)
        assertEquals(SteeringCoach.CENTER_START, SteeringCoach.onEvent(initial))
        assertEquals(SteeringPracticeStage.WAIT_CENTER_BASELINE, steer(session, 0.0, initial = true).stage)
        val centered = steer(session, 0.0)
        assertEquals(SteeringPracticeStage.WAIT_LEFT, centered.stage)
        assertEquals(0, centered.completedSteps)
        assertEquals(SteeringCoach.LEFT, SteeringCoach.onEvent(centered))
    }

    @Test fun `initial subscription center itself prepares but never counts as an action`() {
        val session = session()
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
        feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0))
        val centered = steer(session, 0.0, initial = true, subscription = true)
        assertEquals(SteeringPracticeStage.WAIT_LEFT, centered.stage)
        assertEquals(0, centered.completedSteps)
        assertEquals(centered, steer(session, 0.0))
    }

    @Test fun `high frequency duplicate and within zone callbacks do not advance or grow event log`() {
        val session = session()
        prepare(session)
        steer(session, -5.0)
        val eventCount = session.snapshot().events.size
        repeat(500) { steer(session, if (it % 2 == 0) -5.0 else -6.0) }
        assertEquals(1, session.snapshot().completedSteps)
        assertEquals(eventCount, session.snapshot().events.size)
        assertEquals(SteeringPracticeStage.WAIT_CENTER_AFTER_LEFT, session.snapshot().stage)
    }

    @Test fun `opposite direction requires center retry before target can count`() {
        val session = session()
        prepare(session)
        val wrong = steer(session, 5.0)
        assertEquals(SteeringCoach.WRONG_RIGHT, SteeringCoach.onEvent(wrong))
        assertEquals(SteeringPracticeAction.CENTER, wrong.nextAction)
        assertEquals(0, steer(session, -5.0).completedSteps)
        steer(session, 0.0)
        assertEquals(1, steer(session, -5.0).completedSteps)
    }

    @Test fun `left to right jump without observed center rolls back that half`() {
        val session = session()
        prepare(session)
        steer(session, -5.0)
        val wrong = steer(session, 5.0)
        assertEquals(0, wrong.completedSteps)
        assertEquals(SteeringPracticeStage.WAIT_CENTER_RETRY_LEFT, wrong.stage)
        steer(session, 0.0)
        steer(session, -5.0)
        steer(session, 0.0)
        steer(session, 5.0)
        val wrongRight = steer(session, -5.0)
        assertEquals(2, wrongRight.completedSteps)
        assertEquals(SteeringCoach.WRONG_LEFT, SteeringCoach.onEvent(wrongRight))
        steer(session, 0.0)
        steer(session, 5.0)
        assertEquals(PracticeOutcome.COMPLETED, steer(session, 0.0).outcome)
    }

    @Test fun `gear leaving P immediately interrupts independently of steering or UX callback`() {
        val session = session()
        prepare(session)
        val result = feed(session, reading(ParkingSignalField.GEAR, gear = ParkingGear.R))
        assertEquals("GEAR_NOT_P", result.interruptionReason)
        assertEquals(PracticeOutcome.INTERRUPTED, result.outcome)
        assertEquals(result, feed(session, reading(ParkingSignalField.GEAR, gear = ParkingGear.P)))
    }

    @Test fun `either speed direction above simulation tolerance interrupts`() {
        listOf(0.011, -0.011).forEach { speed ->
            val session = session()
            prepare(session)
            val result = feed(session, reading(ParkingSignalField.SPEED, speed = speed))
            assertEquals("VEHICLE_MOVING", result.interruptionReason)
        }
        val session = session()
        prepare(session)
        assertEquals(PracticeOutcome.ACTIVE, feed(session, reading(ParkingSignalField.SPEED, speed = 0.01)).outcome)
    }

    @Test fun `unknown may wait during startup but unknown and NaN stop a prepared session`() {
        val session = session()
        assertEquals(SteeringPracticeStage.WAIT_SAFE_INPUTS, steer(session, null, initial = true).stage)
        prepare(session)
        assertEquals("SIGNAL_UNKNOWN_STEERING", steer(session, Double.NaN).interruptionReason)
        val other = session()
        prepare(other)
        assertEquals("SIGNAL_UNKNOWN_GEAR", feed(other, reading(ParkingSignalField.GEAR)).interruptionReason)
        val speed = session()
        prepare(speed)
        assertEquals("SIGNAL_UNKNOWN_SPEED", feed(speed, reading(ParkingSignalField.SPEED, speed = Double.POSITIVE_INFINITY)).interruptionReason)
    }

    @Test fun `new steering cannot refresh stale gear and speed`() {
        val session = session()
        prepare(session)
        clock = 4_900
        steer(session, -5.0)
        val result = session.checkFreshness(5_011)
        assertEquals("SIGNAL_STALE_GEAR", result.interruptionReason)
        assertEquals(1, result.completedSteps)
    }

    @Test fun `GET heartbeats refresh safety without completing pending return`() {
        val session = session()
        prepare(session)
        steer(session, -5.0)
        clock = 3_000
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
        feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0))
        steer(session, -5.0, initial = true)
        assertEquals(PracticeOutcome.ACTIVE, session.checkFreshness(5_000).outcome)
        assertEquals(1, session.snapshot().completedSteps)
    }

    @Test fun `speed without a positive measurement timestamp waits initially and interrupts after preparation`() {
        listOf<Long?>(null, 0L, -1L).forEach { source ->
            val session = session()
            feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
            steer(session, 0.0, initial = true, subscription = true)
            val pending = feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0, source = source))
            assertEquals(SteeringPracticeStage.WAIT_SAFE_INPUTS, pending.stage)
            assertNull(pending.speedMps)
            assertEquals(SteeringPracticeStage.WAIT_LEFT,
                feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0)).stage)
            val stopped = feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0, source = source))
            assertEquals("SPEED_TIMESTAMP_UNKNOWN", stopped.interruptionReason)
            assertEquals(PracticeOutcome.INTERRUPTED, stopped.outcome)
        }
    }

    @Test fun `freshly received speed GET with old measurement cannot prepare a session`() {
        val session = session()
        clock = 8_000
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
        steer(session, 0.0, initial = true)
        val stopped = feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0, source = 1_000_000L))
        assertEquals("SIGNAL_STALE_SPEED_MEASUREMENT", stopped.interruptionReason)
        assertEquals(0, stopped.completedSteps)
    }

    @Test fun `repeated GET with same speed measurement cannot extend its five second lifetime`() {
        val session = session()
        prepare(session)
        val firstSpeedTimestamp = 12_000_000L
        clock = 3_000
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P))
        steer(session, 0.0, initial = true)
        assertEquals(PracticeOutcome.ACTIVE,
            feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0, source = firstSpeedTimestamp)).outcome)
        assertEquals(PracticeOutcome.ACTIVE, session.checkFreshness(5_011).outcome)
        val stopped = session.checkFreshness(5_012)
        assertEquals("SIGNAL_STALE_SPEED_MEASUREMENT", stopped.interruptionReason)
        assertEquals(0, stopped.completedSteps)
    }

    @Test fun `old ON_CHANGE gear timestamp is valid when local receipt and continuous speed are fresh`() {
        val session = session()
        clock = 8_000
        feed(session, reading(ParkingSignalField.GEAR, initial = true, gear = ParkingGear.P, source = 1L))
        feed(session, reading(ParkingSignalField.SPEED, initial = true, speed = 0.0))
        val prepared = steer(session, 0.0, initial = true, subscription = true)
        assertEquals(SteeringPracticeStage.WAIT_LEFT, prepared.stage)
        assertEquals(1, steer(session, -5.0).completedSteps)
        assertEquals(PracticeOutcome.ACTIVE, session.checkFreshness(clock).outcome)
    }

    @Test fun `future speed measurement timestamp is rejected`() {
        val session = session()
        prepare(session)
        val result = feed(session, reading(ParkingSignalField.SPEED, speed = 0.0, source = 1_000_000_000L))
        assertEquals("SPEED_TIMESTAMP_INVALID", result.interruptionReason)
    }

    @Test fun `nanosecond fraction within receipt millisecond is not falsely future dated`() {
        val session = session()
        prepare(session)
        val measured = reading(ParkingSignalField.SPEED, speed = 0.0)
        val result = feed(session, measured.copy(
            sourceTimestampNanos = measured.receivedAtElapsedMillis * 1_000_000L + 999_999L,
        ))
        assertEquals(PracticeOutcome.ACTIVE, result.outcome)
        assertNull(result.interruptionReason)
    }

    @Test fun `out of order measurement cannot change progress or latest angle`() {
        val session = session()
        prepare(session)
        feed(session, reading(ParkingSignalField.STEERING, angle = -5.0, source = 200))
        val baseline = session.snapshot()
        val old = reading(ParkingSignalField.STEERING, angle = 0.0, at = clock - 1)
        assertEquals(baseline, session.accept(old, clock))
        assertEquals(baseline, feed(session, reading(ParkingSignalField.STEERING, angle = 0.0, source = 100)))
    }

    @Test fun `elapsed time and optional brake samples never advance an action`() {
        val session = session()
        prepare(session)
        val before = session.snapshot()
        assertEquals(before, session.checkFreshness(500))
        clock = 500
        feed(session, reading(ParkingSignalField.BRAKE))
        feed(session, reading(ParkingSignalField.PARKING_BRAKE))
        assertEquals(before, session.snapshot())
    }

    @Test fun `explicit disconnect interruption is terminal and has no completion praise`() {
        val session = session()
        prepare(session)
        steer(session, -5.0)
        val stopped = session.interrupt("SIGNAL_CONNECTION_LOST", clock)
        assertEquals(SteeringCoach.INTERRUPTED, SteeringCoach.onEvent(stopped))
        assertNull(SteeringCoach.onStart(stopped))
        assertEquals(stopped, steer(session, 0.0))
    }

    @Test fun `transition history is bounded while revision remains monotonic`() {
        val session = session(SteeringPracticeConfig(maxEvents = 4))
        prepare(session)
        repeat(10) { steer(session, 5.0); steer(session, 0.0) }
        val result = session.snapshot()
        assertEquals(4, result.events.size)
        assertTrue(result.feedbackRevision > result.events.size)
        assertEquals(result.feedbackRevision, result.events.last().sequence)
    }

    @Test fun `catalog keys cover emitted messages and are unique valid clip identifiers`() {
        val ids = SteeringCoach.catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[a-z][a-z0-9_]+")) })
        assertEquals(SteeringCoach.WAIT_INPUTS, SteeringCoach.onStart(session().snapshot()))
    }
}
