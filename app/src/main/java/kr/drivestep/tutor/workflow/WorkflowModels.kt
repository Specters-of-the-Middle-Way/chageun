package kr.drivestep.tutor.workflow

/** App contracts, not claims about a vehicle SDK's supported channels. */
enum class InputSource { VEHICLE_SDK, NAVIGATION, WEATHER, MANUAL, SIMULATION }
enum class InputValidity { VALID, STALE, UNSUPPORTED, DENIED, ERROR, UNKNOWN }
data class InputEnvelope<T>(val value: T?, val source: InputSource, val observedAt: Long?, val receivedAt: Long,
    val validity: InputValidity, val unit: String?, val contextId: String) {
    fun effectiveValidity(now: Long, ttl: Long, context: String): InputValidity = when {
        validity != InputValidity.VALID -> validity
        value == null || observedAt == null || contextId != context -> InputValidity.UNKNOWN
        observedAt < 0 || receivedAt < 0 || observedAt > now || receivedAt > now || ttl < 0 -> InputValidity.ERROR
        now - observedAt > ttl -> InputValidity.STALE
        else -> InputValidity.VALID
    }
    fun usable(now: Long, ttl: Long, context: String) = effectiveValidity(now, ttl, context) == InputValidity.VALID
}
enum class EventStatus { CANDIDATE, ACTIVE, COMPLETED, CANCELLED, EXPIRED, INTERRUPTED, UNCONFIRMED }
data class WorkflowEvent(val id: String, val source: InputSource, val at: Long, val routeVersion: Int,
    val status: EventStatus, val fact: String, val unknown: String, val explanation: String? = null)
data class PendingAdvice(val id: String, val text: String, val createdAt: Long, val expiresAt: Long, val routeVersion: Int)

/** One eligible utterance at a time; a muted/interrupted or old utterance is never queued. */
class AdviceArbiter {
    var muted = false; private set
    private val spoken = mutableSetOf<String>()
    fun mute() { muted = true }
    fun resume() { muted = false }
    fun select(advice: PendingAdvice, now: Long, route: Int, active: Boolean, warning: Boolean,
        priorityAudio: Boolean, maneuvering: Boolean = false): String? {
        if (muted || !active || warning || priorityAudio || maneuvering || advice.routeVersion != route ||
            now < advice.createdAt || now >= advice.expiresAt || advice.id in spoken) return null
        return advice.text
    }
    fun acknowledge(id: String) { spoken += id }
}

enum class WeatherCondition { CLEAR, RAIN, SNOW, FOG, COLD }
data class WeatherReading(val region: String, val condition: WeatherCondition, val temperatureC: Double?)
data class WeatherPolicy(val ttlMillis: Long = 30 * 60_000L, val coldThresholdC: Double = 3.0,
    val policySource: String = "날씨별 연습 안내 기준")
interface WeatherProvider { fun read(region: String, context: String, now: Long): InputEnvelope<WeatherReading> }
class UnavailableWeatherProvider : WeatherProvider {
    override fun read(region: String, context: String, now: Long) = InputEnvelope<WeatherReading>(null,
        InputSource.WEATHER, null, now, InputValidity.UNSUPPORTED, "°C", context)
}
class SimulatedWeatherProvider(private val condition: WeatherCondition) : WeatherProvider {
    override fun read(region: String, context: String, now: Long) = InputEnvelope(
        WeatherReading(region, condition, if (condition == WeatherCondition.COLD) 1.0 else null),
        InputSource.SIMULATION, now, now, InputValidity.VALID, "°C", context)
}
class WeatherSession(val contextId: String, val policy: WeatherPolicy = WeatherPolicy()) {
    private val spoken = mutableSetOf<WeatherCondition>()
    var result: InputEnvelope<WeatherReading>? = null; private set
    fun accept(value: InputEnvelope<WeatherReading>, now: Long) {
        val quality = value.effectiveValidity(now, policy.ttlMillis, contextId)
        result = if (quality == InputValidity.VALID) value else value.copy(value = null, validity = quality)
    }
    fun advice(now: Long): PendingAdvice? {
        val input = result ?: return null
        if (!input.usable(now, policy.ttlMillis, contextId)) return null
        val condition = input.value!!.condition
        if (condition == WeatherCondition.CLEAR || condition in spoken) return null
        val text = when (condition) {
            WeatherCondition.RAIN -> "비 상황의 가상 예시예요. 와이퍼와 김서림 제거 기능을 출발 전에 확인해 보세요."
            WeatherCondition.SNOW -> "눈 상황의 가상 예시예요. 출발 전에 차량의 눈과 주변 시야를 직접 확인해 주세요."
            WeatherCondition.FOG -> "안개 상황의 가상 예시예요. 시야와 차량 설명서의 등화 기능을 확인해 주세요."
            WeatherCondition.COLD -> "저온 상황의 가상 예시예요. 유리와 노면을 직접 살펴요. 실제 결빙을 감지한 것은 아니에요."
            WeatherCondition.CLEAR -> return null
        }
        return PendingAdvice("weather:$contextId:$condition", text, now, input.observedAt!! + policy.ttlMillis, 0)
    }
    fun markSpoken() { result?.value?.let { spoken += it.condition } }
}

enum class RouteGoal { BEGINNER, WEAKNESS, RETURN_TRIP }
data class TrainingRoute(val id: String, val version: Int, val source: InputSource, val name: String,
    val distanceKm: Double, val minutes: Int, val tasks: List<String>, val features: Set<String>, val observedAt: Long)
