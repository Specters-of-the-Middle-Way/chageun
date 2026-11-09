package kr.drivestep.tutor.data

import android.content.Context
import android.util.AtomicFile
import kr.drivestep.tutor.practice.PracticeOutcome
import kr.drivestep.tutor.practice.SteeringPracticeAction
import kr.drivestep.tutor.practice.SteeringPracticeEvent
import kr.drivestep.tutor.practice.SteeringPracticeSnapshot
import kr.drivestep.tutor.practice.SteeringPracticeStage
import kr.drivestep.tutor.vehicle.ParkingGear
import kr.drivestep.tutor.vehicle.ParkingSignalField
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local history only. Invoke every operation on the controller's single IO executor. */
class SteeringLogStore(context: Context, fileName: String = "steering-practice.json") {
    private val file = AtomicFile(File(context.applicationContext.filesDir, fileName))
    private var loaded = false
    private var records: List<SteeringPracticeSnapshot> = emptyList()

    @Synchronized
    fun loadAndRecover(): List<SteeringPracticeSnapshot> {
        if (loaded) return records.toList()
        val original = read()
        val recovered = original.map { snapshot ->
            if (snapshot.outcome != PracticeOutcome.ACTIVE) snapshot
            else snapshot.copy(
                stage = SteeringPracticeStage.INTERRUPTED,
                outcome = PracticeOutcome.INTERRUPTED,
                nextAction = SteeringPracticeAction.NONE,
                latestFeedbackKey = "steering_interrupted",
                feedbackRevision = snapshot.feedbackRevision + 1,
                interruptionReason = "PROCESS_RESTART",
            )
        }.sortedWith(ORDER).take(MAX_RECORDS)
        // Recovery preserves the received events; it never invents a vehicle observation.
        if (recovered != original) write(recovered)
        records = recovered
        loaded = true
        return records.toList()
    }

    @Synchronized
    fun save(snapshot: SteeringPracticeSnapshot): List<SteeringPracticeSnapshot> {
        check(loaded) { "Load steering history before saving" }
        val existing = records.firstOrNull { it.id == snapshot.id }
        if (existing != null && (existing.isTerminal || existing.feedbackRevision > snapshot.feedbackRevision)) {
            return records.toList()
        }
        val next = (records.filterNot { it.id == snapshot.id } + snapshot).sortedWith(ORDER).take(MAX_RECORDS)
        if (next != records) write(next)
        records = next
        return records.toList()
    }

    @Synchronized
    fun delete(id: String): List<SteeringPracticeSnapshot> {
        check(loaded) { "Load steering history before deletion" }
        val next = records.filterNot { it.id == id }
        if (next != records) write(next)
        records = next
        return records.toList()
    }

