package kr.drivestep.tutor.gleo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kr.drivestep.tutor.connected.LocationNavigationSource
import kr.drivestep.tutor.workflow.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Reuses the app's existing consent, rounded location, forecast parser and advice policy. */
class GleoWeatherRequest(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val preferences = WeatherPreferences(app)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val location = WeatherLocationSource(app)
    private var generation = 0L
    private var client: OpenMeteoClient? = null
    private var task: Future<*>? = null
    fun request(cached: LiveWeatherReading?, complete: (LiveWeatherReading?, GleoReply) -> Unit) {
        cancel()
        if (!preferences.consent) {
            complete(null, GleoReply("asking", "Enable weather location-sharing consent on screen, then ask again.",
                "날씨 화면에서 지역 정보 전송에 동의한 뒤 다시 요청해 주세요.")); return
        }
        if (cached?.validAt(System.currentTimeMillis()) == true) {
            complete(cached, GleoActionPolicy.weather(cached, System.currentTimeMillis())); return
        }
        val token = generation
        fun fetch(place: WeatherPlace) {
            if (generation != token) return
            val request = OpenMeteoClient(); client = request
            task = executor.submit {
                val reading = runCatching { request.read(place) }.getOrNull()
                main.post {
                    if (generation == token) {
                        val accepted = reading?.takeIf { preferences.consent && it.validAt(System.currentTimeMillis()) }
                        complete(accepted, GleoActionPolicy.weather(accepted, System.currentTimeMillis()))
                    }
                }
            }
        }
        if (app.checkSelfPermission(LocationNavigationSource.LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            fetch(WeatherPlaces.DEFAULT)
        else location.request(timeoutMillis = 1_500) { place, _ -> fetch(WeatherPlaces.locationOrDefault(place)) }
    }
    fun cancel() { generation++; location.cancel(); client?.cancel(); task?.cancel(true) }
    override fun close() { cancel(); executor.shutdownNow() }
}
