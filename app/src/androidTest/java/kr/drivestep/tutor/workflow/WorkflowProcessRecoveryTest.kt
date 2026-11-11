package kr.drivestep.tutor.workflow

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Two instrumented phases separated by am force-stop. The runner always invokes cleanup. */
@RunWith(AndroidJUnit4::class)
class WorkflowProcessRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backup get() = File(context.filesDir,"workflow-process-verification-backup.json")
    private val keys=listOf("record_consent","vehicle_consent","has_profile","height","weight","experience","confidence","guidance","difficulty","seat_fore_aft","seat_height","seat_backrest","seat_steering","setup")
    private lateinit var activity:ActivityScenario<MainActivity>
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
    private fun all():List<View>{var values=emptyList<View>();activity.onActivity{values=views(it.window.decorView)};return values}
    private fun waitFor(message:String,predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+15_000;while(SystemClock.elapsedRealtime()<end){if(predicate())return;SystemClock.sleep(80)};fail(message)}
    private fun has(key:String)=all().any{it.contentDescription?.toString()==key&&it.isShown}
    private fun text(value:String)=all().filterIsInstance<TextView>().any{it.isShown&&it.text.contains(value)}
    private fun click(key:String){waitFor("Missing $key"){all().any{it.contentDescription?.toString()==key&&it.isShown&&it.isEnabled}};activity.onActivity{a->assertTrue(views(a.window.decorView).first{it.contentDescription?.toString()==key}.performClick())};SystemClock.sleep(60)}
    private fun checked(key:String,value:Boolean){waitFor("Missing toggle $key"){has(key)};activity.onActivity{a->(views(a.window.decorView).first{it.contentDescription?.toString()==key} as CompoundButton).isChecked=value}}
    private fun press(key:String,down:Boolean){activity.onActivity{a->val v=views(a.window.decorView).first{it.contentDescription?.toString()==key};val now=SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,if(down)MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,v.width/2f,v.height/2f,0);try{v.dispatchTouchEvent(e)}finally{e.recycle()}}}
    private fun preflight(){for(i in 0..6){val key="journey_preflight_$i";if(all().filterIsInstance<TextView>().none{it.contentDescription?.toString()==key&&it.text.contains("✓")})click(key)}}
    private fun captureOriginal() {
        check(!backup.exists()) { "Restore the previous verification backup first" }
        val profile=context.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE)
        val voice=context.getSharedPreferences("coach",Context.MODE_PRIVATE)
        val workflow=context.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE)
        val row=JSONObject().put("profile",JSONObject())
        keys.forEach{key->row.getJSONObject("profile").put(key,profile.all[key]?:JSONObject.NULL)}
        row.put("voice",voice.all["voice_enabled"]?:JSONObject.NULL).put("checkpoint",workflow.getString("checkpoint",null)?:JSONObject.NULL)
        row.put("records",workflow.getString("records",null)?:JSONObject.NULL)
        backup.writeText(row.toString())
    }
    private fun restore() {
        if(!backup.exists())return
        val row=JSONObject(backup.readText())
        val profile=context.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE).edit()
        keys.forEach{key->when(val value=row.getJSONObject("profile").opt(key)){
            JSONObject.NULL,null->profile.remove(key)
            is Boolean->profile.putBoolean(key,value)
            is Int->profile.putInt(key,value)
            is String->profile.putString(key,value)
        }}
        assertTrue(profile.commit())
        val voice=context.getSharedPreferences("coach",Context.MODE_PRIVATE).edit()
        if(row.isNull("voice"))voice.remove("voice_enabled") else voice.putBoolean("voice_enabled",row.getBoolean("voice"))
        assertTrue(voice.commit())
        val prefs=context.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE)
        // The exercise must not add, evict or rewrite existing history.
        assertEquals(if(row.isNull("records"))null else row.getString("records"),prefs.getString("records",null))
        val checkpoint=prefs.edit()
        if(row.isNull("checkpoint"))checkpoint.remove("checkpoint") else checkpoint.putString("checkpoint",row.getString("checkpoint"))
        assertTrue(checkpoint.commit());assertTrue(backup.delete())
    }
    @Test fun restoreOriginalState(){restore()}
    @Test(timeout=90_000) fun createCheckpointForColdStart() {
        assumeTrue(Build.MODEL.contains("emulator",true)||Build.MODEL.contains("Car_on"))
        captureOriginal()
        activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            click("journey_nav_profile");checked("journey_consent_records",false);checked("voice_toggle",false)
            click("journey_profile_continue");repeat(12){if(!has("journey_preflight_0"))click("journey_setup_confirm")};preflight()
            click("journey_nav_profile");checked("journey_consent_records",true)
            click("coach_workflow");click("workflow_tasks");click("workflow_task_D03");click("workflow_resume")
            press("workflow_gas",true);waitFor("No braking stage"){text("이제 가상 브레이크")};press("workflow_gas",false);click("workflow_pause")
            waitFor("Checkpoint not persisted"){WorkflowStore(context,{true}).pendingTask()?.id=="D03"}
            assertTrue(WorkflowStore(context,{true}).pendingDescription()!!.contains("브레이크"))
        } catch(t:Throwable) { activity.close();restore();throw t }
        activity.close()
    }
    @Test(timeout=90_000) fun verifyColdStartRecoveryAndExplicitPreparation() {
        assertTrue("Run the checkpoint phase first",backup.exists())
        activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            click("coach_workflow");waitFor("Missing restored checkpoint"){has("workflow_recover")}
            assertTrue(text("중단 당시"));assertFalse(has("workflow_gas"))
            click("workflow_recover");waitFor("Preparation was bypassed"){has("journey_preflight_0")};assertFalse(has("workflow_gas"))
            preflight();click("coach_workflow");click("workflow_recover")
            assertTrue(has("workflow_resume"));assertTrue(text("가상 0.0km/h"));assertTrue(text("가상 속도 10km/h에 도달"))
            var speaking=true;activity.onActivity{a->speaking=views(a.window.decorView).filterIsInstance<WorkflowView>().single().isGuidanceSpeaking}
            assertFalse(speaking);SystemClock.sleep(400);assertTrue(text("가상 0.0km/h"))
        } finally {activity.close();restore()}
    }
}
