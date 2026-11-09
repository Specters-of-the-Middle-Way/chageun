# Skill 01 — Architecture & Clean Android Lint Rules

## 1. Package Structure (`kr.drivestep.tutor`)

Preserve the root package `kr.drivestep.tutor` and its domain-oriented subpackages:

```text
app/src/main/java/kr/drivestep/tutor/
├── MainActivity.kt          # Host activity, screen routing, lifecycle & ParkedGate wiring
├── ParkedGate.kt            # CarUxRestrictionsManager + parked safety gate
├── SteeringFeature.kt       # Coordinator for steering SDK practice & persistence
├── design/                  # Pleos Connect official vector adapters (PleosUi, PleosSwitch, PracticeButton)
├── journey/                 # 11-page flowchart state (JourneyState), UI (DriverJourneyView), Canvas (PostureGuideView)
├── parking/                 # Reverse & parallel parking physics (ParkingSimulationSession) & Canvas (ParkingSceneView)
├── simulator/               # Driver-POV lane-change simulator (DrivingSimulationSession, DriverSceneView)
├── contextcoach/            # Speed-delta & lane-warning coaching engine (SituationCoachEngine, SituationCoachView)
├── vehicle/                 # AAOS + Pleos Vehicle SDK 2.3.2 sources & 2-Tier timeout policy (VehicleTimeoutPolicy)
├── practice/                # Turn-signal & steering practice state machines (PracticeSession, SteeringPracticeSession)
├── coach/                   # CoachCue catalogs & TTS/WAV voice engine (VoiceCoach)
├── ride/                    # Stationary pedal, gear & steering familiarization
├── growth/                  # SetupGuides & traffic-sense curriculum (GrowthSession)
├── tutor/                   # Foundational parking concept lessons
└── data/                    # Local repositories (PracticeRepository SQLite, SteeringLogStore AtomicFile JSON)
```

---

## 2. The 3-File Modular Pattern (`*Session`/`*State` + `*View` + `*Test`)

Every feature module MUST strictly separate pure state/physics logic from Android UI rendering and pair them with a deterministic JVM unit test:

1. **Pure Domain State / Session (`*Session.kt` or `*State.kt`)**
   - Zero `android.view.*` or `android.widget.*` imports.
   - Deterministic transitions driven by explicit method calls or immutable input value objects (`accept(reading, nowElapsedMillis)`, `step(dtSeconds)`, `setCalibration(axis, value)`).
   - Emits immutable `*Snapshot` data classes for UI consumption.

2. **Programmatic Android View / Canvas (`*View.kt`)**
   - Renders the latest `*Snapshot` using `PleosUi` color/icon tokens, `PleosSwitch`, `PracticeButton`, and allocation-free `Canvas` drawing.
   - Dispatches user actions back to the `*Session`/`*State` and triggers `render()` or `invalidate()`.

3. **Pure JVM Unit Test (`app/src/test/.../*Test.kt`)**
   - Tests all state transitions, boundary conditions, safety interruptions, and invalidation rules without requiring an Android device or emulator.

> **Hotspot Prevention Rule:** Keep `MainActivity.kt` as a thin host router. Never embed domain state rules, physics calculations, or complex layout trees directly inside `MainActivity.kt`.

---

## 3. The 8 Iron Rules for Clean Android Lint (Zero Warnings)

`:app:lintDebug` MUST pass with **0 errors and 0 warnings**. Always obey these 8 rules:

### Rule 1: No `resources.getIdentifier(...)` (`DiscouragedApi`)
- Never look up resources dynamically by string name at runtime.
- For fonts, use `resources.getFont(R.font.asta_sans)` with a `runCatching` fallback to `Typeface.DEFAULT`.
- For bundled voice clips, read `@array/coach_voice_resources` (`res/values/coach_voice_resources.xml`) via `resources.obtainTypedArray(R.array.coach_voice_resources)` and always call `.recycle()` in a `finally` block.

### Rule 2: Zero Object Allocations in `onDraw(canvas: Canvas)` (`DrawAllocation`)
- Never instantiate `Paint`, `RectF`, `Path`, `DashPathEffect`, `LinearGradient`, or collections (`listOf(...)`, `ArrayList`) inside `onDraw` or any helper method invoked by `onDraw`.
- Pre-allocate all drawing primitives as `private val` fields on the `View` class and mutate/reset them (`rect.set(...)`, `path.rewind()`) during `onDraw`.

### Rule 3: Touch Accessibility via `PracticeButton` (`ClickableViewAccessibility`)
- Never attach `setOnTouchListener` to a plain `Button` or `View` without overriding `performClick()`.
- Always use `kr.drivestep.tutor.design.PracticeButton` for hold-to-actuate controls (e.g., virtual accelerator/brake pedals) and standard interactive buttons.

### Rule 4: Programmatic Custom Views Must Annotate `@SuppressLint("ViewConstructor")`
- Custom `View` or `LinearLayout` subclasses that take required host/session parameters in their constructor and are never inflated from XML must include:
  ```kotlin
  // Created in code with required host/session arguments; never inflated from XML.
  @SuppressLint("ViewConstructor")
  ```

### Rule 5: Resource-Formatted Numeric/Unit Text (`SetTextI18n`)
- When assigning formatted number + unit strings to a `TextView` where Lint flags `SetTextI18n`, use string resources from `app/src/main/res/values/strings.xml` (e.g., `context.getString(R.string.journey_slider_value, value, unit)`).

### Rule 6: No Unreferenced Drawables in `app/src/main/res/drawable/` (`UnusedResources`)
- Only keep drawables actively referenced in code (`PleosUi`, `PleosSwitch`) or XML inside `app/src/main/res/drawable/`.
- Archive unused official Pleos vector assets under `third_party/pleos/unused-drawables/`.

### Rule 7: Manifest Backup & Extraction Hardening (`DataExtractionRules`)
- Preserve `android:allowBackup="false"`, `android:fullBackupContent="false"`, and `android:dataExtractionRules="@xml/data_extraction_rules"` in `AndroidManifest.xml`.

### Rule 8: Voice Resource Array Synchronization
- Every audio file in `app/src/main/res/raw/coach_*.wav` must have a matching `<item>@raw/coach_*</item>` entry in `app/src/main/res/values/coach_voice_resources.xml` so Lint never reports `UnusedResources` on raw audio assets.
