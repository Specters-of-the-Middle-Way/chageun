package kr.drivestep.tutor.gleo

import kr.drivestep.tutor.connected.ReadinessSnapshot
import kr.drivestep.tutor.parking.ParkingCourse
import kr.drivestep.tutor.workflow.InputValidity
import kr.drivestep.tutor.workflow.LiveWeatherReading
import kr.drivestep.tutor.workflow.WeatherPlaces

enum class GleoAction {
    CHECK_DEPARTURE_READINESS, GET_WEATHER_DRIVING_ADVICE, START_PARKING_PRACTICE, TALK_WITH_DRIVING_COACH;
    // The current Gleo host prefixes the package when delivering its Activity Intent.
    val intentAction get() = "kr.drivestep.tutor.$name"
    companion object { fun from(value: String?) = entries.find { it.name == value || it.intentAction == value } }
}

data class GleoRequest(val action: GleoAction, val course: ParkingCourse? = null, val conversation: GleoLessonRequest? = null)
data class GleoReply(val status: String, val message: String, val display: String,
    val data: Map<String, Any?> = emptyMap(), val voiceResponse: Boolean = true)

/** Only current-trip checks and consented, fresh measurements; never a departure clearance. */
object GleoActionPolicy {
    fun preparation(setup: Int, preflight: Int, ready: Boolean, consent: Boolean,
        vehicle: ReadinessSnapshot?, elapsed: Long): GleoReply {
        val belt = if (consent) vehicle?.belt?.at(elapsed) else null
        val warnings = if (consent) vehicle?.warnings?.at(elapsed) else null
        val beltText = when {
            belt?.quality != InputValidity.VALID -> "미확인"
            belt.value == true -> "체결됨"
            else -> "미체결"
        }
        val warningText = if (warnings?.quality == InputValidity.VALID)
            if (warnings.value.orEmpty().any { it.active }) "계기판 경고를 확인해 주세요." else "현재 전달된 경고 없음"
            else "차량 경고 미확인"
        return GleoReply("success", "The preparation checklist was retrieved. This is not permission to depart.",
            "운전 환경 $setup/9 · 출발 확인 $preflight/7\n안전벨트 $beltText · $warningText\n미러 시야·벨트 착용·차량 주변은 직접 확인해 주세요.",
            mapOf("setupChecked" to setup, "setupTotal" to 9, "preflightChecked" to preflight,
                "preflightTotal" to 7, "practiceUnlocked" to ready, "vehicleDataConsent" to consent,
                "belt" to beltText, "warnings" to warningText, "departureAuthorized" to false,
                "manualChecksRequired" to listOf("미러 시야", "안전벨트 착용", "차량 주변")))
    }

    fun weather(reading: LiveWeatherReading?, now: Long): GleoReply {
        if (reading == null || !reading.validAt(now)) return GleoReply("fail",
            "Current weather is unavailable. No weather conditions were assumed.",
            "현재 날씨를 확인하지 못했어요. 날씨 화면에서 다시 조회해 주세요.")
        val fallback = reading.place.name == WeatherPlaces.DEFAULT.name
        val origin = if (fallback) "현재 위치 미확인 · ${reading.place.name} 기준" else reading.place.name
        return GleoReply("success", "Current forecast and driving advice were retrieved. Forecast is not a road observation.",
            "$origin · ${reading.condition} · ${reading.temperatureC}°C\n${reading.advice}",
            mapOf("area" to origin, "fallbackArea" to fallback, "condition" to reading.condition,
                "temperatureC" to reading.temperatureC, "forecastTimeMillis" to reading.modelTime,
                "provider" to "Open-Meteo", "advice" to reading.advice))
    }

    fun parkingReady(ready: Boolean): GleoReply? = if (ready) null else GleoReply("asking",
        "Complete the preparation checklist on screen, then ask to start parking practice again.",
        "주차 연습 전에 운전 환경과 출발 준비를 확인해 주세요. 확인을 마친 뒤 다시 요청해 주세요.")
}

/** A single bounded request; late results and screen exits cannot trigger an old action. */
class GleoRequestState {
    var generation = 0L; private set
    var request: GleoRequest? = null; private set
    var dispatched = false; private set
    private var startedAt = 0L
    fun begin(value: GleoRequest, now: Long): Long {
        generation++; request = value; dispatched = false; startedAt = now; return generation
    }
    fun current(token: Long, now: Long) = request != null && token == generation && now - startedAt in 0 until TIMEOUT_MS
    fun dispatch(token: Long, now: Long, active: Boolean, parked: Boolean): Boolean {
        if (!current(token, now) || dispatched || !active || !parked) return false
        dispatched = true; return true
    }
    fun end() { request = null; generation++; dispatched = false }
    companion object { const val TIMEOUT_MS = 6_000L }
}
