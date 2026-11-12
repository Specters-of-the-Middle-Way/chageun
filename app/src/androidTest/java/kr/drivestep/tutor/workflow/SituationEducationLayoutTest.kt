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
class SituationEducationLayoutTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(width: Int, height: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val themed = target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale = scale })
            themed.setTheme(R.style.AppTheme)
            val names = mutableSetOf<String>()
            val prefix = "education_layout_${System.nanoTime()}_"
            val context = object : ContextWrapper(themed) {
                override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix + name, mode).also { names += prefix + name }
            }
            var ready = true
            var preparationRequests = 0
            val root = WorkflowView(context, {}, { preparationRequests++ }, {}, {}, { ready }, { false }, { false }, {}, {}, {})
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
                views(root).firstOrNull { it.contentDescription == "workflow_guide_previous" }?.let { start ->
                    val bounds = android.graphics.Rect(0, 0, start.width, start.height)
                    root.offsetDescendantRectToMyCoords(start, bounds)
                    assertTrue("Pinned navigation button above viewport: $bounds", bounds.top >= root.paddingTop)
                    assertTrue("Pinned navigation button below viewport: $bounds / ${root.height}", bounds.bottom <= root.height - root.paddingBottom)
                }
            }
            try {
                root.setSpeechEnabled(false); root.setActive(true); measure()
                click("workflow_guides"); checkText()
                assertEquals(7, DrivingGuideCatalog.guides.count { topic -> views(root).any { it.contentDescription == "workflow_guide_${topic.id}" } })
                for (topic in DrivingGuideCatalog.guides) {
                    click("workflow_guide_${topic.id}"); checkText()
                    assertTrue(control("workflow_guide_${topic.id}").isSelected)
                    assertFalse(control("workflow_guide_previous").isEnabled)
                    assertEquals(topic.steps.first(), (control("workflow_guide_instruction") as TextView).text.toString())
                    click("workflow_guide_next"); click("workflow_guide_${topic.id}")
                    assertTrue((control("workflow_guide_progress") as TextView).text.contains("2 / 4"))
                    click("workflow_guide_previous")
                    for (index in topic.steps.indices) {
                        assertEquals(topic.steps[index], (control("workflow_guide_instruction") as TextView).text.toString())
                        topic.illustrations.getOrNull(index)?.let { illustration ->
                            assertTrue(views(root).any { it.contentDescription == "교육 그림 · ${illustration.caption}" })
                            assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == illustration.caption })
                        }
                        checkText()
                        if (index < topic.steps.lastIndex) click("workflow_guide_next")
                    }
                    assertFalse(control("workflow_guide_next").isEnabled)
                    if (topic.relatedTask != null) assertFalse(control("workflow_guide_practice").isEnabled)
                    click("workflow_guide_answer_${1 - topic.answer}"); checkText()
                    assertTrue(control("workflow_guide_answer_${1 - topic.answer}").isSelected)
                    assertTrue((control("workflow_guide_result") as TextView).text.contains("다시 생각"))
                    if (topic.relatedTask != null) assertFalse(control("workflow_guide_practice").isEnabled)
                    click("workflow_guide_answer_${topic.answer}"); checkText()
                    assertTrue((control("workflow_guide_result") as TextView).text.contains("이해 확인 완료"))
                    click("workflow_guide_previous"); click("workflow_guide_next"); checkText()
                    assertFalse(views(root).any { it.contentDescription == "workflow_guide_result" })
                    click("workflow_guide_answer_${topic.answer}")
                    if (topic.relatedTask != null) {
                        ready = false; val before = preparationRequests; click("workflow_guide_practice")
                        assertEquals(before + 1, preparationRequests)
                        assertFalse(views(root).any { it.contentDescription == "workflow_gas" })
                        ready = true; click("workflow_guide_practice"); checkText()
                        assertEquals(ScenarioCatalog.find(topic.relatedTask)!!.instruction, (control("workflow_guidance") as TextView).text.toString())
                        click("workflow_cancel"); click("workflow_home"); click("workflow_guides")
                    }
                }
                click("workflow_guide_following_gap")
                click("gap_study_speed_60"); checkText(); assertTrue((control("gap_study_result") as TextView).text.contains("1.8초"))
                click("gap_study_gap_60"); click("gap_study_target_6"); checkText()
                assertTrue((control("gap_study_result") as TextView).text.contains("100.0m"))
                assertTrue(control("gap_study_target_6").isSelected)
                click("gap_study_speed_0"); checkText()
                root.setActive(false); control("gap_study_speed_60").performClick(); root.setActive(true); checkText()
                assertTrue((control("gap_study_result") as TextView).text.contains("정차 예시"))
                assertTrue(context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE).all.isEmpty())
            } finally { root.close(); names.forEach { target.deleteSharedPreferences(it) } }
        }
    }
    @Test fun allSevenLessonsAndComprehensionStatesFitPleos() = verify(1707, 1018, 1.2f)
    @Test fun largeTextAndNarrowWindowRemainReadable() {
        verify(1707, 1018, 1.6f)
        verify(1100, 650, 1.2f)
    }
}
