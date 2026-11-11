# Skill 02 — Domain Guardrails & 11-Page Flowchart State Machine

## 1. Five Non-Negotiable Safety Principles

1. **Parked-Only Detailed Interaction (`ParkedGate`)**
   - Detailed setup, profile editing, checklists, educational dialogs, and post-drive reviews are permitted ONLY when the real vehicle is stationary in `P` (`ParkingGear.P`, `abs(speedMps) <= 0.01`, and `CarUxRestrictions.isRequiresDistractionOptimization == false`).
   - If the vehicle shifts out of `P` or begins moving during any setup/practice screen, immediately pause/interrupt the active flow, silence any active `VoiceCoach` cue, and display the parked-only gate message.

2. **Signal Provenance Isolation (`SIMULATION` vs. `SDK`)**
   - Never mix simulated controls with real vehicle SDK observations.
   - If the input source changes (`source != input.source`), immediately clear the observation window (`reset()`).
   - Never fabricate or extrapolate missing vehicle signals. `null` or stale signals must transition to `UNKNOWN` / `WAITING_FOR_SIGNAL` / `INTERRUPTED`, never to a "safe" or "completed" state.

3. **No Automatic Physical Judgement of Mirrors, Seat Fit, or Real Obstacles**
   - The app does NOT have interior cameras or ultrasonic/lidar obstacle sensors in Vehicle SDK 2.3.2.
   - Never claim that the app automatically verified physical mirror angles, seatbelt twist, or real-world parking bay clearance. Always require explicit driver confirmation (`"조절 및 확인 완료"`).

4. **Strict Separation of 3 Progress Metrics**
   - Never collapse driver progress into a single score. Always keep these three dimensions separate:
     1. **Learning Completion (`학습 완료`)**: Whether stationary education or checklist steps were completed. Completing stationary education NEVER increases driving mastery.
     2. **Self-Reported Confidence (`운전자 자신감`, 1–5)**: Stored in `DriverProfile.confidence` and post-exercise self-reflection (`"어려웠음" | "보통" | "수월했음"`).
     3. **Observed Exercise Outcome (`연습 관측 결과`)**: Objective virtual maneuver metrics (e.g., `insideTarget`, `angleErrorDegrees`, `collisionCount`, `completedSteps`).

5. **Priority of Vehicle Warnings & Navigation Audio**
   - OEM vehicle warnings (`vehicleWarningActive == true`) and navigation prompts always take priority over coach speech.
   - When a vehicle warning is active or audio focus is lost, suppress or immediately stop `VoiceCoach` playback (`speechSuppressed = true`) and **never queue obsolete driving instructions for delayed replay**.

---

## 2. 11-Page Flowchart State Rules (`JourneyState`)

| Flowchart Page | `JourneyStep` / Screen | Mandatory Guardrails & State Transitions |
|---|---|---|
| **01–02. First Launch & Profile** | `JourneyStep.PROFILE` | Collects `heightCm` (`150..200`), `weightKg` (`50..110`), `experience`, `confidence` (`1..5`), `guidance`, `difficulty`, plus independent `recordConsent` and `vehicleConsent` toggles. Refusing consent does NOT block manual setup or virtual simulation. |
| **03–04. Posture, Mirrors & Belt (9 Steps)** | `JourneyStep.SETUP` (`setupIndex 0..8`) | Steps: `0` Fore-Aft, `1` Height, `2` Backrest, `3` Headrest, `4` Steering, `5` Room Mirror, `6` Left Side Mirror, `7` Right Side Mirror, `8` Seatbelt. Tapping `"설명 다시 확인"` (`leaveSetupUnresolved()`) keeps the step `false` and clears `preflight`. Changing any seat (`0..2`) or steering (`4`) calibration invalidates its own step AND resets all mirror checks (`5..7`) and `preflight` (`0..6`). |
| **05. Pre-Departure 7 Checks** | `JourneyStep.PREFLIGHT` (`0..6`) | All 9 setup checks must be `true` before preflight can be completed. Requires all 7 items (`preflightComplete == true`) to unlock `canPractice`. Unchecking any item immediately revokes `canPractice`. |
| **06. Practice Selection** | `JourneyStep.PRACTICE` | Offers Lane-Change Simulation (`DRIVE`), Parking Simulation (`PARKING`), and Stationary Education (`EDUCATION`). `EDUCATION` is always accessible even without profile/setup completion. |
| **07. Driving / Lane-Change Coach** | `DrivingSimulationSession` & `SituationCoachEngine` | Evaluates turn signal, mirror check, blind-spot wait, and smooth steering. `SituationCoachEngine` requires `gear == D`, fresh signals (`<= 1,500ms`), and `minWindowMs >= 2,000ms` within `windowMs = 3,000ms` before emitting `SPEED_DROP` or `SPEED_RISE`. |
| **08. Parking Practice** | `ParkingSimulationSession` | Interactive maneuver (not a quiz): **Brake Hold → R → Release Hold → Gentle Gas → Steer → Brake Hold → Zero Speed → P → Finish**. Supports `REVERSE_BAY`, `PARALLEL`, `T_BAY`, and `FRONT_BAY` courses using AVANTE CN7 gasoline 1.6 dimensions from `TrainingVehicle`. |
| **09–10. Post-Drive Review** | `JourneyStep.REVIEW` | Separates observed simulation metrics from driver self-evaluation. Saves to local history only when `recordConsent == true`. Provides explicit cause reflection without automatic point deduction for defensive braking. |
| **11. Revisit & Recovery** | `JourneyStep.REVISIT` | On a new visit (`newVisit()`), stored profile and 9 setup checks can be reused via `revisitUnchanged()`, **but all 7 preflight checks (`preflight[0..6]`) are ALWAYS reset to `false`** and must be re-verified for the current trip. `revisitChanged()` resets both `setup` (`0..8`) and `preflight` (`0..6`). |
