package kr.drivestep.tutor.workflow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TrainingSignal { OFF, LEFT, RIGHT }
data class ScenarioSnapshot(val task: ScenarioTask, val status: EventStatus, val paused: Boolean, val elapsed: Double,
    val distance: Double, val speedKmh: Double, val lateral: Double, val rotation: Double, val signal: TrainingSignal,
    val mirrorChecked: Boolean, val waiting: Double, val warning: Boolean, val routeVersion: Int, val phase: Int,
    val leadGapM: Double?, val instruction: String, val cueRevision: Int, val events: List<WorkflowEvent>, val attemptId: String)

/** Deterministic, authored controls and road situations. No vehicle or GPS input is fabricated. */
class ScenarioSession(val task: ScenarioTask, val attemptId: String = "simulation") {
    private var status = EventStatus.ACTIVE
    private var paused = true
    private var elapsed = 0.0
    private var distance = 0.0
    private var speed = 0.0
    private var lateral = 0.0
    private var rotation = 0.0
    private var gas = 0.0
    private var brake = 0.0
    private var steering = 0.0
    private var signal = TrainingSignal.OFF
    private var mirror = false
    private var waiting = 0.0
    private var warning = false
    private var phase = 0
    private var stable = 0.0
    private var route = 1
    private var instruction = task.instruction
    private var revision = 1
    private val events = mutableListOf<WorkflowEvent>()
    private var eventSerial = 0
    val arbiter = AdviceArbiter()
    fun snapshot() = ScenarioSnapshot(task, status, paused, elapsed, distance, speed * 3.6, lateral, rotation, signal,
        mirror, waiting, warning, route, phase, if (task.id == "D02") max(0.0, 35 + elapsed * 4 - distance) else null,
        instruction, revision, events.toList(), attemptId)
    fun resume() { if (status == EventStatus.ACTIVE) { paused = false; gas = 0.0; brake = 0.0; steering = 0.0 } }
    fun pedals(gas: Double, brake: Double) {
        if (!paused && status == EventStatus.ACTIVE) {
            this.gas = if (gas.isFinite()) gas.coerceIn(0.0, 1.0) else 0.0
            this.brake = if (brake.isFinite()) brake.coerceIn(0.0, 1.0) else 0.0
        }
    }
    fun steer(value: Double) { if (!paused && value.isFinite()) steering = value.coerceIn(-1.0, 1.0) }
    fun signal(value: TrainingSignal) { if (!paused) signal = value }
    fun checkMirror() { if (!paused) mirror = true }
    fun waitForGap() { if (!paused && status == EventStatus.ACTIVE) { gas = 0.0; brake = 1.0; tell("가상 브레이크를 유지하며 대기해요. 기다림은 감점하지 않아요.") } }
    fun pause(reason: String = "사용자 일시 정지") {
        if (status != EventStatus.ACTIVE) return
        paused = true; gas = 0.0; brake = 0.0; steering = 0.0; speed = 0.0
        log(EventStatus.INTERRUPTED, reason); tell("중단한 과제예요. 준비 상태를 확인하고 직접 이어가기를 눌러 주세요.")
    }
    fun cancel() { if (status == EventStatus.ACTIVE) finish(EventStatus.CANCELLED, "사용자가 시도를 취소했어요. 취소는 감점하지 않아요.") }
    fun reroute() {
        if (paused || status != EventStatus.ACTIVE) return
        route++; mirror = false; signal = TrainingSignal.OFF
        log(EventStatus.CANCELLED, "이전 가상 경로 안내 폐기")
        tell("가상 재탐색 경로로 바뀌었어요. 이전 경로의 안내는 취소했어요.")
    }
    fun tick(dt: Double) {
        if (paused || status != EventStatus.ACTIVE || !dt.isFinite() || dt <= 0) return
        val step = min(dt, .2)
        elapsed += step
        speed = (speed + (gas * 2.4 - brake * 5.0 - .35) * step).coerceIn(0.0, 60.0 / 3.6)
        distance += speed * step
        lateral += steering * speed * step * .22
        rotation += steering * speed * step * 5
        if (brake > .5 && speed < .1) waiting += step
        val turnReady = mirror && signal == if (task.id in setOf("D06", "D14")) TrainingSignal.RIGHT else TrainingSignal.LEFT
        var complete = false
        when (task.id) {
            "D01" -> { if (abs(lateral) > .8) tell("가상 차로 중앙을 다시 맞춰 봐요.") else complete = distance >= 60 && speed * 3.6 in 5.0..35.0 }
            "D02" -> {
                val gap = 35 + elapsed * 4 - distance
                if (gap < 10) { stable = 0.0; tell("가상 앞차와 가까워졌어요. 브레이크로 간격을 조절해 봐요.") }
                else stable += step
                complete = distance >= 40 && stable >= 3
            }
            "D03" -> {
                if (phase == 0 && speed * 3.6 >= 10) { phase = 1; tell("이제 가상 브레이크로 완전히 멈춰 봐요.") }
                if (phase == 1 && speed < .1 && brake > .5) { phase = 2; tell("멈춤을 확인했어요. 다시 부드럽게 출발해 봐요.") }
                complete = phase == 2 && speed * 3.6 >= 10
            }
            "D04" -> {
                if (phase == 0 && distance >= 40) {
                    phase = if (speed * 3.6 <= 10) 1 else -1
                    tell(if (phase == 1) "가상 방지턱을 천천히 통과했어요." else "가상 방지턱 준비 구간을 놓쳤어요. 다시 연습할 수 있어요.")
                }
                complete = distance >= 60 && phase == 1
                if (distance >= 60 && phase == -1) finish(EventStatus.UNCONFIRMED, "방지턱 접근 속도 목표는 확인하지 못했어요.")
            }
            "D05" -> {
                if (phase == 0 && speed < .1 && brake > .5 && distance < 30) { phase = 1; tell("가상 정차를 확인했어요. 6초 뒤 화면의 신호가 바뀌어요.") }
                if (waiting >= 6 && phase == 1) { phase = 2; tell("가상 신호가 녹색으로 바뀌었어요. 주변 확인 후 연습을 이어가요.") }
                if (distance >= 30 && phase < 2) finish(EventStatus.UNCONFIRMED, "가상 신호·정차 목표를 확인하지 못했어요.")
                complete = phase == 2 && distance >= 60
            }
            "D06", "D07" -> {
                if (phase == 0 && turnReady && waiting >= 2) { phase = 1; tell("가상 대기 순서를 확인했어요. 방향을 유지하며 조향해 봐요.") }
                complete = phase == 1 && turnReady && (if (task.id == "D06") rotation >= 60 else rotation <= -60) && abs(steering) < .15
            }
            "D08" -> complete = turnReady && rotation <= -150 && abs(steering) < .15
            "D09", "D10", "D13" -> {
                val available = task.id == "D09" || waiting >= 6
                if (!available && abs(lateral) > .5) { tell("가상 옆 차가 있어요. 대기하거나 취소할 수 있어요."); mirror = false }
                complete = available && turnReady && lateral <= -3.0 && abs(steering) < .15 && (task.id != "D13" || speed * 3.6 >= 20)
            }
            "D11" -> complete = waiting >= 5 && mirror && distance >= 40
            "D12" -> {
                if (speed * 3.6 in 30.0..40.0) stable += step
                if (stable >= 3 && phase == 0) { phase = 1; tell("가상 연습 속도를 유지했어요. 브레이크로 감속해 봐요.") }
                complete = phase == 1 && speed * 3.6 <= 15 && brake > .5
            }
            "D14" -> {
                if (distance > 70 && phase == 0) finish(EventStatus.EXPIRED, "가상 출구 준비 지점을 지났어요. 늦은 차로 이동 안내는 하지 않아요.")
                if (distance <= 70 && turnReady && lateral >= 3) phase = 1
                complete = phase == 1 && distance >= 90
            }
            "D15" -> complete = distance >= 80 && route > 1
            "D16" -> {
                if (phase == 0) { stable = if (brake > .5 && speed < .1) stable + step else 0.0; if (stable >= 3) phase = 1 }
                complete = phase == 1 && distance >= 30
            }
            "D17" -> {
                if (speed * 3.6 > 20) { phase = -1; tell("가상 보호구역 과제 속도를 넘었어요. 감속하며 다시 살펴요.") }
                if (distance >= 60 && phase == -1) finish(EventStatus.UNCONFIRMED, "가상 과제 속도 유지 목표는 미확인이에요.")
                complete = phase == 0 && distance >= 60
            }
            "D18" -> {
                if (distance >= 20 && phase == 0) { warning = true; phase = 1; tell("가상 차량 경고 · 교육 음성 중단") }
                if (phase == 1 && speed < .1 && brake > .5) { warning = false; phase = 2; complete = true }
            }
        }
        if (complete && status == EventStatus.ACTIVE) finish(EventStatus.COMPLETED, "가상 과제의 조작 목표를 확인했어요.")
    }
    private fun tell(text: String) { if (instruction != text) { instruction = text; revision++ } }
    private fun finish(value: EventStatus, fact: String) { status = value; paused = true; speed = 0.0; gas = 0.0; brake = 0.0; steering = 0.0; log(value, fact); tell(fact) }
    private fun log(value: EventStatus, fact: String) {
        events += WorkflowEvent("$attemptId:${task.id}:${eventSerial++}", InputSource.SIMULATION, (elapsed * 1000).toLong(), route, value, fact, task.unknown)
        if (events.size > 160) events.removeAt(0)
    }
}

/** Missing or stale sensors never become zero-distance or an 'all clear' state. */
class DistanceAssistance(private val contextId: String, private val ttlMillis: Long = 1500) {
    var distanceM: Double? = null; private set
    var validity = InputValidity.UNSUPPORTED; private set
    fun accept(input: InputEnvelope<Double>, now: Long) {
        val quality = input.effectiveValidity(now, ttlMillis, contextId)
        validity = if (quality == InputValidity.VALID && (input.unit != "m" || !input.value!!.isFinite() || input.value < 0)) InputValidity.ERROR else quality
        distanceM = if (validity == InputValidity.VALID) input.value else null
    }
}
