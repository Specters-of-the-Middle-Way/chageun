package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real visible controls and wall-clock production ticks. Snapshot reflection is READ ONLY;
 * no state/phase/completion is injected. User preferences and records are restored in finally. */
@RunWith(AndroidJUnit4::class)
class PrototypeVisibleTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:ActivityScenario<MainActivity>
    private lateinit var host:MainActivity
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun all():List<View> { var result=emptyList<View>(); i.runOnMainSync { result=views(host.window.decorView) }; return result }
    private fun has(key:String)=all().any { it.contentDescription==key && it.isShown }
    private fun text(key:String)=all().filterIsInstance<TextView>().firstOrNull { it.contentDescription==key }?.text.toString()
    private fun waitFor(label:String,seconds:Int=20,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+seconds*1000
        while(SystemClock.elapsedRealtime()<until) { if(condition()) return; SystemClock.sleep(50) }
        fail("$label: ${text("workflow_guidance")} / ${text("workflow_stats")}")
    }
    private fun click(key:String) {
        waitFor("Missing enabled $key") { all().any { it.contentDescription==key && it.isShown && it.isEnabled } }
        i.runOnMainSync { val v=views(host.window.decorView).first { it.contentDescription==key }; v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true); assertTrue(v.performClick()) }
        SystemClock.sleep(80)
    }
    private fun toggle(key:String,value:Boolean)=i.runOnMainSync {
        (views(host.window.decorView).first { it.contentDescription==key } as CompoundButton).isChecked=value
    }
    private fun touch(key:String,down:Boolean,fraction:Float=.5f)=i.runOnMainSync {
        val v=views(host.window.decorView).firstOrNull { it.contentDescription==key } ?: return@runOnMainSync
        assertTrue(v.isEnabled); v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true)
        val now=SystemClock.uptimeMillis(); val inset=16*v.resources.displayMetrics.density
        val e=MotionEvent.obtain(now,now,if(down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
            inset+(v.width-2*inset)*fraction,v.height/2f,0)
        try { v.dispatchTouchEvent(e) } finally { e.recycle() }
    }
    private fun snapshot():ScenarioSnapshot? {
        var value:ScenarioSnapshot?=null
        i.runOnMainSync { views(host.window.decorView).filterIsInstance<ScenarioSurfaceView>().firstOrNull()?.let {
            value=ScenarioSurfaceView::class.java.getDeclaredField("snapshot").apply { isAccessible=true }.get(it) as ScenarioSnapshot?
        } }; return value
    }
    private fun done()=has("workflow_reflect_skip")
    private fun capture(name:String) {
        SystemClock.sleep(150)
        val directory=File(i.targetContext.cacheDir,"prototype-improvements").apply { mkdirs() }
        val bitmap=requireNotNull(i.uiAutomation.takeScreenshot())
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun drive(speed:Double=26.0,condition:()->Boolean={done()}) {
        var down=false
        val until=SystemClock.elapsedRealtime()+65_000
        try {
            while(SystemClock.elapsedRealtime()<until) {
                if(condition()) return
                if(done()) {
                    if(condition()) return // The real ticker can finish between the two UI reads.
                    fail("Unexpected terminal task: ${all().filterIsInstance<TextView>().map { it.text }}")
                }
                val want=(snapshot()?.speedKmh ?: 0.0)<speed
                if(want!=down) { touch("workflow_gas",want); down=want }
                SystemClock.sleep(65)
            }
            fail("Drive timeout ${snapshot()}")
        } finally { if(down && has("workflow_gas")) touch("workflow_gas",false) }
    }
    private fun brake(condition:()->Boolean) {
        touch("workflow_brake",true)
        try { waitFor("Brake did not reach stage",15,condition) } finally { if(has("workflow_brake")) touch("workflow_brake",false) }
    }
    private fun steer(fraction:Float) { touch("workflow_steering",true,fraction); touch("workflow_steering",false,fraction) }
    private fun recheckPreflightAndOpenWorkflow() {
        waitFor("Return must require fresh departure checks") { has("journey_preflight_0") }
        assertFalse(all().first { it.contentDescription=="journey_preflight_continue" }.isEnabled)
        for(index in 0..6) if(!text("journey_preflight_$index").contains("✓")) click("journey_preflight_$index")
        click("journey_preflight_continue"); click("journey_start_workflow")
    }
    private fun isolated(block:()->Unit) {
        val names=listOf("drivestep_journey_v1","coach","workflow_ui_v1","workflow_training_v1","weather_preferences_v1")
        val prefs=names.associateWith { i.targetContext.getSharedPreferences(it,Context.MODE_PRIVATE) }
        val originals=prefs.mapValues { it.value.all.toMap() }
        val weather=WeatherTestSettings(i.targetContext)
        try {
            activity=ActivityScenario.launch(MainActivity::class.java); activity.onActivity { host=it }
            click("journey_nav_profile"); toggle("journey_consent_records",false); toggle("journey_consent_vehicle",false); toggle("voice_toggle",false)
            click("journey_profile_continue"); repeat(9) { click("journey_setup_confirm") }
            for(index in 0..6) if(!text("journey_preflight_$index").contains("✓")) click("journey_preflight_$index")
            click("journey_preflight_continue"); click("journey_start_workflow")
            block()
        } finally {
            if(::activity.isInitialized) activity.close(); weather.close()
            originals.forEach { (name,data) -> prefs.getValue(name).edit().apply {
                // Restore only this test's named preference stores, including absent keys.
                clear(); data.forEach { (key,value) -> when(value) {
                    is Boolean -> putBoolean(key,value); is String -> putString(key,value); is Int -> putInt(key,value)
                    is Long -> putLong(key,value); is Float -> putFloat(key,value)
                    is Set<*> -> putStringSet(key,value.filterIsInstance<String>().toSet())
                } }
            }.commit() }
        }
    }
    @Test(timeout=600_000) fun allEighteenTasksCompleteThroughVisibleControls()=isolated {
        for(task in ScenarioCatalog.tasks) {
            click("workflow_tasks"); click("workflow_task_${task.id}"); click("workflow_resume")
            when(task.id) {
                "D01","D02" -> drive(18.0)
                "D04" -> drive(8.0)
                "D17" -> drive(16.0)
                "D03" -> { drive { snapshot()?.phase==1 }; brake { snapshot()?.phase==2 }; drive() }
                "D05" -> { click("workflow_wait"); waitFor("Signal") { snapshot()?.phase==2 }; drive() }
                "D06","D07","D08" -> {
                    click(if(task.id=="D06") "workflow_signal_right" else "workflow_signal_left"); click("workflow_mirror")
                    if(task.id!="D08") { click("workflow_wait"); waitFor("Turn wait") { (snapshot()?.waiting ?: 0.0)>=2.2 } }
                    steer(if(task.id=="D06") 1f else 0f)
                    drive { abs(snapshot()?.rotation ?: 0.0)>if(task.id=="D08")155 else 65 }
                    click("workflow_center"); waitFor("Turn complete") { done() }
                }
                "D09","D10","D13" -> {
                    if(task.id!="D09") {
                        click("workflow_mirror"); assertFalse(snapshot()!!.mirrorChecked)
                        click("workflow_wait"); waitFor("Visible traffic clear") { snapshot()?.traffic?.entryAvailable==true }
                    }
                    click("workflow_signal_left"); click("workflow_mirror"); steer(0f)
                    drive { (snapshot()?.lateral ?: 0.0)<=-3.1 }; click("workflow_center")
                    if(task.id=="D13") drive() else waitFor("Lane complete") { done() }
                }
                "D11" -> {
                    click("workflow_wait"); waitFor("Oncoming passed") { snapshot()?.traffic?.entryAvailable==true }
                    click("workflow_mirror"); drive(12.0)
                }
                "D12" -> { drive(34.0) { snapshot()?.phase==1 }; brake { done() } }
                "D14" -> {
                    click("workflow_signal_right"); click("workflow_mirror"); steer(1f)
                    drive { (snapshot()?.lateral ?: 0.0)>=3.1 }; click("workflow_center"); drive()
                }
                "D15" -> { click("workflow_reroute"); drive() }
                "D16" -> { click("workflow_wait"); waitFor("Slope stop") { snapshot()?.phase==1 }; drive(16.0) }
                "D18" -> { drive { snapshot()?.warning==true }; brake { done() } }
            }
            assertTrue("${task.id} must actually complete",all().filterIsInstance<TextView>().any { it.text.contains("연습 완료") })
            assertFalse(all().first { it.contentDescription=="workflow_save" }.isEnabled)
            capture("${task.id}-completed"); Log.i("PrototypeVisible","CONTROL_COMPLETION ${task.id} stateInjection=false")
            click("workflow_home")
        }
    }
    @Test(timeout=180_000) fun trafficVariantsWaitCancelPauseResumeAndPedestrianCompletion()=isolated {
        for(id in listOf("D10","D13")) {
            click("workflow_tasks"); click("workflow_task_$id"); click("workflow_variant_1"); click("workflow_resume")
            assertFalse(all().first { it.contentDescription=="workflow_variant_0" }.isEnabled)
            click("workflow_wait"); SystemClock.sleep(6500); click("workflow_mirror")
            assertFalse(snapshot()!!.mirrorChecked); assertFalse(snapshot()!!.traffic!!.entryAvailable)
            click("workflow_signal_left"); steer(0f); drive { snapshot()?.let { it.lateral<=-.39 || it.distance>=1.45 }==true }
            click("workflow_pause"); val frozen=snapshot()!!.traffic
            SystemClock.sleep(600); assertEquals(frozen,snapshot()!!.traffic)
            capture("$id-blocked-paused"); click("workflow_resume"); assertFalse(snapshot()!!.mirrorChecked)
            click("workflow_cancel"); assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("실패나 감점이 아니") })
            capture("$id-cancelled"); click("workflow_home")
        }
        click("workflow_tasks"); click("workflow_task_D11"); click("workflow_variant_1"); click("workflow_resume")
        click("workflow_wait"); capture("D11-occluded")
        waitFor("Pedestrian appears") { snapshot()?.traffic?.let { !it.visibilityBlocked && it.corridorOccupied }==true }
        click("workflow_mirror"); assertFalse(snapshot()!!.mirrorChecked); capture("D11-pedestrian")
        click("workflow_pause"); val frozen=snapshot()!!.traffic
        activity.moveToState(Lifecycle.State.CREATED); SystemClock.sleep(400); activity.moveToState(Lifecycle.State.RESUMED)
        assertEquals(frozen,snapshot()!!.traffic); assertTrue(snapshot()!!.paused)
        click("workflow_resume"); recheckPreflightAndOpenWorkflow(); click("workflow_recover"); click("workflow_resume")
        waitFor("Pedestrian passes") { snapshot()?.traffic?.entryAvailable==true }
        click("workflow_mirror"); drive(12.0); capture("D11-pedestrian-completed")
        assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("연습 완료") })
    }
    @Test(timeout=120_000) fun doubleParkingWrongChoicesRetryBackgroundResumeReviewAndConsent()=isolated {
        click("workflow_guides"); click("workflow_guide_double_parking"); click("workflow_double_parking")
        assertFalse(all().first { it.contentDescription=="double_parking_next" }.isEnabled)
        click("double_parking_choice_front"); assertTrue(text("double_parking_feedback").contains("출차 경로")); capture("double-parking-blocks-exit")
        click("double_parking_retry"); assertFalse(has("double_parking_feedback"))
        click("double_parking_choice_bay")
        activity.moveToState(Lifecycle.State.CREATED); activity.moveToState(Lifecycle.State.RESUMED)
        assertFalse(all().first { it.contentDescription=="double_parking_next" }.isEnabled)
        click("double_parking_resume"); recheckPreflightAndOpenWorkflow()
        click("workflow_guides"); click("workflow_guide_double_parking"); click("workflow_double_parking"); click("double_parking_next")
        click("double_parking_choice_walk"); assertTrue(text("double_parking_feedback").contains("비상 통행")); capture("double-parking-blocks-passage")
        click("double_parking_choice_coordinate"); click("double_parking_next")
        click("double_parking_choice_push"); assertTrue(text("double_parking_feedback").contains("N단"))
        click("double_parking_choice_move"); capture("double-parking-move"); click("double_parking_next")
        assertTrue(text("double_parking_progress").contains("세 상황")); assertFalse(all().first { it.contentDescription=="double_parking_save" }.isEnabled)
        click("double_parking_review_0"); assertTrue(text("double_parking_feedback").contains("정규 공간"))
        capture("double-parking-review"); click("double_parking_retry")
        assertFalse(all().first { it.contentDescription=="double_parking_next" }.isEnabled)
    }
}
