package kr.drivestep.tutor.gleo

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import kr.drivestep.tutor.journey.DriverJourneyView
import kr.drivestep.tutor.journey.JourneyState
import kr.drivestep.tutor.parking.ParkingCourse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Intent/real app tests, NOT evidence of Gleo's speech recognition or server approval. */
@RunWith(AndroidJUnit4::class)
class GleoIntegrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun bridge(activity: MainActivity) = MainActivity::class.java.getDeclaredField("gleo").apply { isAccessible = true }.get(activity) as GleoBridge
    private fun waitReply(scenario: ActivityScenario<MainActivity>, afterSequence: Long = 0): JSONObject {
        var text: String? = null
        val until = SystemClock.elapsedRealtime() + 9_000
        while (text == null && SystemClock.elapsedRealtime() < until) {
            scenario.onActivity { if (bridge(it).responseSequence > afterSequence) text = bridge(it).lastResponse }; SystemClock.sleep(80)
        }
        assertNotNull("No bounded Gleo callback", text)
        return JSONObject(requireNotNull(text))
    }
    private fun request(scenario: ActivityScenario<MainActivity>, action: GleoAction, parameters: String = "{}"): JSONObject {
        var sequence = 0L
        scenario.onActivity { activity ->
            sequence = bridge(activity).responseSequence
            activity.startActivity(Intent(action.intentAction).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("ACTION_PARAM", parameters))
        }
        instrumentation.waitForIdleSync()
        return waitReply(scenario, sequence)
    }
    private fun isolated(run: () -> Unit) {
        val names = listOf("coach", "drivestep_journey_v1", "lesson_llm", "weather_preferences_v1")
        val backups = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        try {
            context.getSharedPreferences("coach", Context.MODE_PRIVATE).edit().putBoolean("voice_enabled", false).commit()
            context.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE).edit()
                .putBoolean("record_consent", false).putBoolean("vehicle_consent", false).commit()
            context.getSharedPreferences("lesson_llm", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
            context.getSharedPreferences("weather_preferences_v1", Context.MODE_PRIVATE).edit()
                .putBoolean("consent", false).putBoolean("startup", false).putBoolean("auto_read", false).commit()
            run()
        } finally {
            backups.forEach { (name, data) ->
                val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                data.forEach { (key, value) -> when (value) {
                    is Boolean -> edit.putBoolean(key, value); is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
                } }; edit.commit()
            }
        }
    }
    @Test fun coldLaunchPreparationAndWarmActionsPreserveRequiredChecks() = isolated {
        val schema = JSONObject(context.assets.open("schema.json").bufferedReader().use { it.readText() })
        assertEquals(3, schema.getJSONArray("actionCalls").length())
        ActivityScenario.launch<MainActivity>(Intent(GleoAction.CHECK_DEPARTURE_READINESS.intentAction)
            .setPackage(context.packageName)).use { scenario ->
            val readiness = waitReply(scenario)
            assertEquals("success", readiness.getString("status"))
            val data = org.json.JSONArray(readiness.getString("data")).getJSONObject(0)
            assertFalse(data.getBoolean("departureAuthorized"))
            assertEquals(0, data.getInt("preflightChecked"))
            assertEquals("asking", request(scenario, GleoAction.START_PARKING_PRACTICE).getString("status"))
            assertEquals("asking", request(scenario, GleoAction.GET_WEATHER_DRIVING_ADVICE).getString("status"))
            assertEquals("fail", request(scenario, GleoAction.START_PARKING_PRACTICE, "{\"course\":\"FLY\"}").getString("status"))
        }
    }
    @Test fun eachParkingCourseStartsOnlyAfterExplicitFixturePreparation() = isolated {
        for (course in ParkingCourse.entries) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(1500); instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val journey = MainActivity::class.java.getDeclaredField("journey").apply { isAccessible = true }.get(activity) as DriverJourneyView
                    // Deterministic test fixture only: never mark the user's actual checklist complete.
                    val prepared = JourneyState(setupChecks = List(9) { true }, profileConfirmed = true)
                    repeat(7) { prepared.setPreflight(it, true) }
                    DriverJourneyView::class.java.getDeclaredField("state").apply { isAccessible = true }.set(journey, prepared)
                }
                val reply = request(scenario, GleoAction.START_PARKING_PRACTICE, "{\"course\":\"${course.name}\"}")
                assertEquals(reply.toString(), "success", reply.getString("status"))
                assertEquals("SIMULATION", org.json.JSONArray(reply.getString("data")).getJSONObject(0).getString("source"))
                assertEquals("asking", request(scenario, GleoAction.START_PARKING_PRACTICE).getString("status"))
            }
        }
    }
    @Test fun parameterParserAcceptsDocumentedShapesAndRejectsMalformedInput() {
        val talk = GleoBridge.parse(Intent(GleoAction.TALK_WITH_DRIVING_COACH.intentAction)
            .putExtra("ACTION_PARAM", "{\"question\":\"왜 그래?\",\"topic\":\"CURRENT\",\"focus\":\"REASON\"}"))
        assertEquals(GleoLessonFocus.REASON, talk.conversation?.focus)
        assertNull(talk.conversation?.topic)
        for (raw in listOf("{\"question\":10}", "{\"operation\":\"DRIVE_CAR\"}", "{\"topic\":\"FLY\"}")) {
            assertTrue(runCatching { GleoBridge.parse(Intent(GleoAction.TALK_WITH_DRIVING_COACH.intentAction).putExtra("ACTION_PARAM", raw)) }.isFailure)
        }
        for (raw in listOf("{\"course\":\"PARALLEL\"}", "{\"actionParameters\":{\"course\":\"PARALLEL\"}}",
            JSONObject.quote("{\"course\":\"PARALLEL\"}"))) {
            assertEquals(ParkingCourse.PARALLEL, GleoBridge.parse(Intent(GleoAction.START_PARKING_PRACTICE.name).putExtra("ACTION_PARAM", raw)).course)
        }
        for (raw in listOf("[]", "null", "{\"course\":1}", "x".repeat(2049))) {
            assertTrue(runCatching { GleoBridge.parse(Intent(GleoAction.START_PARKING_PRACTICE.name).putExtra("ACTION_PARAM", raw)) }.isFailure)
        }
    }
    @Test fun conversationalLessonKeepsContextAndSilencesTheLocalRecognizer() = isolated {
        ActivityScenario.launch<MainActivity>(Intent(GleoAction.TALK_WITH_DRIVING_COACH.intentAction)
            .setPackage(context.packageName).putExtra("ACTION_PARAM", "{\"question\":\"차로 변경 알려줘\",\"topic\":\"LANE_CHANGE\"}")).use { scenario ->
            val first = waitReply(scenario)
            assertEquals("asking", first.getString("status"))
            val follow = request(scenario, GleoAction.TALK_WITH_DRIVING_COACH, "{\"question\":\"왜 그래?\",\"topic\":\"CURRENT\",\"focus\":\"REASON\"}")
            val data = org.json.JSONArray(follow.getString("data")).getJSONObject(0)
            assertEquals("차로 변경", data.getString("topic")); assertEquals("REASON", data.getString("requestedDetail"))
            assertFalse(data.getBoolean("maneuverAuthorized"))
            scenario.onActivity { activity ->
                val lesson = MainActivity::class.java.getDeclaredField("lesson").apply { isAccessible = true }.get(activity)
                val speech = lesson.javaClass.getDeclaredField("speech").apply { isAccessible = true }.get(lesson) as kr.drivestep.tutor.speech.TutorSpeechController
                assertFalse(speech.isConversationActive); assertFalse(speech.isListening)
                fun find(view: android.view.View): Boolean =
                    (view.isShown && view.contentDescription == "gleo_lesson_reply") ||
                        (view is android.view.ViewGroup && (0 until view.childCount).any { find(view.getChildAt(it)) })
                assertTrue("The conversation must be visible in the lesson screen", find(activity.window.decorView))
            }
            val preparation = request(scenario, GleoAction.TALK_WITH_DRIVING_COACH, "{\"operation\":\"PREPARATION\"}")
            assertEquals("success", preparation.getString("status"))
            assertFalse(org.json.JSONArray(preparation.getString("data")).getJSONObject(0).getBoolean("departureAuthorized"))
            assertEquals("success", request(scenario, GleoAction.TALK_WITH_DRIVING_COACH, "{\"operation\":\"END\"}").getString("status"))
            val afterEnd = request(scenario, GleoAction.TALK_WITH_DRIVING_COACH, "{\"question\":\"왜 그래?\"}")
            assertEquals("asking", afterEnd.getString("status"))
        }
    }
    @Test fun weatherActionUsesRealForecastAndReturnsNoCoordinates() = isolated {
        context.getSharedPreferences("weather_preferences_v1", Context.MODE_PRIVATE).edit().putBoolean("consent", true).commit()
        ActivityScenario.launch<MainActivity>(Intent(GleoAction.GET_WEATHER_DRIVING_ADVICE.intentAction)
            .setPackage(context.packageName)).use { scenario ->
            val reply = waitReply(scenario)
            assertEquals(reply.toString(), "success", reply.getString("status"))
            val data = org.json.JSONArray(reply.getString("data")).getJSONObject(0)
            assertEquals("Open-Meteo", data.getString("provider"))
            assertFalse(data.has("latitude")); assertFalse(data.has("longitude"))
            assertTrue(data.getString("advice").isNotBlank())
        }
    }
}
