package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only visible production UI actions. Existing history is neither saved nor deleted. */
@RunWith(AndroidJUnit4::class)
class WorkflowFlowTest {
    private lateinit var activity: ActivityScenario<MainActivity>
    private fun views(root: View): List<View> = listOf(root) + if(root is ViewGroup) (0 until root.childCount).flatMap{views(root.getChildAt(it))} else emptyList()
    private fun all(): List<View> { var result=emptyList<View>();activity.onActivity{result=views(it.window.decorView)};return result }
    private fun eventually(message: String, timeout: Long=10_000, condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(80)}
        fail(message)
    }
    private fun has(key:String)=all().any{it.contentDescription?.toString()==key&&it.isShown}
    private fun hasText(text:String)=all().filterIsInstance<TextView>().any{it.isShown&&it.text.contains(text)}
    private fun click(key:String) {
        eventually("Missing enabled $key") {all().any{it.contentDescription?.toString()==key&&it.isEnabled&&it.isShown}}
        activity.onActivity{ a->val v=views(a.window.decorView).first{it.contentDescription?.toString()==key};v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true);assertTrue(v.performClick())}
        SystemClock.sleep(80)
    }
    private fun checked(key:String,value:Boolean) {
        eventually("Missing toggle $key"){has(key)}
        activity.onActivity{a->(views(a.window.decorView).first{it.contentDescription?.toString()==key} as CompoundButton).isChecked=value}
    }
    private fun press(key:String,down:Boolean) {
        activity.onActivity{a->val v=views(a.window.decorView).first{it.contentDescription?.toString()==key}
            v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true)
            val now=SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,if(down)MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,v.width/2f,v.height/2f,0)
            try{v.dispatchTouchEvent(e)}finally{e.recycle()}}
    }
    private fun speaking():Boolean { var result=false; activity.onActivity{a->result=views(a.window.decorView).filterIsInstance<WorkflowView>().any{it.isGuidanceSpeaking}};return result }
    private val configurationKeys=listOf("record_consent","vehicle_consent","has_profile","height","weight","experience","confidence","guidance","difficulty","seat_fore_aft","seat_height","seat_backrest","seat_steering","setup")
    private fun restoreConfiguration(context:Context,snapshot:Map<String,*>,checkpoint:String?) {
        val prefs=context.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE)
        val edit=prefs.edit()
        for(key in configurationKeys) when(val value=snapshot[key]) {
            null->edit.remove(key)
            is Boolean->edit.putBoolean(key,value)
            is Int->edit.putInt(key,value)
            is String->edit.putString(key,value)
        }
        assertTrue(edit.commit())
        val checkpoints=context.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).edit()
        if(checkpoint==null)checkpoints.remove("checkpoint") else checkpoints.putString("checkpoint",checkpoint)
        assertTrue(checkpoints.commit())
    }
    private fun prepare() {
        click("journey_nav_profile");click("journey_profile_continue")
        repeat(12){if(!has("journey_preflight_0"))click("journey_setup_confirm")}
        for(i in 0..6){val key="journey_preflight_$i";if(all().filterIsInstance<TextView>().none{it.contentDescription?.toString()==key&&it.text.contains("✓")})click(key)}
    }
    private fun back(){activity.onActivity{it.onBackPressed()};SystemClock.sleep(100)}
    @Test(timeout=150_000) fun weatherManualCourseGateControlsReviewAndNewParkingWorkTogether() {
        assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("emulator",true)||Build.MODEL.contains("Car_on"))
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val record=target.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE).getBoolean("record_consent",false)
        val voice=target.getSharedPreferences("coach",Context.MODE_PRIVATE).getBoolean("voice_enabled",true)
        val muted=target.getSharedPreferences("workflow_ui_v1",Context.MODE_PRIVATE).getBoolean("muted",false)
        val originalConfiguration=target.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE).all.toMap()
        val checkpoint=target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).getString("checkpoint",null)
        val before=WorkflowStore(target,{true}).records().map{it.toString()}
        activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            click("journey_nav_profile");checked("journey_consent_records",false);checked("voice_toggle",true)
            click("coach_workflow")
            if(hasText("안내 재개"))click("workflow_mute")
            click("workflow_weather");click("workflow_weather_retry");assertTrue(hasText("현재 날씨 미확인"))
            click("workflow_weather_rain");assertTrue(hasText("SIMULATION"));click("workflow_weather_speak")
            eventually("No real SDK/fallback TTS start",15_000){hasText("음성 안내 중")}
            eventually("Weather advice must become non-repeatable after actual output starts"){
                all().any{it.contentDescription?.toString()=="workflow_weather_speak"&&!it.isEnabled}
            }
            click("workflow_mute");eventually("Voice did not stop"){!speaking()};assertTrue(hasText("껐")||hasText("멈췄"))
            click("workflow_home");click("workflow_manual");click("workflow_manual_seat_heat")
            assertTrue(hasText("설명서 확인 필요"));click("workflow_manual_demo")
            click("workflow_manual_next");click("workflow_manual_next");click("workflow_manual_operate")
            click("workflow_manual_next");click("workflow_manual_confirm");assertTrue(hasText("가상 학습 확인 완료"))
            click("workflow_home");click("workflow_courses");click("workflow_start_location");click("workflow_goal_weakness")
            click("workflow_route_sim-target");assertTrue(hasText("실제 내비 경로는 설정하지 않았어요"))
            click("workflow_course_refresh");assertFalse(has("workflow_course_start"));click("workflow_route_sim-target")
            click("workflow_course_start")
            eventually("Missing preparation gate"){has("journey_nav_profile")}
            assertFalse(has("workflow_gas"))
            click("journey_nav_profile");click("journey_profile_continue")
            repeat(12){if(!has("journey_preflight_0"))click("journey_setup_confirm")}
            assertTrue(has("journey_preflight_0"))
            for(i in 0..6){
                val key="journey_preflight_$i"
                if(all().filterIsInstance<TextView>().none{it.contentDescription?.toString()==key&&it.text.contains("✓")})click(key)
            }
            click("coach_workflow");click("workflow_tasks");click("workflow_task_D03")
            assertTrue(hasText("가상 연습"));click("workflow_resume")
            press("workflow_gas",true);eventually("Acceleration did not reach braking stage"){hasText("이제 가상 브레이크")};press("workflow_gas",false)
            press("workflow_brake",true);eventually("Brake did not reach restart stage"){hasText("다시 부드럽게 출발")};press("workflow_brake",false)
            press("workflow_gas",true);eventually("No completion review"){has("workflow_reflect_skip")}
            click("workflow_reflect_skip");assertTrue(hasText("원인 미확인"))
            assertTrue(all().any{it.contentDescription?.toString()=="workflow_save"&&!it.isEnabled})
            click("workflow_home");click("workflow_parking")
            click("park_course_t_bay");click("park_start");assertTrue(hasText("T자 후진 직각주차"))
            back();back();click("park_course_front_bay");click("park_start");assertTrue(hasText("전면 주차"))
            back();back();back()
            assertEquals(before,WorkflowStore(target,{true}).records().map{it.toString()})
        } finally {
            // Restore settings through the production controls, without rewriting user history.
            runCatching{
                repeat(5){if(!has("journey_nav_profile"))back()}
                if(has("coach_workflow")){
                    click("coach_workflow")
                    if(hasText("안내 재개")!=muted)click("workflow_mute")
                    click("workflow_exit")
                }
                click("journey_nav_profile");checked("journey_consent_records",record);checked("voice_toggle",voice)
            }
            activity.close()
            restoreConfiguration(target,originalConfiguration,checkpoint)
        }
    }
    @Test(timeout=90_000) fun backgroundReturnRequiresExplicitPreparationAndDoesNotRestoreMotion() {
        assumeTrue(Build.MODEL.contains("emulator",true)||Build.MODEL.contains("Car_on"))
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=target.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE)
        val original=prefs.all.toMap()
        val checkpoint=target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).getString("checkpoint",null)
        val voice=target.getSharedPreferences("coach",Context.MODE_PRIVATE).getBoolean("voice_enabled",true)
        activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            click("journey_nav_profile");checked("journey_consent_records",false);checked("voice_toggle",false)
            prepare();click("coach_workflow");click("workflow_tasks");click("workflow_task_D03");click("workflow_resume")
            press("workflow_gas",true);eventually("No acceleration"){hasText("이제 가상 브레이크")};press("workflow_gas",false)
            activity.moveToState(Lifecycle.State.CREATED);SystemClock.sleep(500);activity.moveToState(Lifecycle.State.RESUMED)
            eventually("Paused context not shown"){hasText("중단한 과제")}
            assertFalse(speaking());assertTrue(hasText("가상 0.0km/h"))
            SystemClock.sleep(500);assertTrue(hasText("가상 0.0km/h"))
            click("workflow_resume");eventually("Preparation not rechecked"){has("journey_nav_profile")}
            assertFalse(has("workflow_gas"))
            for(i in 0..6){val key="journey_preflight_$i";if(all().filterIsInstance<TextView>().none{it.contentDescription?.toString()==key&&it.text.contains("✓")})click(key)}
            click("coach_workflow");click("workflow_recover")
            assertTrue(hasText("가상 0.0km/h"));assertTrue(has("workflow_resume"));assertFalse(speaking())
        } finally {
            runCatching{repeat(5){if(!has("journey_nav_profile"))back()};checked("voice_toggle",voice)}
            activity.close();restoreConfiguration(target,original,checkpoint)
        }
    }
}
