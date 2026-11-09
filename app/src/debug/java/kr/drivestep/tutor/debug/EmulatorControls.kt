package kr.drivestep.tutor.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only input fixture for the Pleos emulator. Writes go through Virtual Console,
 * never through practice state or SDK listeners. The host must call stop() when hidden.
 */
class EmulatorControls(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        Build.HARDWARE.lowercase() in setOf("ranchu", "goldfish")
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val active = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var clientId = ""
    private var status = "디버그 전용 · 실제 SDK 수신으로 실습을 확인해요."
    private var statusView: TextView? = null
    private var buttons = emptyList<Button>()

    fun createView(): View? {
        if (!enabled || closed) return null
        activate()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(237, 240, 249))
                setStroke(dp(1), Color.rgb(198, 208, 229))
            }
            contentDescription = "emulator_controls"
        }
        panel.addView(label("에뮬레이터 시험 조작 · 디버그 전용", 17, true))
        panel.addView(label("아래 버튼은 가상 차량 신호를 요청해요. 실제 차량은 제어하지 않아요.", 14))
        val created = mutableListOf<Button>()
        fun row(items: List<Triple<String, String, String>>) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { index, (title, key, path) ->
                val button = Button(context).apply {
                    text = title
                    textSize = 16f
                    isAllCaps = false
                    minHeight = dp(54)
                    isEnabled = !busy.get()
                    tag = key
                    contentDescription = key
                    setOnClickListener { requestInput(path) }
                }
                created.add(button)
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
            panel.addView(row)
        }
        panel.addView(label("방향지시등 조작", 14, true))
        row(listOf(
            Triple("← 왼쪽", "emulator_signal_left", "/vehicle/turn-signal?mode=BlinkLeft"),
            Triple("끄기", "emulator_signal_off", "/vehicle/turn-signal?mode=Off"),
            Triple("오른쪽 →", "emulator_signal_right", "/vehicle/turn-signal?mode=BlinkRight"),
        ))
        panel.addView(label("시험용 조향 입력 · SDK 전륜 조향각(°), 핸들 회전 수 아님", 13))
        // Pleos emulator 2.1.0 console uses the opposite sign from SDK PERF_STEERING_ANGLE.
        // Labels describe the expected SDK receipt, never fabricated app state.
        row(listOf(
            Triple("← −8°", "emulator_steering_negative", "/vehicle/controls?steering=8"),
            Triple("중앙 0°", "emulator_steering_center", "/vehicle/controls?steering=0"),
            Triple("+8° →", "emulator_steering_positive", "/vehicle/controls?steering=-8"),
        ))
        buttons = created
        statusView = label(status, 13).also { panel.addView(it) }
        panel.addView(label("카메라 창이 뜨면 왼쪽 위 ×를 눌러 닫고 ‘끄기’를 누르세요.", 13))
        panel.addView(label("P 복귀는 PC 콘솔에서 해 주세요. 입력값으로 안전성·운전 실력을 평가하지 않아요.", 12))
        return panel
    }

    private fun activate() {
        if (!active.compareAndSet(false, true)) return
        val token = generation.incrementAndGet()
        clientId = "drivestep-panel-${UUID.randomUUID()}"
        val client = clientId
        heartbeat = executor.scheduleWithFixedDelay({
            if (current(token)) runCatching { http("/browser/session?client=$client", "POST", token) }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun requestInput(path: String) {
        if (!active.get() || closed || !busy.compareAndSet(false, true)) return
        val token = generation.get()
        val client = clientId
        buttons.forEach { it.isEnabled = false }
        publish("가상 차량에 시험 신호를 요청하고 있어요.", token)
        executor.execute {
            try {
                if (!current(token)) return@execute
                http("/browser/session?client=$client", "POST", token)
                if (!current(token)) return@execute
                http(path, "PUT", token)
                if (!current(token)) return@execute
                http("/vehicle/state", "GET", token)
                publish("시험 신호 요청 완료 · SDK 수신 대기 (요청 성공은 실습 성공이 아니에요)", token)
            } catch (_: Exception) {
                publish("시험 신호 요청 실패 · Pleos 에뮬레이터의 가상 콘솔 연결을 확인해 주세요.", token)
            } finally {
                main.post {
                    if (current(token)) {
                        busy.set(false)
                        buttons.forEach { it.isEnabled = true }
                    }
                }
            }
        }
    }

    private fun http(path: String, method: String, token: Long): JSONObject {
        check(current(token)) { "Panel stopped" }
        val request = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        connection = request
        try {
            request.requestMethod = method
            request.connectTimeout = 1500
            request.readTimeout = 2000
            request.useCaches = false
            request.instanceFollowRedirects = false
            check(current(token)) { "Panel stopped" }
            check(request.responseCode in 200..299) { "Console HTTP ${request.responseCode}" }
            return request.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            request.disconnect()
            if (connection === request) connection = null
        }
    }

    private fun publish(message: String, token: Long) {
        main.post {
            if (current(token)) {
                status = message
                statusView?.text = message
                onStatus(message)
            }
        }
    }

    private fun current(token: Long) = active.get() && !closed && generation.get() == token

    fun stop() {
        active.set(false)
        generation.incrementAndGet()
        heartbeat?.cancel(false)
        heartbeat = null
        connection?.disconnect()
        connection = null
        busy.set(false)
        statusView = null
        buttons = emptyList()
        status = "디버그 전용 · 실제 SDK 수신으로 실습을 확인해요."
        // No vehicle writes on stop: the browser lease expires on its own.
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        executor.shutdownNow()
    }

    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(39, 53, 73))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
