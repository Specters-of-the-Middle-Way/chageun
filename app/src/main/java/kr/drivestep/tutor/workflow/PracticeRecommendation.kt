package kr.drivestep.tutor.workflow

data class DifficultyRecord(val attempt: String, val task: String, val difficulty: String?, val at: Long)
data class PracticeRecommendation(val task: ScenarioTask, val reports: Int, val latestAt: Long) {
    val reason get() = "최근 90일의 저장 기록에서 직접 어려웠다고 선택한 과제예요 (${reports}회)."
}

/** Uses explicit self-reports only. Completion, braking, waiting and cancellation are not faults. */
object PracticeRecommendations {
    const val MAX_AGE = 90 * 24 * 60 * 60_000L
    fun suggest(records: List<DifficultyRecord>, consent: Boolean, now: Long): List<PracticeRecommendation> {
        if (!consent) return emptyList()
        return records.filter { it.attempt.isNotBlank() && it.at > 0 && it.at <= now && now - it.at <= MAX_AGE &&
            ScenarioCatalog.find(it.task) != null && it.difficulty?.let(ScenarioCatalog::find) != null }
            .sortedByDescending { it.at }.distinctBy { it.attempt }.take(20)
            .groupBy { it.difficulty!! }.map { (id, values) ->
                PracticeRecommendation(ScenarioCatalog.find(id)!!, values.size, values.maxOf { it.at })
            }.sortedWith(compareByDescending<PracticeRecommendation> { it.reports }
                .thenByDescending { it.latestAt }.thenBy { it.task.id }).take(3)
    }
}
