# Skill 03 — Body Profile Guide Bands & Interactive Posture/Mirror Canvas

## 1. Body-Condition Guide Band Model (`DriverProfile` & `PostureGuideBand`)

`DriverProfile` stores physical metadata (`heightCm` bounded to `150..200`, `weightKg` bounded to `50..110`).
To help novice drivers understand how much to adjust their seat, steering wheel, and mirrors while preserving strict safety guardrails:

1. **Neutral Default Invariant (`VirtualSeatCalibration`)**
   - `VirtualSeatCalibration` defaults to neutral `50` across all axes (`FORE_AFT`, `HEIGHT`, `BACKREST`, `STEERING`).
   - Calling `state.updateProfile(...)` MUST NOT silently overwrite `state.calibration` (verified by `JourneyStateTest.specifiedDemoMetadataIsNotAComputedSeatRecommendation`).
   - Instead, `profile.guideBand(axis)` computes a `PostureGuideBand(recommendedCenter, minRecommended, maxRecommended)` purely as a visual reference band, and `state.applyRecommendedPreset()` allows the user to explicitly load `profile.recommendedCalibration()` as their starting slider position.

2. **Heuristic Guide Band Formulas (`DriverProfile.guideBand`)**
   - Given `heightDelta = heightCm - 175` and `weightDelta = weightKg - 75` (with `halfSpan = 8`):
     - `CalibrationAxis.FORE_AFT`: `center = (50 + (heightDelta * 0.48).roundToInt()).coerceIn(25, 75)` — taller drivers start further back to maintain a slight knee bend at full brake travel.
     - `CalibrationAxis.HEIGHT`: `center = (50 - (heightDelta * 0.44).roundToInt()).coerceIn(25, 75)` — shorter drivers start higher for unobstructed instrument cluster and road visibility.
     - `CalibrationAxis.BACKREST`: `center = (50 + (weightDelta * 0.20).roundToInt()).coerceIn(35, 65)` — balanced torso support near upright `100°–110°` equivalent.
     - `CalibrationAxis.STEERING`: `center = (50 + (heightDelta * 0.28).roundToInt()).coerceIn(30, 70)` — reachable with shoulders relaxed against the backrest.

3. **Mandatory Mirror Invalidation Rule**
   - Whenever `setCalibration(axis, value)` or `applyRecommendedPreset()` changes any seat or steering axis:
     - The modified axis step (`0`, `1`, `2`, or `4`) is set to `false`.
     - **All three mirror steps (`5` Room Mirror, `6` Left Side Mirror, `7` Right Side Mirror) are automatically reset to `false`** because changing eye height or fore-aft position shifts the mirror viewing angles.
     - All 7 pre-departure checks (`preflight[0..6]`) are reset to `false`.

---

## 2. Interactive Canvas Specification (`PostureGuideView.kt`)

`kr.drivestep.tutor.journey.PostureGuideView` renders a real-time visual diagram in the main explanation column of `DriverJourneyView.setupPage()`, directly above the `설명 다시 확인` button. It fills the available space and scales posture/belt illustrations uniformly; mirror diagrams keep their aspect ratios, and captions remain readable at a fixed text size:

- **Steps `0..4` (Seat & Steering Calibration)**:
  - Draws a side-profile silhouette of the floor, brake pedal, seat cushion, backrest, headrest, and steering wheel.
  - Renders the **recommended body-profile silhouette** in dashed teal (`guideTint`) and the **current slider calibration** in solid dark (`PleosUi.TEXT`).
  - Displays a horizontal `0..100` gauge bar highlighting the `[minRecommended..maxRecommended]` target band, the `recommendedCenter` tick mark, and the current calibration dot.
- **Step `5` (Room Mirror — `룸미러`)**:
  - Draws the room mirror frame divided vertically at the `1/2` horizon line with an inner target box showing the rear window centered inside the mirror frame.
- **Steps `6..7` (Left & Right Side Mirrors — `왼쪽/오른쪽 사이드미러`)**:
  - Draws the side mirror frame split horizontally at the **`1/2` horizon line** (`상하 1/2 지평선`) and vertically at the **inner `1/4` own-vehicle body reference zone** (`1/4 차체`) vs. **outer `3/4` adjacent lane view** (`3/4 옆 차로 시야`).
- **Step `8` (Seatbelt — `안전벨트`)**:
  - Draws the driver torso and pelvis outline with the 3-point belt path crossing the center of the collarbone/shoulder and low across the hip/pelvis bones.
