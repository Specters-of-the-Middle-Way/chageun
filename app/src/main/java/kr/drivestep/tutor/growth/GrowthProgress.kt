package kr.drivestep.tutor.growth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, explicitly simulated learning results. No driving telemetry is used here. */
class GrowthProgress(context: Context) {
    private val prefs = context.getSharedPreferences("growth_tutor_v1", Context.MODE_PRIVATE)
    var heightGroup: Int = prefs.getInt("height", 1).coerceIn(0, 2)
        private set
    var proportion: Int = prefs.getInt("proportion", 0).coerceIn(0, 2)
        private set
    var setupConfirmed: Boolean = prefs.getBoolean("setup", false)
        private set
    private var results = runCatching { JSONArray(prefs.getString("results", "[]")) }.getOrElse { JSONArray() }
    var best: Map<String, Int> = loadBest()
        private set
    val points: Int get() = best.values.sum()
    val completedCount: Int get() = best.size
    fun profile(height: Int, body: Int) {
        heightGroup = height.coerceIn(0, 2); proportion = body.coerceIn(0, 2)
        setupConfirmed = false
        prefs.edit().putInt("height", heightGroup).putInt("proportion", proportion).putBoolean("setup", false).apply()
    }
    fun confirmSetup() { setupConfirmed = true; prefs.edit().putBoolean("setup", true).apply() }
    fun record(value: GrowthSnapshot) {
        if (!value.completed) return
        val old = best[value.lessonId]
        if (old == null || value.score > old) best = best + (value.lessonId to value.score)
        val row = JSONObject().apply {
            put("lesson", value.lessonId); put("score", value.score); put("mistakes", value.mistakes)
            put("time", System.currentTimeMillis()); put("kind", "SIMULATION")
            put("attempts", JSONArray().apply { value.attempts.forEach { attempt -> put(JSONObject().put("step", attempt.stepId).put("choice", attempt.choiceId).put("correct", attempt.correct)) } })
        }
        val next = JSONArray().put(row)
        for (i in 0 until minOf(results.length(), 29)) next.put(results.getJSONObject(i))
        results = next
        prefs.edit().putString("best", JSONObject(best).toString()).putString("results", results.toString()).apply()
    }
    fun recent(): List<JSONObject> = List(results.length()) { results.getJSONObject(it) }
    fun reset() {
        best = emptyMap(); results = JSONArray(); setupConfirmed = false
        prefs.edit().clear().apply(); heightGroup = 1; proportion = 0
    }
    private fun loadBest(): Map<String, Int> = runCatching {
        val json = JSONObject(prefs.getString("best", "{}") ?: "{}")
        json.keys().asSequence().filter { id -> GrowthCurriculum.lessons.any { it.id == id } }
            .associateWith { json.getInt(it).coerceIn(0, 100) }
    }.getOrDefault(emptyMap())
}
