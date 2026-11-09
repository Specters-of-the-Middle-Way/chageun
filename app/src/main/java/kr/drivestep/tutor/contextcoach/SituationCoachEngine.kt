package kr.drivestep.tutor.contextcoach

import kr.drivestep.tutor.coach.CoachCue
import kotlin.math.abs
import kotlin.math.max

enum class SituationSource { SIMULATION, SDK }
enum class SituationSignal { OFF, LEFT, RIGHT, HAZARD, UNKNOWN }
enum class SituationWarning { UNKNOWN, CLEAR, LEFT, RIGHT, BOTH }
enum class SituationGear { P, D, R, N, UNKNOWN }
enum class SituationRoad { UNKNOWN, STRAIGHT, INTERSECTION, PARKING }
enum class SituationKind { SPEED_DROP, SPEED_RISE, LANE_WARNING }

data class SituationInput(
    val atElapsedMillis: Long,
    val source: SituationSource,
    val signal: SituationSignal,
    val speedKmh: Double?,
    val warning: SituationWarning,
    val gear: SituationGear,
    val brakePressed: Boolean? = null,
    /** Front-wheel angle if supplied by Vehicle SDK; never steering-wheel turns. */
    val steering: Double? = null,
    val roadContext: SituationRoad = SituationRoad.UNKNOWN,
    val vehicleWarningActive: Boolean = false,
    val speedAtMillis: Long = atElapsedMillis,
    val signalAtMillis: Long = atElapsedMillis,
    val warningAtMillis: Long = atElapsedMillis,
)

/** Prototype hypotheses, not legal definitions, calibrated safety limits or OEM thresholds. */
data class SituationConfig(
    val windowMs: Long = 3000,
    val minWindowMs: Long = 2000,
    val deltaThresholdKmh: Double = 12.0,
    val minSpeedKmh: Double = 15.0,
    val cooldownMs: Long = 15000,
    val freshnessMs: Long = 1500,
) {
    init {
        require(windowMs > 0 && minWindowMs in 1..windowMs)
        require(deltaThresholdKmh.isFinite() && deltaThresholdKmh > 0)
        require(minSpeedKmh.isFinite() && minSpeedKmh >= 0)
        require(cooldownMs >= 0 && freshnessMs > 0)
    }
}

data class SituationEvent(
    val id: String,
    val kind: SituationKind,
    val title: String,
    val cue: CoachCue,
    val reason: String,
    val evidence: List<String>,
    val observations: List<SituationInput>,
    /** OEM warnings take priority. Do not queue this speech for later playback. */
    val speechSuppressed: Boolean,
    val source: SituationSource,
    val atElapsedMillis: Long,
)

data class SituationResult(
    val event: SituationEvent?,
    val status: String,
    val speedDeltaKmh: Double?,
    val cooldownRemainingMs: Long,
)

object SituationCues {
    val DROP = CoachCue("situation_speed_drop", "속도가 줄고 있어요. 차로 변경을 서두르지 말고, 앞차와 주변을 살펴요.")
    val RISE = CoachCue("situation_speed_rise", "속도가 늘고 있어요. 급한 조작을 줄이고, 주변 흐름을 살펴요.")
    val WARNING = CoachCue("situation_lane_warning", "차량의 경고를 먼저 확인해요. 급하게 차로를 바꾸지 마세요.")
    val catalog = listOf(DROP, RISE, WARNING)
}

/**
 * Observes evidence of possible difficulty, not proof of a lane change or its safety.
 * No control outputs, collision predictions, safe-gap decisions or legal scoring.
 * Current app wires SIMULATION only; SDK is an explicit, isolated future adapter contract.
 */
class SituationCoachEngine(private val config: SituationConfig = SituationConfig()) {
    private var source: SituationSource? = null
    private var lastTime: Long? = null
    private var attemptDirection: SituationSignal? = null
    private var speedIssued = false
    private var warningIssued = false
    private var previousWarning = SituationWarning.UNKNOWN
    private var lastEventAt: Long? = null
    private var lastKind: SituationKind? = null
    private var sequence = 0L
    private val speedWindow = ArrayDeque<SituationInput>()
    private val history = ArrayDeque<SituationInput>()

