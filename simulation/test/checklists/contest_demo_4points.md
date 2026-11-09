# Contest Live Demonstration — 4-Point Verification Checklist

Use this checklist before and during the live contest demonstration on the Pleos Connect emulator (`DriveStep_Pleos`).

---

## Checkpoint 1: Parked (`P`) Safety Gate & Body-Condition Posture/Mirror Guide
- [ ] Confirm Virtual Console (`http://127.0.0.1:8088/`) shows Gear = `Park` and Speed = `0 km/h`.
- [ ] On the **운전자 설정 (Profile)** screen, verify `heightCm` (`180 cm`) and `weightKg` (`78 kg`) and tap **운전 환경 설정**.
- [ ] On the **운전 환경 (Setup 1..9)** screen, verify that `PostureGuideView` renders the visual seat/steering/mirror diagram and recommended target band (`체격 참고 시작 구간`).
- [ ] Tap **체격 참고 시작값 적용** (or adjust a seat slider) after checking mirrors (`6..8`) and confirm that mirror checks automatically reset (`✓` cleared) so the driver must re-verify mirror alignment.

## Checkpoint 2: Pre-Departure 7 Checks (`출발 전 확인`)
- [ ] Complete all 9 setup steps (`1 ✓` .. `9 ✓`) to enter **출발 전 확인**.
- [ ] Verify that **연습 선택** remains disabled (`enabled == false`) when 6 of 7 items are checked, and enables immediately when all 7 items are checked.
- [ ] Verify that **교육 보기 (Stationary Education)** remains accessible at any time even before all checks are completed.

## Checkpoint 3: Interactive Reverse & Parallel Parking (`주차 연습`)
- [ ] Enter **연습 → 주차** and select **후진 주차** (or **평행 주차**).
- [ ] Execute the manual control sequence:
  1. **브레이크 유지 (Brake Hold ON)**
  2. **R 단 선택**
  3. **브레이크 유지 해제 → 가속 페달을 조금씩 조작하며 가상 핸들 조향**
  4. **목표 구역 진입 후 브레이크 유지 ON → 속도 0 확인 → P 단 선택 → 주차 마침**
- [ ] Shift the real emulator gear to `Drive` in Virtual Console mid-session and confirm that virtual pedals disable immediately and `VoiceCoach` stops speaking. Return real gear to `Park`.

## Checkpoint 4: Post-Drive Review & Separated Metrics (`복습 및 재방문`)
- [ ] On the **복습 (Review)** screen, verify that observed virtual maneuver metrics (`insideTarget`, `angleErrorDegrees`) are displayed separately from the driver's self-reported feeling (`어려웠음 / 보통 / 수월했음`).
- [ ] In **상황 코치 (Situation Coach)**, trigger a speed-drop or lane-warning scenario and verify that the review offers non-punitive cause reflection without automatic skill-score deduction.
- [ ] Re-open the app (`재방문 확인`) and tap **같은 운전자 · 출발 준비 확인**: verify that the 9 posture settings are preserved while all 7 pre-departure checks (`출발 전 확인`) start unchecked for the new trip.
