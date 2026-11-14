package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kr.drivestep.tutor.connected.LocationNavigationSource
import kr.drivestep.tutor.speech.TutorSpeechController
import java.util.concurrent.Executors
import java.util.concurrent.Future

class StartupWeatherController(context: Context, restoredAttempt: Boolean,
    private val onUpdate: (String) -> Unit) : AutoCloseable {
    private val state = StartupWeatherState(restoredAttempt)
    private val app = context.applicationContext
    private val preferences = WeatherPreferences(app)
    private val location = WeatherLocationSource(app)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val speech = TutorSpeechController(app, {}, {}, diagnosticRoute="STARTUP_WEATHER")
    private var client: OpenMeteoClient? = null
    private var task: Future<*>? = null
    private var active = false
    private var enabled = true
    private var closed = false
    val attempted get() = state.attempted
    val currentReading get() = if (preferences.consent) state.weather.fresh(System.currentTimeMillis()) ?: WeatherMemoryCache.getFresh() else null

    init {
        if (preferences.consent && preferences.startup) {
            WeatherMemoryCache.getFresh()?.let { reading ->
                state.weather.consent(true)
                state.weather.restore(reading, System.currentTimeMillis())
                onUpdate(formatAdvice(reading, reading.place.name == WeatherPlaces.DEFAULT.name))
            }
        }
    }

    fun setActive(value: Boolean, voiceEnabled: Boolean) {
        if (closed) return
        enabled = voiceEnabled
        speech.setEnabled(voiceEnabled)
        if (active == value) return
        val wasLoading = state.weather.loading
        active = value
        state.activate(value)
        speech.setActive(value)
        if (!value) {
            cancelWork()
            if (wasLoading) onUpdate("화면을 이동해 날씨 조회를 멈췄어요. 날씨 자세히에서 다시 조회할 수 있어요.")
            return
        }
        if (preferences.consent && state.weather.reading == null) {
            WeatherMemoryCache.getFresh()?.let { reading ->
                state.weather.consent(true)
                state.weather.restore(reading, System.currentTimeMillis())
                onUpdate(formatAdvice(reading, reading.place.name == WeatherPlaces.DEFAULT.name))
            }
        }
        if (state.weather.reading != null && state.weather.fresh(System.currentTimeMillis()) == null) {
            onUpdate("날씨 정보가 오래됐어요. 날씨 자세히에서 다시 조회해 주세요.")
        }
        if (!preferences.startup || !preferences.consent) {
            onUpdate("앱 실행 시 날씨 자동 조회가 꺼져 있어요. 날씨 화면에서 설정할 수 있어요.")
            return
        }
        val token = state.begin(preferences.startup, preferences.consent) ?: return
        if (state.weather.reading == null) {
            onUpdate("현재 위치의 날씨를 확인하고 있어요. 확인되면 출발 전 조언을 알려드릴게요.")
        }
        if (app.checkSelfPermission(LocationNavigationSource.LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            fetch(WeatherPlaces.DEFAULT, token, true)
        } else location.request(timeoutMillis = 1_500) { place, _ ->
            if (state.weather.accepts(token)) fetch(WeatherPlaces.locationOrDefault(place), token, place == null)
        }
    }

    private fun formatAdvice(reading: LiveWeatherReading, fallback: Boolean): String {
        val origin = if (fallback) "현재 위치 미확인 · 복정동 기준" else reading.place.name
        return "$origin · ${reading.condition} · ${reading.temperatureC}°C\n${reading.advice}"
    }

    private fun fetch(place: WeatherPlace, token: Long, fallback: Boolean) {
        if (!state.weather.accepts(token)) return
        val request = OpenMeteoClient(); client = request
        task = executor.submit {
            val result = runCatching { request.read(place) }
            main.post {
                if (!closed && state.weather.accepts(token)) {
                    result.fold({
                        state.weather.accept(token, it, System.currentTimeMillis())
                        WeatherMemoryCache.latest = it
                    }, {
                        if (state.weather.reading == null) {
                            state.weather.fail(token, "날씨를 가져오지 못했어요. 날씨 화면에서 다시 조회해 주세요.")
                        }
                    })
                    val reading = state.weather.fresh(System.currentTimeMillis()) ?: WeatherMemoryCache.getFresh()
                    if (reading == null) onUpdate(state.weather.message)
                    else {
                        val isDefault = fallback || reading.place.name == WeatherPlaces.DEFAULT.name
                        val advice = formatAdvice(reading, isDefault)
                        onUpdate(advice)
                        val origin = if (isDefault) "현재 위치 미확인 · 복정동 기준" else reading.place.name
                        if (enabled && preferences.autoRead && preferences.consent && preferences.startup) {
                            speech.speak("출발 전 날씨를 알려드릴게요. $origin, ${reading.condition}, ${reading.temperatureC}도. ${reading.advice}")
                        }
                    }
                }
            }
        }
    }

    private fun cancelWork() { location.cancel(); client?.cancel(); task?.cancel(true) }
    override fun close() {
        if (closed) return
        closed = true; state.activate(false); cancelWork(); speech.close(); executor.shutdownNow()
    }
}
