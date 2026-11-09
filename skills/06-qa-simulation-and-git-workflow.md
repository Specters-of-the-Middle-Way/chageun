# Skill 06 — QA Simulation Pipeline & Git Workflow (`main`)

## 1. Trunk-Based Git Workflow (`main`)

- **Branch Policy:** Work directly on `main` and push verified commits to `origin/main` (`https://github.com/Specters-of-the-Middle-Way/chageun.git`). Do not create long-lived feature branches unless explicitly requested by the user.
- **Clean Repository Rule:** Never commit `.tooling/`, `local.properties`, `build/`, `.gradle/`, AVD images, or temporary local planning PDFs (`skill plan *.pdf`, `드라이브스텝_*.pdf`).

---

## 2. Mandatory Pre-Push Verification Gate

Before committing and pushing to `main`, EVERY change must pass all 3 local verification checks with **0 errors and 0 warnings**:

### Windows PowerShell
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr" # Or local JDK 17+ path
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python simulation/test/runners/run_scenario_qa.py
```

### macOS / Linux
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 simulation/test/runners/run_scenario_qa.py
```

---

## 3. Three-Stage QA Architecture (`/simulation/test`)

The `/simulation/test` directory provides structured, repeatable quality assurance across offline and emulator environments:

```text
simulation/test/
├── QA_TEST_PLAN.md                        # Master 3-stage QA strategy & pass criteria
├── scenarios/                             # Declarative JSON test scenarios
│   ├── 01_parked_gate_and_d_gear.json     # P-gear gate & D-gear movement interruption
│   ├── 02_timeout_2tier_policy.json       # Tier-1 (10s parked) vs Tier-2 (1.5s-2.5s active) timeouts
│   ├── 03_body_preset_and_mirror_reset.json # Height/weight guide bands & mirror invalidation (5..7)
│   ├── 04_parking_reverse_and_parallel.json # Reverse & parallel parking sequence verification
│   └── 05_review_no_auto_penalty.json     # Post-drive review cause selection without auto-penalty
├── runners/
│   ├── run_unit_qa.ps1                    # One-click PowerShell runner (Gradle test + lint + scenario check)
│   └── run_scenario_qa.py                 # Offline scenario & codebase invariant verifier
└── checklists/
    └── contest_demo_4points.md            # 4-point live contest demonstration checklist
```

### Stage 1 — Offline JVM Unit Tests & Clean Lint (No Emulator Required)
- Executes 205+ unit tests across 10 test suites (`JourneyStateTest`, `ParkingSimulationSessionTest`, `DrivingSimulationSessionTest`, `SituationCoachEngineTest`, `SteeringPracticeSessionTest`, `PracticeSessionTest`, `SignalCoachTest`, `GrowthSessionTest`, `DriveLessonSessionTest`, `ParkingLessonSessionTest`) plus `:app:lintDebug`.

### Stage 2 — Declarative Scenario & Invariant Verification (`run_scenario_qa.py`)
- Validates `/simulation/test/scenarios/*.json` against source invariants (`VehicleTimeoutPolicy`, `JourneyState`, `PostureGuideView`, `coach_voice_resources.xml` 3-way sync, and zero `"백미러"` regressions).

### Stage 3 — Pleos Connect Emulator E2E Verification (`scripts/verify-*.py`)
- When a Pleos Connect 2.1.0 (API 34) emulator is running with `adb forward tcp:8088 tcp:8088`:
  ```bash
  python3 -u scripts/verify-v08.py
  python3 -u scripts/verify-simulator.py
  python3 -u scripts/verify-situation.py
  ```
