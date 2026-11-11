package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Visible production controls, real simulation ticks, and screenshots; no injected simulation state. */
@RunWith(AndroidJUnit4::class)
class VirtualCourseVisibleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var host: MainActivity
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    // Do not wait for animation idleness while watching a moving vehicle enter a small target.
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
    private fun touch(key: String, down: Boolean, fraction: Float = .5f) = instrumentation.runOnMainSync {
        val view = views(host.window.decorView).firstOrNull { it.contentDescription == key }
            ?: if (!down) return@runOnMainSync else error("Missing touch target $key")
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        val now = SystemClock.uptimeMillis()
        val inset = 16 * view.resources.displayMetrics.density
        val event = MotionEvent.obtain(now, now, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
            inset + (view.width - 2 * inset) * fraction, view.height / 2f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun text(key: String) = all().filterIsInstance<TextView>().firstOrNull { it.contentDescription == key }?.text?.toString().orEmpty()
    private fun capture(name: String) {
        SystemClock.sleep(180)
        val directory = File(instrumentation.targetContext.cacheDir, "virtual-course").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test(timeout = 240_000) fun selectAndCompleteBasicCourseThroughProductionControls() {
        val context = instrumentation.targetContext
        val journey = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val coach = context.getSharedPreferences("coach", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
        val history = context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE)
        val original = journey.all.toMap()
        val voice = coach.getBoolean("voice_enabled", true)
        val muted = settings.getBoolean("muted", false)
        val records = history.getString("records", null)
        val checkpoint = history.getString("checkpoint", null)
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { host = it }
        fun completed() = has("workflow_reflect_skip")
        fun driveUntil(condition: () -> Boolean) {
            touch("workflow_gas", true)
            try { waitFor("No expected driving stage", 35, condition) } finally { touch("workflow_gas", false) }
        }
        fun brakeUntil(condition: () -> Boolean) {
            touch("workflow_brake", true)
            try { waitFor("No expected braking stage", 20, condition) } finally { touch("workflow_brake", false) }
        }
        try {
            click("journey_nav_profile"); toggle("journey_consent_records", false); toggle("journey_consent_vehicle", false)
            toggle("voice_toggle", false); click("journey_profile_continue")
            repeat(9) { click("journey_setup_confirm") }
            for (index in 0..6) if (!text("journey_preflight_$index").contains("✓")) click("journey_preflight_$index")
            click("journey_preflight_continue"); click("journey_start_workflow"); click("workflow_courses")
            capture("before-location")
            assertFalse(all().first { it.contentDescription == "workflow_course_start" }.isEnabled)
            click("workflow_start_location"); capture("course-options")
            click("workflow_goal_return_trip"); click("workflow_course_exclusion"); capture("no-matching-course")
            assertFalse(all().first { it.contentDescription == "workflow_course_start" }.isEnabled)
            click("workflow_course_exclusion"); click("workflow_goal_weakness"); capture("targeted-practice")
            click("workflow_course_task")
            SystemClock.sleep(200)
            val taskNode = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("D05 · 교차로·신호 이해").firstOrNull()
            assertNotNull("Visible task picker", taskNode)
            assertTrue(taskNode!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Task choice not applied") { text("workflow_course_task").contains("교차로·신호") }
            click("workflow_route_sim-target"); capture("target-selected")
            click("workflow_course_refresh")
            assertFalse(all().first { it.contentDescription == "workflow_course_start" }.isEnabled)
            click("workflow_goal_beginner"); click("workflow_route_sim-basic"); capture("basic-selected")
            click("workflow_goal_weakness")
            assertTrue("Selecting another route must retain the requested target", text("workflow_course_task").contains("교차로·신호"))
            click("workflow_goal_beginner"); click("workflow_route_sim-basic"); click("workflow_course_start")
            assertTrue(text("workflow_course_progress").contains("1 / 3")); capture("course-ready")
            click("workflow_resume"); click("workflow_pause"); assertFalse(all().first { it.contentDescription == "workflow_gas" }.isEnabled)
            click("workflow_cancel"); capture("course-cancelled")
            click("workflow_next_task"); assertTrue(text("workflow_course_progress").contains("1 / 3"))
            click("workflow_resume")
            driveUntil { Regex("가상 ([0-9.]+)km/h").find(text("workflow_stats"))?.groupValues?.get(1)?.toDouble()?.let { it >= 30 } == true }
            waitFor("First course task did not complete", 25) { completed() }
            assertTrue(text("workflow_course_progress").contains("1/3")); capture("first-task-completed")
            click("workflow_next_task"); assertTrue(text("workflow_course_progress").contains("2 / 3"))
            click("workflow_resume"); driveUntil { text("workflow_guidance").contains("이제 가상 브레이크") }
            brakeUntil { text("workflow_guidance").contains("다시 부드럽게 출발") }
            driveUntil { completed() }; capture("second-task-completed")
            click("workflow_next_task"); assertTrue(text("workflow_course_progress").contains("3 / 3"))
            click("workflow_resume"); click("workflow_signal_right"); click("workflow_mirror"); click("workflow_wait")
            waitFor("Turn waiting phase") { text("workflow_guidance").contains("방향을 유지") }
            touch("workflow_steering", true, .95f); touch("workflow_steering", false, .95f)
            touch("workflow_gas", true)
            try { SystemClock.sleep(4_200); click("workflow_center"); waitFor("Turn not complete") { completed() } }
            finally { touch("workflow_gas", false) }
            assertTrue(text("workflow_course_progress").contains("코스 마침")); capture("course-completed")
            assertFalse(all().first { it.contentDescription == "workflow_save" }.isEnabled)
            assertTrue(has("workflow_course_finish")); assertFalse(has("workflow_next_task"))
            click("workflow_course_finish"); capture("back-to-courses")
            assertFalse(all().first { it.contentDescription == "workflow_course_start" }.isEnabled)
            assertEquals(records, history.getString("records", null))
        } finally {
            activity.close()
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
            edit.commit(); coach.edit().putBoolean("voice_enabled", voice).commit()
            settings.edit().putBoolean("muted", muted).commit()
            history.edit().apply { if (checkpoint == null) remove("checkpoint") else putString("checkpoint", checkpoint) }.commit()
        }
    }
}
