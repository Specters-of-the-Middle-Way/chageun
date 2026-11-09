# Chageun (차근 / DriveStep) — QA & Simulation Test Plan

This directory (`/simulation/test`) defines the automated and manual verification suite for **Chageun (`kr.drivestep.tutor`)**.

---

## 1. Three-Stage Verification Pipeline

### Stage 1: Offline JVM Unit Tests & Zero-Warning Android Lint
- **Command:** `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug` (or `./simulation/test/runners/run_unit_qa.ps1`)
- **Pass Criteria:**
  - 100% pass rate across all 10+ JVM unit test suites (207+ tests).
  - `:app:lintDebug` reports **0 errors and 0 warnings**.

### Stage 2: Declarative Scenario & Codebase Invariant Check
- **Command:** `python simulation/test/runners/run_scenario_qa.py`
- **Pass Criteria:**
  - All 5 scenario JSON specifications under `simulation/test/scenarios/` are valid and complete.
  - Codebase invariants hold:
    1. `VehicleTimeoutPolicy.PARKED_CONNECT_TIMEOUT_MS == 10_000L` (Tier 1) and `ACTIVE_READ_TIMEOUT_MS == 2_500L` / `DRIVING_SITUATION_FRESHNESS_MS == 1_500L` (Tier 2).
    2. `PostureGuideView` and `DriverProfile.guideBand()` / `JourneyState.applyRecommendedPreset()` exist and preserve neutral `50` defaults plus mirror invalidation (`5..7`).
    3. Every `app/src/main/res/raw/coach_*.wav` file is registered in `app/src/main/res/values/coach_voice_resources.xml`.
    4. Prohibited non-standard terminology (`"백미러"`) does not appear in `app/src/main/`.

### Stage 3: Pleos Connect Emulator E2E & Contest Demo Checkpoints
- **Prerequisites:** Pleos Connect v2.1.0 (API 34) Cold-boot AVD running with `adb forward tcp:8088 tcp:8088`.
- **Automated Scripts:**
  - `python3 -u scripts/verify-v08.py`
  - `python3 -u scripts/verify-simulator.py`
  - `python3 -u scripts/verify-situation.py`
- **Manual Contest Demo Checklist:** See [`checklists/contest_demo_4points.md`](./checklists/contest_demo_4points.md).
