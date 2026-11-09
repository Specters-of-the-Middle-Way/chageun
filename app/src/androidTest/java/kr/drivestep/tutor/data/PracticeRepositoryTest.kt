package kr.drivestep.tutor.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.drivestep.tutor.practice.PracticeAction
import kr.drivestep.tutor.practice.PracticeOutcome
import kr.drivestep.tutor.practice.PracticeSession
import kr.drivestep.tutor.practice.PracticeSnapshot
import kr.drivestep.tutor.practice.PracticeStage
import kr.drivestep.tutor.practice.SignalPattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PracticeRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: PracticeRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "practice-test-${UUID.randomUUID()}.db"
        repository = PracticeRepository(context, databaseName)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun completedSessionAndEveryObservationSurviveReopening() {
        val completed = completed("first", 1_000L)
        assertTrue(repository.save(completed))
        repository.close()
        repository = PracticeRepository(context, databaseName)

        assertEquals(completed, repository.get("first"))
        assertEquals(listOf(completed), repository.listRecent())
        assertEquals(0, repository.recoverInterrupted(9_000L))
        assertEquals(completed, repository.get("first"))
    }

    @Test
    fun unfinishedSessionBecomesInterruptedWithoutInventingCompletion() {
        val session = PracticeSession("unfinished", 1_000L)
        session.accept(SignalPattern.OFF, 1_100L)
        val active = session.accept(SignalPattern.LEFT, 1_200L)
        repository.save(active)
        repository.save(completed("completed", 2_000L))
        repository.close()
        repository = PracticeRepository(context, databaseName)

        assertEquals(1, repository.recoverInterrupted(3_000L))
        val recovered = requireNotNull(repository.get("unfinished"))
        assertEquals(PracticeOutcome.INTERRUPTED, recovered.outcome)
        assertEquals(PracticeStage.INTERRUPTED, recovered.stage)
        assertEquals(PracticeAction.NONE, recovered.nextAction)
        assertEquals("PROCESS_RESTART", recovered.interruptionReason)
        assertEquals(3_000L, recovered.endedAtMillis)
        assertEquals(active.completedStepCount, recovered.completedStepCount)
        assertEquals(active.events, recovered.events)
        assertEquals(PracticeOutcome.COMPLETED, repository.get("completed")?.outcome)
        assertEquals(0, repository.recoverInterrupted(4_000L))
    }

    @Test
    fun delayedActiveWriteCannotReviveCompletedOrRecoveredSession() {
        val session = PracticeSession("first", 1_000L)
        val old = session.accept(SignalPattern.OFF, 1_100L)
        repository.save(old)
        session.accept(SignalPattern.LEFT, 1_200L)
        session.accept(SignalPattern.OFF, 1_300L)
        session.accept(SignalPattern.RIGHT, 1_400L)
        val terminal = session.accept(SignalPattern.OFF, 1_500L)
        repository.save(terminal)

        assertFalse(repository.save(old))
        assertEquals(terminal, repository.get("first"))

        val pending = PracticeSession("pending", 2_000L).snapshot()
        repository.save(pending)
        repository.recoverInterrupted(3_000L)
        assertFalse(repository.save(pending))
        assertEquals(PracticeOutcome.INTERRUPTED, repository.get("pending")?.outcome)
    }

    @Test
    fun deletingOneRecordLeavesOtherHistoryIntactAndSorted() {
        repository.save(completed("older", 1_000L))
        val newer = completed("newer", 2_000L)
        repository.save(newer)
        assertEquals(listOf("newer", "older"), repository.listRecent().map { it.id })
        assertEquals(listOf(newer), repository.listRecent(1))

        assertTrue(repository.delete("older"))
        assertFalse(repository.delete("older"))
        assertNull(repository.get("older"))
        assertEquals(listOf(newer), repository.listRecent())
    }

    private fun completed(id: String, startedAt: Long): PracticeSnapshot {
        val session = PracticeSession(id, startedAt)
        listOf(SignalPattern.OFF, SignalPattern.LEFT, SignalPattern.OFF, SignalPattern.RIGHT, SignalPattern.OFF)
            .forEachIndexed { index, signal -> session.accept(signal, startedAt + (index + 1) * 100L) }
        return session.snapshot()
    }
}
