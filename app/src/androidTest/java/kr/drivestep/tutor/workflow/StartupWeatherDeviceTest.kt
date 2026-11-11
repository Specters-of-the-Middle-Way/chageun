package kr.drivestep.tutor.workflow

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupWeatherDeviceTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(startup: Boolean) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        WeatherTestSettings(target).use {
            WeatherPreferences(target).apply { consent = true; this.startup = startup; autoRead = false }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val deadline = SystemClock.elapsedRealtime() + 35_000
                var message = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    activity.onActivity { host ->
                        message = views(host.window.decorView).filterIsInstance<TextView>()
                            .firstOrNull { it.contentDescription == "startup_weather_message" }?.text?.toString().orEmpty()
                    }
                    if (if (startup) message.contains("°C") else message.contains("꺼져")) break
                    SystemClock.sleep(150)
                }
                assertTrue(message, if (startup) message.contains("°C") else message.contains("꺼져"))
                activity.onActivity { host ->
                    assertTrue(views(host.window.decorView).any { it.contentDescription == "coach_workflow" })
                    assertFalse(views(host.window.decorView).any { it.contentDescription == "weather_live_location" })
                    // The test never presses a lookup or condition-example button.
                    if (startup) {
                        assertTrue(views(host.window.decorView).first { it.contentDescription == "startup_weather_details" }.performClick())
                        assertTrue(views(host.window.decorView).filterIsInstance<TextView>().any { it.text.contains("°C") })
                        assertTrue(views(host.window.decorView).any { it.contentDescription == "workflow_weather_practice" })
                        assertFalse(views(host.window.decorView).filterIsInstance<WorkflowView>().any { it.isGuidanceSpeaking })
                    }
                }
            }
        }
    }

    @Test(timeout = 50_000) fun launchFetchesRealWeatherWithoutManualInput() = verify(true)
    @Test(timeout = 50_000) fun savedStartupOptOutIsRespected() = verify(false)
}
