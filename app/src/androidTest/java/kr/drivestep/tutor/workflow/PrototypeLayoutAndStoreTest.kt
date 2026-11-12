package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeLayoutAndStoreTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun largeTextNarrowWindowKeepsNewChoicesAndPinnedActionsReadable() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val target=i.targetContext
            val names=mutableSetOf<String>()
            val prefix="prototype_layout_${System.nanoTime()}_"
            val config=target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale=1.5f }).apply { setTheme(R.style.AppTheme) }
            val context=object:ContextWrapper(config) {
                override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(prefix+name,mode).also { names+=prefix+name }
            }
            val root=WorkflowView(context,{},{},{},{},{true},{false},{false},{},{},{})
            val density=context.resources.displayMetrics.density
            fun control(key:String)=views(root).first { it.contentDescription==key }
            fun measure() {
                root.measure(View.MeasureSpec.makeMeasureSpec((760*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((950*density).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
            }
            fun click(key:String) { assertTrue(key,control(key).isEnabled); control(key).performClick(); measure() }
            fun check(pinned:String?=null) {
                measure()
                views(root).filterIsInstance<TextView>().filter { it.visibility==View.VISIBLE }.forEach { view ->
                    val layout=view.layout ?: return@forEach
                    assertTrue("Text height: ${view.text}",layout.height<=view.height-view.compoundPaddingTop-view.compoundPaddingBottom)
                    repeat(layout.lineCount) { line ->
                        assertEquals("Ellipsis: ${view.text}",0,layout.getEllipsisCount(line))
                        assertTrue("Text width: ${view.text}",layout.getLineMax(line)<=layout.width+1)
                    }
                    if(view.isClickable) assertTrue("Touch height: ${view.text}",view.height>=48*density)
                }
                pinned?.let { val v=control(it); val r=Rect(0,0,v.width,v.height); root.offsetDescendantRectToMyCoords(v,r)
                    assertTrue("Pinned above viewport: $r",r.top>=root.paddingTop)
                    assertTrue("Pinned below viewport: $r",r.bottom<=root.height-root.paddingBottom)
                }
            }
            try {
                root.setSpeechEnabled(false); root.setActive(true); measure()
                click("workflow_guides"); click("workflow_guide_double_parking"); click("workflow_double_parking")
                check("double_parking_next")
                click("double_parking_choice_front"); check("double_parking_next")
                click("double_parking_choice_bay"); click("double_parking_next"); click("double_parking_choice_walk"); check("double_parking_next")
                click("double_parking_choice_coordinate"); click("double_parking_next"); click("double_parking_choice_push"); check("double_parking_next")
                click("double_parking_choice_move"); click("double_parking_next"); check("double_parking_retry")
                click("workflow_home"); click("workflow_tasks"); click("workflow_task_D13"); check("workflow_resume")
                click("workflow_variant_1"); check("workflow_resume")
                click("workflow_traffic_guide"); check()
            } finally { root.close(); names.forEach(target::deleteSharedPreferences) }
        }
    }
    @Test fun newEducationAndTrafficRecordsHonorConsentAndKeepVariantForRecovery() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="prototype_store_${System.nanoTime()}"
        var consent=false
        val store=WorkflowStore(context,{consent},name)
        val scene=ScenarioSession(ScenarioCatalog.find("D13")!!,"isolated",1)
        try {
            assertFalse(store.saveDoubleParking()); assertFalse(store.doubleParkingLearned())
            store.checkpoint(scene.snapshot()); assertNull(store.pendingTask())
            consent=true; assertTrue(store.saveDoubleParking()); assertTrue(store.doubleParkingLearned())
            store.checkpoint(scene.snapshot()); assertEquals("D13",store.pendingTask()?.id); assertEquals(1,store.pendingVariant())
            scene.cancel(); assertTrue(store.save(scene.snapshot(),null,null))
            assertEquals(1,store.records().single().getInt("trafficVariant"))
            assertEquals(EventStatus.CANCELLED.name,store.records().single().getString("status"))
            consent=false; assertFalse(store.save(scene.snapshot(),null,null)); assertNull(store.pendingTask())
            assertEquals(1,store.records().size)
        } finally { context.deleteSharedPreferences(name) }
    }
}
