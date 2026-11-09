# Skill 05 — Local Storage Schemas, Voice Assets & Korean Copy Rules

## v0.10 Additional Lesson History and Speech

`lessoncoach/LessonCoachStore` adds an app-private AtomicFile `files/lesson-coach-history.json`, schema version 1, up to 20 sessions. The current session retains at most 160 recent events (the file decoder bounds at 200). Save only with current record consent. Keep driver reports, SDK observations, navigation events and simulation distinct; no raw audio is saved by the app. Platform/Speech SDK logs may still contain transcripts in a development image.

Dynamic lesson replies use `TutorSpeechController` rather than the legacy `CoachCue` catalogue. They do not require a new WAV entry and must not pretend to have a bundled audio fallback. The existing 194-cue synchronization rule still applies to legacy CoachCue edits.

## 1. Six Local Storage Repositories (Zero Cloud Upload)

All user records are stored strictly inside the current Android user's app-private storage (`allowBackup="false"`). Never mix SDK signal logs with simulated exercise logs.

| # | Storage Medium & File | Owner Class | Stored Schema & Retention Policy |
|---|---|---|---|
| **1** | **SQLite** (`drivestep-practice.db`) | `data/PracticeRepository.kt` | Tables `practice_sessions` and `practice_events` for real Pleos SDK Turn-Signal practice sessions (`PracticeSnapshot`). Requires `recordConsent && vehicleConsent`. |
| **2** | **AtomicFile JSON** (`files/steering-practice.json`) | `data/SteeringLogStore.kt` | JSON array of up to **20** real Pleos SDK Steering practice sessions (`SteeringPracticeSnapshot`). Uses `android.util.AtomicFile` for crash-safe writes. |
| **3** | **SharedPreferences** (`drivestep_journey_v08`) | `journey/DriverJourneyView.kt` | Stores `DriverProfile` (`heightCm`, `weightKg`, `experience`, `confidence`, `guidance`, `difficulty`), `VirtualSeatCalibration` (`foreAft`, `height`, `backrest`, `steering`), `setup_0..8` booleans, `record_consent`, `vehicle_consent`, `education_completed`, and `self_reviews`. Note: **`preflight_0..6` is NEVER persisted across visits.** |
| **4** | **SharedPreferences** (`drivestep_parking_simulator_v1`) | `parking/ParkingSimulationView.kt` | JSON array (`records`) of up to **20** virtual parking sessions (`ParkingRecord`: `course`, `completed`, `insideTarget`, `lateralErrorMeters`, `longitudinalErrorMeters`, `angleErrorDegrees`, `collisionCount`, `feeling`, `source="SIMULATION"`). |
| **5** | **SharedPreferences** (`drivestep_driving_simulator_v1`) | `simulator/DrivingSimulatorView.kt` | JSON array (`records`) of up to **20** virtual lane-change sessions (`DrivingRecord`: `course`, `completed`, `signalUsed`, `mirrorChecked`, `safeGapObserved`, `feeling`, `source="SIMULATION"`). |
| **6** | **SharedPreferences** (`drivestep_situation_coach_v1`) | `contextcoach/SituationCoachView.kt` | JSON array (`records`) of up to **20** detected situation events (`SituationEvent` + driver reflection cause). |

### Consent & Scoped Deletion Rules
- Turning off `recordConsent` immediately stops new record writes without silently wiping historical records.
- Deleting records in a specific module (`journey`, `parking`, `simulator`, `contextcoach`, `data`) must only delete that module's scoped store after user confirmation (`취소` / `삭제`).

---

## 2. Three-Way Voice Asset Synchronization (`VoiceCoach.kt`)

Chageun uses Korean TTS (`TextToSpeech`, `Locale.KOREA`) with an automatic offline fallback to **194 bundled WAV files** (`app/src/main/res/raw/coach_*.wav`).

Whenever you add, rename, or remove a `CoachCue(id, text)`:
1. **Code Catalog:** Ensure `id` matches `Regex("[a-z][a-z0-9_]*")` and is unique across its catalog.
2. **Raw WAV File:** Place or update `app/src/main/res/raw/coach_<id>.wav` and `docs/*-voice-cues.json`.
3. **TypedArray XML Index:** Register `<item>@raw/coach_<id></item>` in `app/src/main/res/values/coach_voice_resources.xml`.
   - Missing `<item>` entries cause `VoiceCoach` fallback lookup to return `0` AND trigger an `UnusedResources` Android Lint warning!

---

## 3. Korean Copywriting & Terminology Standards

- **Tone:** Warm, calm, encouraging passenger-seat coach (`~해요`, `~해 볼까요?`, `~를 확인하세요`). Never scold, blame, or use alarming language.
- **Standardized Automotive Terms:**
  - Always use **`룸미러`** (never `"백미러"`).
  - Always use **`사이드미러`**, **`방향지시등`**, **`비상등`**, **`주차 브레이크`**, **`헤드레스트`**.
  - Always refer to SDK steering angle as **`앞바퀴 조향각`** or **`전륜 조향각`** (never `"핸들 회전각"` or `"핸들 회전수"`).
