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

/** Uses the real foreground location/weather request; restores every changed preference. */
@RunWith(AndroidJUnit4::class)
class LiveWeatherVisibleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ActivityScenario<MainActivity>
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun all(): List<View> { var result = emptyList<View>(); activity.onActivity { result = views(it.window.decorView) }; return result }
    private fun text(key: String) = all().filterIsInstance<TextView>().firstOrNull { it.contentDescription == key }?.text?.toString().orEmpty()
    private fun click(key: String) {
        activity.onActivity { host ->
            val view = views(host.window.decorView).first { it.contentDescription == key }
            assertTrue("disabled $key", view.isEnabled)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true); assertTrue(view.performClick())
        }
        SystemClock.sleep(100)
    }
    private fun checked(key: String, value: Boolean) {
        activity.onActivity { host -> (views(host.window.decorView).first { it.contentDescription == key } as CompoundButton).isChecked = value }
        SystemClock.sleep(100)
    }
    private fun capture(name: String) {
        SystemClock.sleep(180)
        activity.onActivity { host ->
            for (key in listOf("workflow_home", "weather_live_location", "weather_live_speak", "weather_live_cancel")) {
                views(host.window.decorView).firstOrNull { it.contentDescription == key }?.let { view ->
                    val rect = Rect(); assertTrue("$name $key offscreen", view.getGlobalVisibleRect(rect))
                    assertEquals("$name $key clipped height", view.height, rect.height())
                    assertEquals("$name $key clipped width", view.width, rect.width())
                }
            }
        }
        val directory = File(instrumentation.targetContext.cacheDir, "live-weather").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test(timeout = 100_000) fun actualWeatherCancelSettingsAndEducationWork() {
        val context = instrumentation.targetContext
        val workflow = context.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
        val muted = workflow.all["muted"] as? Boolean
        val history = context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE).all.toMap()
        WeatherTestSettings(context).use {
            workflow.edit().putBoolean("muted", true).commit()
            activity = ActivityScenario.launch(MainActivity::class.java)
            try {
                click("coach_workflow"); click("workflow_weather")
                assertFalse(all().first { it.contentDescription == "weather_live_location" }.isEnabled)
                capture("consent-off")
                checked("weather_live_consent", true)
                checked("weather_startup_auto", true); assertTrue(WeatherPreferences(context).startup)
                checked("weather_startup_auto", false)
                checked("weather_live_auto", true); assertTrue(WeatherPreferences(context).autoRead)
                checked("weather_live_auto", false)
                // Cancel in the same UI turn, before any asynchronous location/HTTP callback can complete.
                activity.onActivity { host ->
                    views(host.window.decorView).first { it.contentDescription == "weather_live_location" }.performClick()
                    assertFalse(views(host.window.decorView).first { it.contentDescription == "weather_live_location" }.isEnabled)
                    assertTrue(views(host.window.decorView).first { it.contentDescription == "weather_live_cancel" }.performClick())
                }
                assertTrue(text("weather_live_status").contains("중단")); capture("cancelled")
                click("weather_live_location"); capture("loading")
                val deadline = SystemClock.elapsedRealtime() + 35_000
                while (text("weather_live_temperature").isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(150)
                assertTrue("Real weather missing: ${text("weather_live_status")}", text("weather_live_temperature").contains("°C"))
                assertTrue(text("weather_live_time").contains("예보 기준")); assertTrue(text("weather_live_advice").isNotBlank())
                assertTrue(text("weather_live_place") in setOf("현재 위치 주변", WeatherPlaces.DEFAULT.name))
                assertTrue(all().first { it.contentDescription == "weather_live_location" }.isEnabled)
                capture("real-weather")
                click("weather_live_speak")
                assertFalse(all().filterIsInstance<WorkflowView>().single().isGuidanceSpeaking)
                click("workflow_weather_practice"); assertTrue(all().any { it.contentDescription == "workflow_weather_rain" })
                click("workflow_home"); click("workflow_weather")
                assertTrue(text("weather_live_temperature").contains("°C"))
                checked("weather_live_consent", false)
                assertTrue(text("weather_live_status").contains("동의를 껐어요"))
                assertFalse(all().any { it.contentDescription == "weather_live_speak" || it.contentDescription == "weather_live_temperature" })
                assertFalse(all().first { it.contentDescription == "weather_live_location" }.isEnabled)
                capture("revoked")
                checked("weather_live_consent", true)
                click("weather_live_location"); click("workflow_home")
                SystemClock.sleep(500)
                assertFalse(all().any { it.contentDescription == "weather_live_location" })
                click("workflow_weather")
                assertTrue(all().first { it.contentDescription == "weather_live_location" }.isEnabled)
                assertFalse(all().any { it.contentDescription == "weather_live_cancel" })
                assertEquals(history, context.getSharedPreferences("workflow_training_v1", Context.MODE_PRIVATE).all.toMap())
            } finally {
                activity.close()
                workflow.edit().apply { if (muted == null) remove("muted") else putBoolean("muted", muted) }.commit()
            }
        }
    }
}