data class RouteRequest(val goal: RouteGoal, val taskId: String, val start: String?, val exclusions: Set<String> = emptySet())
interface RouteProvider { fun candidates(request: RouteRequest, now: Long): List<TrainingRoute> }
class SimulatedRouteProvider : RouteProvider {
    override fun candidates(request: RouteRequest, now: Long): List<TrainingRoute> = listOf(
        TrainingRoute("sim-basic", 1, InputSource.SIMULATION, "가상 연습장 · 기초", 1.2, 8,
            listOf("D01", "D03", "D06"), setOf("기초"), now),
        TrainingRoute("sim-target", 1, InputSource.SIMULATION, "가상 연습장 · 선택 과제", 1.8, 12,
            listOf(request.taskId), setOf("선택 과제"), now),
        TrainingRoute("sim-return", 1, InputSource.SIMULATION, "가상 연습장 · 왕복", 2.4, 16,
            listOf("D15", "D09", "D15"), setOf("왕복"), now))
}
data class RouteProposal(val selectedIds: List<String>, val evidenceIds: Set<String>, val distanceClaims: Map<String, Double> = emptyMap())
data class NavigationReceipt(val routeId: String, val version: Int, val source: InputSource, val accepted: Boolean)
interface RouteNavigator { fun submit(route: TrainingRoute): NavigationReceipt }
class SimulationNavigator : RouteNavigator {
    override fun submit(route: TrainingRoute) = NavigationReceipt(route.id, route.version, InputSource.SIMULATION,
        route.source == InputSource.SIMULATION)
}
class CourseSelection {
    var candidates = emptyList<TrainingRoute>(); private set
    var selected: TrainingRoute? = null; private set
    var acknowledged = false; private set
    var message = "실제 GPS·경로 제공자는 연결되지 않았어요. 가상 연습장을 선택할 수 있어요."; private set
    fun load(request: RouteRequest, provider: RouteProvider, now: Long) {
        selected = null; acknowledged = false
        if (request.start.isNullOrBlank()) { candidates = emptyList(); message = "출발지를 직접 선택해 주세요."; return }
        val supplied = runCatching { provider.candidates(request, now) }.getOrElse {
            candidates = emptyList(); message = "경로 조회 오류예요. 정차용 연습을 이용하거나 다시 조회해 주세요."; return
        }
        candidates = supplied.filter { route ->
            route.id.isNotBlank() && route.version > 0 && route.distanceKm.isFinite() && route.distanceKm > 0 && route.minutes > 0 &&
                fresh(route, now) &&
                route.tasks.isNotEmpty() && route.tasks.all { ScenarioCatalog.find(it) != null } &&
                route.features.intersect(request.exclusions).isEmpty() &&
                (request.goal != RouteGoal.RETURN_TRIP || "왕복" in route.features) &&
                (request.goal != RouteGoal.WEAKNESS || request.taskId in route.tasks)
        }.distinctBy { it.id }.sortedBy { route ->
            when (request.goal) {
                RouteGoal.BEGINNER -> if ("기초" in route.features) 0 else 1
                RouteGoal.WEAKNESS -> if (request.taskId in route.tasks) 0 else 1
                RouteGoal.RETURN_TRIP -> if ("왕복" in route.features) 0 else 1
            }
        }.take(3)
        message = if (candidates.isEmpty()) "조건에 맞는 후보가 없어요. 제외 조건을 직접 바꿔 주세요." else "거리와 시간은 작성된 가상 코스의 설정값이에요. 실제 내비 경로가 아니에요."
    }
    private fun fresh(route: TrainingRoute, now: Long) = route.observedAt >= 0 && route.observedAt <= now && now - route.observedAt <= ROUTE_TTL_MS
    fun confirmedRoute(now: Long): TrainingRoute? {
        val route = selected
        if (!acknowledged || route == null) return null
        if (!fresh(route, now)) {
            selected = null; acknowledged = false
            message = "선택한 코스 정보가 오래됐어요. 후보를 다시 조회하고 선택해 주세요."
            return null
        }
        return route
    }
    fun applyProposal(proposal: RouteProposal, now: Long): Boolean {
        val ids = candidates.associateBy { it.id }
        val valid = proposal.selectedIds.isNotEmpty() && proposal.selectedIds.distinct().size == proposal.selectedIds.size &&
            proposal.selectedIds.all { it in ids && it in proposal.evidenceIds && fresh(ids.getValue(it), now) } && proposal.evidenceIds.all { it in ids } &&
            proposal.distanceClaims.all { (id, distance) -> ids[id]?.distanceKm == distance }
        if (!valid) { message = "추천 응답의 근거가 맞지 않아 검증된 후보를 그대로 보여드려요."; return false }
        candidates = proposal.selectedIds.map { ids.getValue(it) } + candidates.filter { it.id !in proposal.selectedIds }
        return true
    }
    fun choose(id: String, navigator: RouteNavigator, now: Long): Boolean {
        val route = candidates.find { it.id == id }
        if (route == null) { selected = null; acknowledged = false; message = "검증된 후보를 다시 선택해 주세요."; return false }
        if (!fresh(route, now)) { selected = null; acknowledged = false; message = "코스 정보가 오래됐어요. 후보를 다시 조회해 주세요."; return false }
        val receipt = runCatching { navigator.submit(route) }.getOrElse {
            selected = null; acknowledged = false; message = "내비 접수 오류예요. 정차용 연습을 이용하거나 다시 선택해 주세요."; return false
        }
        acknowledged = receipt.accepted && receipt.routeId == route.id && receipt.version == route.version && receipt.source == route.source
        selected = if (acknowledged) route else null
        message = if (acknowledged) "가상 코스를 접수했어요. 실제 내비 경로는 설정하지 않았어요." else "내비 접수가 확인되지 않았어요. 다시 선택하거나 정차용 연습을 이용해 주세요."
        return acknowledged
    }
    companion object { const val ROUTE_TTL_MS = 15 * 60_000L }
}
