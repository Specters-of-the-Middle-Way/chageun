package kr.drivestep.tutor.connected

import kr.drivestep.tutor.workflow.InputValidity
import kotlin.math.abs

/** SDK observations are session-only; positions are raw SDK units, never posture scores. */
data class Observed<T>(val value: T? = null, val quality: InputValidity = InputValidity.UNKNOWN,
    val receivedAt: Long = -1, val detail: String = "연결 전") {
    fun at(now: Long, ttl: Long = 3_000): Observed<T> =
        if (quality == InputValidity.VALID && (receivedAt < 0 || now < receivedAt || now - receivedAt > ttl))
            copy(value = null, quality = InputValidity.STALE, detail = "수신이 오래되어 현재 상태 미확인") else this
}
enum class SeatAxis(val step: Int, val title: String) { FORE_AFT(0,"시트 앞뒤"), HEIGHT(1,"시트 높이"), BACKREST(2,"등받이") }
data class SeatPosition(val raw: Int, val min: Int, val max: Int)
data class VehicleWarning(val type: String, val state: String, val color: Int, val priority: Int) {
    val active get() = state == "ON" || state == "BLINK"
    // SDK colors: yellow=3, red=4. Counts are never interpreted as nearby objects.
    val interruptsSpeech get() = active && color in 3..4
}
data class ReadinessSnapshot(val seats: Map<SeatAxis, Observed<SeatPosition>>, val belt: Observed<Boolean>,
    val warnings: Observed<List<VehicleWarning>>, val speechBlocked: Boolean)

