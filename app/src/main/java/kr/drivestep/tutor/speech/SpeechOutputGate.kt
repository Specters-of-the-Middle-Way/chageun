package kr.drivestep.tutor.speech

/** Main-thread ownership and acknowledged asynchronous cleanup, shared across app speech routes.
 * A late release cannot cancel a new owner. Failed cleanup discards queued output; no auto replay. */
class SpeechOutputGate {
    private var serial=0L
    private data class Owner(val id:Long,val interrupt:()->Unit)
    private var owner:Owner?=null
    private val draining=mutableSetOf<Long>()
    private var pending:Pair<Long,()->Unit>?=null
    val activeDrains get()=draining.size
    fun acquire(interrupt:()->Unit):Long {
        val previous=owner
        val next=Owner(++serial,interrupt)
        owner=next; pending=null
        previous?.interrupt?.invoke()
        return next.id
    }
    fun release(id:Long) { if(owner?.id==id) { owner=null; pending=null } }
    fun whenReady(id:Long,action:()->Unit) {
        if(owner?.id!=id) return
        if(draining.isEmpty()) action() else pending=id to action
    }
    fun beginDrain():Long=(++serial).also { draining.add(it) }
    fun finishDrain(id:Long,acknowledged:Boolean) {
        if(!draining.remove(id)) return
        if(!acknowledged) {
            val interrupted=owner; owner=null; pending=null; interrupted?.interrupt?.invoke()
        } else if(draining.isEmpty()) {
            val ready=pending; pending=null
            if(ready!=null && owner?.id==ready.first) ready.second()
        }
    }
}

/** Android callers use this only from their main Handler. Gleo's own final voice is external. */
object AppSpeechOutput { val gate=SpeechOutputGate() }
