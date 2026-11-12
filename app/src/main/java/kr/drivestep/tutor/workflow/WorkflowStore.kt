package kr.drivestep.tutor.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Scoped local records. No coordinates, audio, profile, or legacy history is copied here. */
class WorkflowStore(context: Context, private val consent: () -> Boolean, storeName: String = "workflow_training_v1") {
    private val prefs = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    fun records(): List<JSONObject> = runCatching {
        val rows = JSONArray(prefs.getString("records", "[]"))
        (0 until minOf(rows.length(), 20)).mapNotNull { rows.optJSONObject(it) }
            .filter { ScenarioCatalog.find(it.optString("task")) != null && it.optString("source") == "SIMULATION" }
    }.getOrDefault(emptyList())
    fun recommendations(now: Long): List<PracticeRecommendation> {
        if (!consent()) return emptyList()
        val evidence = records().map { DifficultyRecord(it.optString("attempt"), it.optString("task"),
            if (it.isNull("difficulty")) null else it.optString("difficulty"), it.optLong("at")) }
        return PracticeRecommendations.suggest(evidence, true, now)
    }
    fun save(snapshot: ScenarioSnapshot, explanation: String?, difficulty: String?): Boolean {
        if (!consent()) return false
        val selectedDifficulty = difficulty?.takeIf { ScenarioCatalog.find(it) != null }
        val facts = String.format(java.util.Locale.KOREA,
            "가상 이동 %.1fm · 가상 속도 %.1fkm/h · 방향지시 %s · 주변 확인 학습 %s", snapshot.distance,
            snapshot.speedKmh, snapshot.signal.name, if (snapshot.mirrorChecked) "누름" else "미확인")
        val events = JSONArray()
        snapshot.events.forEach { events.put(JSONObject().put("id", it.id).put("source", it.source.name)
            .put("status", it.status.name).put("at", it.at).put("routeVersion", it.routeVersion).put("fact", it.fact)) }
        val row = JSONObject().put("schema", 1).put("attempt", snapshot.attemptId).put("at", System.currentTimeMillis()).put("task", snapshot.task.id)
            .put("source", "SIMULATION").put("status", snapshot.status.name).put("facts", facts)
            .put("unknown", snapshot.task.unknown).put("explanation", explanation ?: JSONObject.NULL)
            .put("difficulty", selectedDifficulty ?: JSONObject.NULL).put("next", selectedDifficulty ?: snapshot.task.nextTask).put("events", events)
        snapshot.traffic?.let { row.put("trafficVariant",it.variant).put("trafficTitle",it.title) }
        return prefs.edit().putString("records", JSONArray((listOf(row) + records()).take(20)).toString()).commit()
    }
    fun checkpoint(snapshot: ScenarioSnapshot) {
        if (!consent() || snapshot.status != EventStatus.ACTIVE) return
        prefs.edit().putString("checkpoint", JSONObject().put("schema", 1).put("task", snapshot.task.id)
            .put("phase", snapshot.phase).put("stage", snapshot.instruction.take(400)).put("distance", snapshot.distance).put("reason", "INTERRUPTED")
            .put("trafficVariant",snapshot.traffic?.variant ?: 0).put("at", System.currentTimeMillis()).toString()).apply()
    }
    fun pendingVariant(): Int = if(pendingTask()==null) 0 else runCatching {
        JSONObject(prefs.getString("checkpoint", "")!!).optInt("trafficVariant",0).coerceIn(0,1)
    }.getOrDefault(0)
    fun pendingTask(): ScenarioTask? {
        if (!consent()) return null
        return runCatching { ScenarioCatalog.find(JSONObject(prefs.getString("checkpoint", "")!!).getString("task")) }.getOrNull()
    }
    fun pendingDescription(): String? {
        if (pendingTask() == null) return null
        return runCatching {
            val row = JSONObject(prefs.getString("checkpoint", "")!!)
            "중단 당시: ${row.optString("stage").take(400)}\n" + String.format(java.util.Locale.KOREA, "가상 이동 %.1fm · 준비를 다시 확인하고 새 시도로 시작해요.", row.optDouble("distance", 0.0))
        }.getOrNull()
    }
    fun clearCheckpoint() { prefs.edit().remove("checkpoint").apply() }
    fun learned(lesson: ManualLesson) {
        if (consent()) prefs.edit().putBoolean("learned:${lesson.revision}:${lesson.feature.name}", true).apply()
    }
    fun saveDoubleParking(): Boolean = consent() && prefs.edit().putLong("education:double_parking:v1",System.currentTimeMillis()).commit()
    fun doubleParkingLearned(): Boolean = prefs.getLong("education:double_parking:v1",0)>0
    fun delete(): Boolean = prefs.edit().clear().commit()
}
