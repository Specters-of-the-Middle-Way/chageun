package kr.drivestep.tutor.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
 * Explicit, bounded ON_DEVICE speech turns. No wake word, background microphone, server
 * registration, raw audio recording or transcript logging. The host owns the parked UX gate.
 * Pleos failures are visible; Android Korean TTS is a separate, labelled output fallback.
 * Dynamic responses do not use the legacy CoachCue/WAV catalogue.
 */
class TutorSpeechController(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val transcripts = TranscriptGate()
    private var active = false
    private var enabled = true
    private var closed = false
    private var serial = 0L
    private var focus: AudioFocusRequest? = null
    private var deadline: Runnable? = null
    private var stt: SpeechToText? = null
    private var sttListener: ResultListener? = null
    private var pleosTts: PleosTts? = null
    private var ttsListener: EventListener? = null
    private var androidTts: AndroidTts? = null
    private var androidInitializing = false
    private var androidReady = false
    private var output: String? = null
    private var outputStarted = false
    private var usingAndroid = false

    var isListening: Boolean = false
        private set
    var statusText: String = "듣기를 누르면 Pleos 음성 인식을 시작해요."
        private set

    fun setActive(value: Boolean) = onMain {
        if (closed || active == value) return@onMain
        active = value
        if (!value) {
            cancel()
            report("음성 입력과 안내를 쉬고 있어요.")
        }
    }

    /** Mutes output; explicit speech input can still be used with the on-screen response. */
    fun setEnabled(value: Boolean) = onMain {
        if (closed) return@onMain
        enabled = value
        if (!value && !isListening) cancel()
    }

    fun startListening() = onMain {
        if (closed || !active) return@onMain
        cancel() // Cancels output before opening the microphone: no self-recognition.
        val token = serial
        val transcriptToken = transcripts.begin()
        isListening = true
        if (!takeFocus(token, listening = true)) {
            cancel()
            report("다른 차량 소리가 우선이에요. 잠시 뒤 다시 눌러 주세요.")
            return@onMain
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
                    if (!current(token) || !isListening) return@post
                    val finalText = transcripts.accept(transcriptToken, stt, completed) ?: return@post
                    Log.i("TutorSpeech", "STT_FINAL_RECEIVED backend=PLEOS_ON_DEVICE")
                    cancel()
                    report("Pleos 음성 인식 완료")
                    if (active && !closed) onTranscript(finalText)
                }
                override fun onStartedRecognition() = post {
                    if (current(token) && isListening) {
                        Log.i("TutorSpeech", "STT_STARTED backend=PLEOS_ON_DEVICE")
                        report("듣고 있어요. 짧게 말씀해 주세요.")
                    }
                }
                override fun onEndedRecognition() = post {
                    // Some services deliver the final result immediately after the end event.
                    main.postDelayed({
                        if (current(token) && isListening) failListening("말씀을 확인하지 못했어요. 듣기를 다시 눌러 주세요.")
                    }, 500)
                }
                override fun onError(errorType: SttError) = post {
                    if (current(token) && isListening) failListening(sttFailure(errorType))
                }
                override fun onReady() = post {
                    if (current(token) && isListening) {
                        Log.i("TutorSpeech", "STT_READY backend=PLEOS_ON_DEVICE input=MICROPHONE")
                        report("Pleos 듣기 준비됨 · 짧게 말씀해 주세요.")
                    }
                }
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onUpdatedRms(rms: Double) = Unit
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
                            if (current(token) && isListening) failListening("듣기를 마쳤어요. 필요하면 다시 눌러 주세요.")
                        }
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
        if (!closed && isListening) {
            cancel()
            report("듣기를 멈췄어요.")
        }
    }

    fun speak(text: String) = onMain {
        if (closed || !active || !enabled || text.isBlank() || isListening) return@onMain
        cancel()
        val token = serial
        val sentence = text.trim().take(500)
        output = sentence
        if (!takeFocus(token, listening = false)) {
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
                    if (current(token) && !usingAndroid) finishOutput("Pleos 음성 안내 완료")
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
            cancel()
            report("음성 입력과 안내를 멈췄어요.")
        }
    }

    override fun close() = onMain {
        if (!closed) {
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
                if (matchesAndroid(utteranceId)) finishOutput("Android 음성 안내 완료 · Pleos 출력 대체")
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
        serial++
        transcripts.cancel()
        isListening = false
        deadline?.let { main.removeCallbacks(it) }
        deadline = null
        val input = stt
        val listener = sttListener
        stt = null
        sttListener = null
        if (input != null) {
            if (listener != null) runCatching { input.removeListener(listener) }
            runCatching { input.stop() }
            runCatching { input.release() }
        }
        closePleosTts()
        runCatching { androidTts?.stop() }
        focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
        focus = null
        output = null
        outputStarted = false
        usingAndroid = false
    }

    private fun failListening(message: String) { cancel(); report(message) }
    private fun finishOutput(message: String) { cancel(); report(message) }

    private fun sttFailure(error: SttError): String = when (error) {
        SttError.PERMISSION_DENIED -> "Pleos 음성 권한이 없어요. STT_SERVICE 제공 여부를 확인해 주세요."
        SttError.UNAVAILABLE_LANGUAGE -> "기기 내 한국어 인식이 지원되지 않아요. 시험 입력을 사용할 수 있어요."
        SttError.MIC_ERROR, SttError.AUDIO_ERROR -> "마이크를 사용할 수 없어요. 에뮬레이터 마이크 설정을 확인해 주세요."
        SttError.TIMEOUT -> "말씀을 확인하지 못했어요. 듣기를 다시 눌러 주세요."
        else -> "Pleos 음성 인식을 사용할 수 없어요 (${error.name}). 시험 입력으로 확인해 주세요."
    }

    private fun report(message: String) {
        if (closed || statusText == message) return
        statusText = message
        Log.d("TutorSpeech", message)
        onStatus(message)
    }
    private fun post(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else post(block) }
}
