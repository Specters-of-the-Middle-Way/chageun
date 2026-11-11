package kr.drivestep.tutor.simulator

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import kr.drivestep.tutor.parking.ParkingSimulatorView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production controls and Android text layout with isolated, disposable preferences. */
@RunWith(AndroidJUnit4::class)
class SimulationScreensDeviceTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(widthDp: Int, heightDp: Int, fontScale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val base = target.createConfigurationContext(Configuration(target.resources.configuration).apply { this.fontScale = fontScale })
            base.setTheme(R.style.AppTheme)
            val prefix = "simulation_layout_${System.nanoTime()}_"
            val names = mutableSetOf<String>()
            val context = object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val scoped = prefix + name; names.add(scoped)
                    return target.getSharedPreferences(scoped, mode)
                }
            }
            val driving = DrivingSimulatorView(context, {}, {}, {}, { false })
            val parking = ParkingSimulatorView(context, {}, {}, {}, { false })
            var root: View = driving
            fun control(key: String) = views(root).first { it.contentDescription == key }
            fun measure() {
                val density = context.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            fun visible(view: View): Boolean {
                var current: View? = view
                while (current != null) {
                    if (current.visibility != View.VISIBLE) return false
                    current = current.parent as? View
                }
                return true
            }
            fun checkText() {
                measure()
                val exit = control(if (root === driving) "sim_exit" else "park_exit")
                val background = exit.background as RippleDrawable
                assertTrue("Secondary button must use the shared rounded surface", background.getDrawable(0) is GradientDrawable)
                assertEquals(Color.WHITE, (background.getDrawable(0) as GradientDrawable).color?.defaultColor)
                views(root).filterIsInstance<TextView>().filter(::visible).forEach { view ->
                    val text = requireNotNull(view.layout) { "No layout: ${view.text}" }
                    val detail = "$widthDp x $heightDp / $fontScale: ${view.text}"
                    assertTrue("Vertical clipping $detail", text.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                    repeat(text.lineCount) { line ->
                        assertEquals("Ellipsis $detail", 0, text.getEllipsisCount(line))
                        assertTrue("Horizontal clipping $detail", text.getLineMax(line) <= text.width + 1)
                    }
                    if (view.isClickable) assertTrue("Small touch target $detail", view.height >= 48 * context.resources.displayMetrics.density)
                }
            }
            fun click(key: String) { assertTrue("Disabled: $key", control(key).isEnabled); assertTrue(control(key).performClick()); measure() }
            try {
                driving.setActive(true)
                for (course in listOf("left", "right", "blindspot")) {
                    click("sim_start_$course"); checkText()
                    click("sim_begin"); checkText()
                    val direction = if (course == "right") "right" else "left"
                    click("sim_signal_$direction"); assertTrue(control("sim_signal_$direction").isSelected)
                    click("sim_mirror_$direction"); assertTrue(control("sim_mirror_$direction").isSelected); checkText()
                    click("sim_pause"); assertFalse(control("sim_gas").isEnabled); checkText()
                    click("sim_resume"); assertTrue(control("sim_gas").isEnabled)
                    driving.setActive(false); assertFalse(control("sim_gas").isEnabled)
                    driving.setActive(true); assertFalse("Must explicitly resume", control("sim_gas").isEnabled)
                    click("sim_resume"); click("sim_finish"); checkText()
                    click("sim_restart"); click("sim_finish"); checkText(); click("sim_courses")
                }
                assertNull(context.getSharedPreferences("drivestep_driving_simulator_v1", 0).getString("records", null))
                root = parking; parking.setActive(true)
                for (course in listOf("reverse", "parallel", "t_bay", "front_bay")) {
                    click("park_course_$course"); checkText()
                    click("park_start"); checkText()
                    click("park_brake_hold")
                    val gear = if (course == "front_bay") "d" else "r"
                    click("park_gear_$gear"); assertTrue(control("park_gear_$gear").isSelected); checkText()
                    click("park_pause"); assertFalse(control("park_gas").isEnabled); checkText()
                    parking.setActive(false); parking.setActive(true)
                    assertFalse("Must explicitly resume", control("park_gas").isEnabled)
                    click("park_resume"); assertTrue(control("park_gas").isEnabled)
                    click("park_exit"); checkText()
                    click("park_feel_good"); click("park_save"); checkText()
                    assertTrue(views(root).filterIsInstance<TextView>().any { it.text.contains("기록을 저장하지 않았어요") })
                    click("park_retry"); click("park_exit"); checkText(); click("park_courses")
                }
                assertNull(context.getSharedPreferences("drivestep_parking_simulator_v1", 0).getString("records", null))
            } finally {
                driving.close(); parking.close(); names.forEach { target.deleteSharedPreferences(it) }
            }
        }
    }

    @Test fun allCoursesControlsAndReviewsFitPleos() = verify(1_707, 1_018, 1.2f)
    @Test fun largeTextAndNarrowShortWindowsRemainReadable() {
        verify(1_707, 1_018, 1.6f)
        verify(1_100, 650, 1.2f)
    }
}
