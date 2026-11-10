package kr.drivestep.tutor.lessoncoach

enum class TutorLlmProvider { OPENAI, TEST_FIXTURE }
data class TutorLlmReply(
    val provider: TutorLlmProvider,
    val interpretation: ParsedLessonUtterance,
    val text: String,
    val risk: String,
)

/** Only this active lesson's bounded conversation. Historical readings are never live evidence. */
object TutorLlmContext {
    fun build(snapshot: LessonSnapshot, now: Long, live: Boolean): Map<String, Any?> {
        fun fresh(at: Long) = at >= 0 && now >= at && now - at <= 1500
        fun sample(value: Any?, at: Long) = mapOf("value" to value.takeIf { fresh(at) },
            "ageMs" to (now - at).takeIf { at >= 0 && it >= 0 })
        val vehicle = snapshot.vehicle?.let { v -> mapOf(
            "source" to v.source.name,
            "speedKmh" to sample(v.speedKmh?.takeIf { it.isFinite() }, v.speedAtMillis),
            "signal" to sample(v.signal.name, v.signalAtMillis),
            "gear" to sample(v.gear.name, v.gearAtMillis),
            "brakePressed" to sample(v.brakePressed, v.brakeAtMillis),
            "frontWheelAngleDegrees" to sample(v.frontWheelAngleDegrees?.takeIf { it.isFinite() }, v.steeringAtMillis),
        ) }
        val nav = snapshot.navigation?.takeIf { fresh(it.atMillis) }?.let { mapOf(
            "source" to it.source.name, "direction" to it.direction.name,
            "remainingMeters" to it.remainingMeters.takeIf { n -> n.isFinite() }, "ageMs" to now - it.atMillis,
        ) }
        return mapOf("topic" to snapshot.topic.name, "mode" to if (live) "SDK" else "SIMULATION",
            "direction" to snapshot.direction.name, "awaitingDirection" to snapshot.awaitingDirection,
            "awaitingConfirmation" to snapshot.awaitingConfirmation,
            "reportedTraffic" to snapshot.reportedTraffic.takeIf { it.atMillis?.let { at -> now >= at && now - at <= 15000 } == true }
                ?.let { mapOf("source" to "DRIVER_REPORTED", "density" to it.density.name, "flow" to it.flow.name, "approaching" to it.approaching) },
            "vehicle" to vehicle, "navigation" to nav,
            "limitations" to listOf("PARKED_TRAINING_ONLY", "NO_ADJACENT_TRAFFIC_SENSOR", "NO_CAMERA", "NO_ENTRY_PERMISSION", "READINGS_AT_CAPTURE_TIME"))
    }

    fun history(snapshot: LessonSnapshot, now: Long): List<Map<String, Any>> {
        val reset = snapshot.events.indexOfLast { it.kind in setOf(LessonEventKind.START, LessonEventKind.PAUSE, LessonEventKind.RESUME) || it.title == "입력 출처 변경" }
        return snapshot.events.drop(reset + 1).filter {
            it.kind in setOf(LessonEventKind.UTTERANCE, LessonEventKind.GUIDANCE) && now >= it.atMillis && now - it.atMillis <= 120000
        }.takeLast(12).map { e -> mapOf("role" to if (e.kind == LessonEventKind.UTTERANCE) "user" else "assistant",
            "text" to e.detail.take(500), "ageMs" to now - e.atMillis,
            "source" to if (e.kind != LessonEventKind.UTTERANCE) "APP" else if (e.title == "운전자 음성") "PLEOS_STT" else "TEXT_DEMO") }
    }
}

/** Limits generative wording to parked education; direction/entry/control decisions stay local. */
object TutorLlmPolicy {
    private val prohibited = Regex("(지금|바로).{0,15}(진입|끼어|차로.{0,3}변경|출발|가속|들어가|회전)|안전해|안전합니다|안전한.{0,4}틈|진입해|끼어드세요|차로를.{0,5}바꾸세요|핸들을.{0,8}(돌리|꺾)|감지했|확인됐|카메라로|비어.{0,3}있|차가.{0,3}없|\\d+(?:\\.\\d+)?\\s*(km|미터|도|초|회|번)")
    fun interpretation(reply: TutorLlmReply): ParsedLessonUtterance = when (reply.risk) {
        "ENTRY_DECISION", "CONTROL" -> ParsedLessonUtterance(LessonIntent.ENTRY_QUESTION)
        else -> reply.interpretation
    }
    fun canUseWording(reply: TutorLlmReply, snapshot: LessonSnapshot): Boolean =
        reply.risk == "EDUCATION" && reply.interpretation.intent in setOf(LessonIntent.UNKNOWN, LessonIntent.REPORT, LessonIntent.REPEAT) &&
            !snapshot.awaitingConfirmation && !snapshot.awaitingDirection && snapshot.phase == LessonPhase.ACTIVE &&
            reply.text.length in 1..300 && !prohibited.containsMatchIn(reply.text)
}

class TutorLlmRequestGate {
    data class Ticket(val generation: Long, val revision: Long, val atMillis: Long)
    private var generation = 0L
    private var pending: Ticket? = null
    fun begin(revision: Long, now: Long): Ticket = Ticket(++generation, revision, now).also { pending = it }
    fun cancel() { generation++; pending = null }
    fun isCurrent(ticket: Ticket, revision: Long) = pending == ticket && ticket.generation == generation && revision == ticket.revision
    fun accepts(ticket: Ticket, revision: Long, now: Long) = isCurrent(ticket, revision) &&
        now >= ticket.atMillis && now - ticket.atMillis <= 10000
}
