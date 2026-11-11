package kr.drivestep.tutor.lessoncoach

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android HTTP + View + storage test, with clearly labelled host fixture replies. */
@org.junit.Ignore("OpenAI UI retired; GleoIntegrationDeviceTest covers the replacement actions")
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class TutorLlmFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test(timeout = 120000) fun relayContextFallbackCancellationAndReview() {
        assumeTrue("Run scripts/verify-llm.py with its explicit fixture", InstrumentationRegistry.getArguments().getString("llmFixture") == "true")
        val prefs = target.getSharedPreferences("lesson_llm", Context.MODE_PRIVATE)
        val oldEnabled = prefs.getBoolean("enabled", false)
        val oldEndpoint = prefs.getString("endpoint", null)
        val journey = target.getSharedPreferences("drivestep_journey_v1", Context.MODE_PRIVATE)
        val oldRecord = journey.getBoolean("record_consent", false)
        val oldVehicle = journey.getBoolean("vehicle_consent", false)
        val oldVoice = target.getSharedPreferences("coach", Context.MODE_PRIVATE).getBoolean("voice_enabled", true)
        val beforeIds = records().map { it.getString("id") }.toSet()
        assumeTrue("Do not evict existing lesson history", beforeIds.size < 20)
        prefs.edit().putBoolean("enabled", true).putString("endpoint", "http://127.0.0.1:18765").commit()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            click("journey_nav_profile", 15000)
            checked("journey_consent_records", true); checked("journey_consent_vehicle", true)
            checked("voice_toggle", false)
            click("coach_lesson"); click("lesson_mode_live"); click("lesson_start")
            expect("차량 신호")
            send("미러는 봤는데 제가 뭘 놓친 건지 잘 모르겠어요")
            expect("연습용 예시 답변", 12000)
            expect("사이드미러로 옆과 뒤를 살피는 연습")
            checked("voice_toggle", true)
            send("제가 아까 말한 걸 생각해서 다시 설명해 주세요")
            expect("이전 대화와 전달된 자차 상태를 함께 받았어요", 12000)
            expect("Pleos 음성 안내 중", 12000)
            eventually("완료 뒤 음성 상태 문구 숨김", 25000) {
                onMain { views().any { it.contentDescription == "lesson_speech_status" && it.visibility == View.GONE } }
            }
            assertFalse(hasText("Pleos 음성 안내 완료"))
            checked("voice_toggle", false)
            send("연결 오류 시험")
            expect("OpenAI 키 설정 필요 · 기본 안내", 12000)
            send("늦은 답변 시험")
            expect("AI 답변 기다리는 중")
            click("lesson_pause")
            Thread.sleep(2400)
            assertFalse(hasText("중단 이후 도착한 시험 응답"))
            expect("안내를 쉬고 있어요")
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse(hasText("중단 이후 도착한 시험 응답"))
            click("lesson_finish")
            eventually("reflection list") {
                onMain {
                    val list = views().filterIsInstance<ListView>().firstOrNull { it.isShown } ?: return@onMain false
                    val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == "보통이었어요" }
                    list.performItemClick(list.getChildAt(index - list.firstVisiblePosition), index, list.adapter.getItemId(index))
                }
            }
            expect("내 조작 다시보기")
            var saved: JSONObject? = null
            eventually("LLM fixture replies persist in normal review") {
                saved = records().firstOrNull { it.getString("id") !in beforeIds }; saved != null
            }
            val record = requireNotNull(saved)
            val events = record.getJSONArray("events").let { a -> List(a.length()) { a.getJSONObject(it) } }
            assertTrue(record.getBoolean("liveInputs"))
            assertEquals(2, events.count { it.getString("title") == "연습용 예시 답변" })
            assertEquals(3, events.count { it.getString("kind") == "UTTERANCE" })
            assertTrue(events.filter { it.getString("kind") == "UTTERANCE" }.all { it.getString("title") == "시험 문장 입력" })
            assertTrue(events.any { it.getString("kind") == "VEHICLE" && it.getString("source") == "SDK" })
            assertTrue(records().map { it.getString("id") }.containsAll(beforeIds))
            scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
            click("coach_lesson", 15000); click("lesson_history_0"); expect("내 조작 다시보기")
        } finally {
            scenario?.close()
            prefs.edit().putBoolean("enabled", oldEnabled).apply {
                if (oldEndpoint == null) remove("endpoint") else putString("endpoint", oldEndpoint)
            }.commit()
            ActivityScenario.launch(MainActivity::class.java).use {
                click("journey_nav_profile", 15000)
                checked("journey_consent_records", oldRecord); checked("journey_consent_vehicle", oldVehicle)
                checked("voice_toggle", oldVoice)
            }
        }
    }

    private fun records(): List<JSONObject> {
        val file = File(target.filesDir, "lesson-coach-history.json")
        if (!file.exists()) return emptyList()
        val rows = JSONObject(file.readText()).getJSONArray("sessions")
        return List(rows.length()) { rows.getJSONObject(it) }
    }
    private fun send(text: String) {
        onMain { views().filterIsInstance<EditText>().first { it.contentDescription == "lesson_input" }.setText(text); true }
        click("lesson_send")
    }
    private fun click(key: String, timeout: Long = 5000) = eventually(key, timeout) {
        onMain { views().firstOrNull { it.isShown && it.isEnabled && it.contentDescription == key }?.performClick() == true }
    }
    private fun checked(key: String, value: Boolean) = eventually(key) {
        onMain {
            val view = views().filterIsInstance<CompoundButton>().firstOrNull { it.contentDescription == key && it.isShown } ?: return@onMain false
            if (view.isChecked != value) view.performClick()
            view.isChecked == value
        }
    }
    private fun expect(text: String, timeout: Long = 5000) = eventually(text, timeout) { hasText(text) }
    private fun hasText(text: String) = onMain { views().filterIsInstance<TextView>().any { it.isShown && it.text.contains(text) } }
    private fun onMain(block: () -> Boolean): Boolean {
        var result = false; instrumentation.runOnMainSync { result = block() }; return result
    }
    private fun eventually(label: String, timeout: Long = 5000, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do { if (block()) return; Thread.sleep(50) } while (SystemClock.elapsedRealtime() < end)
        fail("Timed out: $label")
    }
    private fun views(): List<View> {
        val result = mutableListOf<View>()
        fun collect(v: View) { result.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        WindowInspector.getGlobalWindowViews().forEach(::collect)
        return result
    }
}
