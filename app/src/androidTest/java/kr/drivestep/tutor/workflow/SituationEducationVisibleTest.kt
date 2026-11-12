package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reads every lesson and answers its comprehension question through the production activity. */
@RunWith(AndroidJUnit4::class)
class SituationEducationVisibleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var host: MainActivity
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun all(): List<View> { var result = emptyList<View>(); instrumentation.runOnMainSync { result = views(host.window.decorView) }; return result }
    private fun has(key: String) = all().any { it.contentDescription == key && it.isShown }
    private fun waitFor(message: String, seconds: Int = 15, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1_000
        while (SystemClock.elapsedRealtime() < end) { if (check()) return; SystemClock.sleep(50) }
        fail(message)
    }
    private fun click(key: String) {
        waitFor("Missing enabled $key") { all().any { it.contentDescription == key && it.isShown && it.isEnabled } }
        instrumentation.runOnMainSync {
            val view = views(host.window.decorView).first { it.contentDescription == key }
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            assertTrue(view.performClick())
        }
        SystemClock.sleep(100)
    }
    private fun toggle(key: String, value: Boolean) = instrumentation.runOnMainSync {
        (views(host.window.decorView).first { it.contentDescription == key } as CompoundButton).isChecked = value
    }
    private fun text(key: String) = all().filterIsInstance<TextView>().firstOrNull { it.contentDescription == key }?.text?.toString().orEmpty()
    private fun capture(name: String) {
        SystemClock.sleep(180)
        instrumentation.runOnMainSync {
            val controls = views(host.window.decorView)
            for (key in listOf("workflow_home", "workflow_guide_previous", "workflow_guide_next", "workflow_guide_practice", "workflow_guide_result")) {
                controls.firstOrNull { it.contentDescription == key }?.let { view ->
                    val visible = Rect()
                    assertTrue("$name: $key offscreen", view.getGlobalVisibleRect(visible))
                    assertEquals("$name: $key vertically clipped", view.height, visible.height())
                    assertEquals("$name: $key horizontally clipped", view.width, visible.width())
                }
            }
        }
        val directory = File(instrumentation.targetContext.cacheDir, "situation-education").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test(timeout = 180_000) fun allLessonsQuestionsAndRelatedPracticeRoutesWork() {
        val context = instrumentation.targetContext
        val journey = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val coach = context.getSharedPreferences("coach", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
        val history = context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE)
        val original = journey.all.toMap()
        val voice = coach.all["voice_enabled"] as? Boolean
        val muted = settings.all["muted"] as? Boolean
        val records = history.getString("records", null)
        val checkpoint = history.getString("checkpoint", null)
        val weather = WeatherTestSettings(context)
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { host = it }
        try {
            click("journey_nav_profile"); toggle("journey_consent_records", false); toggle("journey_consent_vehicle", false)
            toggle("voice_toggle", false); click("journey_profile_continue")
            repeat(9) { click("journey_setup_confirm") }
            for (index in 0..6) if (!text("journey_preflight_$index").contains("✓")) click("journey_preflight_$index")
            click("journey_preflight_continue"); click("journey_start_workflow"); click("workflow_guides")
            capture("topic-selection")
            for (topic in DrivingGuideCatalog.guides) {
                click("workflow_guide_${topic.id}")
                assertTrue(all().first { it.contentDescription == "workflow_guide_${topic.id}" }.isSelected)
                assertTrue(text("workflow_guide_progress").contains("1 / 4"))
                click("workflow_guide_next"); click("workflow_guide_${topic.id}")
                assertTrue(text("workflow_guide_progress").contains("2 / 4"))
                click("workflow_guide_previous"); click("workflow_guide_speak")
                if (topic.id == "following_gap") {
                    click("gap_study_speed_60"); assertTrue(text("gap_study_result").contains("1.8초"))
                    click("gap_study_gap_60"); click("gap_study_target_6")
                    assertTrue(text("gap_study_result").contains("100.0m")); capture("gap-calculator")
                    click("gap_study_speed_0"); assertTrue(text("gap_study_result").contains("정차 예시"))
                    capture("gap-stationary"); click("gap_study_speed_30")
                }
                if (topic.illustrations.isNotEmpty()) capture("${topic.id}-step-1")
                repeat(3) { index ->
                    click("workflow_guide_next")
                    assertEquals(topic.steps[index + 1], text("workflow_guide_instruction"))
                    if (topic.illustrations.isNotEmpty()) capture("${topic.id}-step-${index + 2}")
                }
                if (topic.relatedTask != null) assertFalse(all().first { it.contentDescription == "workflow_guide_practice" }.isEnabled)
                click("workflow_guide_answer_${1 - topic.answer}")
                assertTrue(text("workflow_guide_result").contains("다시 생각"))
                if (topic.id == "look_ahead") capture("try-again")
                click("workflow_guide_answer_${topic.answer}")
                assertTrue(text("workflow_guide_result").contains("이해 확인 완료"))
                capture("${topic.id}-completed")
                if (topic.relatedTask != null) {
                    click("workflow_guide_practice")
                    assertEquals(ScenarioCatalog.find(topic.relatedTask)!!.instruction, text("workflow_guidance"))
                    assertFalse(all().first { it.contentDescription == "workflow_gas" }.isEnabled)
                    if (topic.id == "look_ahead") capture("related-practice")
                    click("workflow_cancel"); click("workflow_home"); click("workflow_guides")
                }
            }
            assertEquals(records, history.getString("records", null))
        } finally {
            activity.close(); weather.close()
            val edit = journey.edit()
            for (key in listOf("record_consent", "vehicle_consent", "has_profile", "height", "weight", "experience", "confidence",
                "guidance", "difficulty", "seat_fore_aft", "seat_height", "seat_backrest", "seat_steering", "setup")) {
                when (val value = original[key]) {
                    null -> edit.remove(key)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is String -> edit.putString(key, value)
                }
            }
            edit.commit()
            coach.edit().apply { if (voice == null) remove("voice_enabled") else putBoolean("voice_enabled", voice) }.commit()
            settings.edit().apply { if (muted == null) remove("muted") else putBoolean("muted", muted) }.commit()
            history.edit().apply { if (checkpoint == null) remove("checkpoint") else putString("checkpoint", checkpoint) }.commit()
        }
    }
}
