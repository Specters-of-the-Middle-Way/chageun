package kr.drivestep.tutor.speech

/** Accepts one final transcript from the current explicit listen request only. */
class TranscriptGate {
    private var generation = 0L
    private var accepting = false

    fun begin(): Long {
        generation++
        accepting = true
        return generation
    }

    fun cancel() {
        generation++
        accepting = false
    }

    fun isCurrent(token: Long): Boolean = accepting && token == generation

    fun accept(token: Long, text: String, completed: Boolean): String? {
        if (!isCurrent(token) || !completed) return null
        val result = text.trim()
        if (result.isEmpty() || result.length > 500) return null
        accepting = false
        return result
    }
}
