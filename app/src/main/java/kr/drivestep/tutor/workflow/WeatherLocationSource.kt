package kr.drivestep.tutor.workflow

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.drivestep.tutor.connected.GeoPoint
import kr.drivestep.tutor.connected.LocationFix
import kr.drivestep.tutor.connected.locationObservation

/** A bounded foreground lookup. No navigation subscription or location persistence. */
class WeatherLocationSource(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var generation = 0L
    private var timeout: Runnable? = null
    fun cancel() {
        generation++
        timeout?.let(main::removeCallbacks); timeout = null
        listener?.let { runCatching { service?.unregisterFusedLocationCallback(it) } }
        runCatching { service?.release() }; service = null; listener = null
    }
    fun request(timeoutMillis: Long = 10_000, onResult: (WeatherPlace?, String?) -> Unit) {
        cancel()
        val token = generation
        fun finish(place: WeatherPlace?, error: String?) {
            if (generation != token) return
            cancel(); onResult(place, error)
        }
        try {
            val fused = FusedLocation(app)
            if (!fused.isSupported()) { finish(null, "현재 위치 서비스를 사용할 수 없어요."); return }
            service = fused; fused.initialize()
            val callback = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    main.post {
                        if (generation == token) {
                            val observed = locationObservation(location?.let {
                                LocationFix(GeoPoint(it.latitude, it.longitude), it.timestampMillis, it.confidence)
                            }, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                            // Ignore stale last-known fixes while waiting for a fresh update.
                            observed.value?.takeIf { observed.quality == InputValidity.VALID }?.let {
                                finish(WeatherPlace("현재 위치 주변", it.point.latitude, it.point.longitude).approximate(), null)
                            }
                        }
                    }
                }
                override fun onError(exception: Exception) {
                    main.post { finish(null, "위치를 읽을 수 없어요. 위치 권한을 확인해 주세요.") }
                }
            }
            listener = callback
            timeout = Runnable { finish(null, "최신 위치를 받지 못했어요. 다시 조회해 주세요.") }
                .also { main.postDelayed(it, timeoutMillis.coerceIn(500, 10_000)) }
            fused.registerFusedLocationCallback(callback); fused.getLastKnownLocation(callback)
        } catch (_: Exception) { finish(null, "현재 위치를 조회하지 못했어요.") }
    }
}
