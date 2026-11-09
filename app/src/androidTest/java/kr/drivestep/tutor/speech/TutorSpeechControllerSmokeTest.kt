package kr.drivestep.tutor.speech

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises request() through the production controller. Does not inject speech/audio. */
@RunWith(AndroidJUnit4::class)
class TutorSpeechControllerSmokeTest {
    @Test fun explicitMicrophoneRequestBecomesReadyAndStopsWithoutLateTranscript() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val transcripts = AtomicInteger()
        val status = AtomicReference("")
        var controller: TutorSpeechController? = null
        try {
            instrumentation.runOnMainSync {
                controller = TutorSpeechController(instrumentation.targetContext,
                    onTranscript = { transcripts.incrementAndGet() },
                    onStatus = { text ->
                        status.set(text)
                        if (text.contains("듣기 준비됨") || text.contains("듣고 있어요")) ready.countDown()
                    }).also {
                    it.setActive(true)
                    it.startListening()
                }
            }
            val reportedReady = ready.await(12, TimeUnit.SECONDS)
            assertTrue("Microphone request did not report readiness: ${status.get()}", reportedReady)
            instrumentation.runOnMainSync {
                controller?.stopListening()
                controller?.setActive(false)
                assertFalse(controller!!.isListening)
            }
            val countAtStop = transcripts.get()
            Thread.sleep(500)
            instrumentation.waitForIdleSync()
            assertEquals("A cancelled microphone request delivered a late transcript", countAtStop, transcripts.get())
            Log.i("TutorSpeechSmoke", "MICROPHONE_REQUEST_READY_AND_CANCELLED human_recognition=NOT_TESTED")
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
        }
    }
}
