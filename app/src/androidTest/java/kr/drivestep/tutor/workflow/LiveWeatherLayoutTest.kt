package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveWeatherLayoutTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun verify(width: Int, height: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val themed = target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale = scale })
            themed.setTheme(R.style.AppTheme)
            val names = mutableSetOf<String>()
            val prefix = "weather_layout_${System.nanoTime()}_"
            val context = object : ContextWrapper(themed) {
                override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix + name, mode).also { names += prefix + name }
            }
            WeatherPreferences(context).apply { consent = false; startup = false; autoRead = false }
            val root = WorkflowView(context, {}, {}, {}, {}, { false }, { false }, { false }, {}, {}, {})
            val density = context.resources.displayMetrics.density
            fun control(key: String) = requireNotNull(views(root).firstOrNull { it.contentDescription == key }) { key }
            fun measure() {
                root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            fun checkText() {
                measure()
                views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.forEach { view ->
                    val message = "$width x $height / $scale: ${view.text}"
                    val layout = requireNotNull(view.layout) { message }
                    assertTrue("Vertical clipping $message", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                    repeat(layout.lineCount) { line ->
                        assertEquals("Ellipsis $message", 0, layout.getEllipsisCount(line))
                        assertTrue("Horizontal clipping $message", layout.getLineMax(line) <= layout.width + 1)
                    }
                    if (view.isClickable) assertTrue("Touch target $message", view.height >= 48 * density)
                }
                for (key in listOf("weather_live_location", "workflow_home", "weather_live_speak")) {
                    views(root).firstOrNull { it.contentDescription == key }?.let { view ->
                        val rect = Rect(0, 0, view.width, view.height); root.offsetDescendantRectToMyCoords(view, rect)
                        assertTrue("$key above viewport: $rect", rect.top >= root.paddingTop)
                        assertTrue("$key below viewport: $rect", rect.bottom <= root.height - root.paddingBottom)
                    }
                }
            }
            try {
                root.setSpeechEnabled(false); root.setActive(true); root.openWeather(); checkText()
                assertFalse(control("weather_live_location").isEnabled)
                (control("weather_live_consent") as CompoundButton).isChecked = true
                assertTrue(control("weather_live_location").isEnabled)
                for (code in listOf(0, 45, 61, 67, 75, 95)) {
                    val reading = LiveWeatherReading(WeatherPlaces.DEFAULT, -2.0, code, System.currentTimeMillis())
                    root.openWeather(reading); checkText()
                    assertEquals(reading.advice, (control("weather_live_advice") as TextView).text.toString())
                    assertTrue((control("weather_live_location_notice") as TextView).text.contains("복정동"))
                    assertTrue(control("weather_live_speak").isEnabled)
                }
                val current = LiveWeatherReading(WeatherPlace("현재 위치 주변", 37.47, 127.13), 14.5, 0, System.currentTimeMillis())
                root.openWeather(current); checkText()
                assertFalse(views(root).any { it.contentDescription == "weather_live_location_notice" })
                (control("weather_startup_auto") as CompoundButton).isChecked = true
                (control("weather_live_auto") as CompoundButton).isChecked = true
                assertTrue(WeatherPreferences(context).startup); assertTrue(WeatherPreferences(context).autoRead)
                root.setActive(false)
                assertFalse(control("weather_live_location").isEnabled); assertFalse(control("weather_live_speak").isEnabled)
                root.setActive(true)
                (control("weather_live_consent") as CompoundButton).isChecked = false; checkText()
                assertFalse(views(root).any { it.contentDescription == "weather_live_temperature" || it.contentDescription == "weather_live_speak" })
                assertFalse(WeatherPreferences(context).consent)
                assertEquals(setOf("consent", "startup", "auto_read"), context.getSharedPreferences("weather_preferences_v1", Context.MODE_PRIVATE).all.keys)
            } finally { root.close(); names.forEach { target.deleteSharedPreferences(it) } }
        }
    }
    @Test fun weatherAdviceAndConsentStatesFitPleos() = verify(1707, 1018, 1.2f)
    @Test fun largeTypeAndNarrowWindowKeepTextAndActionsReadable() {
        verify(1707, 1018, 1.6f)
        verify(1100, 650, 1.2f)
    }
}
