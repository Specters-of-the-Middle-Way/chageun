package kr.drivestep.tutor.lessoncoach

import kotlin.math.abs

/**
 * Parked emulator demonstration / observation engine, not a lane-gap decision system.
 * Host owns UX gating, audio priority and persistence consent. All times are monotonic.
 */
class LessonCoachSession(
    private val freshnessMs: Long = 1500,
    private val dialogueTimeoutMs: Long = 15000,
    private val maxEvents: Int = 160,
) {
    private val parser = LessonUtteranceParser()
    private var state = LessonSnapshot()
    private var eventSequence = 0L
    private var cueSequence = 0L
    private var lastVehicleAt = -1L
    private var lastVehicleEventAt = -1L
    private var lastNavigationAt = -1L
    private var directionAskedAt: Long? = null
    private var pendingTraffic = ReportedTraffic()
    private var activeRequest = false
    private var signalAcknowledged = false
    private var preparedManeuver: String? = null
    private var preparationDirection = LessonDirection.UNKNOWN
    private var lastNavigationIdentity: Pair<NavigationInputSource, String>? = null
    private var requestAtMillis: Long? = null
    private var lastUtteranceAt = -1L
    private var confirmationAtMillis: Long? = null
    private var candidateDirection = LessonDirection.UNKNOWN
    private var candidateTraffic = ReportedTraffic()
    private var candidateIntent = LessonIntent.LANE_CHANGE
    private var directionQuestionIntent = LessonIntent.LANE_CHANGE

    init {
        require(freshnessMs > 0 && dialogueTimeoutMs > 0 && maxEvents > 0)
    }

    fun snapshot(): LessonSnapshot = state

    fun start(topic: LessonTopic, now: Long): LessonSnapshot {
        require(now >= 0)
        clearContext()
        state = LessonSnapshot(topic = topic, phase = LessonPhase.ACTIVE, cueRevision = cueSequence)
        lastVehicleAt = -1; lastVehicleEventAt = -1; lastNavigationAt = -1
        lastUtteranceAt = -1
        add(now, LessonEventKind.START, LessonEvidence.APP, topic.title, "학습 과정 시작 · 운전 숙련도 점수 아님")
        return reply(now, when (topic) {
            LessonTopic.LANE_CHANGE -> "차로 변경 준비를 함께해요. 원하는 방향을 말해 주세요."
            LessonTopic.TURN_PREP -> "다음 회전 정보를 받으면 준비 순서를 짧게 안내할게요."
            LessonTopic.START_STOP -> "출발과 정지 때 내 차의 조작을 기록하고, 마친 뒤 함께 돌아봐요."
        })
    }

    /** Only a completed STT result, or explicitly labelled test text, belongs here. */
    fun acceptUtterance(text: String, source: SpeechInputSource, now: Long, interpretation: ParsedLessonUtterance? = null): LessonSnapshot {
        if (now < 0 || state.phase in setOf(LessonPhase.READY, LessonPhase.FINISHED)) return state
        val clean = text.trim().take(300)
        if (clean.isBlank()) return state
        if (clean == state.lastUtterance && source == state.speechInputSource && now >= lastUtteranceAt && now - lastUtteranceAt < 750) return state
        val parsed = interpretation ?: parser.parse(clean)
        val requiresConfirmation = source == SpeechInputSource.PLEOS_STT || interpretation != null
        if (state.phase == LessonPhase.PAUSED && parsed.intent !in setOf(LessonIntent.RESUME, LessonIntent.CANCEL)) return state
        lastUtteranceAt = now
        state = state.copy(lastUtterance = clean, speechInputSource = source)
        add(now, LessonEventKind.UTTERANCE, LessonEvidence.DRIVER_REPORTED,
            if (source == SpeechInputSource.PLEOS_STT) "운전자 음성" else "시험 문장 입력", clean)
        when (parsed.intent) {
            LessonIntent.CANCEL -> {
                clearContext()
                state = state.copy(direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic(), awaitingDirection = false, awaitingConfirmation = false)
                return reply(now, "요청을 취소했어요. 준비되면 다시 알려 주세요.")
            }
            LessonIntent.PAUSE -> return pause(now, "운전자가 음성 안내를 쉬도록 요청함")
            LessonIntent.RESUME -> return resume(now)
            LessonIntent.REPEAT -> return reply(now, state.reply)
            LessonIntent.ENTRY_QUESTION -> {
                clearContext()
                state = state.copy(direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic(), awaitingDirection = false, awaitingConfirmation = false)
                return reply(now, "주변 차량을 직접 확인할 수 없어 진입 시점은 판단할 수 없어요. 미러와 사각지대를 확인하는 순서를 도와드릴게요.")
            }
            else -> Unit
        }
        if (state.awaitingConfirmation) {
            val freshConfirmation = confirmationAtMillis?.let { now >= it && now - it <= dialogueTimeoutMs } == true
            if (!freshConfirmation) {
                clearConfirmation()
                state = state.copy(awaitingConfirmation = false, direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic())
                if (parsed.intent in setOf(LessonIntent.CONFIRM, LessonIntent.REJECT))
                    return reply(now, "확인 시간이 지나 요청을 비웠어요. 원하는 동작과 방향을 다시 알려 주세요.")
            } else {
                if (parsed.intent == LessonIntent.CONFIRM) {
                    if (parsed.direction != LessonDirection.UNKNOWN && parsed.direction != candidateDirection) {
                        return confirmDirection(parsed.direction, parsed.traffic, candidateIntent, now)
                    }
                    val direction = candidateDirection
                    val traffic = candidateTraffic
                    val intent = candidateIntent
                    clearConfirmation()
                    state = state.copy(awaitingConfirmation = false)
                    return acceptDirection(direction, traffic, intent, now)
                }
                if (parsed.intent == LessonIntent.REJECT) {
                    val intent = candidateIntent
                    clearConfirmation()
                    directionAskedAt = now; directionQuestionIntent = intent; pendingTraffic = ReportedTraffic()
                    state = state.copy(awaitingConfirmation = false, awaitingDirection = true, direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic())
                    return reply(now, "잘못 들었군요. 원하는 방향을 왼쪽 또는 오른쪽으로 다시 알려 주세요.")
                }
                if (parsed.direction != LessonDirection.UNKNOWN) {
                    val intent = if (parsed.intent == LessonIntent.TURN) LessonIntent.TURN else candidateIntent
                    // A changed direction never inherits the old side's traffic report.
                    val traffic = if (parsed.hasTrafficReport) parsed.traffic.copy(atMillis = now)
                    else if (parsed.direction == candidateDirection) candidateTraffic else ReportedTraffic()
                    return confirmDirection(parsed.direction, traffic, intent, now)
                }
                clearConfirmation()
                state = state.copy(awaitingConfirmation = false, direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic())
                return reply(now, "방향을 확정하지 않았어요. 원하는 동작과 방향을 다시 알려 주세요.")
            }
        }
        if (parsed.intent in setOf(LessonIntent.CONFIRM, LessonIntent.REJECT)) {
            return reply(now, "확인 중인 방향이 없어요. 원하는 동작과 방향을 먼저 알려 주세요.")
        }
        val waiting = directionAskedAt?.let { now >= it && now - it <= dialogueTimeoutMs } == true
        if (!waiting && state.awaitingDirection) {
            directionAskedAt = null; pendingTraffic = ReportedTraffic()
            state = state.copy(awaitingDirection = false, direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic())
        }
        if (waiting) {
            if (parsed.direction == LessonDirection.UNKNOWN) {
                directionAskedAt = null; pendingTraffic = ReportedTraffic(); activeRequest = false
                state = state.copy(awaitingDirection = false)
                return reply(now, "방향이 정해지면 왼쪽 또는 오른쪽 차로 변경이라고 알려 주세요.")
            }
            val traffic = if (parsed.hasTrafficReport) parsed.traffic.copy(atMillis = now) else pendingTraffic
            val intent = if (parsed.intent == LessonIntent.TURN) LessonIntent.TURN else directionQuestionIntent
            directionAskedAt = null; pendingTraffic = ReportedTraffic()
            return if (requiresConfirmation) confirmDirection(parsed.direction, traffic, intent, now)
            else acceptDirection(parsed.direction, traffic, intent, now)
        }
        if (parsed.intent == LessonIntent.TURN) {
            if (parsed.direction == LessonDirection.UNKNOWN) return reply(now, "회전 방향을 확인하지 못했어요. 좌회전 또는 우회전으로 다시 알려 주세요.")
            return if (requiresConfirmation) confirmDirection(parsed.direction, ReportedTraffic(), LessonIntent.TURN, now)
            else acceptDirection(parsed.direction, ReportedTraffic(), LessonIntent.TURN, now)
        }
        if (parsed.intent == LessonIntent.LANE_CHANGE || (parsed.direction != LessonDirection.UNKNOWN && !parsed.hasTrafficReport)) {
            signalAcknowledged = false
            state = state.copy(direction = parsed.direction,
                reportedTraffic = if (parsed.hasTrafficReport) parsed.traffic.copy(atMillis = now) else ReportedTraffic())
            if (parsed.direction == LessonDirection.UNKNOWN) {
                directionAskedAt = now; pendingTraffic = state.reportedTraffic; activeRequest = false
                directionQuestionIntent = LessonIntent.LANE_CHANGE
                state = state.copy(awaitingDirection = true)
                return reply(now, "어느 쪽으로 차로를 바꾸고 싶으세요?")
            }
            return if (requiresConfirmation) confirmDirection(parsed.direction, state.reportedTraffic, LessonIntent.LANE_CHANGE, now)
            else acceptDirection(parsed.direction, state.reportedTraffic, LessonIntent.LANE_CHANGE, now)
        }
        if (parsed.hasTrafficReport) {
            if (activeRequest && parsed.direction != LessonDirection.UNKNOWN && parsed.direction != state.direction) {
                // A report about the other side must not silently become evidence for the
                // requested side. Ask once and discard the conflicting report from context.
                directionAskedAt = now; pendingTraffic = ReportedTraffic(); activeRequest = false; requestAtMillis = null
                directionQuestionIntent = LessonIntent.LANE_CHANGE
                state = state.copy(direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic(), awaitingDirection = true)
                return reply(now, "다른 쪽 상황으로 들었어요. 변경하려는 방향을 왼쪽 또는 오른쪽으로 알려 주세요.")
            }
            state = state.copy(reportedTraffic = parsed.traffic.copy(atMillis = now))
            if (activeRequest && requestAtMillis?.let { now >= it && now - it <= dialogueTimeoutMs } == true && state.direction != LessonDirection.UNKNOWN) return lanePreparation(now)
            return reply(now, "말씀하신 주변 상황을 참고할게요. 차로 변경을 원하면 방향도 함께 알려 주세요.")
        }
        return reply(now, "왼쪽 차로 변경처럼 원하는 동작을 짧게 알려 주세요. 다시 설명이나 취소도 말할 수 있어요.")
    }

    /** Replace the just-computed, not-yet-presented response; preserve one actual spoken reply in history. */
    fun applyLlmWording(result: TutorLlmReply, now: Long): LessonSnapshot {
        if (!TutorLlmPolicy.canUseWording(result, state)) return state
        val last = state.events.lastOrNull()
        if (last?.kind != LessonEventKind.GUIDANCE) return state
        val title = if (result.provider == TutorLlmProvider.OPENAI) "AI 튜터 안내 · OpenAI" else "연습용 예시 답변"
        state = state.copy(reply = result.text, cueRevision = ++cueSequence,
            events = state.events.dropLast(1) + last.copy(atMillis = now, title = title, detail = result.text))
        return state
    }

    fun acceptVehicle(observation: VehicleObservation, now: Long): LessonSnapshot {
        if (state.phase != LessonPhase.ACTIVE || now < 0 || !fresh(observation.atMillis, now)) return state
        val previous = state.vehicle
        if (previous != null && observation.source != previous.source) {
            clearContext()
            state = state.copy(vehicle = null, navigation = null, direction = LessonDirection.UNKNOWN,
                reportedTraffic = ReportedTraffic(), awaitingDirection = false, awaitingConfirmation = false, reply = "입력 출처가 바뀌어 새로운 상황부터 확인해요.")
            lastVehicleAt = -1; lastVehicleEventAt = -1; lastNavigationAt = -1
            add(now, LessonEventKind.VEHICLE, LessonEvidence.APP, "입력 출처 변경", "이전 조작·대화 문맥을 비웠어요.")
        }
        if (observation.atMillis <= lastVehicleAt) return state
        lastVehicleAt = observation.atMillis
        val sample = sanitize(observation, now)
        val before = state.vehicle
        state = state.copy(vehicle = sample)
        val evidence = if (sample.source == VehicleInputSource.SDK) LessonEvidence.SDK else LessonEvidence.SIMULATION
        val meaningfulChange = before == null || sample.signal != before.signal || sample.brakePressed != before.brakePressed || sample.gear != before.gear
        if (meaningfulChange || now - lastVehicleEventAt >= 1000) {
            add(now, LessonEventKind.VEHICLE, evidence,
                if (sample.isInitial) "차량 상태 확인" else "내 차 조작 기록", "주변 차량·변경 성공 여부는 판단하지 않음", sample)
            lastVehicleEventAt = now
        }
        if (sample.isInitial || before == null || before.source != sample.source) return state
        val expected = if (activeRequest) state.direction else preparationDirection
        if (!signalAcknowledged && expected != LessonDirection.UNKNOWN && matches(sample.signal, expected) && !matches(before.signal, expected)) {
            signalAcknowledged = true
            return reply(now, "${expected.label} 방향지시등이 켜졌어요. 미러와 사각지대는 직접 확인해 주세요.")
        }
        // The log contains observations, not blame, competence scores or live braking commands.
        return state
    }

    fun acceptNavigation(observation: NavigationObservation, now: Long): LessonSnapshot {
        if (state.phase != LessonPhase.ACTIVE || !fresh(observation.atMillis, now) || observation.atMillis <= lastNavigationAt) return state
        val ownSource = state.vehicle?.source
        if ((ownSource == VehicleInputSource.SDK && observation.source == NavigationInputSource.SIMULATION) ||
            (ownSource == VehicleInputSource.SIMULATION && observation.source == NavigationInputSource.NAVI)) return state
        lastNavigationAt = observation.atMillis
        if (observation.maneuverId.isBlank() || !observation.remainingMeters.isFinite() || observation.remainingMeters < 0 || observation.direction == LessonDirection.UNKNOWN) {
            state = state.copy(navigation = null)
            preparedManeuver = null; preparationDirection = LessonDirection.UNKNOWN; signalAcknowledged = false
            lastNavigationIdentity = null
            return state
        }
        val identity = observation.source to observation.maneuverId
        val changed = lastNavigationIdentity != identity
        lastNavigationIdentity = identity
        state = state.copy(navigation = observation)
        if (changed) {
            preparedManeuver = null; preparationDirection = LessonDirection.UNKNOWN; signalAcknowledged = false
            add(now, LessonEventKind.NAVIGATION,
                if (observation.source == NavigationInputSource.NAVI) LessonEvidence.NAVI else LessonEvidence.SIMULATION,
                "다음 ${observation.direction.label} 회전", "준비 안내용 경로 정보 · 실제 주행 차로와 진입 공간은 미확인")
        }
        // This is a demo preparation window, not an instruction on when to change lanes or signal.
        if (observation.remainingMeters !in 80.0..500.0 || activeRequest || state.awaitingDirection || state.awaitingConfirmation) return state
        if (preparedManeuver == observation.maneuverId) {
            preparationDirection = observation.direction
            return state
        }
        preparedManeuver = observation.maneuverId; preparationDirection = observation.direction
        val sample = state.vehicle
        val alreadyOn = sample != null && fresh(sample.signalAtMillis, now) && matches(sample.signal, observation.direction)
        signalAcknowledged = alreadyOn
        return reply(now, if (alreadyOn)
            "${observation.direction.label} 회전이 다가와요. 방향지시등은 켜져 있어요. 주변을 확인하며 내비 안내를 따라 준비해요."
        else "${observation.direction.label} 회전이 다가와요. 주변을 확인하고 방향지시등을 준비해요.")
    }

    /** Called by host timer if no inputs arrive; never keep stale values looking live. */
    fun tick(now: Long): LessonSnapshot {
        if (state.phase != LessonPhase.ACTIVE || now < 0) return state
        val navigation = state.navigation?.takeIf { fresh(it.atMillis, now) }
        if (navigation == null) preparationDirection = LessonDirection.UNKNOWN
        state = state.copy(vehicle = state.vehicle?.let { sanitize(it, now) }, navigation = navigation)
        if (state.reportedTraffic.atMillis?.let { now - it > dialogueTimeoutMs || now < it } == true) state = state.copy(reportedTraffic = ReportedTraffic())
        if (directionAskedAt?.let { now - it > dialogueTimeoutMs || now < it } == true) {
            directionAskedAt = null; pendingTraffic = ReportedTraffic()
            state = state.copy(awaitingDirection = false)
        }
        if (requestAtMillis?.let { now - it > dialogueTimeoutMs || now < it } == true) {
            requestAtMillis = null; activeRequest = false; signalAcknowledged = false
            state = state.copy(direction = LessonDirection.UNKNOWN)
        }
        if (confirmationAtMillis?.let { now - it > dialogueTimeoutMs || now < it } == true) {
            clearConfirmation()
            state = state.copy(awaitingConfirmation = false, direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic(),
                reply = "확인 시간이 지나 요청을 비웠어요. 원하는 방향을 다시 알려 주세요.")
        }
        return state
    }

    fun pause(now: Long, reason: String = "안내를 잠시 쉬어요"): LessonSnapshot {
        if (state.phase != LessonPhase.ACTIVE) return state
        clearContext()
        state = state.copy(phase = LessonPhase.PAUSED, awaitingDirection = false, awaitingConfirmation = false, direction = LessonDirection.UNKNOWN,
            reportedTraffic = ReportedTraffic(), vehicle = null, navigation = null)
        add(now, LessonEventKind.PAUSE, LessonEvidence.APP, "연수 일시 정지", reason)
        // No cue revision: lifecycle pause must not produce a late spoken instruction.
        state = state.copy(reply = "안내를 쉬고 있어요. 다시 시작하면 새로운 상황부터 확인해요.")
        return state
    }

    fun resume(now: Long): LessonSnapshot {
        if (state.phase != LessonPhase.PAUSED) return state
        lastVehicleAt = -1; lastVehicleEventAt = -1; lastNavigationAt = -1
        state = state.copy(phase = LessonPhase.ACTIVE)
        add(now, LessonEventKind.RESUME, LessonEvidence.APP, "연수 다시 시작", "이전 주변 설명을 이어서 판단하지 않음")
        return reply(now, "다시 함께할게요. 원하는 동작이나 방향을 알려 주세요.")
    }

    fun finish(reflection: LessonReflection, now: Long): LessonSnapshot {
        if (state.phase !in setOf(LessonPhase.ACTIVE, LessonPhase.PAUSED)) return state
        clearContext()
        val next = if (reflection != LessonReflection.EASY) state.topic else when (state.topic) {
            LessonTopic.LANE_CHANGE -> LessonTopic.TURN_PREP
            LessonTopic.TURN_PREP -> LessonTopic.START_STOP
            LessonTopic.START_STOP -> LessonTopic.LANE_CHANGE
        }
        state = state.copy(phase = LessonPhase.FINISHED, awaitingDirection = false, awaitingConfirmation = false, reflection = reflection, nextLesson = next,
            nextLessonReason = if (reflection == LessonReflection.EASY) "수월했다고 답해 다음 학습 항목을 제안해요. 숙련도 인증은 아니에요."
            else "운전자 복습 응답에 따라 같은 항목을 다시 제안해요. 감속을 실수로 채점하지 않아요.")
        add(now, LessonEventKind.FINISH, LessonEvidence.APP, "연수 복습", "${reflection.label} · 다음 학습: ${next.title}")
        return reply(now, "오늘 조작을 함께 돌아봤어요. 다음에는 ${next.title}를 이어서 연습해요.")
    }

    private fun confirmDirection(direction: LessonDirection, traffic: ReportedTraffic, intent: LessonIntent, now: Long): LessonSnapshot {
        candidateDirection = direction; candidateTraffic = traffic; candidateIntent = intent; confirmationAtMillis = now
        directionAskedAt = null; pendingTraffic = ReportedTraffic(); activeRequest = false; requestAtMillis = null
        signalAcknowledged = false; preparationDirection = LessonDirection.UNKNOWN
        state = state.copy(direction = LessonDirection.UNKNOWN, reportedTraffic = ReportedTraffic(), awaitingDirection = false, awaitingConfirmation = true)
        val action = if (intent == LessonIntent.TURN) "회전 준비" else "차로 변경 준비"
        return reply(now, "${direction.label} ${action}가 맞나요? 맞아요 또는 아니요로 알려 주세요.")
    }

    private fun acceptDirection(direction: LessonDirection, traffic: ReportedTraffic, intent: LessonIntent, now: Long): LessonSnapshot {
        state = state.copy(direction = direction, reportedTraffic = traffic, awaitingDirection = false, awaitingConfirmation = false)
        signalAcknowledged = false
        if (intent == LessonIntent.TURN) {
            activeRequest = false; requestAtMillis = null
            return reply(now, "${direction.label} 회전 목적을 확인했어요. 차로 변경 요청과 구분해서, 내비의 회전 안내에 맞춰 준비할게요.")
        }
        activeRequest = true; requestAtMillis = now
        return lanePreparation(now)
    }

    private fun clearConfirmation() {
        confirmationAtMillis = null; candidateDirection = LessonDirection.UNKNOWN; candidateTraffic = ReportedTraffic(); candidateIntent = LessonIntent.LANE_CHANGE
    }

    private fun lanePreparation(now: Long): LessonSnapshot {
        val direction = state.direction.label
        val traffic = state.reportedTraffic
        val context = when {
            traffic.approaching == true -> "차가 계속 온다고 말씀하셨군요. "
            traffic.flow == TrafficFlow.STOPPED -> "옆 차들이 멈춰 있다고 말씀하셨군요. "
            traffic.density == TrafficDensity.HIGH -> "차가 많다고 말씀하셨군요. "
            traffic.density == TrafficDensity.LOW -> "차가 적다는 말씀도 주변 확인을 대신하지는 않아요. "
            else -> ""
        }
        return reply(now, "$context${direction} 차로 변경 준비를 도와드릴게요. 서두르지 말고 미러와 사각지대부터 확인해요.")
    }

    private fun sanitize(value: VehicleObservation, now: Long) = value.copy(
        speedKmh = value.speedKmh?.takeIf { fresh(value.speedAtMillis, now) && it.isFinite() && it in 0.0..250.0 },
        signal = value.signal.takeIf { fresh(value.signalAtMillis, now) } ?: LessonSignal.UNKNOWN,
        gear = value.gear.takeIf { fresh(value.gearAtMillis, now) } ?: LessonGear.UNKNOWN,
        brakePressed = value.brakePressed.takeIf { fresh(value.brakeAtMillis, now) },
        frontWheelAngleDegrees = value.frontWheelAngleDegrees?.takeIf { fresh(value.steeringAtMillis, now) && it.isFinite() && abs(it) <= 90.0 },
    )

    private fun matches(signal: LessonSignal, direction: LessonDirection) =
        (signal == LessonSignal.LEFT && direction == LessonDirection.LEFT) || (signal == LessonSignal.RIGHT && direction == LessonDirection.RIGHT)
    private fun fresh(at: Long, now: Long) = at >= 0 && now >= at && now - at <= freshnessMs
    private fun clearContext() {
        directionAskedAt = null; pendingTraffic = ReportedTraffic(); activeRequest = false; signalAcknowledged = false
        preparedManeuver = null; preparationDirection = LessonDirection.UNKNOWN
        lastNavigationIdentity = null
        requestAtMillis = null
        clearConfirmation(); directionQuestionIntent = LessonIntent.LANE_CHANGE
    }
    private fun reply(now: Long, text: String): LessonSnapshot {
        state = state.copy(reply = text, cueRevision = ++cueSequence)
        add(now, LessonEventKind.GUIDANCE, LessonEvidence.APP, "튜터 안내", text)
        return state
    }
    private fun add(now: Long, kind: LessonEventKind, source: LessonEvidence, title: String, detail: String, vehicle: VehicleObservation? = null) {
        val event = LessonEvent(++eventSequence, now, kind, source, title, detail, vehicle)
        val events = (state.events + event).toMutableList()
        if (events.size > maxEvents) {
            // Periodic vehicle samples must not erase the conversation while a learner
            // listens or pauses to think. Keep the bounded timeline in its original order.
            val observation = events.indexOfFirst {
                it.id != event.id && it.kind == LessonEventKind.VEHICLE && it.vehicle != null
            }
            events.removeAt(if (observation >= 0) observation else 0)
        }
        state = state.copy(events = events.toList())
    }
}
