package kr.drivestep.tutor.workflow

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android text measurement at the Pleos viewport and narrower, enlarged-text widths. */
@RunWith(AndroidJUnit4::class)
class WorkflowHomeLayoutTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(widthDp: Int, fontScale: Float, recovery: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext.createConfigurationContext(
                Configuration(instrumentation.targetContext.resources.configuration).apply { this.fontScale = fontScale })
            context.setTheme(R.style.AppTheme)
            val density = context.resources.displayMetrics.density
            val selected = mutableListOf<WorkflowHomeAction>()
            val root = WorkflowHomeView(context, false,
                if (recovery) "출발·감속·교차로에서의 시선 확인" else null,
                "준비 상태를 확인하고 처음부터 다시 연습해요.", true) { selected.add(it) }
            root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val expected = WorkflowHomeAction.entries.filter { recovery || it != WorkflowHomeAction.RECOVER }
            val buttons = views(root).filter { it.isClickable }
            assertEquals(expected.map { it.key }.toSet(), buttons.map { it.contentDescription.toString() }.toSet())
            val bounds = buttons.map { button ->
                assertTrue("Small target: ${button.contentDescription}", button.height >= 48 * density)
                Rect(0, 0, button.width, button.height).also { root.offsetDescendantRectToMyCoords(button, it) }
            }
            bounds.forEachIndexed { index, rect ->
                assertTrue("Card outside dashboard: $rect", rect.left >= 0 && rect.right <= root.width && rect.top >= 0 && rect.bottom <= root.height)
                bounds.drop(index + 1).forEach { other -> assertFalse("Overlapping cards: $rect, $other", Rect.intersects(rect, other)) }
            }
            views(root).filterIsInstance<TextView>().forEach { text ->
                val layout = requireNotNull(text.layout)
                assertTrue("Text clipped at ${widthDp}dp / $fontScale: ${text.text} " +
                    "(layout=${layout.height}, view=${text.height}, padding=${text.compoundPaddingTop + text.compoundPaddingBottom})",
                    layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                repeat(layout.lineCount) { line ->
                    assertEquals("Truncated text: ${text.text}", 0, layout.getEllipsisCount(line))
                    assertTrue("Text too wide at ${widthDp}dp / $fontScale: ${text.text}",
                        layout.getLineMax(line) <= layout.width + 1f)
                }
            }
            if (widthDp >= 1_000 && !recovery) {
                assertTrue("Important actions should fit the Pleos screen", root.height <= 950 * density)
                val prepare = buttons.first { it.contentDescription == WorkflowHomeAction.PREPARE.key }
                val drive = buttons.first { it.contentDescription == WorkflowHomeAction.DRIVE.key }
                assertTrue(bounds[buttons.indexOf(prepare)].right < bounds[buttons.indexOf(drive)].left)
            }
            assertTrue("Layout must not start an action", selected.isEmpty())
            buttons.forEach { assertTrue(it.performClick()) }
            assertEquals(expected.toSet(), selected.toSet())
            assertEquals(expected.size, selected.size)
        }
    }

    @Test fun pleosDashboardKeepsEveryMainActionVisible() = verify(1_618, 1.2f, false)

    @Test fun narrowDashboardAndRecoveryAllowLargeTextWithoutClipping() {
        verify(780, 1.2f, true)
        verify(480, 2f, true)
    }
}
