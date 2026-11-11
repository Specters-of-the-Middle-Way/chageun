package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CompoundButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningEnhancementsDeviceTest {
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var workflow: WorkflowView
    private lateinit var isolated: Context
    private var consent = true
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun all(): List<View> { var result = emptyList<View>(); activity.onActivity { result = views(workflow) }; return result }
    private fun text(value: String) = all().filterIsInstance<TextView>().any { it.text.contains(value) }
    private fun click(key: String) {
        activity.onActivity {
            val view = views(workflow).first { it.contentDescription == key }
            assertTrue(key, view.isEnabled)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            assertTrue(view.performClick())
        }
        SystemClock.sleep(100)
    }
    private fun checked(key: String, value: Boolean) {
        activity.onActivity { (views(workflow).first { it.contentDescription == key } as CompoundButton).isChecked = value }
        SystemClock.sleep(100)
    }
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        fail(message)
    }
    private fun speaking(): Boolean { var result = false; activity.onActivity { result = workflow.isGuidanceSpeaking }; return result }
    private fun session(block: () -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val weatherSettings = WeatherTestSettings(target)
        val names = mutableSetOf<String>()
        val prefix = "enhancements_test_${System.nanoTime()}_"
        activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            activity.onActivity { host ->
                isolated = object : ContextWrapper(host) {
                    override fun getSharedPreferences(name: String, mode: Int) =
                        super.getSharedPreferences(prefix + name, mode).also { names += prefix + name }
                }
                workflow = WorkflowView(isolated, {}, {}, {}, {}, { true }, { consent }, { false }, {}, {}, {})
                host.setContentView(workflow); workflow.setSpeechEnabled(false); workflow.setActive(true)
            }
            block()
        } finally {
            activity.onActivity { if (::workflow.isInitialized) workflow.close() }
            activity.close()
            weatherSettings.close()
            names.forEach { target.deleteSharedPreferences(it) }
        }
    }
    @Test fun visibilityManualsCompleteOnlyAfterAllSteps() = session {
        click("workflow_manual"); click("workflow_manual_cn7_manual")
        mapOf("wiper" to "5-67", "lights" to "5-62", "rear_defog" to "5-89").forEach { (feature, section) ->
            click("workflow_manual_$feature"); assertTrue(text(section))
            assertFalse(all().first { it.contentDescription == "workflow_manual_confirm" }.isEnabled)
            repeat(3) { click("workflow_manual_next") }
            click("workflow_manual_confirm"); assertTrue(text("설명서 학습 확인 완료"))
            assertFalse(all().any { it.contentDescription == "workflow_manual_operate" })
        }
    }
    @Test fun explicitDifficultyPersistsAndDrivesTheSelectedCourse() = session {
        click("workflow_tasks"); click("workflow_task_D09"); click("workflow_cancel")
        assertTrue(text("선택 사항이에요. 어려움을 자동으로 판단하지 않아요."))
        click("workflow_difficulty_current"); click("workflow_save")
        val store = WorkflowStore(isolated, { consent })
        assertEquals("D09", store.records().single().getString("difficulty"))
        assertEquals("D09", store.records().single().getString("next"))
        assertFalse(all().first { it.contentDescription == "workflow_difficulty_skip" }.isEnabled)
        click("workflow_review_course"); assertTrue(text("직접 어려웠다고 선택한 과제"))
        click("workflow_recommend_D09"); click("workflow_start_location")
        assertTrue(text("차로 변경 준비")); click("workflow_route_sim-target"); click("workflow_course_start")
        assertTrue(text("차로 변경 준비")); click("workflow_home")
        consent = false
        click("workflow_courses"); assertTrue(text("기록 동의가 꺼져 있어"))
        assertFalse(all().any { it.contentDescription == "workflow_recommend_D09" })
        assertEquals(1, store.records().size) // Revocation must not erase existing records.
    }
    @Test fun skippedDifficultyDoesNotBecomeAWeaknessRecommendation() = session {
        click("workflow_tasks"); click("workflow_task_D03"); click("workflow_cancel")
        click("workflow_difficulty_current"); click("workflow_difficulty_skip"); click("workflow_save")
        val store = WorkflowStore(isolated, { true })
        assertTrue(store.records().single().isNull("difficulty"))
        assertTrue(store.recommendations(System.currentTimeMillis()).isEmpty())
        click("workflow_review_course"); assertTrue(text("아직 추천 근거가 없어요"))
    }
    @Test(timeout = 120_000) fun liveWeatherAutoSpeechHonorsMuteStopsAndDoesNotReplay() = session {
        click("workflow_weather")
        checked("weather_live_auto", false)
        assertFalse((all().first { it.contentDescription == "weather_live_auto" } as CompoundButton).isChecked)
        checked("weather_live_auto", true); checked("weather_live_consent", true)
        click("weather_live_location")
        eventually("Muted lookup failed") { text("예보 기준") }
        assertFalse(speaking())
        activity.onActivity { workflow.setSpeechEnabled(true) }
        click("weather_live_location")
        eventually("Automatic speech did not start") { text("음성 안내 중") && speaking() }
        val forecast = all().filterIsInstance<TextView>().first { it.text.contains("예보 기준") }.text.toString()
        checked("weather_live_auto", false); assertFalse(speaking())
        activity.onActivity { workflow.setActive(false); workflow.setActive(true) }
        assertFalse(speaking())
        checked("weather_live_auto", true); click("weather_live_location")
        eventually("Repeat lookup failed") { text("예보 기준") }
        val refreshed = all().filterIsInstance<TextView>().first { it.text.contains("예보 기준") }.text.toString()
        // A provider interval crossing may legitimately deliver a new forecast.
        if (forecast == refreshed) { SystemClock.sleep(1_000); assertFalse(speaking()) }
        checked("weather_live_consent", false); assertFalse(speaking()); assertFalse(text("예보 기준"))
    }
}
