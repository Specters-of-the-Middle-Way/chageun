package kr.drivestep.tutor.lessoncoach

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredLesson(
    val id: String,
    val startedAtMillis: Long,
    val topic: LessonTopic,
    val liveInputs: Boolean,
    val reflection: LessonReflection,
    val nextLesson: LessonTopic,
    val nextLessonReason: String,
    val events: List<LessonEvent>,
)

/** App-private, bounded history. Call on one IO executor, only after recording consent. No audio. */
class LessonCoachStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "lesson-coach-history.json"))

    @Synchronized
    fun read(): List<StoredLesson> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
        check(root.getInt("version") == 1) { "Unsupported lesson history" }
        val rows = root.getJSONArray("sessions")
        return List(minOf(rows.length(), MAX_RECORDS)) { decode(rows.getJSONObject(it)) }
    }

    @Synchronized
    fun save(record: StoredLesson): List<StoredLesson> {
        val rows = (listOf(record.copy(events = record.events.takeLast(MAX_EVENTS))) + read().filterNot { it.id == record.id })
            .sortedByDescending { it.startedAtMillis }.take(MAX_RECORDS)
        val root = JSONObject().put("version", 1).put("sessions", JSONArray().apply { rows.forEach { put(encode(it)) } })
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return rows
    }

    @Synchronized
    fun deleteAll() = file.delete()

    private fun encode(record: StoredLesson) = JSONObject().apply {
        put("id", record.id); put("startedAtMillis", record.startedAtMillis)
        put("topic", record.topic.name); put("liveInputs", record.liveInputs)
        put("reflection", record.reflection.name); put("nextLesson", record.nextLesson.name)
        put("nextLessonReason", record.nextLessonReason)
        put("events", JSONArray().apply {
            record.events.takeLast(MAX_EVENTS).forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id); put("atMillis", event.atMillis)
                    put("kind", event.kind.name); put("source", event.source.name)
                    put("title", event.title); put("detail", event.detail)
                    put("vehicle", event.vehicle?.let(::encodeVehicle) ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun encodeVehicle(value: VehicleObservation) = JSONObject().apply {
        put("atMillis", value.atMillis); put("source", value.source.name)
        put("speedKmh", value.speedKmh ?: JSONObject.NULL); put("signal", value.signal.name)
        put("gear", value.gear.name); put("brakePressed", value.brakePressed ?: JSONObject.NULL)
        put("frontWheelAngleDegrees", value.frontWheelAngleDegrees ?: JSONObject.NULL)
        put("isInitial", value.isInitial)
        put("speedAtMillis", value.speedAtMillis); put("signalAtMillis", value.signalAtMillis)
        put("gearAtMillis", value.gearAtMillis); put("brakeAtMillis", value.brakeAtMillis)
        put("steeringAtMillis", value.steeringAtMillis)
    }

    private fun decode(value: JSONObject): StoredLesson {
        val rows = value.getJSONArray("events")
        return StoredLesson(
            id = value.getString("id").also { require(it.isNotBlank()) },
            startedAtMillis = value.getLong("startedAtMillis").also { require(it >= 0) },
            topic = LessonTopic.valueOf(value.getString("topic")),
            liveInputs = value.getBoolean("liveInputs"),
            reflection = LessonReflection.valueOf(value.getString("reflection")),
            nextLesson = LessonTopic.valueOf(value.getString("nextLesson")),
            nextLessonReason = value.getString("nextLessonReason"),
            events = List(minOf(rows.length(), MAX_EVENTS)) { index ->
                val event = rows.getJSONObject(index)
                LessonEvent(
                    id = event.getLong("id"), atMillis = event.getLong("atMillis"),
                    kind = LessonEventKind.valueOf(event.getString("kind")),
                    source = LessonEvidence.valueOf(event.getString("source")),
                    title = event.getString("title"), detail = event.getString("detail"),
                    vehicle = if (event.isNull("vehicle")) null else decodeVehicle(event.getJSONObject("vehicle")),
                )
            },
        )
    }

    private fun decodeVehicle(value: JSONObject) = VehicleObservation(
        atMillis = value.getLong("atMillis"), source = VehicleInputSource.valueOf(value.getString("source")),
        speedKmh = value.finiteDoubleOrNull("speedKmh"), signal = LessonSignal.valueOf(value.getString("signal")),
        gear = LessonGear.valueOf(value.getString("gear")),
        brakePressed = if (value.isNull("brakePressed")) null else value.getBoolean("brakePressed"),
        frontWheelAngleDegrees = value.finiteDoubleOrNull("frontWheelAngleDegrees"),
        isInitial = value.getBoolean("isInitial"), speedAtMillis = value.getLong("speedAtMillis"),
        signalAtMillis = value.getLong("signalAtMillis"), gearAtMillis = value.getLong("gearAtMillis"),
        brakeAtMillis = value.getLong("brakeAtMillis"), steeringAtMillis = value.getLong("steeringAtMillis"),
    )

    private fun JSONObject.finiteDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else getDouble(key).also { require(it.isFinite()) }

    private companion object {
        const val MAX_RECORDS = 20
        const val MAX_EVENTS = 200
    }
}
