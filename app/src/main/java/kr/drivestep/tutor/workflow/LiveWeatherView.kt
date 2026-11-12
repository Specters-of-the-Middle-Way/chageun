package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.drivestep.tutor.connected.LocationNavigationSource
import kr.drivestep.tutor.design.PleosSwitch
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton
import kr.drivestep.tutor.journey.PreparationColumns
import kr.drivestep.tutor.journey.PreparationStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future

@SuppressLint("ViewConstructor")
class LiveWeatherView(context: Context, private val permission: () -> Unit, private val speak: (String) -> Unit,
    private val stopSpeech: () -> Unit, private val autoSpeak: (String) -> Boolean,
    private val education: () -> Unit) : LinearLayout(context), AutoCloseable {
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
        render(resetScroll = true)
    }
    private fun cancelWork() { client?.cancel(); task?.cancel(true); location.cancel(); main.removeCallbacks(expire) }
    override fun close() { if (closed) return; state.activate(false); cancelWork(); executor.shutdownNow(); closed = true }
    private fun request() {
        if (!state.active || !state.consent || closed) return
        stopSpeech(); cancelWork()
        locationNotice = null
        val token = state.begin() ?: return
        render(resetScroll = true)
        location.request { point, _ ->
            if (state.accepts(token)) {
                val resolved = WeatherPlaces.locationOrDefault(point)
                if (resolved == WeatherPlaces.DEFAULT) {
                    locationNotice = "현재 위치 미확인 · 기본 지역인 ${resolved.name} 기준으로 조회해요."
                    render(resetScroll = true)
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
                    render(resetScroll = true)
                }
            }
        }
    }
    private fun render(resetScroll: Boolean = false) {
        val savedScroll = if (resetScroll) 0 else (getChildAt(0) as? ScrollView)?.scrollY ?: 0
        removeAllViews(); main.removeCallbacks(expire)
        val scroll = ScrollView(context).apply { isFillViewport = true; contentDescription = "weather_live_scroll" }
        addView(scroll, LayoutParams(-1, 0, 1f))
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(10), 0, dp(12)) }
        scroll.addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        val columns = PreparationColumns(context, 1.3f, 1f, 1_100)
        body.addView(columns, full())
        val forecast = LinearLayout(context).apply { orientation = VERTICAL }; columns.addView(forecast)
        val summary = card(PreparationStyle.softAccent); forecast.addView(summary, full())
        val now = System.currentTimeMillis()
        val reading = state.fresh(now)
        if (reading != null) {
            val time = SimpleDateFormat("M.d HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date(reading.modelTime))
            summary.addView(label(reading.place.name, 24, true, "weather_live_place"), full())
            summary.addView(label("${reading.temperatureC}°C · ${reading.condition}", 40, true, "weather_live_temperature"), full())
            summary.addView(label("예보 기준 $time (한국 시간) · 30분 이내 정보", 19, key = "weather_live_time"), full())
            if (state.consent) locationNotice?.let { summary.addView(label(it, 19, key = "weather_live_location_notice"), full()) }
            val advice = card(); forecast.addView(advice, full())
            advice.addView(label("출발 전 이렇게 준비해요", 25, true), full())
            advice.addView(label(reading.advice, 23, key = "weather_live_advice"), full())
            main.postDelayed(expire, (reading.modelTime + LiveWeatherReading.TTL - now + 1).coerceAtLeast(1))
        } else {
            summary.addView(label(if (state.loading) "주변 날씨를 확인하고 있어요" else "출발 전 날씨를 확인해요", 28, true), full())
            val message = when {
                !state.consent -> "날씨 조회 동의를 껐어요. 지역 정보 전송에 동의하면 현재 위치의 날씨와 출발 전 조언을 볼 수 있어요."
                state.reading != null -> "날씨 정보가 오래됐어요. 다시 조회해 주세요."
                else -> state.message
            }
            summary.addView(label(message, 22, key = "weather_live_status"), full())
            locationNotice?.takeIf { state.consent }?.let { summary.addView(label(it, 19, key = "weather_live_location_notice"), full()) }
        }
        val settings = card(); columns.addView(settings)
        settings.addView(label("날씨 안내 설정", 25, true), full())
        switchRow(settings, "지역 정보 전송 동의", "현재 위치를 이용해 날씨를 조회해요.", "weather_live_consent", state.consent) { enabled ->
            stopSpeech(); state.consent(enabled); preferences.consent = enabled
            if (!enabled) { cancelWork(); locationNotice = null }
            render()
        }
        switchRow(settings, "앱 실행 시 자동 조회", "앱을 새로 열면 날씨를 한 번 확인해요.", "weather_startup_auto", preferences.startup) { preferences.startup = it }
        switchRow(settings, "조회 후 조언 자동 읽기", "음소거·화면 이탈·운전 중에는 읽지 않아요.", "weather_live_auto", announcement.enabled) { enabled ->
            announcement.enabled = enabled; preferences.autoRead = enabled
            if (!enabled) stopSpeech()
        }
        settings.addView(label("좌표는 소수점 두 자리로 줄여 Open-Meteo에 전송해요. 위치와 날씨는 저장하지 않아요.", 18), full())
        settings.addView(label("현재 위치를 확인하지 못하면 ${WeatherPlaces.DEFAULT.name}의 날씨를 알려드려요.", 18), full())
        val allowed = context.checkSelfPermission(LocationNavigationSource.LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) summary.addView(label("위치 권한을 허용한 뒤 조회 버튼을 다시 눌러 주세요.", 20), full())
        val reference = card(); body.addView(reference, full())
        val referenceColumns = PreparationColumns(context, 1.3f, 1f, 1_100); reference.addView(referenceColumns, full(0))
        referenceColumns.addView(TextView(context).apply {
            text = Html.fromHtml("날씨: <a href=\"https://open-meteo.com/\">Open-Meteo</a> · <a href=\"https://creativecommons.org/licenses/by/4.0/\">CC BY 4.0</a> · 기상 모델 기반 현재 예보", Html.FROM_HTML_MODE_LEGACY)
            textSize = 18f; setTextColor(PleosUi.SECONDARY); setLinkTextColor(PreparationStyle.accent)
            movementMethod = LinkMovementMethod.getInstance(); minHeight = dp(64); gravity = Gravity.CENTER_VERTICAL
            contentDescription = "weather_live_source"
        })
        referenceColumns.addView(button("날씨별 대응 교육 보기", "workflow_weather_practice", action = education))
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
        addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        actions.addView(button(if (state.loading) "날씨 확인 중…" else if (reading != null) "현재 위치 다시 조회" else "현재 위치 날씨 조회",
            "weather_live_location", state.consent && !state.loading, true) {
            if (allowed) request() else permission()
        }, LayoutParams(0, -2, 1f))
        if (state.loading) actions.addView(button("조회 취소", "weather_live_cancel") {
            state.cancel(); cancelWork(); render(resetScroll = true)
        }, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        else if (reading != null) actions.addView(button("현재 날씨 조언 듣기", "weather_live_speak") {
            state.fresh(System.currentTimeMillis())?.let { speak("${it.place.name}, ${it.condition}, ${it.temperatureC}도. ${it.advice}") } ?: render()
        }, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        scroll.post { if (!closed && scroll.parent === this) scroll.scrollTo(0, savedScroll) }
    }
    private fun switchRow(parent: LinearLayout, title: String, detail: String, key: String, checked: Boolean, change: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
        parent.addView(row, full(18))
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(copy, LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        val toggle = PleosSwitch(context).apply {
            id = View.generateViewId(); contentDescription = key; isChecked = checked; isEnabled = state.active
            setOnCheckedChangeListener { _, enabled -> if (state.active && !closed) change(enabled) }
        }
        copy.addView(label(title, 22, true).apply { labelFor = toggle.id }, full(6))
        copy.addView(label(detail, 18), full(0))
        row.addView(toggle, LayoutParams(dp(80), dp(64)))
    }
    private fun card(fill: Int = PleosUi.SURFACE) = LinearLayout(context).apply {
        orientation = VERTICAL; background = PreparationStyle.surface(context, fill); setPadding(dp(20), dp(18), dp(20), dp(18))
    }
    private fun label(value: String, size: Int, bold: Boolean = false, key: String? = null) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (bold) PleosUi.TEXT else PleosUi.SECONDARY)
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1f); contentDescription = key
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun full(bottom: Int = 12) = LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun button(value: String, key: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) = PracticeButton(context).apply {
        text = value; textSize = 20f; isAllCaps = false; contentDescription = key; gravity = Gravity.CENTER
        minHeight = dp(64); minimumHeight = dp(64); minWidth = 0; minimumWidth = 0
        setPadding(dp(18), dp(12), dp(18), dp(12))
        isEnabled = state.active && enabled; PreparationStyle.button(this, primary)
        setOnClickListener { if (state.active && !closed) action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
