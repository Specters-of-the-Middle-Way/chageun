package kr.drivestep.tutor.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.TurnSignal
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Read-only, typed integration with ai.pleos.playground:Vehicle:2.3.2.
 * A successful initialize/register call is not treated as received vehicle data.
 * No simulated fallback, reflection, hidden API, or vehicle write calls are used.
 */
class PleosTurnSignalSource(context: Context) : TurnSignalSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var vehicleLease: SessionLease<Vehicle>? = null
    private var api: TurnSignal? = null
    private var listener: TurnSignalStateListener? = null
    private var registered = false
    private var generation = 0L
    private var updateSequence = 0L
    private var lastReading: SignalReading? = null
    private var stateCallback: ((SignalReading) -> Unit)? = null
    private var statusCallback: ((SourceStatus) -> Unit)? = null
    private var deadline: Runnable? = null
    private var heartbeat: Runnable? = null

    override fun start(onState: (SignalReading) -> Unit, onStatus: (SourceStatus) -> Unit) {
        onMain {
            closeSession()
            stateCallback = onState
            statusCallback = onStatus
            val token = generation
            publishStatus(SourceState.CONNECTING, "Pleos 차량 서비스에 연결하고 있어요.")
            try {
                val lease = PleosVehicleSessions.acquire(appContext)
                vehicleLease = lease
                val session = lease.value
                vehicle = session
                val signals = session.getTurnSignal()
                api = signals
                armDeadline(
                    token,
                    "차량 응답이 늦어지고 있어요. 차량 연결 상태를 확인한 뒤 다시 연결해 주세요.",
                    VehicleTimeoutPolicy.PARKED_CONNECT_TIMEOUT_MS,
                )
                signals.checkTurnSignalStateCapabilities(
                    onComplete = { capabilities -> dispatch(token) {
                        clearDeadline()
                        if (capabilities.accessType != AccessType.READ &&
                            capabilities.accessType != AccessType.READ_WRITE
                        ) {
                            fail(token, SourceState.UNSUPPORTED, "이 환경에서는 방향지시등 읽기를 지원하지 않습니다.")
                        } else {
                            subscribe(token, signals)
                        }
                    } },
                    onFailed = { error -> dispatch(token) {
                        fail(token, SourceState.ERROR, "방향지시등 기능 확인 실패: ${describe(error)}")
                    } },
                )
            } catch (error: Exception) {
                fail(token, SourceState.ERROR, "Pleos 연결 실패: ${describe(error)}")
            }
        }
    }

    private fun subscribe(token: Long, signals: TurnSignal) {
        val observer = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
                val receivedAt = System.currentTimeMillis()
                dispatch(token) {
                    updateSequence++
                    publishReading(mapReading(state, receivedAt, isInitial = false))
                }
            }

            override fun onFailed(e: Exception) {
                dispatch(token) {
                    fail(token, SourceState.ERROR, "방향지시등 변경 수신 실패: ${describe(e)}")
                }
            }
        }
        listener = observer
        try {
            signals.registerTurnSignalState(observer)
            registered = true
            publishStatus(SourceState.WAITING_FOR_SIGNAL, "구독을 요청했습니다. 실제 방향지시등 값을 확인하고 있어요.")
            readSnapshot(token)
        } catch (error: Exception) {
            fail(token, SourceState.ERROR, "방향지시등 구독 실패: ${describe(error)}")
        }
    }

    /**
     * A bounded GET checks the connection even when the turn signal does not change.
     * Snapshots are marked isInitial=true and cannot advance an exercise. A GET
     * that races with a newer subscription event must not overwrite that event.
     */
    private fun readSnapshot(token: Long) {
        val signals = api ?: return
        val updatesAtRequest = updateSequence
        armDeadline(
            token,
            "차량 신호 확인이 지연되어 실습을 멈춥니다. 다시 연결해 주세요.",
            VehicleTimeoutPolicy.ACTIVE_READ_TIMEOUT_MS,
        )
        try {
            signals.getCurrentTurnSignalState(
                onComplete = { value ->
                    val receivedAt = System.currentTimeMillis()
                    dispatch(token) {
                        clearDeadline()
                        if (updatesAtRequest == updateSequence) {
                            publishReading(mapReading(value, receivedAt, isInitial = true))
                        }
                        scheduleHeartbeat(token)
                    }
                },
                onFailed = { error -> dispatch(token) {
                    fail(token, SourceState.ERROR, "차량 신호 읽기 실패: ${describe(error)}")
                } },
            )
        } catch (error: Exception) {
            fail(token, SourceState.ERROR, "차량 신호 읽기 실패: ${describe(error)}")
        }
    }

    private fun publishReading(reading: SignalReading) {
        lastReading = reading
        if (reading.isKnown) {
            publishStatus(
                SourceState.READY,
                if (reading.isInitial) "현재 방향지시등 값을 확인했습니다. 실제 변경 신호로 실습을 진행해요."
                else "방향지시등 변경 신호를 받았습니다.",
            )
        } else {
            publishStatus(SourceState.WAITING_FOR_SIGNAL, "방향지시등 값이 확인되지 않습니다. 실습 진행을 보류합니다.")
        }
        stateCallback?.invoke(reading)
    }

    private fun scheduleHeartbeat(token: Long) {
        heartbeat?.let(main::removeCallbacks)
        heartbeat = Runnable {
            if (token == generation && vehicle != null) readSnapshot(token)
        }.also { main.postDelayed(it, VehicleTimeoutPolicy.TURN_SIGNAL_HEARTBEAT_MS) }
    }

    private fun armDeadline(token: Long, message: String, timeoutMillis: Long) {
        clearDeadline()
        deadline = Runnable {
            if (token == generation && vehicle != null) fail(token, SourceState.ERROR, message)
        }.also { main.postDelayed(it, timeoutMillis) }
    }

    private fun clearDeadline() {
        deadline?.let(main::removeCallbacks)
        deadline = null
    }

    private fun fail(token: Long, state: SourceState, message: String) {
        if (token != generation) return
        closeSession()
        publishStatus(state, message)
    }

    override fun stop() {
        onMain {
            closeSession()
            publishStatus(SourceState.STOPPED, "차량 신호 연결을 종료했습니다.")
            stateCallback = null
            statusCallback = null
        }
    }

    /** Runs on main; invalidating the generation also rejects late Binder callbacks. */
    private fun closeSession() {
        generation++
        clearDeadline()
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
        if (registered) {
            val observer = listener
            if (observer != null) runCatching { api?.unregisterTurnSignalState(observer) }
        }
        registered = false
        listener = null
        api = null
        runCatching { vehicleLease?.close() }; vehicleLease = null
        vehicle = null
        lastReading = null
        updateSequence = 0L
    }

    private fun publishStatus(state: SourceState, message: String) {
        statusCallback?.invoke(SourceStatus(state, message))
    }

    private fun dispatch(token: Long, action: () -> Unit) {
        main.post { if (token == generation && vehicle != null) action() }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun describe(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        internal fun mapReading(
            value: TurnSignalValue?,
            observedAtMillis: Long,
            isInitial: Boolean,
        ): SignalReading = when (value) {
            TurnSignalValue.OFF -> SignalReading(SignalLamp.OFF, SignalLamp.OFF, observedAtMillis, isInitial)
            TurnSignalValue.LEFT -> SignalReading(SignalLamp.ACTIVE, SignalLamp.OFF, observedAtMillis, isInitial)
            TurnSignalValue.RIGHT -> SignalReading(SignalLamp.OFF, SignalLamp.ACTIVE, observedAtMillis, isInitial)
            null -> SignalReading(SignalLamp.UNKNOWN, SignalLamp.UNKNOWN, observedAtMillis, isInitial)
        }
    }
}