    /** A deliberate new demo/connection. Not called for every observation. */
    fun reset() {
        source = null; lastTime = null; attemptDirection = null
        speedIssued = false; warningIssued = false; previousWarning = SituationWarning.UNKNOWN
        lastEventAt = null; lastKind = null
        speedWindow.clear(); history.clear()
    }

    fun accept(input: SituationInput): SituationResult {
        val now = input.atElapsedMillis
        if (now < 0) return result("관측 시간을 확인할 수 없어요", now)
        if (source != null && source != input.source) {
            reset()
            source = input.source; lastTime = now
            return result("입력 출처가 바뀌어 이전 관측을 비웠어요", now)
        }
        source = input.source
        val previousTime = lastTime
        if (previousTime != null && now <= previousTime) return result("중복·역순 관측은 제외했어요", now)
        if (previousTime != null && now - previousTime > config.freshnessMs) {
            clearAttempt()
            warningIssued = false; previousWarning = SituationWarning.UNKNOWN
            history.clear()
        }
        lastTime = now
        history.addLast(input)
        while (history.isNotEmpty() && (now - history.first().atElapsedMillis > 6000 || history.size > 60)) history.removeFirst()
        if (input.gear != SituationGear.D) {
            clearAttempt(); return result("D 주행 문맥이 아니어서 차로 변경 코칭을 쉬어요", now)
        }
        if (input.roadContext in setOf(SituationRoad.INTERSECTION, SituationRoad.PARKING)) {
            clearAttempt(); return result("교차로·주차 문맥은 차로 변경 상황으로 판단하지 않아요", now)
        }
        if (!fresh(input.signalAtMillis, now) || input.signal in setOf(SituationSignal.UNKNOWN, SituationSignal.HAZARD)) {
            clearAttempt(); return result("방향지시 상태 미확인·비상등은 차로 변경 의도로 판단하지 않아요", now)
        }
        val warningFresh = fresh(input.warningAtMillis, now)
        val activeWarning = warningFresh && input.warning in ACTIVE_WARNINGS
        if (!activeWarning || previousWarning != input.warning) warningIssued = false
        previousWarning = if (warningFresh) input.warning else SituationWarning.UNKNOWN
        // A warning overrides a just-spoken speed observation, but not another repeated warning.
        val warningCanEscalate = lastKind != SituationKind.LANE_WARNING
        if (activeWarning && !warningIssued && (cooldown(now) == 0L || warningCanEscalate)) {
            warningIssued = true; speedIssued = true
            return emit(input, SituationKind.LANE_WARNING, null)
        }
        if (activeWarning) return result("차량 경고를 우선해 같은 안내를 반복하지 않아요", now)
        if (input.signal !in DIRECTIONS) {
            clearAttempt()
            return result("방향지시 의도가 나타나면 조작 변화를 함께 살펴요", now)
        }
        if (attemptDirection != input.signal) {
            attemptDirection = input.signal; speedIssued = false; speedWindow.clear()
        }
        val speed = input.speedKmh
        if (!fresh(input.speedAtMillis, now) || speed == null || !speed.isFinite() || speed < 0 || speed > 250) {
            speedWindow.clear()
            return result("속도 신호가 없거나 오래되어 변화 추정을 멈췄어요", now)
        }
        speedWindow.addLast(input)
        while (speedWindow.isNotEmpty() && (now - speedWindow.first().atElapsedMillis > config.windowMs || speedWindow.size > 100)) speedWindow.removeFirst()
        val first = speedWindow.first()
        val span = now - first.atElapsedMillis
        val baseline = first.speedKmh!!
        val delta = speed - baseline
        if (speedIssued) return result("이번 시도는 이미 안내했어요. 정차 후 복습으로 이어가요", now, delta)
        if (span < config.minWindowMs) return result("방향지시 중 · 속도 변화를 잠시 관찰하고 있어요", now, delta)
        if (max(speed, baseline) < config.minSpeedKmh) return result("저속 조작을 차로 변경으로 단정하지 않아요", now, delta)
        if (abs(delta) < config.deltaThresholdKmh) return result("관측 중 · 필요한 순간에만 짧게 안내해요", now, delta)
        if (cooldown(now) > 0) return result("추가 발화를 잠시 쉬고 있어요", now, delta)
        return emit(input, if (delta < 0) SituationKind.SPEED_DROP else SituationKind.SPEED_RISE, delta)
    }