    private fun read(): List<SteeringPracticeSnapshot> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        check(json.getInt("version") == 1) { "Unsupported steering history version" }
        val items = json.getJSONArray("sessions")
        val result = List(items.length()) { decode(items.getJSONObject(it)) }
        check(result.map { it.id }.distinct().size == result.size) { "Duplicate steering history IDs" }
        return result
    }

    private fun write(next: List<SteeringPracticeSnapshot>) {
        val json = JSONObject().apply {
            put("version", 1)
            put("sessions", JSONArray().apply { next.forEach { put(encode(it)) } })
        }.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(json)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun encode(snapshot: SteeringPracticeSnapshot) = JSONObject().apply {
        put("id", snapshot.id)
        put("startedAtMillis", snapshot.startedAtMillis)
        put("stage", snapshot.stage.name)
        put("outcome", snapshot.outcome.name)
        put("nextAction", snapshot.nextAction.name)
        put("completedSteps", snapshot.completedSteps)
        put("latestFeedbackKey", snapshot.latestFeedbackKey ?: JSONObject.NULL)
        put("feedbackRevision", snapshot.feedbackRevision)
        put("interruptionReason", snapshot.interruptionReason ?: JSONObject.NULL)
        put("gear", snapshot.gear.name)
        put("speedMps", snapshot.speedMps ?: JSONObject.NULL)
        put("angleDegrees", snapshot.angleDegrees ?: JSONObject.NULL)
        put("events", JSONArray().apply {
            snapshot.events.forEach { event ->
                put(JSONObject().apply {
                    put("sequence", event.sequence)
                    put("atElapsedMillis", event.atElapsedMillis)
                    put("observedAtMillis", event.observedAtMillis ?: JSONObject.NULL)
                    put("field", event.field?.name ?: JSONObject.NULL)
                    put("angleDegrees", event.angleDegrees ?: JSONObject.NULL)
                    put("stageBefore", event.stageBefore.name)
                    put("stageAfter", event.stageAfter.name)
                    put("feedbackKey", event.feedbackKey)
                })
            }
        })
    }

    private fun decode(json: JSONObject): SteeringPracticeSnapshot {
        val events = json.getJSONArray("events")
        val snapshot = SteeringPracticeSnapshot(
            id = json.getString("id"),
            startedAtMillis = json.getLong("startedAtMillis"),
            stage = SteeringPracticeStage.valueOf(json.getString("stage")),
            outcome = PracticeOutcome.valueOf(json.getString("outcome")),
            nextAction = SteeringPracticeAction.valueOf(json.getString("nextAction")),
            completedSteps = json.getInt("completedSteps"),
            latestFeedbackKey = json.nullableString("latestFeedbackKey"),
            feedbackRevision = json.getLong("feedbackRevision"),
            interruptionReason = json.nullableString("interruptionReason"),
            gear = ParkingGear.valueOf(json.getString("gear")),
            speedMps = json.nullableDouble("speedMps"),
            angleDegrees = json.nullableDouble("angleDegrees"),
            events = List(events.length()) { index ->
                val event = events.getJSONObject(index)
                SteeringPracticeEvent(
                    sequence = event.getLong("sequence"),
                    atElapsedMillis = event.getLong("atElapsedMillis"),
                    observedAtMillis = if (event.isNull("observedAtMillis")) null else event.getLong("observedAtMillis"),
                    field = event.nullableString("field")?.let(ParkingSignalField::valueOf),
                    angleDegrees = event.nullableDouble("angleDegrees"),
                    stageBefore = SteeringPracticeStage.valueOf(event.getString("stageBefore")),
                    stageAfter = SteeringPracticeStage.valueOf(event.getString("stageAfter")),
                    feedbackKey = event.getString("feedbackKey"),
                )
            },
        )
        check(snapshot.id.isNotBlank() && snapshot.startedAtMillis >= 0 && snapshot.feedbackRevision >= 0)
        check(snapshot.completedSteps in 0..snapshot.totalSteps)
        check(snapshot.speedMps?.isFinite() != false && snapshot.angleDegrees?.isFinite() != false)
        check(snapshot.events.all { it.sequence >= 0 && it.atElapsedMillis >= 0 && it.angleDegrees?.isFinite() != false })
        check(when (snapshot.outcome) {
            PracticeOutcome.COMPLETED -> snapshot.stage == SteeringPracticeStage.COMPLETED && snapshot.completedSteps == snapshot.totalSteps
            PracticeOutcome.INTERRUPTED -> snapshot.stage == SteeringPracticeStage.INTERRUPTED
            PracticeOutcome.ACTIVE -> snapshot.stage != SteeringPracticeStage.COMPLETED && snapshot.stage != SteeringPracticeStage.INTERRUPTED
        }) { "Inconsistent steering history outcome" }
        return snapshot
    }

    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableDouble(key: String) = if (isNull(key)) null else getDouble(key)

    private companion object {
        const val MAX_RECORDS = 20
        val ORDER = compareByDescending<SteeringPracticeSnapshot> { it.startedAtMillis }.thenByDescending { it.id }
    }
}
