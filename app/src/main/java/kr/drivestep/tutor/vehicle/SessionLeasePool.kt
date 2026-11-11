package kr.drivestep.tutor.vehicle

/** Main-thread ownership: one SDK connection, released only after its final user. */
class SessionLeasePool<T : Any>(private val create: ()->T, private val dispose: (T)->Unit) {
    private var current: T? = null
    private var users = 0
    fun acquire(): SessionLease<T> {
        val value = current ?: create().also { current = it }
        users++
        return SessionLease(value) {
            users--
            if (users == 0) {
                current = null
                dispose(value)
            }
        }
    }
}
class SessionLease<T : Any>(val value: T, private val release: ()->Unit) : AutoCloseable {
    private var closed = false
    override fun close() { if (!closed) { closed = true; release() } }
}
