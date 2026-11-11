package kr.drivestep.tutor.connected

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedFlowTest {
    private lateinit var activity: ActivityScenario<MainActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun views(v: View): List<View> = listOf(v)+if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun all(): List<View> { var values = emptyList<View>(); activity.onActivity { values=views(it.window.decorView) }; return values }
    private fun waitFor(message: String, condition: ()->Boolean) {
        val end = SystemClock.elapsedRealtime()+20_000
        while (SystemClock.elapsedRealtime()<end) { if (condition()) return; SystemClock.sleep(100) }; fail(message)
    }
    private fun click(key: String) {
        waitFor("Missing $key") { all().any { it.contentDescription?.toString()==key && it.isShown && it.isEnabled } }
        activity.onActivity { a -> assertTrue(views(a.window.decorView).first { it.contentDescription?.toString()==key }.performClick()) }
        SystemClock.sleep(80)
    }
    private fun checked(key: String,value: Boolean) {
        waitFor("Missing $key") { all().any { it.contentDescription?.toString()==key } }
        activity.onActivity { a -> (views(a.window.decorView).first { it.contentDescription?.toString()==key } as CompoundButton).isChecked=value }
    }
    private fun text(key: String) = all().filterIsInstance<TextView>().first { it.contentDescription?.toString()==key }.text.toString()
    private fun input(key: String,value: String) { activity.onActivity { a -> (views(a.window.decorView).first { it.contentDescription?.toString()==key } as EditText).setText(value) } }
    @Test fun sdkReadingsConsentValidationAndForegroundCleanupUseProductionViews() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE)
        val keys = listOf("record_consent","vehicle_consent","setup")
        val original = prefs.all.filterKeys { it in keys }
        val connected = context.getSharedPreferences("connected_ui_v1",Context.MODE_PRIVATE)
        val originalConsent = connected.all["location_consent"]
        val voicePrefs=context.getSharedPreferences("coach",Context.MODE_PRIVATE)
        val oldVoice=voicePrefs.all["voice_enabled"]
        val workflowPrefs=context.getSharedPreferences("workflow_ui_v1",Context.MODE_PRIVATE)
        val oldMuted=workflowPrefs.all["muted"]
        val records = context.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).getString("records",null)
        activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            click("journey_nav_profile"); checked("journey_consent_records",false); checked("journey_consent_vehicle",true); checked("voice_toggle",true)
            click("coach_workflow");click("workflow_connected")
            waitFor("Seat receipt missing") { text("connected_seats").contains("SDK 범위") }
            waitFor("Belt receipt missing") { text("connected_belt").contains("체결 신호 수신") }
            waitFor("Warning receipt missing") { text("connected_warnings").contains("이번 SDK 응답") || text("connected_warnings").contains("켜진 차량 표시") }
            checked("connected_location_consent",true)
            waitFor("Actual location missing") { text("connected_location").contains("FusedLocation 수신") }
            waitFor("Actual navigation response missing") { text("connected_navigation").contains("NaviHelper 수신") }
            click("connected_destination_send")
            assertTrue(text("connected_request_status").contains("올바른"))
            input("connected_destination_name","UI 확인 · 전송 안 함")
            input("connected_destination_latitude","999");input("connected_destination_longitude","127")
            click("connected_destination_send");assertTrue(text("connected_request_status").contains("올바른"))
            input("connected_destination_latitude","37.5")
            click("connected_destination_send")
            waitFor("Confirmation dialog missing") { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("내비 목적지를 변경할까요?")?.isNotEmpty()==true }
            val cancel = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("취소").first { it.isClickable }
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var panel: ConnectedVehicleView? = null
            activity.onActivity { a -> panel = views(a.window.decorView).filterIsInstance<ConnectedVehicleView>().single() }
            activity.moveToState(Lifecycle.State.CREATED)
            assertFalse(panel!!.hasLocationConnection);SystemClock.sleep(500);assertFalse(panel!!.hasLocationConnection)
            activity.moveToState(Lifecycle.State.RESUMED)
            waitFor("Read-only reconnect failed") { text("connected_location").contains("FusedLocation 수신") }
            checked("connected_location_consent",false);assertFalse(panel!!.hasLocationConnection)
            assertFalse(text("connected_location").contains("FusedLocation 수신"))
            click("workflow_home")
            if (all().filterIsInstance<TextView>().any { it.contentDescription?.toString()=="workflow_mute" && it.text.contains("안내 재개") }) click("workflow_mute")
            EmulatorWarningFixture().use { warning ->
                click("workflow_weather");click("workflow_weather_rain");click("workflow_weather_speak")
                waitFor("No real TTS start") { all().filterIsInstance<TextView>().any { it.isShown && it.text.contains("음성 안내 중") } }
                var workflow:kr.drivestep.tutor.workflow.WorkflowView?=null
                activity.onActivity { a -> workflow=views(a.window.decorView).filterIsInstance<kr.drivestep.tutor.workflow.WorkflowView>().single() }
                warning.set(true)
                waitFor("Vehicle warning did not cancel current speech") { !workflow!!.isGuidanceSpeaking }
                assertTrue((all().first { it.contentDescription?.toString()=="voice_toggle" } as CompoundButton).isChecked)
                warning.set(false);SystemClock.sleep(1_200)
                assertFalse("Old speech resumed after warning clear",workflow!!.isGuidanceSpeaking)
            }

        } finally {
            activity.close()
            val edit=prefs.edit()
            keys.forEach { key -> when (val value=original[key]) { null -> edit.remove(key); is Boolean -> edit.putBoolean(key,value); is String -> edit.putString(key,value) } }
            assertTrue(edit.commit())
            val consent=connected.edit()
            if (originalConsent is Boolean) consent.putBoolean("location_consent",originalConsent) else consent.remove("location_consent")
            assertTrue(consent.commit())
            val voice=voicePrefs.edit()
            if (oldVoice is Boolean) voice.putBoolean("voice_enabled",oldVoice) else voice.remove("voice_enabled")
            assertTrue(voice.commit())
            val mute=workflowPrefs.edit()
            if (oldMuted is Boolean) mute.putBoolean("muted",oldMuted) else mute.remove("muted")
            assertTrue(mute.commit())
            assertEquals(records,context.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).getString("records",null))
        }
    }
}
