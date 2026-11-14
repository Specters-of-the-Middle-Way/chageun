package kr.drivestep.tutor.workflow

import kotlin.math.round

data class WeatherPlace(val name: String, val latitude: Double, val longitude: Double, val label: String = name) {
    val valid get() = name.isNotBlank() && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun approximate() = copy(latitude = round(latitude * 100) / 100, longitude = round(longitude * 100) / 100)
}

object WeatherPlaces {
    // Seongnam's published Bokjeong community-center bus-stop coordinate; rounded before transmission.
    // https://www.seongnam.go.kr/contents/down/10458_9.pdf
    val DEFAULT = WeatherPlace("경기도 성남시 수정구 복정동", 37.46619867, 127.126595, "복정동")
    fun locationOrDefault(location: WeatherPlace?) = location?.takeIf { it.valid } ?: DEFAULT
}

/** Provider model time is UTC epoch milliseconds; never replaced by response arrival time. */
data class LiveWeatherReading(val place: WeatherPlace, val temperatureC: Double, val code: Int, val modelTime: Long) {
    fun validAt(now: Long) = place.valid && temperatureC.isFinite() && temperatureC in -90.0..60.0 &&
        code in SUPPORTED_CODES && modelTime > 0 && modelTime <= now + 60_000 && now - modelTime <= TTL
    val condition get() = when (code) {
        0 -> "맑음"
        1, 2, 3 -> "구름"
        45, 48 -> "안개"
        56, 57, 66, 67 -> "어는 비·이슬비"
        71, 73, 75, 77, 85, 86 -> "눈"
        95, 96, 99 -> "뇌우"
        else -> "비·이슬비"
    }
    val advice: String get() = buildList {
        when (code) {
            45, 48 -> add("안개 예보예요. 출발 전에 등화와 김서림 제거 기능을 확인하고 시야가 충분한지 직접 살펴요.")
            56, 57, 66, 67 -> add("어는 비나 이슬비 예보예요. 결빙 가능성을 고려하고 도로 기상과 통제 정보를 먼저 확인해요.")
            71, 73, 75, 77, 85, 86 -> add("눈 예보예요. 유리·미러·등화의 눈을 제거하고 출발 가능 여부를 직접 확인해요.")
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> add("비 예보예요. 와이퍼와 시야를 확인하고 급조작을 피하며 충분한 차간 거리를 확보해요.")
            95, 96, 99 -> add("뇌우 예보예요. 기상 특보와 도로 상황을 확인하고 필요하면 출발을 미뤄요.")
        }
        if (temperatureC <= 3.0) add("기온이 낮아요. 출발 전 앞유리의 성에를 제거하고, 그늘·교량 등에는 살얼음이 있을 수 있으니 노면을 직접 확인해요. 기온만으로 결빙을 판단할 수는 없어요.")
        if (isEmpty()) add("현재 예보에 따른 추가 안내는 없어요. 출발 전 시야와 주변을 직접 확인해요.")
    }.joinToString("\n\n")
    companion object {
        const val TTL = 30 * 60_000L
        val SUPPORTED_CODES = setOf(0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99)
    }
}

/** In-memory cache across workflow and startup cards during the app session. Coordinates/weather are never persisted to disk. */
object WeatherMemoryCache {
    @Volatile var latest: LiveWeatherReading? = null
    fun getFresh(now: Long = System.currentTimeMillis()): LiveWeatherReading? = latest?.takeIf { it.validAt(now) }
    fun clear() { latest = null }
}

/** Consent and lifecycle changes invalidate both HTTP and location callbacks. */
class LiveWeatherState {
    var consent = false; private set
    var active = false; private set
    var generation = 0L; private set
    var loading = false; private set
    var reading: LiveWeatherReading? = null; private set
    var message = "현재 날씨 미확인 · 현재 위치 날씨를 조회해 주세요."; private set
    fun activate(value: Boolean) { active = value; if (!value) cancel() }
    fun consent(value: Boolean) {
        consent = value
        if (!value) {
            cancel()
            reading = null
            WeatherMemoryCache.clear()
            message = "날씨 조회 동의를 껐어요."
        }
    }
    fun begin(): Long? {
        if (!active || !consent) return null
        generation++; loading = true; reading = null; message = "날씨 정보 조회 중…"
        return generation
    }
    fun accepts(token: Long) = active && consent && loading && token == generation
    fun accept(token: Long, value: LiveWeatherReading, now: Long) {
        if (!accepts(token)) return
        loading = false
        if (value.validAt(now)) {
            reading = value
            WeatherMemoryCache.latest = value
            message = "Open-Meteo 현재 예보 · 현장 센서 관측이 아니에요."
        } else {
            reading = null
            message = "날씨 시각·값을 확인할 수 없어요. 다시 조회해 주세요."
        }
    }
    fun fail(token: Long, reason: String) {
        if (!accepts(token)) return
        loading = false; reading = null; message = reason
    }
    fun fresh(now: Long) = reading?.takeIf { it.validAt(now) }
    /** Share an already fetched foreground result without issuing another request or announcement. */
    fun restore(value: LiveWeatherReading, now: Long) {
        if (!consent || !value.validAt(now)) return
        cancel()
        reading = value
        message = "Open-Meteo 현재 예보 · 현장 센서 관측이 아니에요."
    }
    fun cancel() {
        generation++
        if (loading) message = "조회를 중단했어요. 다시 조회해 주세요."
        loading = false
    }
}
