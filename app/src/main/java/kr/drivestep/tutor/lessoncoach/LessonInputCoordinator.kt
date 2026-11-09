package kr.drivestep.tutor.lessoncoach

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.drivestep.tutor.vehicle.*

/** Read-only adapters. No camera, surrounding-object inference or vehicle control. */
class LessonInputCoordinator(
    context: Context,
    private val onVehicle: (VehicleObservation) -> Unit,
    private val onNavigation: (NavigationObservation) -> Unit,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val parking = PleosParkingSignalSource(app)
    private val turn = PleosTurnSignalSource(app)
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var generation = 0L
    private var running = false
    private var seenTurn = false
    private var observation = emptyObservation()
    private var carStatus = "차량 연결 대기"
    private var turnStatus = "방향지시등 연결 대기"
    private var naviStatus = "내비 연결 대기"
    private var maneuverSequence = 0L
    private var previousTurn: TurnType? = null
    private var previousDistance: Double? = null
    private var poll: Runnable? = null

    fun start() {
        if (running) return
        running = true
        val token = ++generation
        observation = emptyObservation()
        seenTurn = false
        carStatus = "차량 연결 중"; turnStatus = "방향지시등 연결 중"; naviStatus = "내비 연결 중"
        report()
        parking.start(onState = { reading ->
            if (running && token == generation) {
                val at = reading.receivedAtElapsedMillis
                // Freshness means a confirmed value receipt; a GET is never a new action.
                observation = when (reading.field) {
                    ParkingSignalField.GEAR -> observation.copy(gear = LessonGear.valueOf(reading.gear.name), gearAtMillis = at)
                    ParkingSignalField.SPEED -> observation.copy(speedKmh = reading.speedMps?.let { kotlin.math.abs(it) * 3.6 },
                        // AAOS continuous speed includes measurement time. Re-reading a cached
                        // property must not make an old measurement appear newly observed.
                        speedAtMillis = reading.sourceTimestampNanos?.takeIf { it > 0L }?.div(1_000_000L) ?: at)
                    ParkingSignalField.STEERING -> observation.copy(frontWheelAngleDegrees = reading.angleDegrees, steeringAtMillis = at)
                    ParkingSignalField.BRAKE -> observation.copy(brakePressed = reading.brakePressed, brakeAtMillis = at)
                    ParkingSignalField.PARKING_BRAKE -> observation
                }
                // This aggregate can carry a cached blinker. Only the turn-signal source
                // may mark an observation as a new blinker action for the current lesson.
                publishVehicle(true)
            }
        }, onStatus = { status ->
            if (running && token == generation) {
                carStatus = status.message
                if (status.state in FAILED) {
                    observation = emptyObservation().copy(signal = observation.signal, signalAtMillis = observation.signalAtMillis)
                    publishVehicle(true)
                }
                report()
            }
        })
        turn.start(onState = { value ->
            if (running && token == generation) {
                val initial = value.isInitial || !seenTurn
                seenTurn = true
                val signal = when {
                    !value.isKnown -> LessonSignal.UNKNOWN
                    value.left == SignalLamp.ACTIVE && value.right == SignalLamp.ACTIVE -> LessonSignal.UNKNOWN
                    value.left == SignalLamp.ACTIVE -> LessonSignal.LEFT
                    value.right == SignalLamp.ACTIVE -> LessonSignal.RIGHT
                    else -> LessonSignal.OFF
                }
                observation = observation.copy(signal = signal, signalAtMillis = SystemClock.elapsedRealtime())
                publishVehicle(initial)
            }
        }, onStatus = { status ->
            if (running && token == generation) {
                turnStatus = status.message
                if (status.state in FAILED) {
                    observation = observation.copy(signal = LessonSignal.UNKNOWN, signalAtMillis = -1)
                    publishVehicle(true)
                }
                report()
            }
        })
        startNavigation(token)
        poll = object : Runnable {
            override fun run() {
                if (!running || token != generation) return
                // Tick preserves every channel's receipt time, so stale values become unknown.
                publishVehicle(true)
                main.postDelayed(this, 1_000)
            }
        }.also { main.postDelayed(it, 1_000) }
    }

    private fun publishVehicle(initial: Boolean) {
        if (running) onVehicle(observation.copy(atMillis = SystemClock.elapsedRealtime(), isInitial = initial))
    }

    private fun startNavigation(token: Long) {
        try {
            val helper = NaviHelper(app)
            navi = helper
            val listener = object : NaviHelperEventListener {
                override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = dispatch(token) {
                    val next = tbtInfoList.firstOrNull()
                    val direction = when (next?.type) {
                        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> LessonDirection.LEFT
                        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> LessonDirection.RIGHT
                        else -> LessonDirection.UNKNOWN
                    }
                    if (next == null || !next.distance.isFinite() || next.distance < 0 || direction == LessonDirection.UNKNOWN) {
                        invalidateNavigation()
                        naviStatus = "내비 연결됨 · 다음 좌·우회전 정보 대기"
                    } else {
                        if (next.type != previousTurn || previousDistance == null || next.distance > previousDistance!! + 75) maneuverSequence++
                        previousTurn = next.type; previousDistance = next.distance
                        onNavigation(NavigationObservation(SystemClock.elapsedRealtime(), NavigationInputSource.NAVI,
                            "navi-$token-$maneuverSequence", direction, next.distance))
                        naviStatus = "내비 좌·우회전 정보 수신됨"
                    }
                    report()
                }
                override fun onRouteCancelled() = dispatch(token) { invalidateNavigation(); naviStatus = "내비 경로 취소됨"; report() }
                override fun onReRouteEnded() = dispatch(token) { invalidateNavigation(); requestNavigation() }
                override fun onRoutingCompleted() = dispatch(token) { invalidateNavigation(); requestNavigation() }
                override fun onError(errorCode: NaviErrorCode) = dispatch(token) {
                    invalidateNavigation(); naviStatus = "내비 정보 수신 오류 · ${errorCode.name}"; report()
                }
            }
            naviListener = listener
            helper.addListener(listener)
            helper.initialize(onConnected = { dispatch(token) {
                naviStatus = "내비 서비스 연결됨 · 경로 정보 대기"; report(); requestNavigation()
            } }, onFailed = { dispatch(token) {
                invalidateNavigation(); naviStatus = "내비 서비스 연결 불가 · 시험 경로는 가상 모드에서 확인해요"; report()
            } })
        } catch (_: Exception) {
            naviStatus = "내비 사용 권한 또는 서비스 연결을 확인해 주세요"; report()
        }
    }

    private fun requestNavigation() {
        runCatching { navi?.getTBTInfo() }.onFailure {
            invalidateNavigation(); naviStatus = "내비 회전 정보 요청 실패"; report()
        }
    }

    private fun invalidateNavigation() {
        previousTurn = null; previousDistance = null; maneuverSequence++
        onNavigation(NavigationObservation(SystemClock.elapsedRealtime(), NavigationInputSource.NAVI,
            "navi-unavailable-$maneuverSequence", LessonDirection.UNKNOWN, Double.NaN))
    }

    private fun dispatch(token: Long, action: () -> Unit) {
        main.post { if (running && token == generation) action() }
    }
    private fun report() = onStatus("$carStatus\n$turnStatus\n$naviStatus")

    fun stop() {
        if (!running) return
        running = false; generation++
        poll?.let(main::removeCallbacks); poll = null
        parking.stop(); turn.stop()
        naviListener?.let { listener -> runCatching { navi?.removeListener(listener) } }
        runCatching { navi?.release() }
        navi = null; naviListener = null
        previousTurn = null; previousDistance = null
        observation = emptyObservation()
    }

    override fun close() = stop()

    private fun emptyObservation() = VehicleObservation(SystemClock.elapsedRealtime(), VehicleInputSource.SDK,
        isInitial = true, speedAtMillis = -1, signalAtMillis = -1, gearAtMillis = -1, brakeAtMillis = -1, steeringAtMillis = -1)
    private companion object { val FAILED = setOf(SourceState.ERROR, SourceState.UNSUPPORTED, SourceState.STOPPED) }
}
