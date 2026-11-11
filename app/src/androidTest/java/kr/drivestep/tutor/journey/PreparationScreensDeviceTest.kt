package kr.drivestep.tutor.journey

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CompoundButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated preferences and real Android measurement; never changes the driver's saved profile. */
@RunWith(AndroidJUnit4::class)
class PreparationScreensDeviceTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(widthDp: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val base = target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale = scale })
            base.setTheme(R.style.AppTheme)
            val prefix = "preparation_layout_${System.nanoTime()}_"
            val temporaryPreferences = mutableSetOf<String>()
            val context = object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val scopedName = prefix + name; temporaryPreferences.add(scopedName)
                    return target.getSharedPreferences(scopedName, mode)
                }
            }
            val destinations = mutableListOf<JourneyDestination>()
            val root = DriverJourneyView(context, { destinations.add(it) }, {})
            root.setSpeechEnabled(false); root.setActive(true)
            fun control(key: String) = views(root).first { it.contentDescription == key }
            fun measure() {
                val density = context.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((1_018 * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            fun checkText() {
                measure()
                views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.forEach { text ->
                    val layout = requireNotNull(text.layout) { "Missing text layout: ${text.text}" }
                    val detail = "$widthDp / $scale: ${text.text} (${text.width}x${text.height}, text height=${layout.height})"
                    assertTrue("Vertically clipped $detail", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    repeat(layout.lineCount) { line ->
                        assertEquals("Ellipsized $detail", 0, layout.getEllipsisCount(line))
                        assertTrue("Horizontally clipped $detail", layout.getLineMax(line) <= layout.width + 1)
                    }
                    if (text.isClickable) assertTrue("Small touch target $detail", text.height >= 48 * densityOf(context))
                }
            }
            fun click(key: String) { assertTrue("Disabled $key", control(key).isEnabled); assertTrue(control(key).performClick()); measure() }
            try {
                checkText()
                click("journey_experience_0"); checkText()
                click("journey_profile_continue")
                for (index in 0..8) {
                    checkText()
                    assertFalse("Readiness bypass at step $index", root.canPractice)
                    assertTrue(views(root).any { it is PostureGuideView && it.height >= 180 * densityOf(context) })
                    click("journey_setup_confirm")
                }
                checkText()
                assertFalse(control("journey_preflight_continue").isEnabled)
                for (index in 0..6) {
                    click("journey_preflight_$index"); checkText()
                    assertTrue((control("journey_preflight_$index") as TextView).text.lineSequence().first().endsWith("✓"))
                }
                assertTrue(root.canPractice); assertTrue(control("journey_preflight_continue").isEnabled)
                click("journey_preflight_0")
                assertFalse(root.canPractice); assertFalse(control("journey_preflight_continue").isEnabled)
                click("journey_preflight_0")
                click("journey_preflight_continue"); checkText()
                listOf("workflow", "drive", "parking", "lesson").forEach { click("journey_start_$it") }
                assertEquals(listOf(JourneyDestination.WORKFLOW, JourneyDestination.DRIVE, JourneyDestination.PARKING, JourneyDestination.LESSON), destinations)
                click("journey_nav_review"); checkText()
                click("journey_feeling_0"); click("journey_difficulty_2"); checkText()
                click("journey_review_save")
                val prefs = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
                assertNull("No record without consent", prefs.getString("self_reports", null))
                click("journey_nav_profile")
                (control("journey_consent_records") as CompoundButton).isChecked = true
                click("journey_nav_review"); click("journey_review_save"); click("journey_nav_review")
                assertTrue(prefs.getString("self_reports", "")!!.contains("한결 편해졌어요"))
                checkText()
                assertFalse(views(root).filterIsInstance<TextView>().any { it.text.toString() == "P · 정차 중 사용" })
                root.openProfile(); click("journey_profile_continue"); click("journey_apply_recommended_preset")
                assertFalse("Posture change must invalidate preparation", root.canPractice)
                for (index in 5..7) assertFalse((control("journey_setup_step_$index") as TextView).text.contains("✓"))
                checkText()
            } finally {
                root.close()
                temporaryPreferences.forEach { target.deleteSharedPreferences(it) }
            }
        }
    }
    private fun densityOf(context: Context) = context.resources.displayMetrics.density
    @Test fun threeScreensFitPleosAndKeepPreparationGates() = verify(1_707, 1.2f)
    @Test fun enlargedTextAndNarrowWindowRemainReadable() {
        verify(1_707, 1.6f)
        verify(1_100, 1.2f)
    }
}
