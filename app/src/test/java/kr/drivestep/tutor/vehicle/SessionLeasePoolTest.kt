package kr.drivestep.tutor.vehicle

import org.junit.Assert.*
import org.junit.Test

class SessionLeasePoolTest {
    @Test fun closingOneAdapterDoesNotReleaseOtherReaders() {
        var created = 0;var disposed = 0
        val pool = SessionLeasePool({ ++created },{ disposed++ })
        val a=pool.acquire();val b=pool.acquire();val c=pool.acquire()
        assertEquals(a.value,b.value);assertEquals(1,created)
        b.close();b.close();assertEquals(0,disposed)
        a.close();assertEquals(0,disposed)
        c.close();assertEquals(1,disposed)
        val next=pool.acquire();assertEquals(2,created);assertNotEquals(a.value,next.value)
        next.close();assertEquals(2,disposed)
    }
    @Test fun failedCreationDoesNotRetainADeadConnection() {
        var attempts=0
        val pool=SessionLeasePool({ if (++attempts==1) throw IllegalStateException() else 2 },{})
        try { pool.acquire();fail() } catch (_:IllegalStateException) { }
        val lease=pool.acquire();assertEquals(2,lease.value);lease.close()
    }
}
