package kr.drivestep.tutor.lessoncoach

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CompoundButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import kr.drivestep.tutor.workflow.WeatherTestSettings
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production consent dialog, shared profile choice and lifecycle cancellation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class VehicleConsentFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun views(): List<View> {
        fun collect(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { collect(view.getChildAt(it)) } else emptyList()
        return WindowInspector.getGlobalWindowViews().flatMap(::collect)
    }
    private fun onMain(block: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = block() }
        return result
    }
    private fun eventually(label: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; SystemClock.sleep(60) }
        var visible = ""
        instrumentation.runOnMainSync {
            visible = views().filterIsInstance<TextView>().filter { it.isShown }.joinToString(" | ") { it.text.toString() }
        }
        fail("$label; visible: $visible")
    }
    private fun click(key: String) {
        eventually("Missing $key") {
            onMain { views().firstOrNull { it.isShown && it.isEnabled && it.contentDescription == key }?.performClick() == true }
        }
        instrumentation.waitForIdleSync()
    }
    private fun checked(key: String, value: Boolean) = eventually("Missing switch $key") {
        onMain {
            val control = views().filterIsInstance<CompoundButton>().firstOrNull { it.isShown && it.isEnabled && it.contentDescription == key }
                ?: return@onMain false
            if (control.isChecked != value) control.performClick()
            control.isChecked == value
        }
    }
    private fun dialogButton(id: Int) = eventually("Missing dialog button $id") {
        onMain { views().firstOrNull { it.isShown && it.isEnabled && it.id == id }?.performClick() == true }
    }
    private fun dialogShown() = onMain { views().filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "동의하고 연결" } }
    private fun restore(prefs: SharedPreferences, snapshot: Map<String, *>) {
        assertTrue(prefs.edit().apply {
            clear()
            snapshot.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
        }.commit())
    }

    @Test(timeout = 100_000) fun consentRequiresAnExplicitChoiceAndSharesTheProfileSetting() {
        assumeTrue(Build.MODEL.contains("emulator", true) || Build.MODEL.contains("Car_on"))
        val target = instrumentation.targetContext
        val journey = target.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val voice = target.getSharedPreferences("coach", Context.MODE_PRIVATE)
        val llm = target.getSharedPreferences("lesson_llm", Context.MODE_PRIVATE)
        val originals = listOf(journey, voice, llm).associateWith { it.all.toMap() }
        val weather = WeatherTestSettings(target)
        var activity: ActivityScenario<MainActivity>? = null
        try {
            journey.edit().putBoolean("vehicle_consent", false).putBoolean("record_consent", false).commit()
            voice.edit().putBoolean("voice_enabled", false).commit()
            llm.edit().putBoolean("enabled", false).commit()
            activity = ActivityScenario.launch(MainActivity::class.java)
            click("coach_lesson"); click("lesson_mode_live")
            eventually("Consent explanation missing", ::dialogShown)
            assertFalse(journey.getBoolean("vehicle_consent", false))
            dialogButton(android.R.id.button2)
            assertFalse(journey.getBoolean("vehicle_consent", false))

            click("lesson_mode_live")
            activity.moveToState(Lifecycle.State.CREATED)
            eventually("Inactive dialog must close") { !dialogShown() }
            activity.moveToState(Lifecycle.State.RESUMED)
            assertFalse(journey.getBoolean("vehicle_consent", false))
            click("lesson_mode_live"); dialogButton(android.R.id.button1)
            eventually("Consent was not persisted") { journey.getBoolean("vehicle_consent", false) }
            assertFalse(journey.getBoolean("record_consent", true))
            assertFalse(llm.getBoolean("enabled", true))
            click("lesson_mode_live")
            assertFalse("Existing consent should not be requested again", dialogShown())
            click("lesson_start")
            if (target.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) {
                eventually("Live lesson input was not selected") {
                    onMain { views().filterIsInstance<LessonCoachView>().any { it.wantsLiveInputs } }
                }
            } else {
                assertTrue("Denied platform permission must keep the lesson on its menu", onMain {
                    views().any { it.isShown && it.contentDescription == "lesson_start" } &&
                        views().filterIsInstance<LessonCoachView>().none { it.wantsLiveInputs }
                })
            }

            activity.close()
            activity = ActivityScenario.launch(MainActivity::class.java)
            click("journey_nav_profile")
            assertTrue(onMain { views().filterIsInstance<CompoundButton>().any { it.contentDescription == "journey_consent_vehicle" && it.isChecked } })
            checked("journey_consent_vehicle", false)
            assertFalse(journey.getBoolean("vehicle_consent", true))
            click("coach_lesson"); click("lesson_mode_live")
            eventually("Revoked consent should be requested again", ::dialogShown)
            dialogButton(android.R.id.button2)
        } finally {
            activity?.close()
            originals.forEach { (prefs, snapshot) -> restore(prefs, snapshot) }
            weather.close()
        }
    }
}