class VehicleReadinessState {
    private val seats = SeatAxis.entries.associateWith { Observed<SeatPosition>() }.toMutableMap()
    // Session-only change baseline survives missing/stale reads; never used as current data.
    private val knownSeatPositions = mutableMapOf<SeatAxis,Int>()
    private var belt = Observed<Boolean>()
    private var warnings = Observed<List<VehicleWarning>>()
    private var warningHold = false
    fun seat(axis: SeatAxis, raw: Int?, min: Int, max: Int, now: Long): Set<Int> {
        val previous = knownSeatPositions[axis]
        seats[axis] = when {
            raw == null -> Observed(detail = "SDK 위치 값 미확인")
            min > max || raw !in min..max -> Observed(quality = InputValidity.ERROR, detail = "SDK 범위와 위치 값 불일치")
            else -> Observed(SeatPosition(raw,min,max),InputValidity.VALID,now,"SDK 수신 · 위치 단위")
        }
        val valid = seats.getValue(axis).value ?: return emptySet()
        knownSeatPositions[axis] = valid.raw
        return if (previous != null && previous != valid.raw)
            setOf(axis.step,5,6,7) else emptySet()
    }
    fun belt(value: Boolean?, now: Long): Set<Int> {
        val previous = belt.at(now).value
        belt = if (value == null) Observed(detail = "버클 상태 미확인") else Observed(value,InputValidity.VALID,now,"SDK 버클 상태")
        return if (value == false && previous != false) setOf(8) else emptySet()
    }
    fun warnings(value: List<VehicleWarning>?, now: Long) {
        warnings = if (value == null) Observed(detail = "차량 경고 미확인") else Observed(value,InputValidity.VALID,now,"SDK 경고 조회")
        if (value != null) warningHold = value.any { it.interruptsSpeech }
    }
    fun unavailable(channel: String, quality: InputValidity, detail: String) {
        when (channel) {
            "belt" -> belt = Observed(quality = quality, detail = detail)
            "warnings" -> warnings = Observed(quality = quality, detail = detail)
            else -> SeatAxis.entries.find { it.name == channel }?.let { seats[it] = Observed(quality = quality, detail = detail) }
        }
    }
    fun snapshot(now: Long) = ReadinessSnapshot(seats.mapValues { it.value.at(now) },belt.at(now),warnings.at(now),warningHold)
    fun reset(detail: String = "차량 신호 연결 중지") {
        SeatAxis.entries.forEach { seats[it] = Observed(detail = detail) }
        knownSeatPositions.clear()
        belt = Observed(detail = detail); warnings = Observed(detail = detail); warningHold = false
    }
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
data class LocationFix(val point: GeoPoint, val timestampMillis: Long, val confidence: Float)
fun locationObservation(fix: LocationFix?, receivedElapsed: Long, wallNow: Long): Observed<LocationFix> = when {
    fix == null -> Observed(detail = "위치 데이터 없음")
    !fix.point.valid || !fix.confidence.isFinite() -> Observed(quality = InputValidity.ERROR,detail = "위치 값 오류")
    fix.timestampMillis <= 0 || fix.timestampMillis > wallNow + 1_000 -> Observed(quality = InputValidity.UNKNOWN,detail = "출처 시각 확인 필요")
    wallNow - fix.timestampMillis > 30_000 -> Observed(quality = InputValidity.STALE,detail = "마지막 위치가 30초 이상 지났어요")
    else -> Observed(fix,InputValidity.VALID,receivedElapsed,"FusedLocation SDK · 출처 시각 확인됨")
}
data class NavigationDestination(val name: String, val point: GeoPoint, val remainingMeters: Int?, val remainingSeconds: Int?)
fun navigationDestination(name: String, point: GeoPoint, meters: Int, seconds: Int, now: Long): Observed<NavigationDestination> = when {
    !point.valid -> Observed(quality=InputValidity.ERROR,detail="내비 목적지 좌표 오류")
    name.isBlank() -> Observed(detail="목적지 정보 미확인 · 빈 응답을 0m 목적지로 표시하지 않아요")
    else -> Observed(NavigationDestination(name,point,meters.takeIf { it >= 0 },seconds.takeIf { it >= 0 }),InputValidity.VALID,now,"NaviHelper 목적지 응답")
}
data class NavigationGoal(val name: String, val point: GeoPoint) {
    val valid get() = name.isNotBlank() && name.length <= 80 && point.valid
}
enum class RequestPhase { IDLE, WAITING, CONFIRMED, FAILED }
internal const val NAVIGATION_FRESHNESS_MS = 5_000L
/** No echoed request ID: require a provider start/completion, active route AND matching destination. */
class NavigationRequestState {
    var phase = RequestPhase.IDLE; private set
    var goal: NavigationGoal? = null; private set
    var message = "목적지 요청 전"; private set
    private var requestedAt = -1L
    private var routed = false
    private var matched = false
    private var activeRoute = false
    private var started = false
    private var activeRouteAt = -1L
    private var destinationAt = -1L
    fun begin(next: NavigationGoal, now: Long): Boolean {
        if (!next.valid || phase == RequestPhase.WAITING) return false
        goal = next; requestedAt = now; routed = false; matched = false; activeRoute = false; started = false; phase = RequestPhase.WAITING
        activeRouteAt = -1; destinationAt = -1
        message = "내비 응답 대기 · 요청 전송을 성공으로 처리하지 않아요."; return true
    }
    fun routingStarted(now: Long) { if (pending(now)) { started = true; complete(now) } }
    fun cancelled() {
        if (phase == RequestPhase.WAITING) {
            routed = false; matched = false; activeRoute = false; started = false
            activeRouteAt = -1; destinationAt = -1
            message = "경로 취소 수신 · 새 경로의 시작·완료·목적지 확인을 기다려요."
        } else fail("내비 경로가 취소됐어요")
    }
    fun activeRoute(value: Boolean, now: Long) {
        expire(now)
        if (phase !in setOf(RequestPhase.WAITING,RequestPhase.CONFIRMED)) return
        activeRoute = value; activeRouteAt = if (value) now else -1
        if (!value) { matched = false; destinationAt = -1 }
        if (!value && phase == RequestPhase.CONFIRMED) fail("활성 경로가 없어 이전 요청 확인을 해제했어요.")
        else complete(now)
    }
    fun routingCompleted(now: Long) { if (pending(now)) { routed = true; complete(now) } }
    fun destination(point: GeoPoint, now: Long) {
        expire(now)
        if (phase !in setOf(RequestPhase.WAITING,RequestPhase.CONFIRMED) || (!routed && !started) || !activeRoute) return
        val target = goal?.point ?: return
        matched = point.valid && abs(point.latitude-target.latitude) < .00001 && abs(point.longitude-target.longitude) < .00001
        if (!matched) { fail("내비 목적지가 요청 좌표와 달라요. 확인 후 다시 요청해 주세요."); return }
        destinationAt = now; complete(now)
    }
    private fun complete(now: Long) {
        if ((routed || started) && activeRoute && matched && fresh(activeRouteAt,now) && fresh(destinationAt,now)) {
            phase = RequestPhase.CONFIRMED; message = "내비의 활성 경로와 요청 목적지 일치를 확인했어요."
        }
    }
    private fun fresh(at: Long, now: Long) = at >= 0 && now >= at && now-at <= NAVIGATION_FRESHNESS_MS
    private fun pending(now: Long): Boolean {
        expire(now); return phase == RequestPhase.WAITING
    }
    fun expire(now: Long) {
        if (phase == RequestPhase.WAITING && (now < requestedAt || now-requestedAt > 15_000)) fail("내비 응답 시간 초과 · 설정 성공 미확인")
        else if (phase == RequestPhase.CONFIRMED && (!fresh(activeRouteAt,now) || !fresh(destinationAt,now)))
            fail("활성 경로·목적지 응답이 오래되어 이전 요청 확인을 해제했어요.")
    }
    fun evidenceUnavailable(reason: String) {
        activeRoute = false; matched = false; activeRouteAt = -1; destinationAt = -1
        if (phase == RequestPhase.CONFIRMED) fail(reason)
    }
    fun fail(reason: String) {
        phase = RequestPhase.FAILED; routed = false; matched = false; activeRoute = false; started = false
        activeRouteAt = -1; destinationAt = -1; message = reason
    }
    fun reset() { phase = RequestPhase.IDLE; goal = null; requestedAt = -1; routed = false; matched = false; activeRoute = false; started = false; activeRouteAt = -1; destinationAt = -1; message = "목적지 요청 전" }
}
