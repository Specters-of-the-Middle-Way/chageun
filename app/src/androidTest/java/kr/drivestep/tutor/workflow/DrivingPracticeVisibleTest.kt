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
class DrivingPracticeVisibleTest {
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
        val directory = File(instrumentation.targetContext.cacheDir, "driving-practice").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test(timeout = 240_000) fun actualPracticeControlsCompleteFourRepresentativeTasks() {
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
        fun choose(id: String) {
            click("workflow_home"); click("workflow_tasks"); click("workflow_task_$id"); click("workflow_resume")
        }
        try {
            click("journey_nav_profile"); toggle("journey_consent_records", false); toggle("journey_consent_vehicle", false)
            toggle("voice_toggle", false); click("journey_profile_continue")
            repeat(9) { click("journey_setup_confirm") }
            for (index in 0..6) if (!text("journey_preflight_$index").contains("✓")) click("journey_preflight_$index")
            click("journey_preflight_continue"); click("journey_start_workflow"); click("workflow_tasks")
            capture("task-selection")
            instrumentation.runOnMainSync {
                val last = views(host.window.decorView).first { it.contentDescription == "workflow_task_D18" }
                last.requestRectangleOnScreen(Rect(0, 0, last.width, last.height), true)
            }
            capture("task-selection-lower")
            click("workflow_task_D03"); capture("practice-ready")
            assertFalse(all().first { it.contentDescription == "workflow_gas" }.isEnabled)
            click("workflow_resume"); click("workflow_signal_left"); click("workflow_mirror")
            assertTrue(all().first { it.contentDescription == "workflow_signal_left" }.isSelected)
            assertTrue(all().first { it.contentDescription == "workflow_mirror" }.isSelected)
            click("workflow_signal_off")
            touch("workflow_steering", true, .6f); touch("workflow_steering", false, .6f); click("workflow_center")
            capture("practice-controls")
            click("workflow_pause"); capture("practice-paused")
            assertFalse(all().first { it.contentDescription == "workflow_gas" }.isEnabled)
            assertTrue(text("workflow_stats").contains("가상 0.0km/h"))
            click("workflow_resume")
            driveUntil { text("workflow_guidance").contains("이제 가상 브레이크") }
            brakeUntil { text("workflow_guidance").contains("다시 부드럽게 출발") }
            driveUntil { completed() }
            assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("연습 완료") })
            capture("practice-completed")
            click("workflow_reflect_1"); click("workflow_difficulty_current"); capture("practice-reflection")
            assertFalse(all().first { it.contentDescription == "workflow_save" }.isEnabled)
            click("workflow_next_task"); assertTrue(text("workflow_guidance").contains("10km/h"))
            click("workflow_cancel"); capture("practice-cancelled")

            choose("D05"); click("workflow_wait"); capture("signal-waiting")
            waitFor("No green signal", 20) { text("workflow_guidance").contains("녹색") }
            capture("signal-green"); driveUntil { completed() }; capture("signal-completed")

            choose("D15"); click("workflow_reroute")
            assertTrue(text("workflow_guidance").contains("이전 경로"))
            capture("reroute"); driveUntil { completed() }; capture("reroute-completed")

            choose("D18")
            driveUntil { text("workflow_guidance").contains("가상 차량 경고") }
            capture("warning"); brakeUntil { completed() }; capture("warning-completed")
            assertEquals(records, history.getString("records", null))
        } finally {
            activity.close()
            val edit = journey.edit()
            for (key in listOf("record_consent", "vehicle_consent")) {
                val value = original[key]
                if (value is Boolean) edit.putBoolean(key, value) else edit.remove(key)
            }
            edit.commit(); coach.edit().putBoolean("voice_enabled", voice).commit()
            settings.edit().putBoolean("muted", muted).commit()
            history.edit().apply { if (checkpoint == null) remove("checkpoint") else putString("checkpoint", checkpoint) }.commit()
        }
    }
}
