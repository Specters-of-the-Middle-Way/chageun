package kr.drivestep.tutor.speech

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
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
    @Test fun conversationWaitsForOutputThenListensAutomaticallyAndHonorsStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transcripts = AtomicInteger()
        val status = AtomicReference("")
        val outputStarted = AtomicBoolean()
        val outputCompleted = AtomicBoolean()
        lateinit var controller: TutorSpeechController
        var created = false
        fun awaitState(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            do {
                var matched = false
                instrumentation.runOnMainSync { matched = predicate() }
                if (matched) return true
                Thread.sleep(25)
            } while (SystemClock.elapsedRealtime() < deadline)
            return false
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        val servicePids = shell("pidof ai.pleos.playground.caas").trim().split(Regex("\\s+"))
            .filter { it.toIntOrNull() != null }
        assertTrue("CAAS service process must be running", servicePids.isNotEmpty())
        val activeTrack = Regex("^\\s+yes\\s+\\d+\\s+(?:${servicePids.joinToString("|")})\\s+", RegexOption.MULTILINE)
        fun hasActiveRecorder(): Boolean = shell("dumpsys media.audio_flinger")
            .split(Regex("(?m)(?=^(?:Input|Output) thread )"))
            .any { it.startsWith("Input thread ") && activeTrack.containsMatchIn(it) }
        fun awaitRecorder(expectedActive: Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 3_000
            do {
                if (hasActiveRecorder() == expectedActive) return true
                Thread.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            return false
        }
        fun assertStaysStopped() {
            val countAtStop = transcripts.get()
            val deadline = SystemClock.elapsedRealtime() + 1_200
            do {
                instrumentation.runOnMainSync {
                    assertFalse("Conversation restarted without explicit action", controller.isConversationActive)
                    assertFalse("A stale callback reopened the microphone", controller.isListening)
                }
                Thread.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals("A stopped conversation delivered a late transcript", countAtStop, transcripts.get())
        }
        try {
            instrumentation.runOnMainSync {
                controller = TutorSpeechController(instrumentation.targetContext,
                    onTranscript = { transcripts.incrementAndGet() },
                    onStatus = {
                        status.set(it)
                        if (it.startsWith("Pleos 음성 안내 중")) outputStarted.set(true)
                        if (it == "Pleos 음성 안내 완료") outputCompleted.set(true)
                    })
                created = true
                controller.setActive(true)
                controller.speak("안내가 끝나면 자동으로 듣기를 시작할게요.")
            }
            assertTrue("Actual Pleos TTS did not start: ${status.get()}",
                awaitState(8_000) { outputStarted.get() && controller.isSpeaking })
            instrumentation.runOnMainSync {
                controller.startConversation()
                assertTrue(controller.isConversationActive)
                assertTrue("Starting a conversation must preserve ongoing guidance", controller.isSpeaking)
                assertFalse("The microphone opened while guidance was playing", controller.isListening)
            }
            assertTrue("No automatic microphone request after natural TTS completion: ${status.get()}",
                awaitState(15_000) {
                    if (!outputCompleted.get()) {
                        assertFalse("Microphone request preceded the real TTS completion callback", controller.isListening)
                    }
                    controller.isMicrophoneReady
                })
            assertTrue("Listening started without a successful Pleos completion", outputCompleted.get())
            assertTrue("Automatic request did not open a real CAAS recording track", awaitRecorder(true))

            // A fresh SDK-action cue during automatic listening must be heard, not dropped.
            // It closes capture first, then returns to listening after its own completion.
            var readyBeforeCue = 0
            instrumentation.runOnMainSync {
                readyBeforeCue = controller.microphoneReadyCount
                outputStarted.set(false)
                outputCompleted.set(false)
                controller.speak("방향지시등 신호를 받았어요. 주변을 직접 확인해 주세요.")
                assertTrue(controller.isConversationActive)
                assertFalse("Action guidance must stop input before speaking", controller.isListening)
                assertTrue(controller.isMicrophoneClosing)
                assertTrue("Action guidance was dropped instead of queued", controller.isSpeaking)
            }
            assertTrue("Action guidance never reached Pleos output: ${status.get()}",
                awaitState(8_000) { outputStarted.get() && controller.isSpeaking })
            assertFalse("Input recorder overlaps action guidance", hasActiveRecorder())
            assertTrue("Action guidance did not return to automatic listening: ${status.get()}",
                awaitState(15_000) {
                    if (!outputCompleted.get()) assertFalse(controller.isListening)
                    controller.microphoneReadyCount > readyBeforeCue && controller.isMicrophoneReady
                })
            assertTrue(outputCompleted.get())
            assertTrue(awaitRecorder(true))

            var beforeNetworkWait = 0
            instrumentation.runOnMainSync {
                beforeNetworkWait = controller.microphoneReadyCount
                controller.waitForReply()
                assertTrue(controller.isWaitingForReply)
                assertFalse(controller.isListening)
            }
            assertTrue(awaitState(3000) { !controller.isMicrophoneClosing })
            assertTrue("A pending text response kept the microphone open", awaitRecorder(false))
            Thread.sleep(600)
            instrumentation.runOnMainSync {
                assertTrue(controller.isConversationActive)
                assertTrue(controller.isWaitingForReply)
                assertFalse("Auto-listening raced the asynchronous response", controller.isListening)
                controller.completeReplyWait() // Muted/on-screen answer completion.
            }
            assertTrue("Completed text-only reply did not rearm conversation", awaitState(12000) {
                controller.isMicrophoneReady && controller.microphoneReadyCount > beforeNetworkWait
            })

            instrumentation.runOnMainSync { controller.stopConversation() }
            assertTrue("Explicit stop cleanup timed out", awaitState(3_000) { !controller.isMicrophoneClosing })
            assertTrue("Recording continued after explicit conversation stop", awaitRecorder(false))
            assertStaysStopped()

            instrumentation.runOnMainSync { controller.startConversation() }
            assertTrue("Explicit conversation restart did not listen: ${status.get()}",
                awaitState(12_000) { controller.isMicrophoneReady })
            assertTrue(awaitRecorder(true))
            instrumentation.runOnMainSync { controller.setActive(false) }
            assertTrue("Inactive cleanup timed out", awaitState(3_000) { !controller.isMicrophoneClosing })
            assertTrue("Recording continued after inactive state", awaitRecorder(false))
            instrumentation.runOnMainSync { controller.setActive(true) }
            assertStaysStopped()

            instrumentation.runOnMainSync { controller.startConversation() }
            assertTrue("Final explicit restart did not listen: ${status.get()}",
                awaitState(12_000) { controller.isMicrophoneReady })
            assertTrue(awaitRecorder(true))
            instrumentation.runOnMainSync { controller.close() }
            assertTrue("Close cleanup timed out", awaitState(3_000) { !controller.isMicrophoneClosing })
            assertTrue("Recording continued after close", awaitRecorder(false))
            assertStaysStopped()
            Log.i("TutorSpeechSmoke", "CONVERSATION_TTS_DONE_TO_AUTO_MIC_READY_AND_STOP_VERIFIED human_multiturn=NOT_TESTED")
        } finally {
            if (created) {
                instrumentation.runOnMainSync { controller.close() }
                awaitState(8_000) { !controller.isMicrophoneClosing }
            }
        }
    }

    @Test fun explicitMicrophoneRequestBecomesReadyAndStopsWithoutLateTranscript() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transcripts = AtomicInteger()
        val status = AtomicReference("")
        lateinit var controller: TutorSpeechController
        var created = false
        fun awaitState(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            do {
                var matched = false
                instrumentation.runOnMainSync { matched = predicate() }
                if (matched) return true
                Thread.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            return false
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        val servicePids = shell("pidof ai.pleos.playground.caas").trim().split(Regex("\\s+"))
            .filter { it.toIntOrNull() != null }
        assertTrue("CAAS service process must be running", servicePids.isNotEmpty())
        val activeTrack = Regex("^\\s+yes\\s+\\d+\\s+(?:${servicePids.joinToString("|")})\\s+", RegexOption.MULTILINE)
        fun hasActiveServiceRecorder(): Boolean {
            val dump = shell("dumpsys media.audio_flinger")
            return dump.split(Regex("(?m)(?=^(?:Input|Output) thread )"))
                .any { it.startsWith("Input thread ") && activeTrack.containsMatchIn(it) }
        }
        fun awaitRecorder(expectedActive: Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 3_000
            do {
                if (hasActiveServiceRecorder() == expectedActive) return true
                Thread.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            return false
        }
        try {
            instrumentation.runOnMainSync {
                controller = TutorSpeechController(instrumentation.targetContext,
                    onTranscript = { transcripts.incrementAndGet() },
                    onStatus = { status.set(it) })
                created = true
                controller.setActive(true)
            }
            // Repeated requests wait for SDK Binder drain. Independently inspect AudioFlinger
            // record tracks, because a controller flag/timer cannot prove service capture stopped.
            // The last iteration closes the controller itself, exercising cleanup after closed.
            repeat(3) { iteration ->
                instrumentation.runOnMainSync { controller.startListening() }
                assertTrue("Microphone request did not report readiness: ${status.get()}",
                    awaitState(12_000) { controller.isMicrophoneReady })
                assertTrue("CAAS never opened an AudioFlinger recording track", awaitRecorder(true))
                instrumentation.runOnMainSync {
                    if (iteration == 2) controller.close() else controller.stopListening()
                    assertFalse(controller.isListening)
                    assertFalse(controller.isMicrophoneReady)
                    assertTrue("Stop must retain the Binder while the asynchronous close drains", controller.isMicrophoneClosing)
                    if (iteration != 2) {
                        controller.startListening()
                        assertFalse("A new request raced the old asynchronous close", controller.isListening)
                        controller.setActive(false)
                    }
                }
                val countAtStop = transcripts.get()
                assertTrue("SDK Binder cleanup did not complete: ${status.get()}",
                    awaitState(3_000) {
                        !controller.isMicrophoneClosing && controller.microphoneCleanupCount == iteration + 1
                    })
                assertTrue("CAAS recording track remained active after stop and cleanup", awaitRecorder(false))
                Thread.sleep(100)
                instrumentation.waitForIdleSync()
                assertEquals("A cancelled microphone request delivered a late transcript", countAtStop, transcripts.get())
                if (iteration != 2) instrumentation.runOnMainSync { controller.setActive(true) }
            }
            Log.i("TutorSpeechSmoke", "MICROPHONE_REQUEST_READY_AND_AUDIOFLINGER_RECORDER_STOPPED cycles=3 human_recognition=NOT_TESTED")
        } finally {
            if (created) {
                instrumentation.runOnMainSync { controller.close() }
                awaitState(8_000) { !controller.isMicrophoneClosing }
            }
        }
    }
}
