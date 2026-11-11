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
class DrivingPracticeLayoutTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(width: Int, height: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val themed = target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale = scale })
            themed.setTheme(R.style.AppTheme)
            val names = mutableSetOf<String>()
            val prefix = "driving_layout_${System.nanoTime()}_"
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
            }
            try {
                root.setSpeechEnabled(false); root.setActive(true); measure()
                click("workflow_tasks"); checkText()
                assertEquals(18, views(root).count { it.contentDescription?.toString()?.startsWith("workflow_task_D") == true })
                for (task in ScenarioCatalog.tasks) {
                    click("workflow_task_${task.id}"); checkText()
                    assertFalse(control("workflow_gas").isEnabled)
                    click("workflow_resume"); assertTrue(control("workflow_gas").isEnabled)
                    click("workflow_signal_left"); assertTrue(control("workflow_signal_left").isSelected)
                    click("workflow_mirror"); assertTrue(control("workflow_mirror").isSelected)
                    click("workflow_pause"); checkText(); assertFalse(control("workflow_gas").isEnabled)
                    root.setActive(false); root.setActive(true); measure()
                    assertFalse("Explicit resume required", control("workflow_gas").isEnabled)
                    click("workflow_resume"); click("workflow_cancel"); checkText()
                    click("workflow_reflect_1"); click("workflow_difficulty_current"); checkText()
                    assertFalse(control("workflow_save").isEnabled)
                    click("workflow_next_task"); click("workflow_cancel"); checkText()
                    click("workflow_home"); click("workflow_tasks")
                }
                ready = false; click("workflow_task_D03")
                assertTrue(requestedPreparation)
                assertFalse(views(root).any { it.contentDescription == "workflow_gas" })
                assertTrue(WorkflowStore(context, { true }).records().isEmpty())
            } finally { root.close(); names.forEach { target.deleteSharedPreferences(it) } }
        }
    }
    @Test fun allEighteenTasksAndReviewsFitPleos() = verify(1707, 1018, 1.2f)
    @Test fun largeTextAndNarrowWindowRemainReadable() {
        verify(1707, 1018, 1.6f)
        verify(1100, 650, 1.2f)
    }
}
