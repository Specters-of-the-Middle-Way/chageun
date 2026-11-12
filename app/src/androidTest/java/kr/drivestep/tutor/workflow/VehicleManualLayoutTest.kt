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
class VehicleManualLayoutTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(width: Int, height: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val themed = target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale = scale })
            themed.setTheme(R.style.AppTheme)
            val names = mutableSetOf<String>()
            val prefix = "manual_layout_${System.nanoTime()}_"
            val context = object : ContextWrapper(themed) {
                override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix + name, mode).also { names += prefix + name }
            }
            val root = WorkflowView(context, {}, {}, {}, {}, { false }, { false }, { false }, {}, {}, {})
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
                views(root).firstOrNull { it.contentDescription == "workflow_manual_confirm" }?.let { start ->
                    val bounds = android.graphics.Rect(0, 0, start.width, start.height)
                    root.offsetDescendantRectToMyCoords(start, bounds)
                    assertTrue("Pinned start button above viewport: $bounds", bounds.top >= root.paddingTop)
                    assertTrue("Pinned start button below viewport: $bounds / ${root.height}", bounds.bottom <= root.height - root.paddingBottom)
                }
            }
            try {
                root.setSpeechEnabled(false); root.setActive(true); measure()
                click("workflow_manual"); checkText()
                assertFalse(views(root).any { it.contentDescription == "workflow_manual_confirm" })
                assertTrue(control("workflow_manual_real").isSelected)
                assertEquals(10, ManualFeature.entries.count { feature -> views(root).any { it.contentDescription == "workflow_manual_${feature.name.lowercase()}" } })
                for (mode in listOf("cn7_manual", "cn7_auto", "demo")) {
                    click("workflow_manual_$mode"); checkText()
                    for (feature in ManualFeature.entries.filter { it != ManualFeature.OTHER && (mode!="demo" || ManualRepository().find(ManualRepository.DEMO_VEHICLE,it)!=null) }) {
                        click("workflow_manual_${feature.name.lowercase()}"); checkText()
                        assertTrue(control("workflow_manual_${feature.name.lowercase()}").isSelected)
                        assertFalse(control("workflow_manual_previous").isEnabled)
                        assertFalse(control("workflow_manual_confirm").isEnabled)
                        val first = (control("workflow_manual_instruction") as TextView).text.toString()
                        click("workflow_manual_next"); checkText()
                        assertNotEquals(first, (control("workflow_manual_instruction") as TextView).text.toString())
                        click("workflow_manual_${feature.name.lowercase()}")
                        assertTrue((control("workflow_manual_progress") as TextView).text.contains("2 / 4"))
                        click("workflow_manual_previous"); assertEquals(first, (control("workflow_manual_instruction") as TextView).text.toString())
                        repeat(3) { click("workflow_manual_next"); checkText() }
                        if (mode == "demo") {
                            assertFalse(control("workflow_manual_confirm").isEnabled)
                            click("workflow_manual_previous"); checkText()
                            click("workflow_manual_operate"); assertTrue(control("workflow_manual_operate").isSelected)
                            click("workflow_manual_next")
                        } else assertFalse(views(root).any { it.contentDescription == "workflow_manual_operate" })
                        assertFalse(control("workflow_manual_next").isEnabled)
                        if(mode!="demo") Cn7Manual.find(Cn7Manual.MANUAL_CLIMATE,feature)?.quiz?.let { quiz ->
                            assertFalse(control("workflow_manual_confirm").isEnabled)
                            click("workflow_manual_answer_${1-quiz.answer}"); checkText()
                            assertFalse(control("workflow_manual_confirm").isEnabled)
                            click("workflow_manual_answer_${quiz.answer}"); checkText()
                        }
                        click("workflow_manual_confirm"); checkText()
                        assertFalse(control("workflow_manual_confirm").isEnabled)
                        assertTrue((control("workflow_manual_learning_status") as TextView).text.contains("학습 확인 완료"))
                        click("workflow_manual_other"); checkText()
                        assertFalse(views(root).any { it.contentDescription == "workflow_manual_confirm" })
                    }
                }
                click("workflow_manual_real"); click("workflow_manual_ac"); click("workflow_manual_cn7_manual")
                click("workflow_manual_next"); click("workflow_manual_cn7_manual")
                assertTrue((control("workflow_manual_progress") as TextView).text.contains("2 / 4"))
                root.setActive(false); measure()
                control("workflow_manual_next").performClick()
                assertTrue((control("workflow_manual_progress") as TextView).text.contains("2 / 4"))
                root.setActive(true); measure(); checkText()
                assertTrue((control("workflow_manual_progress") as TextView).text.contains("2 / 4"))
                assertTrue(context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE).all.isEmpty())
            } finally { root.close(); names.forEach { target.deleteSharedPreferences(it) } }
        }
    }
    @Test fun allOfficialAndSimulatedManualsFitPleos() = verify(1707, 1018, 1.2f)
    @Test fun largeTextAndNarrowWindowRemainReadable() {
        verify(1707, 1018, 1.6f)
        verify(1100, 650, 1.2f)
    }
}
