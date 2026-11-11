package kr.drivestep.tutor.speech

/** Successful playback needs no persistent result message; controller events stay unchanged. */
object SpeechStatusText {
    fun forDisplay(status: String): String = when (status) {
        "Pleos 음성 안내 완료", "Android 음성 안내 완료 · Pleos 출력 대체" -> ""
        else -> status
    }
}
