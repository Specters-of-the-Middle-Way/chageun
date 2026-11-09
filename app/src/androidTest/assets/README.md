# Speech SDK smoke-test audio

`lesson_left_16khz_mono_s16le.pcm` is synthetic Korean speech saying
“왼쪽 차로로 변경하고 싶어요.” It contains no participant recording.

- Generated locally with macOS `say -v Yuna -r 160`.
- Signed 16-bit little-endian PCM, 16,000 Hz, mono, no WAV header.
- 0.5 seconds of leading and 1 second of trailing silence.
- Used only by `PleosSpeechSmokeTest` through the documented ON_DEVICE
  `sendAudio()` / `completeAudioSend()` interface.
- Passing this fixture tests one supplied audio sample, not microphone routing,
  cabin-noise performance, accent coverage, or general recognition accuracy.

## Observed recognition limitation

On 2026-11-10, Pleos CAAS `4.2.10-coral-aosp` with SpeechToText SDK `3.3.1`
decoded this **left** phrase as **“오른쪽 차로로 변경하고 싶어요”** (right).
The original audio is retained. The integration test therefore verifies that a
real SDK result is treated as an unconfirmed direction, read back to the driver,
and rejected without activating that direction when the driver says “아니야”.
This is a confirmation-boundary test, not a passing direction-accuracy result.

Source: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data
