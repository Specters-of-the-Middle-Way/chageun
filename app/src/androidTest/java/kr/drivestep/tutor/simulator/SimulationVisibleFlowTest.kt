package kr.drivestep.tutor.simulator

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
import kr.drivestep.tutor.parking.ParkingSceneView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Visible production controls, real simulation ticks, and screenshots; no injected simulation state. */
@RunWith(AndroidJUnit4::class)
class SimulationVisibleFlowTest {
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
        val directory = File(instrumentation.targetContext.cacheDir, "simulation-style").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test(timeout = 180_000) fun visibleLaneChangeAndParkingFinishThroughNormalControls() {
        val context = instrumentation.targetContext
        val journey = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val coach = context.getSharedPreferences("coach", Context.MODE_PRIVATE)
        val original = journey.all.toMap()
        val originalVoice = coach.getBoolean("voice_enabled", true)
        val driveBefore = context.getSharedPreferences("drivestep_driving_simulator_v1", 0).getString("records", null)
        val parkBefore = context.getSharedPreferences("drivestep_parking_simulator_v1", 0).getString("records", null)
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { host = it }
        try {
            click("journey_nav_profile"); toggle("journey_consent_records", false); toggle("journey_consent_vehicle", false)
            toggle("voice_toggle", false); click("journey_profile_continue")
            repeat(9) { click("journey_setup_confirm") }
            for (index in 0..6) click("journey_preflight_$index")
            click("journey_preflight_continue"); click("journey_start_drive")
            for (course in listOf("left", "right", "blindspot")) {
                click("sim_start_$course"); capture("lane-selection-$course")
                click("sim_begin"); capture("lane-driving-$course")
                if (course == "left") {
                    click("sim_pause"); assertFalse(all().first { it.contentDescription == "sim_gas" }.isEnabled)
                    capture("lane-paused"); click("sim_resume")
                }
                val direction = if (course == "right") "right" else "left"
                click("sim_signal_$direction"); click("sim_mirror_$direction")
                waitFor("No steering phase", 25) { text("sim_phase").contains("차로 옮기기") }
                val amount = if (course == "right") .615f else .385f
                touch("sim_steering", true, amount); touch("sim_steering", false, amount)
                waitFor("No centering phase", 25) { text("sim_phase").contains("핸들 중앙") }
                click("sim_center"); click("sim_signal_off")
                waitFor("No completed lane review") { has("sim_courses") }
                assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("연습 마침") })
                capture("lane-review-$course"); click("sim_courses")
            }
            click("sim_exit"); click("journey_nav_practice"); click("journey_start_parking")
            for (course in listOf("parallel", "t_bay", "front_bay", "reverse")) {
                click("park_course_$course"); capture("parking-selection-$course")
            }
            click("park_start"); capture("parking-driving")
            click("park_pause"); assertFalse(all().first { it.contentDescription == "park_gas" }.isEnabled)
            capture("parking-paused"); click("park_resume")
            click("park_brake_hold"); click("park_gear_r"); click("park_brake_hold")
            touch("park_gas", true)
            try {
                waitFor("Vehicle did not enter the parking target", 20) {
                    all().filterIsInstance<ParkingSceneView>().any { it.contentDescription.contains("목표 구역 안") }
                }
            } finally { touch("park_gas", false) }
            click("park_brake_hold")
            waitFor("Vehicle did not stop") { text("park_speed") == "0.0" }
            click("park_gear_p"); click("park_finish")
            waitFor("No parking review") { has("park_feel_good") }
            assertTrue(all().filterIsInstance<TextView>().any { it.text.contains("가상 주차 마침") })
            capture("parking-review"); click("park_feel_good"); click("park_save"); capture("parking-unsaved")
            assertEquals(driveBefore, context.getSharedPreferences("drivestep_driving_simulator_v1", 0).getString("records", null))
            assertEquals(parkBefore, context.getSharedPreferences("drivestep_parking_simulator_v1", 0).getString("records", null))
        } finally {
            activity.close()
            val edit = journey.edit()
            for (key in listOf("record_consent", "vehicle_consent")) {
                if (original.containsKey(key)) edit.putBoolean(key, original[key] as Boolean) else edit.remove(key)
            }
            assertTrue(edit.commit())
            assertTrue(coach.edit().putBoolean("voice_enabled", originalVoice).commit())
        }
    }
}
