package kr.drivestep.tutor.workflow

import android.os.SystemClock
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import kr.drivestep.tutor.gleo.GleoAction
import kr.drivestep.tutor.gleo.GleoBridge
import kr.drivestep.tutor.speech.TutorSpeechController
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

    /** A direct Intent checks app routing only. It does not synthesize Gleo's final voice or use the microphone. */
    @Test(timeout=100_000) fun automaticAdviceStopsForNearbyDirectGleoRequest() {
        val i=InstrumentationRegistry.getInstrumentation(); val target=i.targetContext
        val coach=target.getSharedPreferences("coach",Context.MODE_PRIVATE)
        val journey=target.getSharedPreferences("drivestep_journey_v1",Context.MODE_PRIVATE)
        val voice=coach.all["voice_enabled"] as? Boolean; val record=journey.all["record_consent"] as? Boolean
        WeatherTestSettings(target).use {
            try {
                coach.edit().putBoolean("voice_enabled",true).commit(); journey.edit().putBoolean("record_consent",false).commit()
                WeatherPreferences(target).apply { consent=true; startup=true; autoRead=true }
                for(waitForSpeech in listOf(true,false)) ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    lateinit var speech:TutorSpeechController; lateinit var bridge:GleoBridge
                    activity.onActivity { host ->
                        val weather=MainActivity::class.java.getDeclaredField("startupWeather").apply { isAccessible=true }.get(host)
                        speech=StartupWeatherController::class.java.getDeclaredField("speech").apply { isAccessible=true }.get(weather) as TutorSpeechController
                        bridge=MainActivity::class.java.getDeclaredField("gleo").apply { isAccessible=true }.get(host) as GleoBridge
                    }
                    fun await(label:String,ms:Long,check:()->Boolean) {
                        val until=SystemClock.elapsedRealtime()+ms
                        while(SystemClock.elapsedRealtime()<until) { var good=false; i.runOnMainSync { good=check() }; if(good) return; SystemClock.sleep(50) }
                        fail(label)
                    }
                    if(waitForSpeech) await("Actual automatic weather speech did not start",40_000) {
                        TutorSpeechController::class.java.getDeclaredField("outputStarted").apply { isAccessible=true }.getBoolean(speech)
                    }
                    val before=bridge.responseSequence
                    activity.onActivity { host -> host.startActivity(Intent(GleoAction.TALK_WITH_DRIVING_COACH.intentAction)
                        .setPackage(target.packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("ACTION_PARAM","{\"question\":\"차로 변경 알려줘\",\"topic\":\"LANE_CHANGE\"}")) }
                    await("No direct Gleo response",12_000) { bridge.responseSequence>before }
                    i.runOnMainSync { assertFalse("Weather speech overlaps Gleo reply routing",speech.isSpeaking) }
                    SystemClock.sleep(2500)
                    i.runOnMainSync { assertFalse("Late weather response restarted voice",speech.isSpeaking) }
                    android.util.Log.i("AudioLifecycle","WEATHER_GLEO_APP_ROUTING waitForSpeech=$waitForSpeech microphone=false finalGleoAudio=false")
                }
            } finally {
                coach.edit().apply { if(voice==null) remove("voice_enabled") else putBoolean("voice_enabled",voice) }.commit()
                journey.edit().apply { if(record==null) remove("record_consent") else putBoolean("record_consent",record) }.commit()
            }
        }
    }
}
