package kr.drivestep.tutor.gleo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.drivestep.tutor.parking.ParkingCourse
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Official Gleo Action/Callback transport. No API key, LLM HTTP request or transcript logging. */
class GleoBridge(private val context: Context, private val canSpeak: () -> Boolean,
    private val execute: (GleoRequest, (GleoReply) -> Unit) -> Unit,
    private val cancelWork: () -> Unit, private val onMessage: (String) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val state = GleoRequestState()
    private var active = false
    private var parked = false
    var lastResponse: String? = null; private set
    var responseSequence = 0L; private set
    private val timeout = Runnable { cancel("정차 상태 또는 응답을 확인하지 못했어요. 화면에서 확인한 뒤 다시 요청해 주세요.") }

    fun receive(intent: Intent) {
        val action = GleoAction.from(intent.action) ?: return
        cancel("새 요청으로 이전 요청을 중단했어요.")
        lastResponse = null
        val request = runCatching { parse(intent) }.getOrElse {
            reply(action, GleoReply("fail", "The action parameters are invalid.", "요청 내용을 확인하지 못했어요. 다시 말씀해 주세요.")); return
        }
        state.begin(request, SystemClock.elapsedRealtime())
        onMessage("요청하신 기능을 확인하고 있어요.")
        main.postDelayed(timeout, GleoRequestState.TIMEOUT_MS)
        main.post { dispatch() }
    }

    fun availability(foreground: Boolean, parkedAllowed: Boolean) {
        val wasActive = active
        active = foreground; parked = parkedAllowed
        if ((!foreground && (wasActive || state.dispatched)) || (!parkedAllowed && state.dispatched)) cancel("화면 이탈 또는 차량 상태 변경으로 요청을 중단했어요.")
        else main.post { dispatch() }
    }

    private fun dispatch() {
        val request = state.request ?: return
        val token = state.generation
        if (!state.dispatch(token, SystemClock.elapsedRealtime(), active, parked)) return
        execute(request) { result ->
            if (state.current(token, SystemClock.elapsedRealtime()) && active && parked) {
                state.end(); main.removeCallbacks(timeout); reply(request.action, result)
            }
        }
    }

    private fun cancel(display: String) {
        val request = state.request ?: return
        state.end(); main.removeCallbacks(timeout); cancelWork()
        reply(request.action, GleoReply("fail", "The request was cancelled or timed out. Ask again while parked.", display))
    }

    private fun reply(action: GleoAction, result: GleoReply) {
        val response = JSONObject().put("functionName", action.name).put("status", result.status)
            .put("message", result.message).put("mode", if (result.voiceResponse && active && parked && canSpeak()) "voice" else "silent")
            .put("data", JSONArray().put(JSONObject(result.data + ("displayText" to result.display))).toString())
        lastResponse = response.toString()
        responseSequence++
        // The current Pleos image's Gleo host. Explicit routing keeps vehicle results out of other apps.
        context.sendBroadcast(Intent(RESPONSE_ACTION).setPackage(GLEO_PACKAGE)
            .putExtra("packageName", context.packageName).putExtra("response", lastResponse))
        onMessage(result.display)
        Log.i("GleoBridge", "ACTION_RESULT action=${action.name} status=${result.status}")
    }

    override fun close() { active = false; cancel("앱 종료로 요청을 중단했어요."); main.removeCallbacksAndMessages(null) }

    companion object {
        const val GLEO_PACKAGE = "ai.umos.ambientai"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        fun parse(intent: Intent): GleoRequest {
            val action = requireNotNull(GleoAction.from(intent.action))
            val raw = intent.getStringExtra("ACTION_PARAM")
            require(raw == null || raw.length <= 2_048)
            val params = if (raw == null) JSONObject() else {
                // The guide shows both object JSON and quoted JSON strings.
                val decoded = JSONTokener(raw).nextValue()
                val obj = if (decoded is String) JSONObject(decoded) else decoded as? JSONObject
                requireNotNull(obj).let { it.optJSONObject("actionParameters") ?: it }
            }
            val allowed = when (action) {
                GleoAction.START_PARKING_PRACTICE -> setOf("course")
                GleoAction.TALK_WITH_DRIVING_COACH -> setOf("operation", "question", "topic", "focus")
                else -> emptySet()
            }
            require(params.keys().asSequence().all { it in allowed })
            if (action == GleoAction.TALK_WITH_DRIVING_COACH) {
                fun field(key: String): String? {
                    val value = if (params.has(key)) params.get(key) else intent.getStringExtra(key)
                    require(value == null || value is String)
                    return value as String?
                }
                val question = field("question").orEmpty()
                require(question.length <= 600)
                val operation = field("operation")?.let { GleoLessonOperation.valueOf(it) } ?: GleoLessonOperation.ASK
                val topic = field("topic")?.takeUnless { it == "CURRENT" }?.let { GleoLessonTopic.valueOf(it) }
                val focus = field("focus")?.let { GleoLessonFocus.valueOf(it) }
                return GleoRequest(action, conversation = GleoLessonRequest(operation, question, topic, focus))
            }
            val courseValue = if (params.has("course")) params.get("course") else intent.getStringExtra("course")
            val course = if (courseValue == null) null else {
                require(action == GleoAction.START_PARKING_PRACTICE && courseValue is String)
                requireNotNull(ParkingCourse.entries.find { it.name == courseValue })
            }
            return GleoRequest(action, course)
        }
    }
}