    private fun emit(input: SituationInput, kind: SituationKind, delta: Double?): SituationResult {
        lastEventAt = input.atElapsedMillis; lastKind = kind
        if (kind != SituationKind.LANE_WARNING) speedIssued = true
        val side = when (input.signal) { SituationSignal.LEFT -> "왼쪽"; SituationSignal.RIGHT -> "오른쪽"; else -> "방향지시 없음" }
        val title = when(kind) {
            SituationKind.SPEED_DROP -> "방향지시 중 속도가 줄었어요"
            SituationKind.SPEED_RISE -> "방향지시 중 속도가 늘었어요"
            SituationKind.LANE_WARNING -> "차로 변경 경고가 들어왔어요"
        }
        val cue = when(kind) { SituationKind.SPEED_DROP -> SituationCues.DROP; SituationKind.SPEED_RISE -> SituationCues.RISE; SituationKind.LANE_WARNING -> SituationCues.WARNING }
        val warningLabel = when(input.warning) {
            SituationWarning.UNKNOWN -> "미확인"; SituationWarning.CLEAR -> "없음으로 입력됨"
            SituationWarning.LEFT -> "왼쪽"; SituationWarning.RIGHT -> "오른쪽"; SituationWarning.BOTH -> "양쪽"
        }
        val evidence = listOf(
            "방향지시: $side",
            if (delta == null) "속도 변화 조건과 별도로 경고를 감지" else "최근 ${config.windowMs / 1000}초 이내 관측 변화: ${String.format(java.util.Locale.KOREA, "%+.1f", delta)} km/h",
            "차로 변경 경고: $warningLabel",
            "브레이크: ${input.brakePressed?.let { if (it) "눌림" else "놓임" } ?: "미확인"}",
            "조향값: ${input.steering?.takeIf { it.isFinite() }?.let { "$it° · 전륜 기준 입력" } ?: "미확인"}",
            if (input.roadContext == SituationRoad.UNKNOWN) "도로 문맥 미확인 · 차로 변경 확정 아님" else "도로 문맥: 직선도로로 입력됨",
            if (input.source == SituationSource.SIMULATION) "시험용 가상 입력 · 실제 도로 감지 아님" else "차량 입력 · 도로의 안전성을 판정하지 않음",
        )
        val reason = if (kind == SituationKind.LANE_WARNING) "입력된 차량 경고를 우선해 해당 상황의 복습을 준비했어요."
            else "방향지시와 속도 변화가 함께 나타났어요. 어려움을 겪는 상황일 수 있어 짧게 돕되, 실제 차로 변경 여부는 확정하지 않아요."
        val event = SituationEvent("${input.source.name}:${input.atElapsedMillis}:${++sequence}", kind, title, cue, reason,
            evidence, history.toList(), input.vehicleWarningActive, input.source, input.atElapsedMillis)
        return SituationResult(event,
            if (event.speechSuppressed) "차량 경고음이 우선이에요. 추가 음성 없이 복습만 남겼어요" else "상황을 감지해 코치가 먼저 안내해요",
            delta, config.cooldownMs)
    }

    private fun clearAttempt() { attemptDirection = null; speedIssued = false; speedWindow.clear() }
    private fun fresh(measured: Long, now: Long) = measured >= 0 && measured <= now && now - measured <= config.freshnessMs
    private fun cooldown(now: Long): Long = lastEventAt?.let { (config.cooldownMs - (now - it)).coerceAtLeast(0) } ?: 0
    private fun result(message: String, now: Long, delta: Double? = null) = SituationResult(null, message, delta, cooldown(now))
    private companion object {
        val DIRECTIONS = setOf(SituationSignal.LEFT, SituationSignal.RIGHT)
        val ACTIVE_WARNINGS = setOf(SituationWarning.LEFT, SituationWarning.RIGHT, SituationWarning.BOTH)
    }
}
