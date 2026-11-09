# v0.3 기어·조향 실습 검증

기준일: 2026-11-08. 개발 환경: Pleos 에뮬레이터 2.1.0, Android 14, Vehicle SDK 2.3.2. 실차 검증 결과가 아닙니다.

## 검증 결과

- debug APK / release APK 빌드 성공, Android lint 성공.
- 단위 테스트 **57개 통과**, 실패·오류 0개. 조향 28개에는 초기값, 중복, 반대 방향, GET/구독 도착 순서, 기어 변경, 움직임, 신호 만료, 속도 측정 시각 검증을 포함합니다.
- 에뮬레이터에서 조향 버튼 네 번으로 **4/4 완료**, 실제 SDK 수신 후 저장된 각도·상태 전이 확인.
- 단계별 음성 재생 시작 로그, 반대 방향 안내 → 중앙 복귀 → 재시도 확인.
- R 전환 시 중단과 음성 중지, P 복귀 후 자동 재개되지 않음 확인.
- 앱 강제 종료 후 PROCESS_RESTART 복구, 원래 수신 이벤트 보존 및 완료 이력 유지 확인.
- 방향지시등 앱 버튼으로 왼쪽 → 끄기 → 오른쪽 → 끄기 수행, SDK 수신·저장 **5/5 완료** 확인.
- release 병합 manifest에는 INTERNET 및 디버그 localhost cleartext 설정이 없음을 확인.

## 구현 범위

설명 → P·정지·조향 신호 확인 → 좌·중앙·우·중앙 조작 → 고정 한국어 음성 → 로컬 기록·복습입니다. 실제 후진 주행, 주차 공간 인식, 핸들 회전 수 지시, 주차 성공·안전성 평가는 포함하지 않습니다. 기존 후진주차 설명·퀴즈는 별도 메뉴입니다.

| 항목 | 구현 및 확인 기준 |
|---|---|
| 기어 | AAOS GEAR_SELECTION, P 외 값이면 중단 |
| 실제 속도 | AAOS PERF_VEHICLE_SPEED, m/s. CAR_SPEED 런타임 권한 필요 |
| 조향 | Pleos SteeringWheelAngle GET + 변경 구독 |
| 신호 구분 | GET/첫 구독 baseline/이후 구독 변경을 별도로 구분. 조회만으로 완료 불가 |
| 만료 | 필수 항목별 로컬 수신 5초, 속도 원본 측정 시각도 5초. 오래된 GET으로 연장 불가 |
| 조작 구간 | 좌≤−5, 중앙±2, 우≥5. 데모 설정이며 실차 권장 각도 아님 |
| 저장 | 조향 AtomicFile JSON 최대20개. 미완료 기록은 재실행 시 중단으로 복구 |
| 음성 | 한국어 TTS 또는 기본 WAV45개. 새 안내로 교체, UX 제한·화면 이탈·음소거 시 중지 |
| 시험 버튼 | debug 에뮬레이터에서만. HTTP 콘솔→차량 서비스→SDK, 앱 상태 직접 변경 없음 |
| release | 콘솔 제어 구현·INTERNET·localhost cleartext 설정 미포함 |

## 통합 과정에서 발견하고 반영한 사항

**1. 콘솔과 SDK의 조향 부호가 반대입니다.** 현재 에뮬레이터에서 콘솔 −8 입력이 SDK +8로 들어왔습니다. 앱 디버그 버튼은 기대 SDK값 기준으로 표시하고 콘솔 입력을 반대로 전달합니다. PC 패널은 콘솔 원본값을 표시합니다. 이 보정은 확인한 에뮬레이터 전용이며 실차 신호에는 적용하지 않습니다.

**2. GET이 변경 구독보다 먼저 올 수 있습니다.** 관찰 로그에서 +8 GET 뒤 12ms 후 +8 구독이 들어왔습니다. UI 최신값과 마지막 구독값을 분리해, GET은 진도를 올리지 않지만 뒤따르는 실제 구독 변경을 놓치지 않도록 했습니다.

**3. SDK 문서와 콘솔의 각도 설명이 다릅니다.** SDK는 앞바퀴/bicycle-model 각도(좌 음수), 콘솔은 핸들 각도처럼 설명하며 범위도 다릅니다. 현재 화면은 ‘SDK 전륜 조향각’과 데모 입력임을 명시합니다. 핸들 회전 수로 환산하지 않습니다. 실제 차량에서 의미·단위·부호 검증이 필요합니다.

**4. R·N·D에서 앱 UX 제한이 발생합니다.** DO 선언으로 우회하지 않습니다. R/D 주차 실습을 제품 기능으로 주장하지 않으며, P·정지 조작 확인만 제공합니다. PC 패널에서 P로 복귀해 새 실습을 시작할 수 있습니다.

## 재현

```bash
./scripts/build.sh :app:assembleRelease
./scripts/install.sh
python3 scripts/verify-steering.py
python3 scripts/verify-controls.py
```

통합 스크립트는 에뮬레이터만 허용하며 기록을 추가합니다. 저장소를 초기화하지 않습니다. `verify-steering.py`는 앱 버튼으로 조향 입력, SDK 수신 후 저장된 전이·각도, 음성 재생 시작, 잘못된 방향 재안내, R 중단, 프로세스 종료 복구를 확인합니다. 음성 로그는 재생 시작의 증거이며 인간이 듣고 음질을 평가한 결과는 아닙니다.

## 추가 확인이 필요한 부분

- 실제 차종의 SDK 권한·지원 신호·조향 부호·단위·갱신 주기.
- 차량 전원/조향 보조 상태와 정차 조작 시 교육 적합성. 실차 조향 안내는 별도 검증 전 배포 대상이 아닙니다.
- 앱 배포 정책, 주행 중 허용되는 UX·음성 범위. 플랫폼 제약 확인 없이 R/D 실습을 확장하지 않습니다.
- 실제 후진 경로·장애물·보행자·주차 성공 평가에 필요한 별도 센서와 공식 접근 권한.

## 근거

- [Pleos Virtual Console](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)
- [Pleos 주행 제한 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [Vehicle 2.3.2 공식 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar)
- [Android GEAR_SELECTION](https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION)
- [Android PERF_VEHICLE_SPEED](https://developer.android.com/reference/android/car/VehiclePropertyIds#PERF_VEHICLE_SPEED)
