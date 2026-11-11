package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualCourseLayoutTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(width: Int, height: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val themed = target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale = scale })
            themed.setTheme(R.style.AppTheme)
            val names = mutableSetOf<String>()
            val prefix = "course_layout_${System.nanoTime()}_"
            val context = object : ContextWrapper(themed) {
                override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix + name, mode).also { names += prefix + name }
            }
            var ready = true
            var requestedPreparation = false
            val root = WorkflowView(context, {}, { requestedPreparation = true }, {}, {}, { ready }, { false }, { false }, {}, {}, {})
            val density = context.resources.displayMetrics.density
            fun control(key: String) = requireNotNull(views(root).firstOrNull { it.contentDescription == key }) { "Missing control $key" }
            fun measure() {
                root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            fun click(key: String) { assertTrue(key, control(key).isEnabled); assertTrue(control(key).performClick()); measure() }
            fun visible(view: View): Boolean {
                var current: View? = view
                while (current != null) { if (current.visibility != View.VISIBLE) return false; current = current.parent as? View }
                return true
            }
            fun checkText() {
                measure()
                views(root).filterIsInstance<TextView>().filter(::visible).forEach { view ->
                    val detail = "$width x $height / $scale: ${view.text}"
                    val layout = requireNotNull(view.layout) { detail }
                    assertTrue("Vertical clipping $detail", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                    repeat(layout.lineCount) { line ->
                        assertEquals("Ellipsis $detail", 0, layout.getEllipsisCount(line))
                        assertTrue("Horizontal clipping $detail", layout.getLineMax(line) <= layout.width + 1)
                    }
                    if (view.isClickable) assertTrue("Touch target $detail", view.height >= 48 * density)
                }
                val background = control("workflow_home").background as RippleDrawable
                assertTrue(background.getDrawable(0) is GradientDrawable)
                views(root).firstOrNull { it.contentDescription == "workflow_course_start" }?.let { start ->
                    val bounds = android.graphics.Rect(0, 0, start.width, start.height)
                    root.offsetDescendantRectToMyCoords(start, bounds)
                    assertTrue("Pinned start button above viewport: $bounds", bounds.top >= root.paddingTop)
                    assertTrue("Pinned start button below viewport: $bounds / ${root.height}", bounds.bottom <= root.height - root.paddingBottom)
                }
            }
            try {
                root.setSpeechEnabled(false); root.setActive(true); measure()
                click("workflow_courses"); checkText()
                assertFalse(control("workflow_course_start").isEnabled)
                assertFalse(control("workflow_course_refresh").isEnabled)
                assertTrue(control("workflow_goal_beginner").isSelected)
                click("workflow_start_location"); checkText()
                assertTrue(control("workflow_start_location").isSelected)
                fun routes() = views(root).filter { it.contentDescription?.toString()?.startsWith("workflow_route_sim-") == true }
                assertEquals(3, routes().size)
                click("workflow_route_sim-basic"); checkText()
                assertTrue(control("workflow_route_sim-basic").isSelected)
                assertTrue(control("workflow_course_start").isEnabled)
                click("workflow_course_refresh"); checkText()
                assertFalse(control("workflow_course_start").isEnabled)
                click("workflow_goal_return_trip"); checkText(); assertEquals(1, routes().size)
                click("workflow_route_sim-return"); checkText()
                click("workflow_course_exclusion"); checkText(); assertEquals(0, routes().size)
                assertFalse(control("workflow_course_start").isEnabled)
                click("workflow_course_exclusion"); checkText(); assertEquals(1, routes().size)
                click("workflow_goal_weakness"); checkText()
                assertTrue((control("workflow_course_task") as TextView).text.contains("가속·감속"))
                assertTrue(views(root).filterIsInstance<TextView>().any { it.text.contains("기록 동의가 꺼져 있어") })
                click("workflow_route_sim-target"); checkText()
                ready = false; click("workflow_course_start"); assertTrue(requestedPreparation)
                assertFalse(views(root).any { it.contentDescription == "workflow_gas" })
                ready = true; click("workflow_course_start"); checkText()
                assertTrue((control("workflow_course_progress") as TextView).text.contains("1 / 1"))
                click("workflow_resume"); click("workflow_pause"); checkText()
                assertFalse(control("workflow_gas").isEnabled)
                click("workflow_cancel"); checkText()
                assertTrue((control("workflow_next_task") as TextView).text.contains("현재 과제 다시"))
                assertFalse(views(root).any { it.contentDescription == "workflow_course_finish" })
                click("workflow_next_task"); checkText()
                assertTrue((control("workflow_course_progress") as TextView).text.contains("1 / 1"))
                click("workflow_cancel"); click("workflow_home"); click("workflow_courses")
                click("workflow_goal_beginner"); click("workflow_route_sim-basic"); click("workflow_course_start"); checkText()
                assertTrue((control("workflow_course_progress") as TextView).text.contains("1 / 3"))
                click("workflow_cancel"); click("workflow_review_course"); checkText()
                assertTrue(WorkflowStore(context, { true }).records().isEmpty())
            } finally { root.close(); names.forEach { target.deleteSharedPreferences(it) } }
        }
    }
    @Test fun courseSelectionProgressAndGatesFitPleos() = verify(1707, 1018, 1.2f)
    @Test fun largeTextAndNarrowWindowRemainReadable() {
        verify(1707, 1018, 1.6f)
        verify(1100, 650, 1.2f)
    }
}
