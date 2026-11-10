package kr.drivestep.tutor.speech

/** Permission for one next microphone turn in an explicitly started conversation. */
class ConversationTurnGate {
    data class Ticket(val generation: Long, val earliestMillis: Long)

    var isActive = false
        private set
    private var generation = 0L
    private var pending: Ticket? = null

    fun start(nowMillis: Long) {
        isActive = true
        pending = Ticket(++generation, nowMillis)
    }

    /** Output or input owns the turn. No timer may open a second microphone. */
    fun waitForResponse() {
        generation++
        pending = null
    }

    fun responseFinished(nowMillis: Long) {
        if (isActive) pending = Ticket(++generation, nowMillis + OUTPUT_TAIL_MILLIS)
    }

    fun stop() {
        isActive = false
        waitForResponse()
    }

    fun ticket(): Ticket? = pending.takeIf { isActive }

    fun claim(ticket: Ticket, nowMillis: Long, inputBusy: Boolean, outputBusy: Boolean): Boolean {
        if (!isActive || pending != ticket || nowMillis < ticket.earliestMillis || inputBusy || outputBusy) return false
        waitForResponse()
        return true
    }

    companion object { const val OUTPUT_TAIL_MILLIS = 400L }
}
