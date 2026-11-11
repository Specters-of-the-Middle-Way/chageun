package kr.drivestep.tutor.workflow

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkflowStoreTest {
    @Test fun consentRevocationCheckpointRecoveryAndDeletionAreScoped() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="workflow-test-${UUID.randomUUID()}"
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        var consent=false
        val store=WorkflowStore(context,{consent},name)
        try {
            val s=ScenarioSession(ScenarioCatalog.find("D10")!!);s.resume();s.cancel()
            assertFalse(store.save(s.snapshot(),null,null));assertTrue(store.records().isEmpty())
            consent=true;assertTrue(store.save(s.snapshot(),null,null))
            var row=store.records().single();assertTrue(row.isNull("explanation"));assertEquals("SIMULATION",row.getString("source"))
            consent=false;assertFalse(store.save(s.snapshot(),"test",null));assertEquals(1,store.records().size)
            val running=ScenarioSession(ScenarioCatalog.find("D03")!!);running.resume();running.pause("SCREEN_LEFT")
            store.checkpoint(running.snapshot());assertNull(store.pendingTask())
            consent=true;store.checkpoint(running.snapshot());assertEquals("D03",store.pendingTask()!!.id)
            val reloaded=WorkflowStore(context,{consent},name)
            assertEquals("D03",reloaded.pendingTask()!!.id)
            row=reloaded.records().single();assertEquals("CANCELLED",row.getString("status"))
            val legacy=context.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE).all.toMap()
            assertTrue(store.delete());assertTrue(store.records().isEmpty());assertNull(store.pendingTask())
            assertEquals(legacy,context.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE).all)
        } finally { prefs.edit().clear().commit() }
    }
}
