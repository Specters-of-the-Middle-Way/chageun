package kr.drivestep.tutor.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks the current parked-practice cue. The caller must setActive(false) onStop and
 * when driving restrictions become active. No cue is queued for later focus/TTS recovery.
 * A packaged coach_<cue.id> WAV is used when Korean TTS is unavailable or fails.
 */
class VoiceCoach(context: Context, private val onStatus: (String) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var active = false
    private var enabled = true
    private var closed = false
    private var serial = 0L
    private var current: Playback? = null
    private var lastCueKey: Pair<String, String>? = null
    private var lastStatus: String? = null

    private enum class Backend { TTS, BUNDLE }
    private class Playback(val cue: CoachCue, val utteranceId: String) {
        var backend = Backend.TTS
        var focus: AudioFocusRequest? = null
        var player: MediaPlayer? = null
        var timeout: Runnable? = null
    }

    init {
        // Post even on the main thread: the owner's constructor can finish before callbacks.
        main.post {
            if (!closed) {
                initializeTts()
                reportAvailability()
            }
        }
    }

    fun setActive(value: Boolean) = onMain {
        if (!closed) {
            active = value
            if (!value) cancelCurrent(clearDuplicate = true)
            reportAvailability()
        }
    }

    fun setEnabled(value: Boolean) = onMain {
        if (!closed) {
            enabled = value
            if (!value) cancelCurrent(clearDuplicate = true)
            reportAvailability()
        }
    }

    fun speak(cue: CoachCue) = onMain {
        if (!closed && active && enabled && cue.text.isNotBlank()) {
            val key = cue.id to cue.text
            if (key != lastCueKey) {
                cancelCurrent(clearDuplicate = false)
                lastCueKey = key
                val playback = Playback(cue, "coach_${++serial}")
                current = playback
                if (takeFocus(playback)) {
                    if (ttsReady) playTts(playback) else playBundle(playback)
                } else {
                    finish(playback)
                    report("다른 소리 우선 · 화면 안내 사용")
                }
            }
        }
    }

    /** Cancels current and pending playback. It never resumes an interrupted instruction. */
    fun stop() = onMain {
        if (!closed) {
            cancelCurrent(clearDuplicate = true)
            reportAvailability()
        }
    }

    override fun close() = onMain {
        if (!closed) {
            closed = true
            active = false
            cancelCurrent(clearDuplicate = true)
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
        }
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(app) { status ->
                // Some engines report initialization from a Binder thread or synchronously.
                main.post {
                    if (!closed) configureTts(status)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "TTS initialization unavailable; using packaged voice", error)
            ttsReady = false
        }
    }

    private fun configureTts(status: Int) {
        val engine = tts
        ttsReady = try {
            engine != null && status == TextToSpeech.SUCCESS &&
                engine.isLanguageAvailable(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE &&
                engine.setLanguage(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE &&
                engine.setAudioAttributes(attributes) == TextToSpeech.SUCCESS
        } catch (error: Exception) {
            Log.w(TAG, "Korean TTS unavailable; using packaged voice", error)
            false
        }
        if (ttsReady && engine != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = onMain {
                    findTtsPlayback(utteranceId)?.let { playback ->
                        Log.i(TAG, "Playback started: tts cue=${playback.cue.id}")
                        armTimeout(playback, PLAYBACK_TIMEOUT_MS) {
                            finish(playback)
                            report("음성 응답 지연 · 화면 안내 사용")
                        }
                    }
                }

                override fun onDone(utteranceId: String?) = onMain {
                    findTtsPlayback(utteranceId)?.let(::finish)
                }

                @Deprecated("Legacy engine callback")
                override fun onError(utteranceId: String?) = handleTtsError(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) =
                    handleTtsError(utteranceId)
            })
        }
        // Initialization only changes the backend for future cues. Never replay old cues.
        if (current == null) reportAvailability()
    }

    private fun handleTtsError(utteranceId: String?) = onMain {
        findTtsPlayback(utteranceId)?.let { playback ->
            Log.w(TAG, "TTS failed: cue=${playback.cue.id}; using packaged voice")
            ttsReady = false
            playBundle(playback)
        }
    }

    private fun findTtsPlayback(id: String?): Playback? = current?.takeIf {
        !closed && active && enabled && it.utteranceId == id && it.backend == Backend.TTS
    }

    private fun playTts(playback: Playback) {
        if (!isCurrent(playback)) return
        playback.backend = Backend.TTS
        report("한국어 음성 안내")
        if (!isCurrent(playback)) return
        // A stalled engine must not speak an obsolete instruction much later.
        armTimeout(playback, START_TIMEOUT_MS) {
            ttsReady = false
            playBundle(playback)
        }
        val result = try {
            // Keep the silence and speech in one TTS queue; FLUSH on speak would discard it.
            val engine = tts
            val silence = engine?.playSilentUtterance(
                PRE_ROLL_MS, TextToSpeech.QUEUE_FLUSH, "${playback.utteranceId}_preroll"
            )
            if (silence == TextToSpeech.SUCCESS) {
                engine?.speak(playback.cue.text, TextToSpeech.QUEUE_ADD, null, playback.utteranceId)
            } else TextToSpeech.ERROR
        } catch (error: Exception) {
            Log.w(TAG, "TTS speak unavailable", error)
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS && isCurrent(playback) && playback.backend == Backend.TTS) {
            ttsReady = false
            playBundle(playback)
        }
    }

    private fun playBundle(playback: Playback) {
        if (!isCurrent(playback)) return
        // Ignore any delayed TTS callbacks before stopping its request.
        playback.backend = Backend.BUNDLE
        runCatching { tts?.stop() }
        clearTimeout(playback)
        releasePlayer(playback)
        val resource = if (RESOURCE_ID.matches(playback.cue.id)) {
            app.resources.getIdentifier("coach_${playback.cue.id}", "raw", app.packageName)
        } else 0
        if (resource == 0) {
            finish(playback)
            report("음성 파일 없음 · 화면 안내 사용")
            return
        }
        try {
            val player = MediaPlayer()
            playback.player = player
            player.setAudioAttributes(attributes)
            requireNotNull(app.resources.openRawResourceFd(resource)).use { file ->
                player.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            player.setOnPreparedListener {
                if (isCurrent(playback) && playback.player === it) {
                    try {
                        it.start()
                        Log.i(TAG, "Playback started: bundle cue=${playback.cue.id}")
                        report("기본 음성 안내")
                        armTimeout(playback, PLAYBACK_TIMEOUT_MS) {
                            finish(playback)
                            report("음성 응답 지연 · 화면 안내 사용")
                        }
                    } catch (error: Exception) {
                        failBundle(playback, error)
                    }
                }
            }
            player.setOnCompletionListener { if (isCurrent(playback)) finish(playback) }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Packaged voice error: $what/$extra")
                if (isCurrent(playback)) failBundle(playback)
                true
            }
            armTimeout(playback, START_TIMEOUT_MS) { failBundle(playback) }
            player.prepareAsync()
        } catch (error: Exception) {
            failBundle(playback, error)
        }
    }

    private fun failBundle(playback: Playback, error: Exception? = null) {
        if (!isCurrent(playback)) return
        Log.w(TAG, "Packaged voice unavailable: cue=${playback.cue.id}", error)
        finish(playback)
        report("음성 재생 불가 · 화면 안내 사용")
    }

    private fun takeFocus(playback: Playback): Boolean {
        val manager = audioManager ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && isCurrent(playback)) {
                    // Do not resume: an old steering-related instruction can become misleading.
                    finish(playback)
                    report("다른 소리 우선 · 화면 안내 사용")
                }
            }, main)
            .build()
        playback.focus = request
        return try {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (error: Exception) {
            Log.w(TAG, "Audio focus unavailable", error)
            false
        }
    }

    private fun armTimeout(playback: Playback, delayMillis: Long, action: () -> Unit) {
        clearTimeout(playback)
        val task = Runnable { if (isCurrent(playback)) action() }
        playback.timeout = task
        main.postDelayed(task, delayMillis)
    }

    private fun clearTimeout(playback: Playback) {
        playback.timeout?.let(main::removeCallbacks)
        playback.timeout = null
    }

    private fun isCurrent(playback: Playback) = !closed && active && enabled && current === playback

    private fun finish(playback: Playback) {
        if (current !== playback) return
        current = null // Invalidate callbacks before stop/release can produce them.
        clearTimeout(playback)
        runCatching { tts?.stop() }
        releasePlayer(playback)
        playback.focus?.let { request -> runCatching { audioManager?.abandonAudioFocusRequest(request) } }
        playback.focus = null
    }

    private fun releasePlayer(playback: Playback) {
        playback.player?.let { player ->
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.release() }
        }
        playback.player = null
    }

    private fun cancelCurrent(clearDuplicate: Boolean) {
        current?.let(::finish)
        if (clearDuplicate) lastCueKey = null
    }

    private fun reportAvailability() = report(when {
        !enabled -> "음성 꺼짐"
        !active -> "음성 대기"
        ttsReady -> "한국어 음성 안내"
        else -> "기본 음성 안내"
    })

    private fun report(message: String) {
        if (!closed && message != lastStatus) {
            lastStatus = message
            onStatus(message)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private companion object {
        const val TAG = "VoiceCoach"
        const val PRE_ROLL_MS = 200L
        const val START_TIMEOUT_MS = 3_000L
        const val PLAYBACK_TIMEOUT_MS = 25_000L
        val RESOURCE_ID = Regex("[a-z0-9_]+")
    }
}
