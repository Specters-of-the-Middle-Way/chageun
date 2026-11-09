package kr.drivestep.tutor.growth

import kr.drivestep.tutor.coach.CoachCue

/** An answer in a fictional lesson, never a measured driver violation or vehicle event. */
data class GrowthAttempt(val stepId: String, val choiceId: String, val correct: Boolean)

data class GrowthSnapshot(
    val lessonId: String,
    val stepIndex: Int,
    val completedSteps: Int,
    val totalSteps: Int,
    val answeredCorrectly: Boolean,
    val completed: Boolean,
    val mistakes: Int,
    /** Comprehension score only. In-progress scores do not count as course completion. */
    val score: Int,
    val feedback: CoachCue?,
    val animation: String?,
    val selectedChoiceId: String?,
    val attempts: List<GrowthAttempt>,
    /** Only changes when the session accepts a new answer or advances to a new step. */
    val revision: Int,
)

/**
 * Pure decision state. Run choose/next on the host's UI thread.
 *
 * A correct answer unlocks next(); the last correct answer completes the lesson.
 * Each incorrect option is charged at most once per step, so a double tap cannot
 * multiply a penalty. Answers from another step and all post-completion input
 * are ignored. Finishing a lesson demonstrates this simulated decision sequence,
 * not physical driving competence, legal compliance or readiness to drive alone.
 */
class GrowthSession(val lesson: GrowthLesson) {
    private var stepIndex = 0
    private var answeredCorrectly = false
    private var completed = false
    private var feedback: CoachCue? = lesson.steps.firstOrNull()?.cue
    private var animation: String? = "WAIT"
    private var selectedChoiceId: String? = null
    private var revision = 0
    private val attempts = mutableListOf<GrowthAttempt>()
    private val acceptedChoiceIds = mutableSetOf<String>()

    init {
        require(lesson.id.isNotBlank() && lesson.steps.isNotEmpty())
        require(lesson.steps.map { it.id }.distinct().size == lesson.steps.size)
        require(lesson.steps.all { step ->
            step.id.isNotBlank() && step.choices.size >= 2 &&
                step.choices.count { it.correct } == 1 &&
                step.choices.all { it.id.isNotBlank() }
        })
        val ids = lesson.steps.flatMap { it.choices }.map { it.id }
        require(ids.distinct().size == ids.size) { "Choice IDs must be unique across the lesson" }
    }

    fun snapshot(): GrowthSnapshot {
        val completedSteps = if (completed) lesson.steps.size else stepIndex + if (answeredCorrectly) 1 else 0
        val mistakes = attempts.count { !it.correct }
        val score = (completedSteps * 100 / lesson.steps.size - mistakes * MISTAKE_COST).coerceIn(0, 100)
        return GrowthSnapshot(
            lessonId = lesson.id,
            stepIndex = stepIndex,
            completedSteps = completedSteps,
            totalSteps = lesson.steps.size,
            answeredCorrectly = answeredCorrectly,
            completed = completed,
            mistakes = mistakes,
            score = score,
            feedback = feedback,
            animation = animation,
            selectedChoiceId = selectedChoiceId,
            attempts = attempts.toList(),
            revision = revision,
        )
    }

    fun choose(choiceId: String): GrowthSnapshot {
        if (completed || answeredCorrectly) return snapshot()
        val step = lesson.steps[stepIndex]
        val choice = step.choices.firstOrNull { it.id == choiceId } ?: return snapshot()
        if (!acceptedChoiceIds.add(choice.id)) return snapshot()
        selectedChoiceId = choice.id
        answeredCorrectly = choice.correct
        completed = choice.correct && stepIndex == lesson.steps.lastIndex
        feedback = choice.feedback
        // Wrong decisions freeze the fictional scene; they never animate an unsafe move.
        animation = if (choice.correct) choice.animation else "STOP"
        attempts += GrowthAttempt(step.id, choice.id, choice.correct)
        revision++
        return snapshot()
    }

    fun next(): GrowthSnapshot {
        if (completed || !answeredCorrectly) return snapshot()
        stepIndex++
        answeredCorrectly = false
        selectedChoiceId = null
        feedback = lesson.steps[stepIndex].cue
        animation = "WAIT"
        revision++
        return snapshot()
    }

    private companion object { const val MISTAKE_COST = 10 }
}
