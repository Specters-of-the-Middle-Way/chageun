# Chageun (DriveStep) — AI Agent Skills Index

This `/skills` directory contains the authoritative architectural, domain, SDK, storage, and quality-assurance specifications for **Chageun (`차근`, `kr.drivestep.tutor`)**, an Android Automotive / Pleos Connect driving coach prototype (`v0.9.0+`).

AI coding agents (**Codex**, **Antigravity**, and peer subagents) MUST read this index first and load only the skill files relevant to their current task to maximize token efficiency and preserve zero-warning build stability.

---

## Quick Project Metadata

| Property | Value |
|---|---|
| **App Name** | `차근` (`@string/app_name`) |
| **Root Package** | `kr.drivestep.tutor` |
| **Gradle Project Name** | `DriveStep` |
| **SDK / Build Config** | `minSdk = 28`, `targetSdk = 35`, `compileSdk = 35`, JDK 17, Kotlin 2.0.0, AGP 8.8.2 |
| **Vehicle Libraries** | `useLibrary("android.car")` + `implementation("ai.pleos.playground:Vehicle:2.3.2")` |
| **UI Stack** | Programmatic Android `View` / `LinearLayout` / `Canvas` + Official Pleos Vector Adapters (`PleosUi`, `PleosSwitch`, `PracticeButton`) — **No Jetpack Compose** |
| **Target Vehicle Spec** | Hyundai IONIQ 3 European Standard Body (`4,430 × 1,870 × 1,560 mm`, wheelbase `2,740 mm`, turning radius `5.32 m`) |

---

## Skill Catalog & Task Routing

Read the specific skill document(s) matching your task before editing code:

1. **[`01-architecture-and-clean-lint.md`](./01-architecture-and-clean-lint.md)**
   - **When to read:** Creating or modifying any Kotlin class, Android `View`, `Canvas`, or XML resource.
   - **Covers:** Package map (`design/`, `journey/`, `parking/`, `simulator/`, `contextcoach/`, `vehicle/`, `practice/`, `coach/`, `data/`), the **3-File Modular Pattern (`*Session`/`*State` + `*View` + `*Test`)**, and the **8 Iron Rules for Zero-Warning Android Lint**.

2. **[`02-domain-guardrails-and-flowchart.md`](./02-domain-guardrails-and-flowchart.md)**
   - **When to read:** Implementing or modifying user flows, safety gates, checklists, driving/parking coaching, or post-drive review logic.
   - **Covers:** The 5 Non-Negotiable Safety Principles, `ParkedGate` (`P` gear + `speed == 0` + UX restriction check), the 11-Page Flowchart state machine (`JourneyState`), and strict separation of **Learning Completion / Confidence / Mastery**.

3. **[`03-body-profile-and-posture-canvas.md`](./03-body-profile-and-posture-canvas.md)**
   - **When to read:** Working on `DriverProfile`, `VirtualSeatCalibration`, `PostureGuideBand`, `PostureGuideView`, or the 9-step posture/mirror setup screen.
   - **Covers:** Height/weight (`heightCm`, `weightKg`) reference guide band calculation, interactive `Canvas` posture & mirror visualization (`PostureGuideView`), neutral `50` calibration rules, and automatic mirror invalidation (`steps 5..7`) when seat or steering changes.

4. **[`04-pleos-sdk-and-2tier-timeout.md`](./04-pleos-sdk-and-2tier-timeout.md)**
   - **When to read:** Touching `vehicle/`, `practice/`, `contextcoach/`, or `coach/VoiceCoach.kt`.
   - **Covers:** AAOS (`android.car`) vs. Pleos Vehicle SDK (`2.3.2`) API priority, the **2-Tier Vehicle I/O Timeout Policy** (`VehicleTimeoutPolicy` — 10s parked connection vs. 1.5s–2.5s active maneuver deadlines), `isInitial = true` snapshot isolation, and signal provenance (`SIMULATION` vs. `SDK`).

5. **[`05-local-storage-and-voice-assets.md`](./05-local-storage-and-voice-assets.md)**
   - **When to read:** Modifying persistence (`data/`, `SharedPreferences`, `SQLite`, `AtomicFile`), editing Korean UI strings, or adding/changing `CoachCue` voice prompts.
   - **Covers:** The 6 local storage schemas, scoped record deletion & consent rules, 3-way voice asset synchronization (`CoachCue` ↔ `res/raw/coach_*.wav` ↔ `res/values/coach_voice_resources.xml`), and Korean beginner-driver copywriting standards (`"룸미러"` instead of `"백미러"`).

6. **[`06-qa-simulation-and-git-workflow.md`](./06-qa-simulation-and-git-workflow.md)**
   - **When to read:** Running tests, validating changes, adding QA scenarios under `/simulation/test`, or committing/pushing to `main`.
   - **Covers:** Trunk-based `main` workflow, mandatory Gradle verification gates (`assembleDebug`, `testDebugUnitTest`, `lintDebug`), and the 3-stage `/simulation/test` QA pipeline.
