package kr.drivestep.tutor

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Fail closed: no practice before the platform confirms unrestricted stationary use. */
class ParkedGate(private val context: Context) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0
    private var callback: ((Boolean, String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var listener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener? = null

    fun start(onChange: (Boolean, String) -> Unit) {
        stop()
        callback = onChange
        onChange(false, "차량의 정차 사용 가능 상태를 확인하고 있어요.")
        val token = generation
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (token == generation) {
                    if (!ready) onChange(false, "차량 상태 연결이 끊겼습니다.")
                    else try {
                        runCatching { manager?.unregisterListener() }
                        manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        val observer = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
                            if (token == generation) update(it)
                        }
                        listener = observer
                        manager?.registerListener(observer)
                        update(manager?.currentCarUxRestrictions)
                    } catch (_: Exception) {
                        onChange(false, "차량의 정차 상태를 확인할 수 없어 실습을 시작할 수 없습니다.")
                    }
                }
            }
        } catch (_: Exception) {
            onChange(false, "Pleos 차량 환경에서 정차 상태를 확인해 주세요.")
        }
    }

    private fun update(restrictions: CarUxRestrictions?) {
        val allowed = restrictions != null && !restrictions.isRequiresDistractionOptimization
        callback?.invoke(allowed, if (allowed) "정차 중 실습할 수 있어요." else "주행 중에는 실습과 상세 복습을 사용할 수 없어요.")
    }

    fun stop() {
        generation++
        runCatching { manager?.unregisterListener() }
        manager = null
        listener = null
        runCatching { car?.disconnect() }
        car = null
        callback = null
    }
}
