package kr.drivestep.tutor.lessoncoach

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CompoundButton
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run on a Pleos emulator held in P at zero speed. Uses the production View actions,
 * including explicitly labelled test utterances; microphone recognition has a separate test.
 * Existing history is preserved and the original recording/audio preferences are restored via UI.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class LessonCoachFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test(timeout = 120_000)
    fun interactionPreparationObservationReviewAndRecoveryUseOneLessonFlow() {
        val originalRecords = target.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
            .getBoolean("record_consent", false)
        val originalVoice = target.getSharedPreferences("coach", Context.MODE_PRIVATE).getBoolean("voice_enabled", true)
        val beforeIds = savedRecords().map { it.getString("id") }.toSet()
        // Saving a 21st record would exercise normal retention but evict existing user history.
        assumeTrue("Use an emulator with fewer than 20 saved lessons", beforeIds.size < 20)
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            click("journey_nav_profile", 15_000)
            setChecked("journey_consent_records", true)
            setChecked("voice_toggle", false)
            click("coach_lesson")
            click("lesson_mode_demo")
            click("lesson_topic_lane_change")
            click("lesson_start")
            expectText("시연 데이터 · 정차 중 연수")
            expectText("방향지시등 꺼짐") // First snapshot establishes a baseline, never an action.

            // Starting the lesson itself starts voice input; no lesson_listen click.
            eventually("lesson entry automatically opens Pleos microphone", 12_000) {
                hasText("Pleos 듣기 준비됨")
            }

            click("lesson_example_ask")
            expectText("어느 쪽으로 차로를 바꾸고 싶으세요?")
            click("lesson_example_correct")
            expectText("요청 방향 · 왼쪽")
            click("lesson_example_traffic")
            expectText("차가 많다고 설명함")
            expectText("멈춰 있다고 설명함")
            expectText("시험 입력 · 왼쪽으로 가고 싶은데 차가 많고 멈춰 있어")
            click("lesson_signal_left")
            expectText("왼쪽 방향지시등이 켜졌어요.")
            click("lesson_demo_slow")
            expectText("시연 신호 · 8.0 km/h")
            expectText("브레이크 누름")

            click("lesson_pause")
            expectText("안내를 쉬고 있어요.")
            click("lesson_resume")
            expectText("방향지시등 왼쪽") // Resume requires a fresh baseline before a new operation.
            click("lesson_demo_right")
            expectText("오른쪽 회전이 다가와요.")
            click("lesson_signal_right")
            expectText("오른쪽 방향지시등이 켜졌어요.")
            click("lesson_finish")
            expectText("오늘 연수는 어땠나요?")
            chooseReflection("수월했어요")
            expectText("내 조작 다시보기")
            expectText("수월했다고 답해 다음 학습 항목을 제안해요.")

            var saved: JSONObject? = null
            eventually("a new consented lesson is persisted", 5_000) {
                saved = savedRecords().firstOrNull { it.getString("id") !in beforeIds }
                saved != null
            }
            val record = requireNotNull(saved)
            val recordId = record.getString("id")
            assertFalse(record.getBoolean("liveInputs"))
            assertEquals("EASY", record.getString("reflection"))
            assertEquals("TURN_PREP", record.getString("nextLesson"))
            val events = record.getJSONArray("events").let { rows -> List(rows.length()) { rows.getJSONObject(it) } }
            val utterances = events.filter { it.getString("kind") == "UTTERANCE" }
            assertEquals(3, utterances.size)
            assertTrue(utterances.all { it.getString("source") == "DRIVER_REPORTED" && it.getString("title") == "시험 문장 입력" })
            val vehicle = events.filter { it.getString("kind") == "VEHICLE" }
            assertTrue(vehicle.isNotEmpty())
            assertTrue(vehicle.all { it.getString("source") == "SIMULATION" })
            assertTrue(vehicle.any { it.getJSONObject("vehicle").optBoolean("brakePressed") })
            assertTrue(events.any { it.getString("kind") == "NAVIGATION" && it.getString("source") == "SIMULATION" })
            assertTrue(events.any { it.getString("kind") == "PAUSE" })
            assertTrue(events.any { it.getString("kind") == "RESUME" })
            assertTrue("Existing histories must remain", savedRecords().map { it.getString("id") }.containsAll(beforeIds))

            click("lesson_review_next")
            expectText("첫 기록 후")
            click("lesson_replay")
            expectText("재생 멈추기")
            click("lesson_review_next") // Manual stepping cancels playback.
            expectText("순서대로 보기")
            click("lesson_next")
            expectText("회전 미리 준비")
            click("lesson_example_ask")
            expectText("방향을 짧게 확인하고 있어요")
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            expectText("안내를 쉬고 있어요.")
            assertFalse(hasText("방향을 짧게 확인하고 있어요"))
            click("lesson_resume")
            click("lesson_finish")
            expectText("오늘 연수는 어땠나요?")
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            expectText("안내를 쉬고 있어요.")
            assertFalse("Detailed dialog must close on background/gate interruption", hasText("오늘 연수는 어땠나요?"))

            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            click("coach_lesson", 15_000)
            expectText("지난 연수 이어보기")
            click("lesson_history_0")
            expectText("내 조작 다시보기")
            assertTrue("Finished lesson survives activity restart", savedRecords().any { it.getString("id") == recordId })
        } finally {
            scenario.close()
            // Restore settings through their normal UI handlers, without deleting records.
            ActivityScenario.launch(MainActivity::class.java).use {
                click("journey_nav_profile", 15_000)
                setChecked("journey_consent_records", originalRecords)
                setChecked("voice_toggle", originalVoice)
            }
        }
    }

    private fun click(key: String, timeout: Long = 5_000) {
        eventually("enabled action $key", timeout) {
            onMain {
                val view = allViews().firstOrNull { it.contentDescription?.toString() == key && it.isShown && it.isEnabled }
                view?.performClick() == true
            }
        }
    }

    private fun setChecked(key: String, checked: Boolean) {
        eventually("toggle $key = $checked", 5_000) {
            onMain {
                val view = allViews().filterIsInstance<CompoundButton>()
                    .firstOrNull { it.contentDescription?.toString() == key && it.isShown && it.isEnabled }
                if (view == null) false else {
                    if (view.isChecked != checked) view.performClick()
                    view.isChecked == checked
                }
            }
        }
    }

    private fun chooseReflection(label: String) {
        eventually("reflection choice $label", 5_000) {
            onMain {
                val list = allViews().filterIsInstance<ListView>().firstOrNull { it.isShown } ?: return@onMain false
                val adapter = list.adapter ?: return@onMain false
                val index = (0 until adapter.count).firstOrNull { adapter.getItem(it).toString() == label } ?: return@onMain false
                val child = list.getChildAt(index - list.firstVisiblePosition) ?: return@onMain false
                list.performItemClick(child, index, adapter.getItemId(index))
            }
        }
    }

    private fun expectText(fragment: String) = eventually("text: $fragment", 5_000) { hasText(fragment) }

    private fun hasText(fragment: String): Boolean = onMain {
        allViews().filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains(fragment) }
    }

    private fun allViews(): List<View> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val result = ArrayList<View>()
        fun collect(view: View) {
            result.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        WindowInspector.getGlobalWindowViews().forEach(::collect)
        return result
    }

    private fun onMain(block: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = block() }
        return result
    }

    private fun eventually(label: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return
            SystemClock.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $label. Emulator must remain in P at zero speed.")
    }

    private fun savedRecords(): List<JSONObject> {
        val file = File(target.filesDir, "lesson-coach-history.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            JSONObject(file.readText()).getJSONArray("sessions").let { rows -> List(rows.length()) { rows.getJSONObject(it) } }
        }.getOrDefault(emptyList()) // Atomic write may be in flight; callers poll until complete.
    }
}
