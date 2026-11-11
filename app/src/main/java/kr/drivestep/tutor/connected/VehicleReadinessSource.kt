package kr.drivestep.tutor.connected

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.drivestep.tutor.vehicle.VehicleTimeoutPolicy
import kr.drivestep.tutor.vehicle.PleosVehicleSessions
import kr.drivestep.tutor.vehicle.SessionLease
import kr.drivestep.tutor.workflow.InputValidity

/** Read-only public Vehicle 2.3.2 calls. Permission/capability checks remain enforced. */
class VehicleReadinessSource(context: Context, private val onUpdate: (ReadinessSnapshot, Set<Int>) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val state = VehicleReadinessState()
    private val area = SeatArea(SeatValue.ROW_1_LEFT)
    private var vehicle: Vehicle? = null
    private var vehicleLease: SessionLease<Vehicle>? = null
    private var running = false
    private var generation = 0L
    private val channels = mutableListOf<Channel<*>>()
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            channels.forEach { it.request() }; publish()
            main.postDelayed(this,VehicleTimeoutPolicy.ACTIVE_HEARTBEAT_MS)
        }
    }
    val isRunning get() = running
    fun start() {
        if (running) return
        running = true; generation++; val token = generation; state.reset("차량 상태를 불러오고 있어요")
        publish()
        try {
            val lease = PleosVehicleSessions.acquire(app); vehicleLease = lease
            val session = lease.value; vehicle = session
            val seat = session.getSeat()
            position(seat,SeatAxis.FORE_AFT,seat::checkSeatForeAftPositionCapabilities,seat::getCurrentSeatForeAftPosition) { receive ->
                val listener = object : SeatForeAftPositionListener {
                    override fun onSeatForeAftPositionUpdated(position: Int?) = receive(position)
                    override fun onFailed(e: Exception) = dispatch(token) { fail(SeatAxis.FORE_AFT.name,e) }
                }
                seat.registerSeatForeAftPosition(area,listener); { seat.unregisterSeatForeAftPosition(area,listener) }
            }
            position(seat,SeatAxis.HEIGHT,seat::checkSeatHeightPositionCapabilities,seat::getCurrentSeatHeightPosition) { receive ->
                val listener = object : SeatHeightPositionListener {
                    override fun onSeatHeightPositionUpdated(position: Int?) = receive(position)
                    override fun onFailed(e: Exception) = dispatch(token) { fail(SeatAxis.HEIGHT.name,e) }
                }
                seat.registerSeatHeightPosition(area,listener); { seat.unregisterSeatHeightPosition(area,listener) }
            }
            position(seat,SeatAxis.BACKREST,seat::checkSeatBackrestAnglePositionCapabilities,seat::getCurrentSeatBackrestAnglePosition) { receive ->
                val listener = object : SeatBackrestAnglePositionListener {
                    override fun onSeatBackrestAnglePositionUpdated(position: Int?) = receive(position)
                    override fun onFailed(e: Exception) = dispatch(token) { fail(SeatAxis.BACKREST.name,e) }
                }
                seat.registerSeatBackrestAnglePosition(area,listener); { seat.unregisterSeatBackrestAnglePosition(area,listener) }
            }
            val belt = Channel<Boolean>("belt", { ok,bad -> seat.getCurrentSeatBeltBuckledState(area,ok,bad) }) {
                state.belt(it,now())
            }
            belt.inspect { ready,bad -> seat.checkSeatBeltBuckledCapabilities({ config,areas ->
                ready(readable(config) && areas.any { SeatValue.ROW_1_LEFT in it.values })
            },bad) }
            belt.subscribe = { receive ->
                val listener = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = receive(state)
                    override fun onFailed(e: Exception) = dispatch(token) { fail("belt",e) }
                }
                seat.registerSeatBeltBuckledState(area,listener); { seat.unregisterSeatBeltBuckledState(area,listener) }
            }
            // The interface annotates getTelltalesInfo with CAR_SAFETY, but its actual
            // implementation also checks PRIVATE_CAR_SAFETY. Both are declared; never bypass it.
            val safety = session.getSafety()
            val warnings = Channel<List<VehicleWarning>>("warnings", { ok,bad ->
                safety.getTelltalesInfo({ data -> ok(mapWarnings(data)) },bad)
            }) { value -> state.warnings(value,now()); emptySet() }
            warnings.subscribe = { receive ->
                val listener = object : TelltaleInfoListener {
                    override fun onTelltaleInfoUpdated(telltales: Telltales?) = receive(telltales?.let(::mapWarnings))
                    override fun onFailed(e: Exception) = dispatch(token) { fail("warnings",e) }
                }
                safety.registerTelltales(listener); { safety.unregisterTelltales(listener) }
            }
            warnings.inspect { ready,bad -> safety.checkTelltalesCapabilities({ ready(readable(it)) },bad) }
            main.post(poll)
        } catch (e: Exception) {
            channels.forEach { it.close() }; channels.clear()
            (SeatAxis.entries.map { it.name } + listOf("belt","warnings")).forEach { state.unavailable(it,quality(e),"차량 서비스 연결 실패") }
            publish()
        }
    }
    private fun position(@Suppress("UNUSED_PARAMETER") seat: Seat, axis: SeatAxis,
        check: ((CapabilityConfig,Map<SeatArea,AreaBounds<Int>>) -> Unit,(Exception)->Unit) -> Unit,
        read: (SeatArea,(Int?)->Unit,(Exception)->Unit)->Unit,
        subscribe: ((Int?)->Unit)->(() -> Unit)) {
        var bounds: AreaBounds<Int>? = null
        val channel = Channel<Int>(axis.name,{ ok,bad -> read(area,ok,bad) }) { value ->
            val range = bounds
            if (range == null) emptySet() else state.seat(axis,value,range.minValue,range.maxValue,now())
        }
        channel.subscribe = subscribe
        channel.inspect { ready,bad -> check({ config,areas ->
            bounds = areas.entries.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values }?.value
            ready(readable(config) && bounds != null)
        },bad) }
    }
    private inner class Channel<T>(val key: String,
        val read: ((T?)->Unit,(Exception)->Unit)->Unit, val accept: (T?)->Set<Int>) {
        private val token = generation
        private var closed = false
        private var enabled = false
        private var pending = false
        private var sequence = 0L
        private var deadline: Runnable? = null
        private var unregister: (() -> Unit)? = null
        var subscribe: (((T?)->Unit)->(() -> Unit))? = null
        init { channels += this }
        private fun arm(delay: Long) {
            deadline?.let(main::removeCallbacks)
            deadline = Runnable {
                if (running && token == generation) {
                    close(); state.unavailable(key,InputValidity.ERROR,"차량 응답이 늦어지고 있어요. 다시 연결해 주세요."); publish()
                }
            }.also { main.postDelayed(it,delay) }
        }
        fun inspect(check: ((Boolean)->Unit,(Exception)->Unit)->Unit) {
            arm(VehicleTimeoutPolicy.PARKED_CONNECT_TIMEOUT_MS)
            try { check({ supported -> dispatch(token) {
                if (closed) return@dispatch
                deadline?.let(main::removeCallbacks)
                if (!supported) { state.unavailable(key,InputValidity.UNSUPPORTED,"이 차량에서는 해당 상태를 확인할 수 없어요"); publish() }
                else {
                    enabled = true
                    try { unregister = subscribe?.invoke { value -> dispatch(token) { if (!closed) { sequence++; publish(accept(value)) } } } }
                    catch (e: Exception) { fail(key,e) }
                    request()
                }
            } },{ e -> dispatch(token) { close(); fail(key,e) } }) } catch (e: Exception) { close(); fail(key,e) }
        }
        fun request() {
            if (!enabled || pending || !running) return
            pending = true; val before = sequence
            arm(VehicleTimeoutPolicy.ACTIVE_READ_TIMEOUT_MS)
            try { read({ value -> dispatch(token) {
                if (closed || !pending) return@dispatch
                pending = false; deadline?.let(main::removeCallbacks)
                if (before == sequence) publish(accept(value))
            } },{ e -> dispatch(token) { close(); fail(key,e) } }) } catch (e: Exception) { close(); fail(key,e) }
        }
        fun close() {
            closed = true; enabled = false; pending = false; deadline?.let(main::removeCallbacks); deadline = null
            runCatching { unregister?.invoke() }; unregister = null
        }
    }
    private fun mapWarnings(value: Telltales) = value.telltales.map { VehicleWarning(it.type.name,it.state.name,it.color,it.priority) }
    private fun readable(config: CapabilityConfig) = config.accessType in setOf(AccessType.READ,AccessType.READ_WRITE)
    private fun quality(e: Exception) = if (e is SecurityException || e.message.orEmpty().contains("permission",true)) InputValidity.DENIED else InputValidity.ERROR
    private fun fail(key: String, e: Exception) {
        val token = generation
        dispatch(token) { state.unavailable(key,quality(e),if (quality(e) == InputValidity.DENIED) "차량 정보 이용 권한이 필요해요" else "차량 상태를 불러오지 못했어요. 다시 연결해 주세요."); publish() }
    }
    private fun dispatch(token: Long, action: () -> Unit) { main.post { if (running && token == generation) action() } }
    private fun now() = SystemClock.elapsedRealtime()
    private fun publish(changed: Set<Int> = emptySet()) = onUpdate(state.snapshot(now()),changed)
    fun stop() {
        if (!running) return
        running = false; generation++; main.removeCallbacks(poll)
        channels.forEach { it.close() }; channels.clear()
        runCatching { vehicleLease?.close() }; vehicleLease = null; vehicle = null
        state.reset(); publish()
    }
    override fun close() = stop()
}
