package kr.drivestep.tutor.workflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
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

/** Exercises the official and simulated manual flows through the production activity. */
@RunWith(AndroidJUnit4::class)
class VehicleManualVisibleTest {
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
    private fun text(key: String) = all().filterIsInstance<TextView>().firstOrNull { it.contentDescription == key }?.text?.toString().orEmpty()
    private fun capture(name: String) {
        SystemClock.sleep(180)
        val directory = File(instrumentation.targetContext.cacheDir, "vehicle-manual").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test(timeout = 180_000) fun officialAndSimulatedManualsCompleteWithoutWritingHistory() {
        val context = instrumentation.targetContext
        val journey = context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val coach = context.getSharedPreferences("coach", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
        val history = context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE)
        val record = journey.all["record_consent"] as? Boolean
        val voice = coach.all["voice_enabled"] as? Boolean
        val muted = settings.all["muted"] as? Boolean
        val before = history.all.toMap()
        val weather = WeatherTestSettings(context)
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { host = it }
        try {
            click("journey_nav_profile"); toggle("journey_consent_records", false); toggle("voice_toggle", false)
            click("coach_workflow"); click("workflow_manual"); capture("choose-manual")
            assertFalse(has("workflow_manual_confirm"))
            click("workflow_manual_cn7_manual"); capture("manual-air-conditioning")
            for (feature in ManualFeature.entries.filter { it != ManualFeature.OTHER }) {
                click("workflow_manual_${feature.name.lowercase()}")
                assertTrue(text("workflow_manual_progress").contains("1 / 4"))
                assertFalse(all().first { it.contentDescription == "workflow_manual_confirm" }.isEnabled)
                click("workflow_manual_next"); click("workflow_manual_previous")
                repeat(3) { click("workflow_manual_next") }
                assertFalse(has("workflow_manual_operate"))
                Cn7Manual.find(Cn7Manual.MANUAL_CLIMATE,feature)?.quiz?.let { quiz ->
                    click("workflow_manual_answer_${1-quiz.answer}")
                    assertFalse(all().first { it.contentDescription=="workflow_manual_confirm" }.isEnabled)
                    click("workflow_manual_answer_${quiz.answer}")
                    capture("${feature.name.lowercase()}-quiz-confirmed")
                }
                click("workflow_manual_confirm")
                assertTrue(text("workflow_manual_learning_status").contains("설명서 학습 확인 완료"))
                if (feature == ManualFeature.WIPER) capture("wiper-completed")
            }
            click("workflow_manual_cn7_auto"); click("workflow_manual_defog")
            assertTrue(text("workflow_manual_reference").contains("5-88")); capture("auto-defog")
            repeat(3) { click("workflow_manual_next") }; click("workflow_manual_confirm")
            click("workflow_manual_demo"); capture("virtual-panel-introduction")
            for (feature in ManualFeature.entries.filter { ManualRepository().find(ManualRepository.DEMO_VEHICLE,it)!=null }) {
                click("workflow_manual_${feature.name.lowercase()}")
                repeat(3) { click("workflow_manual_next") }
                assertFalse(all().first { it.contentDescription == "workflow_manual_confirm" }.isEnabled)
                click("workflow_manual_previous"); click("workflow_manual_operate")
                if (feature == ManualFeature.DEFOG) capture("virtual-panel-on")
                click("workflow_manual_next"); click("workflow_manual_confirm")
                assertTrue(text("workflow_manual_learning_status").contains("가상 학습 확인 완료"))
            }
            capture("virtual-panel-completed")
            click("workflow_manual_other"); capture("unsupported-feature")
            assertFalse(has("workflow_manual_confirm"))
            click("workflow_manual_real"); click("workflow_manual_wiper")
            assertTrue(all().first { it.contentDescription == "workflow_manual_cn7_auto" }.isSelected)
            click("workflow_manual_next"); click("workflow_manual_wiper")
            assertTrue(text("workflow_manual_progress").contains("2 / 4"))
            click("workflow_manual_repeat")
            assertEquals(before, history.all.toMap())
        } finally {
            activity.close(); weather.close()
            journey.edit().apply { if (record == null) remove("record_consent") else putBoolean("record_consent", record) }.commit()
            coach.edit().apply { if (voice == null) remove("voice_enabled") else putBoolean("voice_enabled", voice) }.commit()
            settings.edit().apply { if (muted == null) remove("muted") else putBoolean("muted", muted) }.commit()
        }
    }
}
