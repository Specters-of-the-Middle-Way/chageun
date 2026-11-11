package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
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
import kr.drivestep.tutor.journey.DriverJourneyView
import kr.drivestep.tutor.vehicle.Powertrain
import kr.drivestep.tutor.vehicle.VehicleEducation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningFeaturesDeviceTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private lateinit var activity: ActivityScenario<MainActivity>
    private fun all(): List<View> { var result = emptyList<View>(); activity.onActivity { result = views(it.window.decorView) }; return result }
    private fun hasText(value: String) = all().filterIsInstance<TextView>().any { it.isShown && it.text.contains(value) }
    private fun click(key: String) {
        activity.onActivity {
            val view = views(it.window.decorView).first { v -> v.contentDescription?.toString() == key }
            assertTrue("disabled $key", view.isEnabled)
            view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true); assertTrue(view.performClick())
        }
        SystemClock.sleep(100)
    }
    private fun checked(key: String, value: Boolean) { activity.onActivity { a ->
        (views(a.window.decorView).first { it.contentDescription?.toString() == key } as CompoundButton).isChecked = value
    }; SystemClock.sleep(100) }
    private fun eventually(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; SystemClock.sleep(150) }
        fail(message)
    }
    private fun session(block: () -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = target.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val weatherSettings = WeatherTestSettings(target)
        val record = prefs.all["record_consent"] as? Boolean
        val workflow = target.getSharedPreferences("workflow_ui_v1", Context.MODE_PRIVATE)
        val muted = workflow.all["muted"] as? Boolean
        prefs.edit().putBoolean("record_consent",false).commit(); workflow.edit().putBoolean("muted",true).commit()
        activity = ActivityScenario.launch(MainActivity::class.java)
        try { block() } finally {
            activity.close()
            weatherSettings.close()
            prefs.edit().apply { if (record == null) remove("record_consent") else putBoolean("record_consent",record) }.commit()
            workflow.edit().apply { if (muted == null) remove("muted") else putBoolean("muted",muted) }.commit()
        }
    }
    @Test(timeout = 90_000) fun officialManualAndComprehensionGuidesWorkWithoutRecordConsent() = session {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val before = target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).all.toMap()
        click("coach_workflow"); click("workflow_manual")
        assertTrue(hasText("설명서 확인 필요"))
        click("workflow_manual_cn7_auto"); click("workflow_manual_defog")
        assertTrue(hasText("5-88")); assertTrue(hasText("김서림"))
        repeat(3) { click("workflow_manual_next") }; assertTrue(hasText("성에"))
        assertFalse(all().any { it.contentDescription == "workflow_manual_operate" })
        click("workflow_manual_confirm"); assertTrue(hasText("설명서 학습 확인 완료"))
        click("workflow_manual_cn7_manual"); assertTrue(hasText("5-87"))
        click("workflow_home"); click("workflow_guides"); click("workflow_guide_double_parking")
        assertTrue(hasText("가로막아")); repeat(3) { click("workflow_guide_next") }
        click("workflow_guide_answer_1"); assertTrue(hasText("다시 생각해"))
        click("workflow_guide_answer_0"); assertTrue(hasText("이해 확인 완료"))
        click("workflow_guide_shoulder"); assertTrue(hasText("사각지대"))
        repeat(3) { click("workflow_guide_next") }; click("workflow_guide_answer_1"); assertTrue(hasText("이해 확인 완료"))
        click("workflow_guide_winter"); repeat(3) { click("workflow_guide_next") }
        click("workflow_guide_answer_1"); assertTrue(hasText("기온만으로"))
        assertEquals(before,target.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).all.toMap())
    }
    @Test(timeout = 90_000) fun liveWeatherUsesExplicitConsentAndClearsOnRevocation() = session {
        click("coach_workflow"); click("workflow_weather")
        assertTrue(hasText("현재 위치 날씨 조회"))
        assertFalse(hasText("지역별 현재 날씨"))
        assertFalse(all().any { it.contentDescription?.toString()?.startsWith("weather_place_") == true })
        assertFalse(all().any { it.contentDescription == "weather_live_fetch" })
        assertTrue(all().first { it.contentDescription == "weather_live_location" }.let { !it.isEnabled })
        checked("weather_live_consent",true); click("weather_live_location")
        eventually("Real Open-Meteo response not shown") { hasText("예보 기준") && hasText("°C") }
        assertTrue(hasText("현재 위치 주변 ·") || hasText("현재 위치 미확인 · 기본 지역인 경기도 성남시 수정구 복정동"))
        assertTrue(hasText("Open-Meteo 현재 예보"))
        checked("weather_live_consent",false)
        assertFalse(hasText("예보 기준")); assertFalse(all().any { it.contentDescription == "weather_live_speak" })
        assertTrue(hasText("날씨 조회 동의를 껐어요"))
    }
    @Test fun actualEducationViewsOnlyShowEvContentForElectricVehicles() = session {
        activity.onActivity { host ->
            for (type in listOf(Powertrain.GASOLINE,Powertrain.ELECTRIC,Powertrain.UNKNOWN)) {
                val preferenceName = "planning_vehicle_view_test_${type.name}"
                host.deleteSharedPreferences(preferenceName)
                val isolated = object : ContextWrapper(host) {
                    override fun getSharedPreferences(name: String, mode: Int) =
                        super.getSharedPreferences(if (name == "drivestep_journey_v1") preferenceName else name, mode)
                }
                val view = DriverJourneyView(isolated,{}, {},VehicleEducation(type))
                try {
                    view.setActive(true)
                    views(view).first { it.contentDescription == "journey_education" }.performClick()
                    assertEquals(type == Powertrain.ELECTRIC,views(view).any { it.contentDescription == "journey_ev_braking" })
                    // Complete the real preparation flow; the preflight gate must not be bypassed.
                    views(view).first { it.contentDescription == "journey_nav_profile" }.performClick()
                    views(view).first { it.contentDescription == "journey_profile_continue" }.performClick()
                    repeat(9) { views(view).first { it.contentDescription == "journey_setup_confirm" }.performClick() }
                    val text = views(view).filterIsInstance<TextView>().joinToString { it.text.toString() }
                    assertTrue("$type preflight missing: $text",text.contains(VehicleEducation(type).energyCheck.first))
                    if (type == Powertrain.GASOLINE) assertFalse(text.contains("배터리 잔량"))
                } finally { view.close(); host.deleteSharedPreferences(preferenceName) }
            }
        }
    }
    @Test fun weatherJsonParserValidatesUnitsAndRequiredFields() {
        val place = WeatherPlace("서울",37.57,126.98)
        val json = """{"current_units":{"time":"unixtime","temperature_2m":"°C","weather_code":"wmo code"},"current":{"time":1794385800,"temperature_2m":1.2,"weather_code":61}}"""
        val result = OpenMeteoClient.parse(json,place)
        assertEquals(1794385800000,result.modelTime); assertEquals(61,result.code)
        assertTrue(runCatching { OpenMeteoClient.parse(json.replace("°C","°F"),place) }.isFailure)
        assertTrue(runCatching { OpenMeteoClient.parse(json.replace("\"weather_code\":61","\"weather_code\":61.5"),place) }.isFailure)
        assertTrue(runCatching { OpenMeteoClient.parse("{}",place) }.isFailure)
    }
}
