package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import kr.drivestep.tutor.data.PracticeRepository
import kr.drivestep.tutor.journey.DriverJourneyView
import kr.drivestep.tutor.lessoncoach.LessonTopic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production routes, native dialogs and typed practice; preserves existing history and never sends a destination. */
@RunWith(AndroidJUnit4::class)
class AllScreensVisibleTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:ActivityScenario<MainActivity>
    private fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun all():List<View> { var result=emptyList<View>(); activity.onActivity { result=views(it.window.decorView) }; return result }
    private fun click(key:String) {
        val deadline=SystemClock.elapsedRealtime()+12_000
        while(all().none { it.contentDescription==key && it.isShown && it.isEnabled } && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(80)
        activity.onActivity { host ->
            val view=views(host.window.decorView).first { it.contentDescription==key }
            assertTrue("Disabled $key",view.isEnabled); view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true); assertTrue(view.performClick())
        }; SystemClock.sleep(120)
    }
    private fun checked(key:String,value:Boolean) { activity.onActivity { host -> (views(host.window.decorView).first { it.contentDescription==key } as CompoundButton).isChecked=value }; SystemClock.sleep(80) }
    private fun has(key:String)=all().any { it.contentDescription==key }
    private fun textContains(value:String)=all().filterIsInstance<TextView>().any { it.text.contains(value) }
    private fun dialogAction(label:String) {
        val end=SystemClock.elapsedRealtime()+5_000
        while(SystemClock.elapsedRealtime()<end) {
            val node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString()==label && it.isClickable }
            if(node!=null) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(150); return }
            SystemClock.sleep(80)
        }; fail("Missing dialog action $label")
    }
    private fun capture(name:String,dialog:Boolean=false) {
        SystemClock.sleep(180)
        if(!dialog) activity.onActivity { host ->
            views(host.window.decorView).filterIsInstance<TextView>().filter { it.isShown && it !is EditText && it.text.isNotEmpty() }.forEach { view ->
                val layout=view.layout ?: return@forEach
                assertTrue("$name clipped ${view.text}",layout.height<=view.height-view.compoundPaddingTop-view.compoundPaddingBottom)
                repeat(layout.lineCount) { line -> assertEquals("$name ellipsis ${view.text}",0,layout.getEllipsisCount(line)) }
            }
            for(key in listOf("workflow_home","lesson_start","lesson_finish","lesson_review_back")) views(host.window.decorView).firstOrNull { it.contentDescription==key }?.let {
                val rect=Rect(); assertTrue("$name $key offscreen",it.getGlobalVisibleRect(rect)); assertEquals("$name $key clipped",it.height,rect.height())
            }
        }
        val directory=File(instrumentation.targetContext.cacheDir,"all-screens").apply { mkdirs() }
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(150) }

    @Test(timeout=90_000) fun vehiclePracticeActiveAndInterruptedScreensRemainReadable() {
        val target=instrumentation.targetContext
        val preferenceNames=listOf("drivestep_journey_v1","coach")
        val original=preferenceNames.associateWith { target.getSharedPreferences(it,Context.MODE_PRIVATE).all.toMap() }
        val repository=PracticeRepository(target)
        val existingIds=repository.listRecent(200).map { it.id }.toSet()
        val steeringFile=File(target.filesDir,"steering-practice.json")
        val steeringOriginal=steeringFile.takeIf { it.exists() }?.readBytes()
        WeatherTestSettings(target).use {
            target.getSharedPreferences("coach",Context.MODE_PRIVATE).edit().putBoolean("voice_enabled",false).commit()
            activity=ActivityScenario.launch(MainActivity::class.java)
            try {
                click("journey_nav_profile"); checked("journey_consent_records",true); checked("journey_consent_vehicle",true)
                click("journey_education"); click("journey_sdk_signal"); click("guide_start")
                assertTrue(has("stop_practice")); capture("26-signal-active")
                click("stop_practice"); assertTrue(textContains("이번에는 여기까지 했어요"))
                capture("27-signal-review"); click("delete_record"); dialogAction("삭제")
                click("journey_education"); click("journey_sdk_steering"); click("steering_start")
                assertTrue(has("stop_steering")); capture("28-steering-active")
                click("stop_steering"); assertTrue(textContains("기어·조향 실습 복습")); capture("29-steering-review")
                click("delete_steering"); dialogAction("삭제"); click("journey_nav_profile")
                SystemClock.sleep(400)
                assertEquals(existingIds,repository.listRecent(200).map { it.id }.toSet())
            } finally {
                activity.close(); SystemClock.sleep(400)
                // Remove only records created by this isolated device run, preserving prior history.
                repository.listRecent(200).filter { it.id !in existingIds }.forEach { repository.delete(it.id) }
                repository.close()
                if(steeringOriginal!=null) steeringFile.writeBytes(steeringOriginal) else steeringFile.delete()
                original.forEach { (name,values) -> target.getSharedPreferences(name,Context.MODE_PRIVATE).edit().apply {
                    clear(); values.forEach { (key,value) -> when(value) {
                        is Boolean->putBoolean(key,value); is String->putString(key,value); is Int->putInt(key,value)
                        is Long->putLong(key,value); is Float->putFloat(key,value)
                        is Set<*>->putStringSet(key,value.filterIsInstance<String>().toSet())
                    } }; commit()
                } }
            }
        }
    }

    @Test(timeout=180_000) fun currentRoutesDialogsAndLessonReviewRemainUsable() {
        val target=instrumentation.targetContext
        val preferenceNames=listOf("drivestep_journey_v1","coach","workflow_ui_v1","connected_ui_v1")
        val original=preferenceNames.associateWith { target.getSharedPreferences(it,Context.MODE_PRIVATE).all.toMap() }
        val records=target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).all.toMap()
        WeatherTestSettings(target).use {
            target.getSharedPreferences("coach",Context.MODE_PRIVATE).edit().putBoolean("voice_enabled",false).commit()
            target.getSharedPreferences("workflow_ui_v1",Context.MODE_PRIVATE).edit().putBoolean("muted",true).commit()
            activity=ActivityScenario.launch(MainActivity::class.java)
            try {
                click("journey_nav_profile"); checked("journey_consent_records",false); checked("journey_consent_vehicle",false); capture("01-profile")
                click("journey_profile_continue"); capture("02-setup"); repeat(9) { click("journey_setup_confirm") }; capture("03-preflight")
                for(index in 0..6) if(!all().filterIsInstance<TextView>().first { it.contentDescription=="journey_preflight_$index" }.text.contains("✓")) click("journey_preflight_$index")
                click("journey_preflight_continue"); capture("04-practice-selection")
                click("journey_nav_review"); capture("05-review")
                activity.onActivity { host -> views(host.window.decorView).filterIsInstance<DriverJourneyView>().single().beginVisit() }; capture("06-revisit")
                click("journey_education"); capture("07-education")
                click("journey_parking_guide"); capture("08-education-dialog",true); dialogAction("닫기")
                click("journey_sdk_signal"); capture("09-connection-dialog",true); dialogAction("확인")
                click("journey_nav_profile"); checked("journey_consent_records",true); checked("journey_consent_vehicle",true)
                click("journey_education"); click("journey_sdk_signal"); capture("10-signal-guide"); back()
                click("journey_education"); click("journey_sdk_steering"); capture("11-steering-guide"); back()
                click("journey_nav_profile"); checked("journey_consent_records",false); checked("journey_consent_vehicle",false)
                click("coach_workflow"); capture("12-workflow-home")
                click("workflow_connected"); capture("13-connected")
                activity.onActivity { host -> views(host.window.decorView).first { it.contentDescription=="connected_destination_send" }.let { view -> view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true) } }
                capture("14-destination-form")
                click("workflow_home"); click("workflow_weather"); click("workflow_weather_practice")
                for(condition in WeatherCondition.entries) { click("workflow_weather_${condition.name.lowercase()}"); capture("15-weather-${condition.name.lowercase()}") }
                click("workflow_weather_manual"); assertTrue(has("workflow_manual_cn7_manual"))
                click("workflow_home"); click("workflow_history"); capture("16-history")
                click("workflow_delete"); capture("17-delete-dialog",true); dialogAction("취소")
                click("workflow_exit"); click("coach_lesson"); capture("18-lesson-menu")
                click("lesson_gleo_help"); capture("19-gleo-help",true); dialogAction("닫기")
                click("lesson_mode_live"); capture("20-lesson-consent",true); dialogAction("나중에")
                click("lesson_gleo_start"); capture("21-gleo"); click("gleo_lesson_back")
                for(topic in LessonTopic.entries) {
                    click("lesson_topic_${topic.name.lowercase()}"); click("lesson_start"); click("lesson_stop_listening")
                    click("lesson_example_traffic"); click("lesson_signal_left"); click("lesson_demo_slow")
                    assertTrue(textContains("8.0 km/h")); capture("22-lesson-${topic.name.lowercase()}")
                    click("lesson_pause"); assertTrue(textContains("쉬고 있어요")); capture("23-paused-${topic.name.lowercase()}")
                    click("lesson_resume"); click("lesson_stop_listening"); click("lesson_finish"); capture("24-reflection",true)
                    dialogAction("수월했어요"); assertTrue(has("lesson_review_next")); capture("25-replay-${topic.name.lowercase()}")
                    click("lesson_review_next"); click("lesson_review_previous"); click("lesson_replay"); click("lesson_review_next")
                    click("lesson_review_back")
                }
                assertEquals(records,target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).all.toMap())
            } finally {
                activity.close()
                original.forEach { (name,values) -> target.getSharedPreferences(name,Context.MODE_PRIVATE).edit().apply {
                    clear(); values.forEach { (key,value) -> when(value) {
                        is Boolean->putBoolean(key,value); is String->putString(key,value); is Int->putInt(key,value)
                        is Long->putLong(key,value); is Float->putFloat(key,value)
                        is Set<*>->putStringSet(key,value.filterIsInstance<String>().toSet())
                    } }; commit()
                } }
            }
        }
    }
}
