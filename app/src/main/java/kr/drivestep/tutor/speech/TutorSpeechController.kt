package kr.drivestep.tutor.speech

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener
import java.util.Locale

/**
 * Explicitly started ON_DEVICE conversations with bounded microphone turns. No wake word, background microphone, server
 * registration, raw audio recording or transcript logging. The host owns the parked UX gate.
 * Pleos failures are visible; Android Korean TTS is a separate, labelled output fallback.
 * Dynamic responses do not use the legacy CoachCue/WAV catalogue.
 */
class TutorSpeechController(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConversationStopped: () -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val transcripts = TranscriptGate()
    private val conversation = ConversationTurnGate()
    private var nextConversationTurn: Runnable? = null
    val isConversationActive: Boolean get() = conversation.isActive
    var isWaitingForReply: Boolean = false
        private set
    val isSpeaking: Boolean get() = output != null || pendingAfterMicrophoneClose != null
    var microphoneReadyCount: Int = 0
        private set
    private var active = false
    private var enabled = true
    private var closed = false
    private var serial = 0L
    private var focus: AudioFocusRequest? = null
    private var deadline: Runnable? = null
    private var stt: SpeechToText? = null
    private var sttListener: ResultListener? = null
    private var sttRequested = false
    private var sttEnded = false
    private data class MicrophoneClose(val client: SpeechToText, val listener: ResultListener?)
    private var microphoneClose: MicrophoneClose? = null
    private var microphoneCloseDeadline: Runnable? = null
    private var pendingAfterMicrophoneClose: String? = null

    val isMicrophoneClosing: Boolean get() = microphoneClose != null
    var microphoneCleanupCount: Int = 0
        private set
    private var pleosTts: PleosTts? = null
    private var ttsListener: EventListener? = null
    private var androidTts: AndroidTts? = null
    private var androidInitializing = false
    private var androidReady = false
    private var output: String? = null
    private var outputStarted = false
    private var usingAndroid = false
    private val debugDiagnostics = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var rmsCount = 0
    private var rmsMin = Double.POSITIVE_INFINITY
    private var rmsMax = Double.NEGATIVE_INFINITY

    var isMicrophoneReady: Boolean = false
        private set

    var isListening: Boolean = false
        private set
    var statusText: String = "대화 시작을 누르면 안내가 끝날 때마다 자동으로 다시 들어요."
        private set

    fun setActive(value: Boolean) = onMain {
        if (closed || active == value) return@onMain
        active = value
        if (!value) {
            endConversation()
            cancel()
            report("음성 입력과 안내를 쉬고 있어요.")
        }
    }

    /** Mutes output; explicit speech input can still be used with the on-screen response. */
    fun setEnabled(value: Boolean) = onMain {
        if (closed || enabled == value) return@onMain
        enabled = value
        if (!value && !isListening) {
            endConversation()
            cancel()
            report("음성 안내를 껐어요. 대화 시작을 누르면 화면 안내로 대화할 수 있어요.")
        }
    }

    /** One tap starts alternating input/output turns; no wake word or background restart. */
    fun startConversation() = onMain {
        if (closed || !active || isConversationActive) return@onMain
        conversation.start(SystemClock.elapsedRealtime())
        if (isSpeaking || isListening) conversation.waitForResponse()
        report(if (isSpeaking) "안내가 끝나면 자동으로 들을게요." else "대화를 시작해요. 듣기 준비가 되면 말씀해 주세요.")
        scheduleConversationTurn()
    }

    fun stopConversation() = onMain {
        if (!closed) {
            endConversation()
            cancel()
            report("대화를 마쳤어요. 다시 시작할 때 대화 시작을 눌러 주세요.")
        }
    }

    /** Retained for explicit one-shot SDK callers; the lesson UI uses startConversation. */
    fun startListening() = onMain {
        if (closed || !active) return@onMain
        endConversation()
        // Do not interrupt an asynchronous output with input and risk self-recognition.
        if (isSpeaking) {
            report("안내가 끝난 뒤 다시 눌러 주세요.")
            return@onMain
        }
        beginListening()
    }

    private fun beginListening() {
        if (closed || !active) return
        if (isMicrophoneClosing) {
            report("이전 마이크 입력을 종료하고 있어요. 잠시 뒤 다시 눌러 주세요.")
            return
        }
        if (isListening) return
        cancel() // Called only after output and microphone cleanup are idle.
        val token = serial
        val transcriptToken = transcripts.begin()
        isListening = true
        if (!takeFocus(token, listening = true)) {
            endConversation()
            cancel()
            report("다른 차량 소리가 우선이에요. 잠시 뒤 다시 눌러 주세요.")
            return
        }
        report("Pleos 음성 인식 연결 중 · 기기 내 처리")
        armDeadline(10_000) {
            if (current(token) && isListening) failListening("음성 서비스 연결 시간이 초과됐어요. 시험 입력으로 흐름을 확인할 수 있어요.")
        }
        try {
            val client = SpeechToText(app, SttMode.ON_DEVICE)
            stt = client
            val listener = object : ResultListener {
                override fun onUpdated(stt: String, completed: Boolean) = post {
                    diagnostic("STT_RESULT_CALLBACK final=$completed current=${current(token)}")
                    if (!current(token) || !isListening) return@post
                    val finalText = transcripts.accept(transcriptToken, stt, completed) ?: return@post
                    Log.i("TutorSpeech", "STT_FINAL_RECEIVED backend=PLEOS_ON_DEVICE")
                    conversation.waitForResponse()
                    cancel()
                    report("Pleos 음성 인식 완료")
                    if (active && !closed) onTranscript(finalText)
                    // The host synchronously submits the reply. Muted/text-only replies still
                    // get a bounded gap; queued or initializing TTS must finish first.
                    if (isConversationActive && !isSpeaking && !isListening && !isWaitingForReply) {
                        conversation.responseFinished(SystemClock.elapsedRealtime())
                        scheduleConversationTurn()
                    }
                }
                override fun onStartedRecognition() = post {
                    if (current(token) && isListening) {
                        Log.i("TutorSpeech", "STT_STARTED backend=PLEOS_ON_DEVICE")
                        report("듣고 있어요. 짧게 말씀해 주세요.")
                    }
                }
                override fun onEndedRecognition() = postCleanup {
                    // Cleanup must outlive active/closed and transcript generation checks.
                    // SDK stop() queues a Binder call. Keep its Binder available during cleanup.
                    // CAAS 4.2.10 does not emit this callback for explicit stop(), so it is optional.
                    diagnostic("STT_ENDED current=${current(token)}")
                    val closing = microphoneClose
                    if (closing?.client === client) {
                        completeMicrophoneClose(closing, acknowledged = true)
                    } else if (stt === client) {
                        sttEnded = true
                        // Some services deliver the final result after the end event.
                        main.postDelayed({
                            if (current(token) && isListening) failListening("말씀을 확인하지 못해 대화를 쉬고 있어요. 대화 시작을 눌러 다시 말해 주세요.")
                        }, 500)
                    }
                }
                override fun onError(errorType: SttError) = post {
                    diagnostic("STT_ERROR type=${errorType.name} current=${current(token)}")
                    if (current(token) && isListening) failListening(sttFailure(errorType))
                }
                override fun onReady() = post {
                    if (current(token) && isListening) {
                        isMicrophoneReady = true
                        microphoneReadyCount++
                        Log.i("TutorSpeech", "STT_READY backend=PLEOS_ON_DEVICE input=MICROPHONE")
                        report("Pleos 듣기 준비됨 · 짧게 말씀해 주세요.")
                    }
                }
                override fun onUpdatedEpdData(on: Long, off: Long) = post {
                    if (current(token) && isListening) diagnostic("STT_EPD onByte=$on offByte=$off")
                }
                override fun onUpdatedRms(rms: Double) = post {
                    if (current(token) && isListening && rms.isFinite()) {
                        rmsCount++
                        rmsMin = minOf(rmsMin, rms)
                        rmsMax = maxOf(rmsMax, rms)
                    }
                }
            }
            sttListener = listener
            client.initialize(object : SttInitializeListener {
                override fun onSuccess() = post {
                    if (!current(token) || stt !== client || !isListening) return@post
                    Log.i("TutorSpeech", "STT_INITIALIZED backend=PLEOS_ON_DEVICE")
                    try {
                        client.addListener(listener)
                        client.setLocale(Locale.KOREA)
                        armDeadline(15_000) {
                            if (current(token) && isListening) failListening("입력 대기 시간이 지나 대화를 마쳤어요. 대화 시작을 누르면 다시 들어요.")
                        }
                        sttRequested = true
                        sttEnded = false
                        client.request()
                    } catch (_: Exception) {
                        failListening("Pleos 마이크를 시작하지 못했어요. 서비스와 권한을 확인해 주세요.")
                    }
                }
                override fun onFailure(errorType: SttError) = post {
                    if (current(token)) failListening(sttFailure(errorType))
                }
            })
        } catch (_: Exception) {
            failListening("Pleos 음성 인식을 연결하지 못했어요. 시험 입력으로 확인할 수 있어요.")
        }
    }

    fun stopListening() = onMain {
        endConversation()
        if (!closed && isListening) {
            cancel()
            report(if (isMicrophoneClosing) "마이크 입력을 종료하고 있어요." else "듣기를 멈췄어요.")
        }
    }

    fun speak(text: String) = onMain {
        if (closed || !active || !enabled || text.isBlank()) return@onMain
        if (isListening && !isConversationActive) return@onMain
        isWaitingForReply = false
        conversation.waitForResponse()
        clearConversationTimer()
        // Fresh vehicle/navigation coaching must not be swallowed by automatic listening.
        // Invalidate the interrupted input, drain capture, then deliver this current cue.
        if (isListening) cancel()
        if (isMicrophoneClosing) {
            // Keep output behind the pending microphone stop/cleanup window.
            pendingAfterMicrophoneClose = text.trim().take(500)
            report("마이크 입력을 마친 뒤 안내할게요.")
            return@onMain
        }
        cancel()
        val token = serial
        val sentence = text.trim().take(500)
        output = sentence
        if (!takeFocus(token, listening = false)) {
            endConversation()
            cancel()
            report("다른 차량 소리가 우선이에요. 안내는 화면에서 확인해 주세요.")
            return@onMain
        }
        report("Pleos 음성 안내 준비 중")
        armDeadline(4_000) { if (current(token)) useAndroid(token, sentence) }
        try {
            val client = PleosTts(app, TtsMode.ON_DEVICE)
            pleosTts = client
            val listener = object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = post {
                    if (current(token) && !usingAndroid) {
                        outputStarted = true
                        Log.i("TutorSpeech", "TTS_STARTED backend=PLEOS_ON_DEVICE")
                        report("Pleos 음성 안내 중 · 기기 내 처리")
                        armOutputDeadline(token)
                    }
                }
                override fun onDone() = post {
                    if (current(token) && !usingAndroid) finishOutput("Pleos 음성 안내 완료", completed = true)
                }
                override fun onStop() = post {
                    if (current(token) && !usingAndroid) finishOutput("음성 안내를 멈췄어요.")
                }
                override fun onError(errorType: TtsError) = post {
                    if (current(token) && !usingAndroid) {
                        Log.i("TutorSpeech", "TTS_ERROR backend=PLEOS_ON_DEVICE error=${errorType.name}")
                        if (outputStarted) finishOutput("Pleos 안내가 중단됐어요. 화면에서 확인해 주세요.")
                        else useAndroid(token, sentence)
                    }
                }
                override fun onUpdatedRms(rms: Double) = Unit
            }
            ttsListener = listener
            client.initialize(object : TtsInitializeListener {
                override fun onSuccess() = post {
                    if (!current(token) || pleosTts !== client || usingAndroid) return@post
                    Log.i("TutorSpeech", "TTS_INITIALIZED backend=PLEOS_ON_DEVICE")
                    try {
                        client.addEventListener(listener)
                        client.setLocale(Locale.KOREA)
                        client.setAudioAttributes(attributes)
                        armDeadline(3_000) { if (current(token) && !outputStarted) useAndroid(token, sentence) }
                        client.speak(sentence)
                    } catch (_: Exception) {
                        useAndroid(token, sentence)
                    }
                }
                override fun onFailure(errorType: TtsError) = post {
                    if (current(token)) {
                        Log.i("TutorSpeech", "TTS_INIT_FAILED backend=PLEOS_ON_DEVICE error=${errorType.name}")
                        useAndroid(token, sentence)
                    }
                }
            })
        } catch (_: Exception) {
            useAndroid(token, sentence)
        }
    }

    fun stop() = onMain {
        if (!closed) {
            endConversation()
            cancel()
            report(if (isMicrophoneClosing) "음성 안내를 멈추고 마이크 입력을 종료하고 있어요." else "음성 입력과 안내를 멈췄어요.")
        }
    }

    /** An asynchronous text response owns this turn; STT must not reopen while it is pending. */
    fun waitForReply() = onMain {
        if (closed || !active) return@onMain
        conversation.waitForResponse()
        clearConversationTimer()
        cancel()
        isWaitingForReply = true
        report("말씀과 차량 정보를 확인하고 있어요.")
    }

    fun completeReplyWait() = onMain {
        if (!isWaitingForReply) return@onMain
        isWaitingForReply = false
        if (active && !closed && isConversationActive && !isSpeaking) {
            conversation.responseFinished(SystemClock.elapsedRealtime())
            scheduleConversationTurn()
        }
    }

    override fun close() = onMain {
        if (!closed) {
            endConversation()
            cancel()
            closed = true
            active = false
            runCatching { androidTts?.shutdown() }
            androidTts = null
            androidReady = false
        }
    }

    private fun useAndroid(token: Long, text: String) {
        if (!current(token) || usingAndroid) return
        usingAndroid = true
        closePleosTts()
        report("Pleos 출력 연결 실패 · Android 음성으로 안내해요.")
        armDeadline(4_000) {
            if (current(token) && !outputStarted) finishOutput("음성 출력을 사용할 수 없어요. 화면 안내를 확인해 주세요.")
        }
        if (androidReady) playAndroid(token, text)
        else if (!androidInitializing) {
            androidInitializing = true
            try {
                androidTts = AndroidTts(app) { status -> post {
                    androidInitializing = false
                    val engine = androidTts
                    androidReady = runCatching {
                        status == AndroidTts.SUCCESS && engine != null &&
                            engine.setLanguage(Locale.KOREA) >= AndroidTts.LANG_AVAILABLE &&
                            engine.setAudioAttributes(attributes) == AndroidTts.SUCCESS
                    }.getOrDefault(false)
                    if (closed) return@post
                    configureAndroidListener()
                    // Initialization may finish after another request replaces this one. Read
                    // only the current, still-bounded request; never replay captured old text.
                    val pendingText = output
                    if (active && usingAndroid && pendingText != null) {
                        if (androidReady) playAndroid(serial, pendingText)
                        else finishOutput("한국어 음성 출력을 사용할 수 없어요. 화면 안내를 확인해 주세요.")
                    }
                } }
            } catch (_: Exception) {
                androidInitializing = false
                finishOutput("음성 출력을 연결하지 못했어요. 화면 안내를 확인해 주세요.")
            }
        }
    }

    private fun configureAndroidListener() {
        androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = post {
                if (matchesAndroid(utteranceId)) {
                    outputStarted = true
                    Log.i("TutorSpeech", "TTS_STARTED backend=ANDROID_FALLBACK")
                    report("Android 음성 안내 중 · Pleos 출력 대체")
                    armOutputDeadline(serial)
                }
            }
            override fun onDone(utteranceId: String?) = post {
                if (matchesAndroid(utteranceId)) finishOutput("Android 음성 안내 완료 · Pleos 출력 대체", completed = true)
            }
            @Deprecated("Android legacy callback")
            override fun onError(utteranceId: String?) = androidError(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = androidError(utteranceId)
        })
    }

    private fun androidError(id: String?) = post {
        if (matchesAndroid(id)) finishOutput("음성 출력에 실패했어요. 화면 안내를 확인해 주세요.")
    }

    private fun playAndroid(token: Long, text: String) {
        if (!current(token) || !usingAndroid || !androidReady) return
        val result = runCatching {
            androidTts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "tutor_$token")
        }.getOrNull()
        if (result != AndroidTts.SUCCESS) finishOutput("음성 출력을 시작하지 못했어요. 화면 안내를 확인해 주세요.")
    }

    private fun matchesAndroid(id: String?) = active && !closed && usingAndroid && output != null && id == "tutor_$serial"
    private fun current(token: Long) = active && !closed && serial == token

    private fun takeFocus(token: Long, listening: Boolean): Boolean {
        val manager = audio ?: return false
        val request = AudioFocusRequest.Builder(if (listening) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (current(token) && change != AudioManager.AUDIOFOCUS_GAIN) {
                    endConversation()
                    cancel()
                    report("차량 소리를 우선해 음성 입력과 안내를 멈췄어요.")
                }
            }, main).build()
        focus = request
        return runCatching { manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
    }

    private fun armOutputDeadline(token: Long) = armDeadline(25_000) {
        if (current(token)) finishOutput("안내 시간이 지나 음성을 멈췄어요.")
    }

    private fun armDeadline(delay: Long, action: () -> Unit) {
        deadline?.let { main.removeCallbacks(it) }
        deadline = Runnable(action).also { main.postDelayed(it, delay) }
    }

    private fun closePleosTts() {
        val client = pleosTts
        val listener = ttsListener
        pleosTts = null
        ttsListener = null
        if (client != null) {
            if (listener != null) runCatching { client.removeEventListener(listener) }
            runCatching { client.stop() }
            runCatching { client.release() }
        }
    }

    private fun cancel() {
        if (isListening) {
            // SDK RMS values are signal amplitude, not dB or speech-confidence scores.
            diagnostic("STT_INPUT_SUMMARY rmsCallbacks=$rmsCount min=${rmsMin.takeIf { it.isFinite() }} max=${rmsMax.takeIf { it.isFinite() }}")
        }
        serial++
        isWaitingForReply = false
        transcripts.cancel()
        isListening = false
        isMicrophoneReady = false
        rmsCount = 0; rmsMin = Double.POSITIVE_INFINITY; rmsMax = Double.NEGATIVE_INFINITY
        deadline?.let { main.removeCallbacks(it) }
        deadline = null
        pendingAfterMicrophoneClose = null
        val input = stt
        val listener = sttListener
        val needsStopDrain = sttRequested && !sttEnded
        stt = null
        sttListener = null
        sttRequested = false
        sttEnded = false
        if (input != null) {
            if (needsStopDrain) beginMicrophoneClose(input, listener)
            else releaseMicrophone(input, listener)
        }
        closePleosTts()
        runCatching { androidTts?.stop() }
        focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
        focus = null
        output = null
        outputStarted = false
        usingAndroid = false
    }

    private fun beginMicrophoneClose(client: SpeechToText, listener: ResultListener?) {
        check(microphoneClose == null) { "Previous microphone cleanup must finish before another request" }
        val closing = MicrophoneClose(client, listener)
        microphoneClose = closing
        diagnostic("STT_STOP_REQUESTED binderDrainMillis=1000")
        runCatching { client.stop() }
        // Pleos 3.3.1 stop() queues its Binder call. Immediate release() can erase that
        // Binder before close executes. CAAS 4.2.10 does not acknowledge explicit stop
        // through onEndedRecognition. Keep the Binder alive for this bounded drain and
        // serialize new input/output behind it. 1 s is an emulator-tested workaround,
        // not an SDK timing guarantee or proof that hardware capture has stopped.
        microphoneCloseDeadline = Runnable {
            if (microphoneClose === closing) completeMicrophoneClose(closing, acknowledged = false)
        }.also { main.postDelayed(it, 1_000) }
    }

    private fun completeMicrophoneClose(closing: MicrophoneClose, acknowledged: Boolean) {
        if (microphoneClose !== closing) return
        microphoneCloseDeadline?.let { main.removeCallbacks(it) }
        microphoneCloseDeadline = null
        microphoneClose = null
        microphoneCleanupCount++
        releaseMicrophone(closing.client, closing.listener)
        diagnostic("STT_STOP_CLEANUP_COMPLETE endedCallback=$acknowledged hardwareStopConfirmed=false")
        val pending = pendingAfterMicrophoneClose
        pendingAfterMicrophoneClose = null
        if (!closed && active) {
            if (pending != null && enabled) speak(pending)
            else {
                report("음성 연결 정리를 마쳤어요. 다시 말할 수 있어요.")
                scheduleConversationTurn()
            }
        }
    }

    private fun releaseMicrophone(client: SpeechToText, listener: ResultListener?) {
        if (listener != null) runCatching { client.removeListener(listener) }
        runCatching { client.release() }
    }

    private fun failListening(message: String) {
        endConversation()
        cancel()
        report(message)
    }

    private fun finishOutput(message: String, completed: Boolean = false) {
        if (!completed) endConversation()
        cancel()
        report(message)
        if (completed && isConversationActive) {
            conversation.responseFinished(SystemClock.elapsedRealtime())
            scheduleConversationTurn()
        }
    }

    private fun clearConversationTimer() {
        nextConversationTurn?.let { main.removeCallbacks(it) }
        nextConversationTurn = null
    }

    private fun endConversation() {
        val wasActive = conversation.isActive
        conversation.stop()
        isWaitingForReply = false
        clearConversationTimer()
        if (wasActive) onConversationStopped()
    }

    private fun scheduleConversationTurn() {
        clearConversationTimer()
        if (!active || closed) return
        val ticket = conversation.ticket() ?: return
        if (isSpeaking || isMicrophoneClosing || isListening || isWaitingForReply) return
        report("곧 다시 들어요. 듣기 준비가 되면 이어서 말씀해 주세요.")
        nextConversationTurn = Runnable {
            nextConversationTurn = null
            if (active && !closed && conversation.claim(ticket, SystemClock.elapsedRealtime(),
                    inputBusy = isListening || isMicrophoneClosing, outputBusy = isSpeaking)) {
                diagnostic("CONVERSATION_NEXT_TURN")
                beginListening()
            }
        }.also { main.postDelayed(it, (ticket.earliestMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
    }

    private fun sttFailure(error: SttError): String = when (error) {
        SttError.PERMISSION_DENIED -> "Pleos 음성 권한이 없어요. STT_SERVICE 제공 여부를 확인해 주세요."
        SttError.UNAVAILABLE_LANGUAGE -> "기기 내 한국어 인식이 지원되지 않아요. 시험 입력을 사용할 수 있어요."
        SttError.MIC_ERROR, SttError.AUDIO_ERROR -> "마이크를 사용할 수 없어요. 에뮬레이터 마이크 설정을 확인해 주세요."
        SttError.TIMEOUT -> "말씀을 확인하지 못해 대화를 마쳤어요. 대화 시작을 눌러 다시 말해 주세요."
        else -> "Pleos 음성 인식을 사용할 수 없어요 (${error.name}). 시험 입력으로 확인해 주세요."
    }

    private fun report(message: String) {
        if (closed || statusText == message) return
        statusText = message
        Log.d("TutorSpeech", message)
        onStatus(message)
    }
    /** Debug-only lifecycle/signal metadata. Never logs audio or recognized sentences. */
    private fun diagnostic(message: String) { if (debugDiagnostics) Log.i("TutorSpeech", message) }
    private fun post(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun postCleanup(block: () -> Unit) { main.post(block) }
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else post(block) }
}
