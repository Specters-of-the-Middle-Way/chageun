package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoadLearningDeviceTest {
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var workflow: WorkflowView
    private var ready = true
    private var preparationRequests = 0
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun all(): List<View> { var result = emptyList<View>(); activity.onActivity { result = views(workflow) }; return result }
    private fun text(value: String) = all().filterIsInstance<TextView>().any { it.text.contains(value) }
    private fun enabled(key: String) = all().first { it.contentDescription == key }.isEnabled
    private fun click(key: String) {
        activity.onActivity {
            val view = views(workflow).first { it.contentDescription == key }
            assertTrue(key,view.isEnabled)
            view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true); assertTrue(view.performClick())
        }
        SystemClock.sleep(80)
    }
    private fun press(key: String, down: Boolean) {
        activity.onActivity {
            val view = views(workflow).first { it.contentDescription == key }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now,now,if(down)MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,view.width/2f,view.height/2f,0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun eventually(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+25_000
        while(SystemClock.elapsedRealtime()<end) { if(condition()) return; SystemClock.sleep(100) }
        fail(message)
    }
    private fun session(block: () -> Unit) {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val original=target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).all.toMap()
        val names=mutableSetOf<String>(); val prefix="road_learning_test_${System.nanoTime()}_"
        val weatherSettings = WeatherTestSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            activity.onActivity { host ->
                val isolated=object:ContextWrapper(host) {
                    override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(prefix+name,mode).also { names+=prefix+name }
                }
                workflow=WorkflowView(isolated,{}, {preparationRequests++}, {}, {}, {ready}, {false}, {false}, {}, {}, {})
                host.setContentView(workflow); workflow.setSpeechEnabled(false); workflow.setActive(true)
            }
            block()
        } finally {
            activity.onActivity { if(::workflow.isInitialized)workflow.close() }; activity.close()
            weatherSettings.close()
            names.forEach { target.deleteSharedPreferences(it) }
            assertEquals(original,target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).all.toMap())
        }
    }
    private fun completeGuide(id:String) {
        val lesson=RoadLearningCatalog.guides.first { it.id==id }
        click("workflow_guide_$id"); repeat(3){click("workflow_guide_next")}
        click("workflow_guide_answer_${lesson.answer}")
    }
    @Test fun allNewGuidesNeedComprehensionBeforeLinkingToTheirPractice()=session {
        for(lesson in RoadLearningCatalog.guides) {
            click("workflow_guides"); click("workflow_guide_${lesson.id}")
            lesson.illustrations.firstOrNull()?.let { illustration -> assertTrue(all().any { it.contentDescription=="교육 그림 · ${illustration.caption}" }) }
            repeat(3){click("workflow_guide_next")}
            assertFalse(enabled("workflow_guide_practice"))
            click("workflow_guide_answer_${1-lesson.answer}"); assertFalse(enabled("workflow_guide_practice"))
            click("workflow_guide_answer_${lesson.answer}"); assertTrue(enabled("workflow_guide_practice"))
            click("workflow_guide_practice"); assertTrue(text(ScenarioCatalog.find(lesson.relatedTask!!)!!.title))
            assertTrue(all().any { it.contentDescription=="workflow_resume" }); click("workflow_home")
        }
    }
    @Test fun gapExamplesRecalculateAndInactiveControlsDoNotChangeState()=session {
        click("workflow_guides"); click("workflow_guide_following_gap")
        click("gap_study_speed_60"); assertTrue(text("시간 간격 1.8초"))
        click("gap_study_gap_60"); assertTrue(text("시간 간격 3.6초"))
        click("gap_study_target_6"); assertTrue(text("100.0m")); assertTrue(text("예시 목표보다 작아요"))
        click("gap_study_speed_30"); assertTrue(text("시간 간격 7.2초"))
        click("gap_study_speed_0"); assertTrue(text("정차 예시"))
        activity.onActivity {
            workflow.setActive(false)
            views(workflow).first { it.contentDescription=="gap_study_speed_60" }.performClick()
            workflow.setActive(true)
        }
        assertTrue(text("정차 예시"))
        click("workflow_home"); click("workflow_guides"); assertTrue(text("정차 예시"))
    }
    @Test fun lessonCompletionDoesNotBypassPreparation()=session {
        ready=false; click("workflow_guides"); completeGuide("look_ahead")
        click("workflow_guide_practice"); assertEquals(1,preparationRequests)
        assertFalse(all().any { it.contentDescription=="workflow_gas" })
    }
    @Test(timeout=70_000) fun followingTaskShowsTimeHeadwayAndCompletesWithControls()=session {
        click("workflow_guides"); completeGuide("following_gap"); click("workflow_guide_practice")
        assertTrue(text("정차·저속 시 시간 간격 미계산")); click("workflow_resume"); press("workflow_gas",true)
        eventually("Did not accelerate") {
            val values=all().filterIsInstance<TextView>().map { it.text.toString() }
            values.any { value -> Regex("가상 ([0-9.]+)km/h").find(value)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it>=25 }==true }
        }
        press("workflow_gas",false); assertTrue(text("교육 목표 3초"))
        eventually("No completion review") { text("복습 · 앞차와 거리 맞추기") }
        assertTrue(text("COMPLETED")); assertFalse(enabled("workflow_save"))
    }
}
