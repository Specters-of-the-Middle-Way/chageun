package kr.drivestep.tutor.speech

import android.media.AudioAttributes
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.drivestep.tutor.lessoncoach.LessonCoachSession
import kr.drivestep.tutor.lessoncoach.LessonDirection
import kr.drivestep.tutor.lessoncoach.LessonEvidence
import kr.drivestep.tutor.lessoncoach.LessonTopic
import kr.drivestep.tutor.lessoncoach.LessonUtteranceParser
import kr.drivestep.tutor.lessoncoach.SpeechInputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a Pleos image with Speech Service; never replaces real callbacks with test data. */
@RunWith(AndroidJUnit4::class)
class PleosSpeechSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pleosOnDeviceTtsStartsKoreanSynthesis() {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        var client: TextToSpeech? = null
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() {
                Log.i("PleosSpeechSmoke", "TTS_STARTED backend=PLEOS_ON_DEVICE")
                finished.countDown()
            }
            override fun onDone() = Unit
            override fun onStop() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onError(errorType: TtsError) {
                failure.set("TTS: ${errorType.name}")
                finished.countDown()
            }
        }
        try {
            instrumentation.runOnMainSync {
                val sdk = TextToSpeech(instrumentation.targetContext, TtsMode.ON_DEVICE)
                client = sdk
                sdk.initialize(object : TtsInitializeListener {
                    override fun onSuccess() {
                        sdk.addEventListener(listener)
                        sdk.setLocale(Locale.KOREA)
                        sdk.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        sdk.speak("차근과 함께 차로 변경 준비를 연습해요.")
                    }
                    override fun onFailure(errorType: TtsError) {
                        failure.set("TTS initialize: ${errorType.name}")
                        finished.countDown()
                    }
                })
            }
            assertTrue("Pleos TTS did not signal playback start within 20 seconds", finished.await(20, TimeUnit.SECONDS))
            assertNull(failure.get(), failure.get())
        } finally {
            instrumentation.runOnMainSync {
                client?.let { sdk ->
                    runCatching { sdk.removeEventListener(listener) }
                    runCatching { sdk.stop() }
                    runCatching { sdk.release() }
                }
            }
        }
    }

    @Test fun pleosSttResultRequiresDirectionConfirmation() {
        val initialized = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val recognized = AtomicReference<String?>()
        var client: SpeechToText? = null
        val listener = object : ResultListener {
            override fun onUpdated(stt: String, completed: Boolean) {
                if (completed && stt.isNotBlank()) {
                    recognized.set(stt)
                    // This test owns a synthetic fixture; never add this raw-text log to
                    // the production microphone controller.
                    Log.i("PleosSpeechSmoke", "STT_FINAL_RECEIVED input=SYNTHETIC_PCM result=$stt")
                    finished.countDown()
                }
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onError(errorType: SttError) {
                failure.set("STT: ${errorType.name}")
                initialized.countDown()
                finished.countDown()
            }
        }
        try {
            instrumentation.runOnMainSync {
                val sdk = SpeechToText(instrumentation.targetContext, SttMode.ON_DEVICE)
                client = sdk
                sdk.initialize(object : SttInitializeListener {
                    override fun onSuccess() {
                        sdk.addListener(listener)
                        sdk.setLocale(Locale.KOREA)
                        initialized.countDown()
                    }
                    override fun onFailure(errorType: SttError) {
                        failure.set("STT initialize: ${errorType.name}")
                        initialized.countDown()
                        finished.countDown()
                    }
                })
            }
            assertTrue("Pleos STT initialization timed out", initialized.await(10, TimeUnit.SECONDS))
            assertNull(failure.get(), failure.get())
            val pcm = instrumentation.context.assets.open("lesson_left_16khz_mono_s16le.pcm").use { it.readBytes() }
            // This is the documented PCM-input path, not request()'s microphone path.
            client!!.sendAudio(pcm)
            client!!.completeAudioSend()
            assertTrue("No final PCM recognition result within 25 seconds", finished.await(25, TimeUnit.SECONDS))
            assertNull(failure.get(), failure.get())
            val actual = requireNotNull(recognized.get())
            val interpreted = LessonUtteranceParser().parse(actual)
            assertTrue("Synthetic direction fixture produced unsupported intent: $actual",
                interpreted.direction in setOf(LessonDirection.LEFT, LessonDirection.RIGHT))
            // The image has misrecognized this LEFT fixture as RIGHT. Retain the original
            // fixture and prove the app does not act on either direction without read-back.
            val session = LessonCoachSession()
            session.start(LessonTopic.LANE_CHANGE, 0)
            val pending = session.acceptUtterance(actual, SpeechInputSource.PLEOS_STT, 100)
            assertTrue("Actual SDK result must require confirmation: $actual", pending.awaitingConfirmation)
            assertEquals(LessonDirection.UNKNOWN, pending.direction)
            assertTrue("Confirmation must read back the recognized direction", pending.reply.contains(interpreted.direction.label))
            assertTrue("Speech must not become sensor evidence", pending.events.none { it.source == LessonEvidence.SDK })

            // This second utterance is a deterministic domain input, not a claim that the
            // SDK recognized a second audio sample or that microphone accuracy was tested.
            val rejected = session.acceptUtterance("아니야", SpeechInputSource.PLEOS_STT, 1000)
            assertFalse(rejected.awaitingConfirmation)
            assertTrue(rejected.awaitingDirection)
            assertEquals(LessonDirection.UNKNOWN, rejected.direction)
            Log.i("PleosSpeechSmoke", "STT_CONFIRMATION_GUARD_PASSED input=SYNTHETIC_PCM accuracy=UNVERIFIED")
        } finally {
            instrumentation.runOnMainSync {
                client?.let { sdk ->
                    runCatching { sdk.removeListener(listener) }
                    runCatching { sdk.stop() }
                    runCatching { sdk.release() }
                }
            }
        }
    }
}
