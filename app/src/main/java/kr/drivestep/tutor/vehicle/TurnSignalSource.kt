package kr.drivestep.tutor.vehicle

/** Direction state only: Pleos Vehicle 2.3.2 does not expose Blink/Flash here. */
enum class SignalLamp { OFF, ACTIVE, UNKNOWN }

data class SignalReading(
    val left: SignalLamp,
    val right: SignalLamp,
    val observedAtMillis: Long,
    /** A GET snapshot, including health checks; never count it as an exercise action. */
    val isInitial: Boolean = false,
) {
    val isKnown: Boolean get() = left != SignalLamp.UNKNOWN && right != SignalLamp.UNKNOWN
}

enum class SourceState {
    CONNECTING, WAITING_FOR_SIGNAL, READY, UNSUPPORTED, ERROR, STOPPED,
}

data class SourceStatus(val state: SourceState, val message: String)

interface TurnSignalSource {
    /** All callbacks are delivered on Android's main thread. */
    fun start(onState: (SignalReading) -> Unit, onStatus: (SourceStatus) -> Unit)
    fun stop()
}
