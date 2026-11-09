package kr.drivestep.tutor.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.SteeringWheel
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Read-only hybrid: public AAOS gear/speed/parking-brake + Pleos 2.3.2 steering/brake.
 * Pleos's deprecated Gear API is not used; cruise target speed is not vehicle speed.
 * No UX restrictions are overridden. This source does not authorize any maneuver.
 */
class PleosParkingSignalSource(context: Context) : ParkingSignalSource {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var vehicle: Vehicle? = null
    private var steering: SteeringWheel? = null
    private var brake: Brake? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var carObserver: CarPropertyManager.CarPropertyEventCallback? = null
    private var worker: ExecutorService? = null
    private var stateCallback: ((ParkingSignalReading) -> Unit)? = null
    private var statusCallback: ((SourceStatus) -> Unit)? = null
    private var startupTimeout: Runnable? = null
    private var heartbeat: Runnable? = null
    private var lastStatus: SourceStatus? = null
    private val channels = EnumMap<ParkingSignalField, Channel>(ParkingSignalField::class.java)

    private class Channel(val field: ParkingSignalField, val required: Boolean) {
        var enabled = false
        var valid = false
        var firstCallback = true
        var sequence = 0L
        var lastSourceTimestamp = 0L
        var pending = false
        var deadline: Runnable? = null
        var unregister: (() -> Unit)? = null
    }

    private data class Raw(val value: Any?, val sourceTimestamp: Long? = null)
    private data class Receipt(val wall: Long, val elapsed: Long) {
        companion object {
            fun now() = Receipt(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }
    }

    override fun start(onState: (ParkingSignalReading) -> Unit, onStatus: (SourceStatus) -> Unit) = onMain {
        closeSession()
        stateCallback = onState
        statusCallback = onStatus
        lastStatus = null
        ParkingSignalField.entries.forEach { field -> channels[field] = Channel(field, field in REQUIRED) }
        val token = generation
        publishStatus(SourceState.CONNECTING, "기어·속도·조향 신호를 확인하고 있어요.")
        if (app.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            fail(token, SourceState.ERROR, "차량 속도 읽기 권한이 필요합니다. 권한을 허용한 뒤 다시 연결해 주세요.")
            return@onMain
        }
        worker = Executors.newSingleThreadExecutor()
        startupTimeout = Runnable {
            fail(token, SourceState.ERROR, "필수 차량 신호 확인 시간이 초과됐습니다. 다시 연결해 주세요.")
        }.also { main.postDelayed(it, CONNECT_TIMEOUT_MS) }
        try {
            val sdk = Vehicle(app)
            vehicle = sdk
            sdk.initialize()
            steering = sdk.getSteeringWheel()
            brake = sdk.getBrake()
            setupSteering(token)
            setupBrake(token)
            val createdCar = Car.createCar(app, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (token == generation) {
                    if (!ready) fail(token, SourceState.ERROR, "차량 속도·기어 서비스 연결이 끊겼습니다.")
                    else {
                        car = connected
                        try {
                            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                            properties = manager
                            setupCarProperties(token, manager)
                        } catch (error: Exception) {
                            fail(token, SourceState.ERROR, "차량 속성 연결 실패: ${describe(error)}")
                        }
                    }
                }
            }
            if (token == generation) {
                car = createdCar
                scheduleHeartbeat(token)
            } else runCatching { createdCar?.disconnect() }
        } catch (error: Exception) {
            fail(token, SourceState.ERROR, "차량 신호 연결 실패: ${describe(error)}")
        }
    }

    private fun setupSteering(token: Long) {
        val api = steering ?: return
        api.checkSteeringWheelAngleCapabilities(
            onComplete = { capability, _ -> dispatch(token) {
                if (!readable(capability.accessType)) {
                    fail(token, SourceState.UNSUPPORTED, "이 차량 환경은 조향각 읽기를 지원하지 않습니다.")
                } else {
                    val channel = channels.getValue(ParkingSignalField.STEERING)
                    val observer = object : SteeringWheelAngleListener {
                        override fun onSteeringWheelAngleUpdated(angle: Float?) =
                            onListener(token, channel, Raw(angle))
                        override fun onFailed(e: Exception) = channelError(token, channel, e)
                    }
                    channel.enabled = true
                    channel.unregister = { api.unregisterSteeringWheelAngle(observer) }
                    try {
                        api.registerSteeringWheelAngle(observer)
                        requestRead(token, channel)
                    } catch (error: Exception) { channelError(token, channel, error) }
                }
            } },
            onFailed = { error -> dispatch(token) {
                fail(token, SourceState.ERROR, "조향각 지원 확인 실패: ${describe(error)}")
            } },
        )
    }

