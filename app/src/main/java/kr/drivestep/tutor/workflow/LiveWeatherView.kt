package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kr.drivestep.tutor.connected.LocationNavigationSource
import kr.drivestep.tutor.design.PleosSwitch
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future

@SuppressLint("ViewConstructor")
class LiveWeatherView(context: Context, private val permission: () -> Unit, private val speak: (String) -> Unit,
    private val stopSpeech: () -> Unit, private val autoSpeak: (String) -> Boolean) : LinearLayout(context), AutoCloseable {
    private val state = LiveWeatherState()
    private val preferences = WeatherPreferences(context)
    private val announcement = WeatherAutoAnnouncement()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val location = WeatherLocationSource(context)
    private var client: OpenMeteoClient? = null
    private var task: Future<*>? = null
    private var locationNotice: String? = null
    private var closed = false
    private val expire = Runnable { if (state.active && !closed) render() }
    init {
        state.consent(preferences.consent); announcement.enabled = preferences.autoRead
        orientation = VERTICAL; render()
    }
    fun setActive(value: Boolean) {
        if (closed || value == state.active) return
        state.activate(value)
        if (!value) cancelWork()
        render()
    }
    fun permissionResult() { if (state.active) render() } // A grant never silently transmits location.
    fun showReading(reading: LiveWeatherReading) {
        if (closed || !preferences.consent) return
        cancelWork()
        state.restore(reading, System.currentTimeMillis())
        locationNotice = if (reading.place.name == WeatherPlaces.DEFAULT.name)
            "현재 위치 미확인 · 기본 지역인 ${reading.place.name} 기준으로 조회했어요." else null
        render()
    }
    private fun cancelWork() { client?.cancel(); task?.cancel(true); location.cancel(); main.removeCallbacks(expire) }
    override fun close() { if (closed) return; state.activate(false); cancelWork(); executor.shutdownNow(); closed = true }
    private fun request() {
        if (!state.active || !state.consent || closed) return
        stopSpeech(); cancelWork()
        locationNotice = null
        val token = state.begin() ?: return
        render()
        location.request { point, _ ->
            if (state.accepts(token)) {
                val resolved = WeatherPlaces.locationOrDefault(point)
                if (resolved == WeatherPlaces.DEFAULT) {
                    locationNotice = "현재 위치 미확인 · 기본 지역인 ${resolved.name} 기준으로 조회해요."
                    render()
                }
                fetch(resolved, token)
            }
        }
    }
    private fun fetch(point: WeatherPlace, token: Long) {
        val request = OpenMeteoClient(); client = request
        task = executor.submit {
            val result = runCatching { request.read(point) }
            main.post {
                if (!closed && state.accepts(token)) {
                    result.fold({ state.accept(token, it, System.currentTimeMillis()) },
                        { state.fail(token, "날씨 조회 실패 · 인터넷 연결을 확인하고 다시 조회해 주세요.") })
                    announcement.request(state.fresh(System.currentTimeMillis()), System.currentTimeMillis(),
                        state.active, state.consent, autoSpeak)
                    render()
                }
            }
        }
    }
    private fun render() {
        removeAllViews(); main.removeCallbacks(expire)
        label("현재 위치 날씨 조회", 25)
        label("조회하면 현재 위치의 좌표를 소수점 두 자리로 줄여 Open-Meteo에 전송해요. 위치와 날씨는 저장하지 않아요.", 16)
        label("기본 지역: ${WeatherPlaces.DEFAULT.name} · 현재 위치를 확인하지 못하면 이 지역으로 조회해요.", 16)
        addView(PleosSwitch(context).apply {
            text = "날씨 조회를 위한 지역 정보 전송 동의"; contentDescription = "weather_live_consent"
            isChecked = state.consent; isEnabled = state.active
            setOnCheckedChangeListener { _, enabled ->
                stopSpeech(); state.consent(enabled); preferences.consent = enabled
                if (!enabled) { cancelWork(); locationNotice = null }
                render()
            }
        }, LayoutParams(-1, dp(64)))
        addView(PleosSwitch(context).apply {
            text = "앱 실행 시 날씨 자동 조회"; contentDescription = "weather_startup_auto"
            isChecked = preferences.startup; isEnabled = state.active
            setOnCheckedChangeListener { _, enabled -> preferences.startup = enabled }
        }, LayoutParams(-1, dp(60)))
        addView(PleosSwitch(context).apply {
            text = "조회 후 날씨 조언 자동 읽기"; contentDescription = "weather_live_auto"
            isChecked = announcement.enabled; isEnabled = state.active
            setOnCheckedChangeListener { _, enabled ->
                announcement.enabled = enabled
                preferences.autoRead = enabled
                if (!enabled) stopSpeech()
            }
        }, LayoutParams(-1, dp(60)))
        label("앱을 새로 열면 날씨를 한 번 조회해 출발 전 조언을 알려드려요. 음소거·화면 이탈·운전 중에는 읽지 않아요.", 15)
        val allowed = context.checkSelfPermission(LocationNavigationSource.LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) label("위치 권한을 허용한 뒤 조회 버튼을 다시 눌러 주세요.", 16)
        addView(button("현재 위치 날씨 조회", "weather_live_location", state.consent && !state.loading) {
            if (allowed) request() else permission()
        }, LayoutParams(-1, dp(60)))
        if (state.loading) addView(button("조회 취소", "weather_live_cancel") {
            state.cancel(); cancelWork(); render()
        }, LayoutParams(-1, dp(56)))
        val now = System.currentTimeMillis()
        val reading = state.fresh(now)
        label(if (state.reading != null && reading == null) "날씨 정보가 오래됐어요. 다시 조회해 주세요." else state.message, 17)
        if (state.consent) locationNotice?.let { label(it, 17) }
        if (reading != null) {
            val time = SimpleDateFormat("M.d HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date(reading.modelTime))
            label("${reading.place.name} · ${reading.condition} · ${reading.temperatureC}°C\n예보 기준 $time (한국 시간) · 30분 이내 정보", 21)
            label(reading.advice, 19)
            addView(button("현재 날씨 조언 듣기", "weather_live_speak") {
                state.fresh(System.currentTimeMillis())?.let { speak("${it.place.name}, ${it.condition}, ${it.temperatureC}도. ${it.advice}") } ?: render()
            }, LayoutParams(-1, dp(56)))
            main.postDelayed(expire, (reading.modelTime + LiveWeatherReading.TTL - now + 1).coerceAtLeast(1))
        }
        addView(TextView(context).apply {
            text = Html.fromHtml("날씨: <a href=\"https://open-meteo.com/\">Open-Meteo</a> · <a href=\"https://creativecommons.org/licenses/by/4.0/\">CC BY 4.0</a> · 기상 모델 기반 현재 예보", Html.FROM_HTML_MODE_LEGACY)
            textSize = 14f; setTextColor(PleosUi.SECONDARY); setLinkTextColor(PleosUi.TEXT)
            movementMethod = LinkMovementMethod.getInstance(); setPadding(dp(6),dp(8),dp(6),dp(8))
        }, LayoutParams(-1,-2))
    }
    private fun label(value: String, size: Int) = addView(TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(PleosUi.TEXT); setPadding(dp(6), dp(8), dp(6), dp(8))
    }, LayoutParams(-1, -2))
    private fun button(value: String, key: String, enabled: Boolean = true, action: () -> Unit) = PracticeButton(context).apply {
        text = value; textSize = 17f; isAllCaps = false; contentDescription = key; gravity = Gravity.CENTER
        isEnabled = state.active && enabled; PleosUi.decorate(this, false)
        setOnClickListener { if (state.active && !closed) action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
