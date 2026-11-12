package kr.drivestep.tutor.speech

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.coach.SignalCoach
import kr.drivestep.tutor.coach.VoiceCoach
import kr.drivestep.tutor.ride.RideCoach
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic sentences only. Playback callbacks verify lifecycle, NOT perceptual sound quality.
 * Android fallback is deliberately selected via its production method, not a real engine failure. */
@RunWith(AndroidJUnit4::class)
class AudioLifecycleDeviceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context=i.targetContext
    private val folder=File(context.cacheDir,"prototype-audio").apply { mkdirs() }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun note(value:String) { Log.i("AudioLifecycle", "t=${SystemClock.elapsedRealtime()} $value") }
    private fun waitFor(label:String,ms:Long=15_000,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+ms
        while(SystemClock.elapsedRealtime()<until) {
            var yes=false; i.runOnMainSync { yes=condition() }; if(yes) return; SystemClock.sleep(40)
        }; fail(label)
    }
    private fun dump(label:String) { File(folder,"$label.txt").writeText(shell("dumpsys media.audio_flinger")) }
    private fun current(coach:VoiceCoach):Any?=VoiceCoach::class.java.getDeclaredField("current").apply { isAccessible=true }.get(coach)
    private fun serial(s:TutorSpeechController)=TutorSpeechController::class.java.getDeclaredField("serial").apply { isAccessible=true }.getLong(s)
    private fun forceFallback(s:TutorSpeechController,text:String) {
        TutorSpeechController::class.java.getDeclaredMethod("useAndroid",Long::class.javaPrimitiveType,String::class.java).apply { isAccessible=true }.invoke(s,serial(s),text)
    }
    companion object {
        val sentences=listOf("주변을 직접 확인해 주세요.",
            "이 안내는 정차한 상태에서 사용하는 가상 연습이에요. 옆 차로의 차량 위치와 통로를 확인하고 시야가 가려져 있으면 기다리거나 시도를 취소해 주세요.",
            "현재 기온은 12.5도예요. 가상 연습의 거리는 30미터이고 속도는 시속 20킬로미터예요.")
    }
    @Test(timeout=240_000) fun syntheticColdWarmPleosAndAndroidFallback() {
        for(fallback in listOf(false,true)) {
            lateinit var controller:TutorSpeechController
            var started=0; var completed=0
            var lastStatus=""
            i.runOnMainSync { controller=TutorSpeechController(context,{}, { status ->
                lastStatus=status
                if(status.contains("음성 안내 중")) started++
                if(status.contains("음성 안내 완료")) completed++
            }); controller.setActive(true) }
            try {
                repeat(2) { cycle -> sentences.forEachIndexed { index,sentence ->
                    val before=completed; val beforeStart=started
                    note("REQUEST backend=${if(fallback) "ANDROID_FORCED_FALLBACK" else "PLEOS"} cycle=$cycle sentence=$index chars=${sentence.length}")
                    i.runOnMainSync { controller.speak(sentence); if(fallback) forceFallback(controller,sentence) }
                    waitFor("No start or bounded unavailable result fallback=$fallback sentence=$index") { started>beforeStart || !controller.isSpeaking }
                    if(started==beforeStart) {
                        assertTrue("Pleos must actually speak",fallback)
                        assertTrue(lastStatus,lastStatus.contains("출력을 사용할 수 없") || lastStatus.contains("출력을 연결하지 못"))
                        assertFalse(controller.isSpeaking)
                        i.runOnMainSync { assertNull(TutorSpeechController::class.java.getDeclaredField("androidTts").apply { isAccessible=true }.get(controller)) }
                        note("UNSUPPORTED_ANDROID_FALLBACK cycle=$cycle sentence=$index perceptualQuality=UNTESTED")
                        return@forEachIndexed
                    }
                    waitFor("No completion fallback=$fallback sentence=$index",35_000) { completed>before && !controller.isSpeaking }
                    note("COMPLETED backend=$fallback cycle=$cycle sentence=$index")
                } }
            } finally { i.runOnMainSync { controller.close() } }
        }
    }
    @Test(timeout=120_000) fun rapidRequestsInactiveMuteReplacementAndAudioFocus() {
        lateinit var s:TutorSpeechController
        var started=0
        i.runOnMainSync { s=TutorSpeechController(context,{}, { if(it.contains("음성 안내 중")) started++ }); s.setActive(true) }
        val audio=context.getSystemService(AudioManager::class.java)
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        val competing=MediaPlayer.create(context,R.raw.coach_ride_red_wait)
        assertNotNull(competing)
        try {
            i.runOnMainSync {
                val before=serial(s); repeat(12) { s.speak(sentences[1]) }
                note("RAPID_IDENTICAL serialDelta=${serial(s)-before}")
                assertEquals("Identical pending requests must coalesce",1L,serial(s)-before)
            }
            waitFor("Rapid text never starts") { started>0 }; dump("rapid-playing")
            i.runOnMainSync { s.setActive(false); assertFalse(s.isSpeaking) }
            SystemClock.sleep(350); dump("inactive-350ms")
            SystemClock.sleep(900); dump("inactive-1250ms")
            val stoppedStarts=started
            i.runOnMainSync { s.setActive(true) }; SystemClock.sleep(700)
            assertEquals("Returned screen replayed old output",stoppedStarts,started)
            i.runOnMainSync { s.speak(sentences[1]) }; waitFor("Restart") { started>stoppedStarts }
            i.runOnMainSync { s.setEnabled(false) }; SystemClock.sleep(1200); dump("muted")
            assertFalse(s.isSpeaking)
            i.runOnMainSync { s.setEnabled(true); s.speak(sentences[1]) }
            waitFor("Before replacement") { s.isSpeaking }
            i.runOnMainSync { s.speak(sentences[2]); s.speak(sentences[0]) }
            waitFor("Replacement completion",35_000) { !s.isSpeaking }
            i.runOnMainSync { s.speak(sentences[1]) }
            SystemClock.sleep(1000)
            val grant=audio.requestAudioFocus(focus); note("COMPETING_FOCUS grant=$grant")
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,grant)
            competing.start(); assertTrue(competing.isPlaying)
            note("COMPETING_AUDIO bundledSyntheticCue=true")
            SystemClock.sleep(800); dump("focus-loss")
            var speaking=false; i.runOnMainSync { speaking=s.isSpeaking }; note("FOCUS_LOSS isSpeaking=$speaking")
            assertFalse("App speech must stop when competing audio takes focus",speaking)
            waitFor("Competing fixed cue did not finish",10_000) { !competing.isPlaying }
            audio.abandonAudioFocusRequest(focus)
            val count=started; SystemClock.sleep(1000)
            assertEquals("Focus recovery must not replay old output",count,started)
            i.runOnMainSync { s.speak(sentences[0]) }
            waitFor("Explicit replay after focus recovery must work") { started>count }
            waitFor("Explicit replay completion",20_000) { !s.isSpeaking }
            i.runOnMainSync { s.stop() }; val stopped=started; SystemClock.sleep(500)
            assertEquals("Late start after stop",stopped,started)
        } finally { competing.release(); audio.abandonAudioFocusRequest(focus); i.runOnMainSync { s.close() }; SystemClock.sleep(1200); dump("closed") }
    }
    @Test(timeout=120_000) fun fixedGuidanceAvailableBackendRepeatsAndCancels() {
        lateinit var coach:VoiceCoach
        i.runOnMainSync { coach=VoiceCoach(context,{}); coach.setActive(true); coach.speak(SignalCoach.START) }
        try {
            waitFor("Cold bundle completion",20_000) { current(coach)==null }
            repeat(2) { cycle -> listOf(RideCoach.RED_WAIT,SignalCoach.START,SignalCoach.COMPLETED).forEach { cue ->
                note("FIXED_REQUEST cycle=$cycle cue=${cue.id} chars=${cue.text.length}")
                i.runOnMainSync { coach.stop(); coach.speak(cue); assertNotNull("Must submit actual fixed audio",current(coach)) }
                waitFor("Fixed guidance completion",35_000) { current(coach)==null }
            } }
            i.runOnMainSync { coach.stop(); coach.speak(SignalCoach.START); coach.setActive(false) }
            SystemClock.sleep(1000); i.runOnMainSync { assertNull(current(coach)); coach.setActive(true); assertNull(current(coach)) }
        } finally { i.runOnMainSync { coach.close() }; dump("fixed-closed") }
    }
}
