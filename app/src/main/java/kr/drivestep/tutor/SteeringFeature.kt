package kr.drivestep.tutor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.drivestep.tutor.coach.CoachCue
import kr.drivestep.tutor.coach.SteeringCoach
import kr.drivestep.tutor.data.SteeringLogStore
import kr.drivestep.tutor.practice.PracticeOutcome
import kr.drivestep.tutor.practice.SteeringPracticeSession
import kr.drivestep.tutor.practice.SteeringPracticeSnapshot
import kr.drivestep.tutor.vehicle.ParkingSignalField
import kr.drivestep.tutor.vehicle.ParkingSignalReading
import kr.drivestep.tutor.vehicle.PleosParkingSignalSource
import kr.drivestep.tutor.vehicle.SourceState
import kr.drivestep.tutor.vehicle.SourceStatus
import java.util.UUID
import java.util.concurrent.Executors

/** Main-thread controller. The host owns foreground/UX gating and microphone/audio policy. */
class SteeringFeature(
    context: Context,
    private val onChange: () -> Unit,
    private val onCue: (CoachCue) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store = SteeringLogStore(context)
    private val source = PleosParkingSignalSource(context)
    private var session: SteeringPracticeSession? = null
    private var sourceGeneration = 0L
    private var sourceActive = false
    private var closed = false
    private var notificationPending = false
    private var ticker: Runnable? = null

    var status: SourceStatus = SourceStatus(SourceState.STOPPED, "조향 연습을 위한 차량 신호 연결 전입니다.")
        private set
    var readings: Map<ParkingSignalField, ParkingSignalReading> = emptyMap()
        private set
    var snapshot: SteeringPracticeSnapshot? = null
        private set
    var history: List<SteeringPracticeSnapshot> = emptyList()
        private set
    var storageReady: Boolean = false
        private set
    var storageError: String? = null
        private set

    init {
        io.execute {
            try {
                val recovered = store.loadAndRecover()
                main.post {
                    if (!closed) {
                        history = recovered
                        storageReady = true
                        changed()
                    }
                }
            } catch (_: Exception) {
                main.post {
                    if (!closed) {
                        storageError = "조향 기록을 불러오지 못했습니다. 앱을 다시 시작해 주세요."
                        changed()
                    }
                }
            }
        }
    }

    /** Call only after the host has obtained required permissions and allows a connection. */
    fun connect() {
        checkMain()
        if (closed || sourceActive) return
        connectFresh()
    }

    fun begin() {
        checkMain()
        if (closed || !storageReady || storageError != null || status.state != SourceState.READY ||
            snapshot?.outcome == PracticeOutcome.ACTIVE) return
        val fresh = SteeringPracticeSession(UUID.randomUUID().toString(), System.currentTimeMillis())
        session = fresh
        snapshot = fresh.snapshot()
        persist(snapshot!!)
        // Discard every previous connection's baseline; only new source callbacks feed this session.
        connectFresh()
        startTicker()
        SteeringCoach.onStart(snapshot!!)?.let(onCue)
        changed()
    }

    private fun connectFresh() {
        val token = ++sourceGeneration
        source.stop()
        sourceActive = true
        readings = emptyMap()
        status = SourceStatus(SourceState.CONNECTING, "기어·속도·조향 신호를 새로 확인하고 있어요.")
        changed()
        source.start(
            onState = { reading ->
                if (!closed && sourceActive && token == sourceGeneration) {
                    val previous = readings[reading.field]
                    readings = readings + (reading.field to reading)
                    val valueChanged = previous == null || !sameValue(previous, reading)
                    val transitioned = session?.let {
                        applySnapshot(it.accept(reading, SystemClock.elapsedRealtime()), speak = true)
                    } ?: false
                    if (valueChanged || transitioned) changed()
                }
            },
            onStatus = { next ->
                if (!closed && token == sourceGeneration) {
                    val differs = status != next
                    status = next
                    if (next.state in FAILED_STATES) {
                        sourceActive = false
                        cancelTicker()
                        interrupt("SOURCE_${next.state.name}")
                    }
                    if (differs) changed()
                }
            },
        )
    }

    fun interrupt(reason: String, speak: Boolean = true) {
        checkMain()
        if (closed) return
        val current = session ?: return
        if (applySnapshot(current.interrupt(reason, SystemClock.elapsedRealtime()), speak)) changed()
    }

    fun stop(reason: String) {
        checkMain()
        if (closed) return
        interrupt(reason, speak = false)
        sourceGeneration++
        sourceActive = false
        source.stop()
        cancelTicker()
        val next = SourceStatus(SourceState.STOPPED, "조향 연습 신호 연결을 종료했습니다.")
        val differs = status != next || readings.isNotEmpty()
        status = next
        readings = emptyMap()
        if (differs) changed()
    }

    fun clearSession() {
        checkMain()
        if (closed) return
        interrupt("SESSION_CLEARED", speak = false)
        session = null
        snapshot = null
        cancelTicker()
        changed()
    }

    fun delete(id: String) {
        checkMain()
        if (closed || !storageReady || id.isBlank()) return
        if (snapshot?.id == id) clearSession()
        io.execute {
            try {
                val remaining = store.delete(id)
                main.post {
                    if (!closed) {
                        history = remaining
                        changed()
                    }
                }
            } catch (_: Exception) {
                storageFailure("조향 기록을 삭제하지 못했습니다. 앱을 다시 시작해 주세요.")
            }
        }
    }

    fun close() {
        checkMain()
        if (closed) return
        stop("APP_CLOSED")
        closed = true
        // Previously queued terminal snapshots must finish committing.
        io.shutdown()
    }

    private fun applySnapshot(next: SteeringPracticeSnapshot, speak: Boolean): Boolean {
        val previous = snapshot
        snapshot = next
        val transitioned = previous?.id != next.id || previous.feedbackRevision != next.feedbackRevision
        if (transitioned) {
            persist(next)
            if (speak) SteeringCoach.onEvent(next)?.let(onCue)
        }
        if (next.isTerminal) cancelTicker()
        return transitioned
    }

    private fun persist(value: SteeringPracticeSnapshot) {
        io.execute {
            try {
                val saved = store.save(value)
                main.post {
                    if (!closed && history != saved) {
                        history = saved
                        changed()
                    }
                }
            } catch (_: Exception) {
                storageFailure("조향 기록을 저장하지 못해 연습을 중단했습니다. 앱을 다시 시작해 주세요.")
            }
        }
    }

    private fun storageFailure(message: String) {
        main.post {
            if (!closed) {
                storageError = message
                interrupt("STORAGE_ERROR", speak = false)
                changed()
            }
        }
    }

    private fun startTicker() {
        cancelTicker()
        val runnable = object : Runnable {
            override fun run() {
                if (closed || ticker !== this) return
                val current = session ?: return
                if (applySnapshot(current.checkFreshness(SystemClock.elapsedRealtime()), speak = true)) changed()
                if (ticker === this && snapshot?.outcome == PracticeOutcome.ACTIVE) main.postDelayed(this, 500)
            }
        }
        ticker = runnable
        main.postDelayed(runnable, 500)
    }

    private fun cancelTicker() {
        ticker?.let(main::removeCallbacks)
        ticker = null
    }

    /** Receipt timestamps stay current without rebuilding the screen for identical GET values. */
    private fun sameValue(a: ParkingSignalReading, b: ParkingSignalReading) = when (b.field) {
        ParkingSignalField.GEAR -> a.gear == b.gear
        ParkingSignalField.STEERING -> a.angleDegrees == b.angleDegrees
        ParkingSignalField.SPEED -> a.speedMps == b.speedMps
        ParkingSignalField.BRAKE -> a.brakePressed == b.brakePressed
        ParkingSignalField.PARKING_BRAKE -> a.parkingBrakeOn == b.parkingBrakeOn
    }

    private fun changed() {
        if (closed || notificationPending) return
        notificationPending = true
        main.post {
            notificationPending = false
            if (!closed) onChange()
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Call SteeringFeature on the main thread" }

    private companion object {
        val FAILED_STATES = setOf(SourceState.ERROR, SourceState.UNSUPPORTED, SourceState.STOPPED)
    }
}