    private fun setupBrake(token: Long) {
        val api = brake ?: return
        val channel = channels.getValue(ParkingSignalField.BRAKE)
        try {
            api.checkBrakePedalStatusCapabilities(
                onComplete = { capability -> dispatch(token) {
                    if (!readable(capability.accessType)) disableOptional(channel)
                    else {
                        val observer = object : BrakePedalStatusListener {
                            override fun onBrakePedalStatusUpdated(status: Boolean?) =
                                onListener(token, channel, Raw(status))
                            override fun onFailed(e: Exception) = channelError(token, channel, e)
                        }
                        channel.enabled = true
                        channel.unregister = { api.unregisterBrakePedalStatus(observer) }
                        try {
                            api.registerBrakePedalStatus(observer)
                            requestRead(token, channel)
                        } catch (error: Exception) { channelError(token, channel, error) }
                    }
                } },
                onFailed = { error -> channelError(token, channel, error) },
            )
        } catch (error: Exception) { channelError(token, channel, error) }
    }

    private fun setupCarProperties(token: Long, manager: CarPropertyManager) {
        if (carObserver != null) return
        val observer = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.areaId != GLOBAL_AREA) return
                val field = carField(value.propertyId) ?: return
                val channel = channels[field] ?: return
                onListener(token, channel, carRaw(value))
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                val field = carField(propertyId) ?: return
                val channel = channels[field] ?: return
                channelError(token, channel, IllegalStateException("차량 속성 오류"))
            }
        }
        carObserver = observer
        // Config and synchronous Binder calls run off main; callback delivery remains on main.
        worker?.execute {
            for (field in CAR_FIELDS) {
                try {
                    val id = carProperty(field)
                    val config = manager.getCarPropertyConfig(id)
                    val supported = config != null && config.isGlobalProperty &&
                        config.propertyType == carType(field) &&
                        (config.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ ||
                            config.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE)
                    dispatch(token) {
                        val channel = channels.getValue(field)
                        if (!supported) {
                            if (channel.required) fail(token, SourceState.UNSUPPORTED, "${label(field)} 읽기를 지원하지 않습니다.")
                            else disableOptional(channel)
                        } else {
                            channel.enabled = true
                            channel.unregister = { manager.unregisterCallback(observer, id) }
                            try {
                                val rate = if (field == ParkingSignalField.SPEED) CarPropertyManager.SENSOR_RATE_UI
                                    else CarPropertyManager.SENSOR_RATE_ONCHANGE
                                if (!manager.registerCallback(observer, id, rate)) {
                                    channelError(token, channel, IllegalStateException("구독을 허용하지 않습니다."))
                                } else requestRead(token, channel)
                            } catch (error: Exception) { channelError(token, channel, error) }
                        }
                    }
                } catch (error: Exception) {
                    dispatch(token) { channelError(token, channels.getValue(field), error) }
                }
            }
        }
    }

    private fun onListener(token: Long, channel: Channel, raw: Raw) {
        val receipt = Receipt.now()
        dispatch(token) {
            if (!channel.enabled) return@dispatch
            channel.sequence++
            val initial = channel.firstCallback
            channel.firstCallback = false
            publishReading(channel, raw, receipt, initial, isSubscription = true)
        }
    }

    /** GET success proves service responsiveness, not that the hardware measurement is recent. */
    private fun requestRead(token: Long, channel: Channel) {
        if (!channel.enabled || channel.pending) return
        channel.pending = true
        val sequence = channel.sequence
        channel.deadline = Runnable {
            channelError(token, channel, IllegalStateException("읽기 응답 시간이 초과됐습니다."))
        }.also { main.postDelayed(it, READ_TIMEOUT_MS) }
        val complete: (Raw) -> Unit = { raw ->
            val receipt = Receipt.now()
            dispatch(token) {
                clearPending(channel)
                if (channel.enabled && sequence == channel.sequence) {
                    publishReading(channel, raw, receipt, initial = true)
                }
            }
        }
        val failed: (Exception) -> Unit = { channelError(token, channel, it) }
        try {
            when (channel.field) {
                ParkingSignalField.STEERING -> steering!!.getCurrentSteeringWheelAngle({ complete(Raw(it)) }, failed)
                ParkingSignalField.BRAKE -> brake!!.getCurrentBrakePedalStatus({ complete(Raw(it)) }, failed)
                else -> {
                    val manager = properties ?: throw IllegalStateException("차량 속성 연결 없음")
                    worker?.execute {
                        try {
                            val value = manager.getProperty<Any>(carProperty(channel.field), GLOBAL_AREA)
                            complete(if (value == null) Raw(null) else carRaw(value))
                        } catch (error: Exception) { failed(error) }
                    }
                }
            }
        } catch (error: Exception) { failed(error) }
    }

    private fun publishReading(channel: Channel, raw: Raw, receipt: Receipt, initial: Boolean, isSubscription: Boolean = false) {
        // Preserve AAOS measurement time. A later GET must not make old sensor data look new.
        val timestamp = raw.sourceTimestamp?.takeIf { it > 0L }
        if (timestamp != null && timestamp < channel.lastSourceTimestamp) return
        if (timestamp != null) channel.lastSourceTimestamp = timestamp
        val gear = if (channel.field == ParkingSignalField.GEAR) mapGear(raw.value as? Int) else ParkingGear.UNKNOWN
        val numeric = (raw.value as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val angle = if (channel.field == ParkingSignalField.STEERING) numeric else null
        val speed = if (channel.field == ParkingSignalField.SPEED) numeric else null
        val pedal = if (channel.field == ParkingSignalField.BRAKE) raw.value as? Boolean else null
        val parking = if (channel.field == ParkingSignalField.PARKING_BRAKE) raw.value as? Boolean else null
        channel.valid = when (channel.field) {
            ParkingSignalField.GEAR -> gear != ParkingGear.UNKNOWN
            ParkingSignalField.STEERING -> angle != null
            ParkingSignalField.SPEED -> speed != null
            ParkingSignalField.BRAKE -> pedal != null
            ParkingSignalField.PARKING_BRAKE -> parking != null
        }
        val reading = ParkingSignalReading(
            field = channel.field, gear = gear, angleDegrees = angle, speedMps = speed,
            brakePressed = pedal, parkingBrakeOn = parking, observedAtMillis = receipt.wall,
            receivedAtElapsedMillis = receipt.elapsed, sourceTimestampNanos = timestamp, isInitial = initial, isSubscription = isSubscription,
        )
        val token = generation
        updateStatus()
        if (token == generation) stateCallback?.invoke(reading)
    }

    private fun updateStatus() {
        val ready = REQUIRED.all { channels[it]?.let { c -> c.enabled && c.valid } == true }
        if (ready) {
            startupTimeout?.let(main::removeCallbacks)
            startupTimeout = null
            val extras = listOf(ParkingSignalField.BRAKE, ParkingSignalField.PARKING_BRAKE)
                .filter { channels[it]?.valid != true }.joinToString("·") { label(it) }
            publishStatus(SourceState.READY, "기어·속도·앞바퀴 조향각을 수신했습니다." +
                if (extras.isEmpty()) "" else " $extras 값은 확인되지 않습니다.")
        } else publishStatus(SourceState.WAITING_FOR_SIGNAL, "필수 기어·속도·조향 신호를 기다리고 있어요.")
    }

    private fun disableOptional(channel: Channel) {
        channel.enabled = false
        clearPending(channel)
        runCatching { channel.unregister?.invoke() }
        channel.unregister = null
        publishReading(channel, Raw(null), Receipt.now(), initial = true)
    }

    private fun channelError(token: Long, channel: Channel, error: Exception) = dispatch(token) {
        if (channel.required) fail(token, SourceState.ERROR, "${label(channel.field)} 확인 실패: ${describe(error)}")
        else disableOptional(channel)
    }

    private fun scheduleHeartbeat(token: Long) {
        heartbeat = Runnable {
            if (token == generation && vehicle != null) {
                channels.values.toList().forEach { requestRead(token, it) }
                scheduleHeartbeat(token)
            }
        }.also { main.postDelayed(it, HEARTBEAT_MS) }
    }

    private fun clearPending(channel: Channel) {
        channel.deadline?.let(main::removeCallbacks)
        channel.deadline = null
        channel.pending = false
    }

    override fun stop() = onMain {
        closeSession()
        publishStatus(SourceState.STOPPED, "기어·속도·조향 신호 연결을 종료했습니다.")
        stateCallback = null
        statusCallback = null
    }

    private fun fail(token: Long, state: SourceState, message: String) {
        if (token != generation) return
        closeSession()
        publishStatus(state, message)
    }

    private fun closeSession() {
        generation++
        startupTimeout?.let(main::removeCallbacks)
        startupTimeout = null
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
        channels.values.forEach {
            clearPending(it)
            runCatching { it.unregister?.invoke() }
        }
        channels.clear()
        carObserver = null
        properties = null
        runCatching { car?.disconnect() }
        car = null
        steering = null
        brake = null
        runCatching { vehicle?.release() }
        vehicle = null
        worker?.shutdownNow()
        worker = null
    }

    private fun publishStatus(state: SourceState, message: String) {
        val value = SourceStatus(state, message)
        if (value != lastStatus) {
            lastStatus = value
            statusCallback?.invoke(value)
        }
    }

    private fun dispatch(token: Long, action: () -> Unit) {
        main.post { if (token == generation) action() }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private companion object {
        const val GLOBAL_AREA = 0
        const val CONNECT_TIMEOUT_MS = VehicleTimeoutPolicy.PARKED_CONNECT_TIMEOUT_MS
        const val READ_TIMEOUT_MS = VehicleTimeoutPolicy.ACTIVE_READ_TIMEOUT_MS
        const val HEARTBEAT_MS = VehicleTimeoutPolicy.ACTIVE_HEARTBEAT_MS
        val REQUIRED = setOf(ParkingSignalField.GEAR, ParkingSignalField.STEERING, ParkingSignalField.SPEED)
        val CAR_FIELDS = listOf(ParkingSignalField.GEAR, ParkingSignalField.SPEED, ParkingSignalField.PARKING_BRAKE)
        fun readable(access: AccessType) = access == AccessType.READ || access == AccessType.READ_WRITE
        fun describe(error: Exception) = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        fun label(field: ParkingSignalField) = when (field) {
            ParkingSignalField.GEAR -> "기어"
            ParkingSignalField.STEERING -> "앞바퀴 조향각"
            ParkingSignalField.SPEED -> "차량 속도"
            ParkingSignalField.BRAKE -> "브레이크 페달"
            ParkingSignalField.PARKING_BRAKE -> "주차 브레이크"
        }
        fun carProperty(field: ParkingSignalField) = when (field) {
            ParkingSignalField.GEAR -> VehiclePropertyIds.GEAR_SELECTION
            ParkingSignalField.SPEED -> VehiclePropertyIds.PERF_VEHICLE_SPEED
            ParkingSignalField.PARKING_BRAKE -> VehiclePropertyIds.PARKING_BRAKE_ON
            else -> error("Not an AAOS property: $field")
        }
        fun carField(id: Int) = CAR_FIELDS.firstOrNull { carProperty(it) == id }
        fun carType(field: ParkingSignalField): Class<*> = when (field) {
            ParkingSignalField.GEAR -> Int::class.javaObjectType
            ParkingSignalField.SPEED -> Float::class.javaObjectType
            ParkingSignalField.PARKING_BRAKE -> Boolean::class.javaObjectType
            else -> error("Not an AAOS property: $field")
        }
        fun carRaw(value: CarPropertyValue<*>) = Raw(
            if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value else null,
            value.timestamp,
        )
        fun mapGear(value: Int?) = when (value) {
            VehicleGear.GEAR_PARK -> ParkingGear.P
            VehicleGear.GEAR_REVERSE -> ParkingGear.R
            VehicleGear.GEAR_NEUTRAL -> ParkingGear.N
            VehicleGear.GEAR_DRIVE -> ParkingGear.D
            else -> ParkingGear.UNKNOWN
        }
    }
}
