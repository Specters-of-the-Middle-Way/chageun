# 첫 기능 구현 및 검증 결과

이 문서는 **v0.1의 기록**입니다. 이후 추가한 음성·조작법·주차 상황 튜터는 [v0.2 검증](TUTOR_VALIDATION.md)을 확인하세요.

검증일: 2026-11-07. 앱 `kr.drivestep.tutor`, 개발용 debug APK.

**방향지시등 실습 → Pleos SDK 변경 신호 → SQLite 저장 → 재실행 후 복습**을 Pleos 전용 에뮬레이터에서 확인했습니다. 실차 시험이나 운전 교육 효과 검증 결과는 아닙니다.

## 실제 실행한 환경

- macOS Apple Silicon, JDK 17.
- Pleos Connect **v2.1.0 ARM64** 시스템 이미지, Android 14 / API 34.
- Vehicle SDK **2.3.2**, compileSdk 35, targetSdk 34.
- Gradle 8.10.2 / Android Gradle Plugin 8.8.2 / Kotlin 2.0.0.
- AVD: `DriveStep_Pleos`, 1920×1080, RAM 4096 MB.
- 차량의 현재 사용자로 앱 실행. 이 환경의 현재 사용자 ID는 10이었음.

## 통과한 검증

| 검증 | 결과와 확인 범위 |
|---|---|
| APK 빌드 | `assembleDebug` 성공, 실제 에뮬레이터 설치·실행 성공 |
| 실습 상태 단위 테스트 | **13개 통과**, 실패·오류 0개. 정상 순서, 잘못된 방향, 중복·오래된 신호, 이미 켜진 초기 상태, 중단·완료의 불변성 등 |
| 저장소 장치 테스트 | **4개 통과**, 실패·오류 0개. DB 재열기, 미완료 복구, 늦은 쓰기 방어, 개별 삭제 |
| 정적 검사 | `lintDebug` 성공, 오류 0개. 최신 target/API·테스트 라이브러리, orientation, 백업 규칙 관련 경고 6개는 남아 있음 |
| SDK 실제 연결 | 기능 지원 조회와 실제 상태 수신 후에만 홈의 연결 상태가 준비로 변경됨 |
| 실습 완료 | 콘솔 `Off → BlinkLeft → Off → BlinkRight → Off` 입력 후 SDK 리스너를 통해 단계 진행, `COMPLETED`와 다섯 관측이 DB에 저장됨 |
| 재실행 후 복습 | 앱 강제 종료·재시작 후 완료 기록이 목록에 남고, 해당 기록을 눌러 다섯 관측을 다시 확인함 |
| 잘못된 방향 | 왼쪽 안내 중 오른쪽을 켜면 완료하지 않고 끄기 요구. 끈 뒤 왼쪽 연습으로 복귀함 |
| 실습 중 강제 종료 | 진행 기록이 다음 실행에서 `PROCESS_RESTART` 중단 기록으로 복구되고 완료 처리되지 않음 |
| 주행 사용 제한 | 실습 중 콘솔의 D / 15 km/h 상태로 변경. `DRIVING_RESTRICTION`으로 저장되고 실습 조작 화면이 숨겨짐. P / 0 km/h 복귀 후 중단 기록 확인 |
| 화면 확인 | 홈, 실습, 완료 복습, 중단 복습 화면을 실제 스크린샷으로 확인함 |

실습 자동 시험에서는 가상 콘솔의 실제 HTTP 입력을 사용했습니다. 앱 내부에 가짜 완료 이벤트를 넣거나 DB를 수정하지 않았습니다. 방향지시등 활성화 중에는 에뮬레이터의 시스템 카메라가 UIAutomator 조회를 가리는 경우가 있어, **SDK 수신 후 저장된 debug 앱 DB를 읽기 전용으로 조회**하여 중간 단계를 검증했습니다. 완료·재실행·복습은 UI에서도 확인했습니다.

## 재현 방법

프로젝트 루트에서 다음 명령을 사용합니다.

```bash
./scripts/build.sh
./scripts/run-emulator.sh
# 부팅 후 다른 터미널에서
./scripts/install.sh
python3 scripts/verify-emulator.py
```

같은 AVD가 이미 실행 중이면 실행 명령을 반복하지 않습니다. `verify-emulator.py`는 테스트 기록을 추가하며 앱 데이터 전체 삭제를 하지 않습니다. 현재 에뮬레이터에 맞춰 확인한 콘솔 경로를 사용하므로 향후 이미지 버전이 바뀌면 재확인이 필요합니다. 기어·속도 변경은 에뮬레이터 테스트 스크립트의 입력이고, 제품 앱은 차량 값을 쓰지 않습니다.

저장소 장치 시험은 `./gradlew :app:connectedDebugAndroidTest`입니다. 테스트 도구가 종료 시 대상 APK를 제거할 수 있으므로 이후 `./scripts/install.sh`로 다시 설치합니다. 이때 기존 앱 데이터가 사라질 수 있으니 개발 테스트 환경에서 사용합니다.

## 실제 화면

- [홈](screenshots/01-home.png)
- [실습](screenshots/02-practice.png)
- [완료 복습](screenshots/03-review.png)
- [강제 종료 후 복구](screenshots/04-interrupted.png)
- [주행 제한](screenshots/05-driving-restricted.png)
- [에뮬레이터 시스템 카메라 겹침 사례](screenshots/06-emulator-camera-overlay.png)

## 현재 제한과 다음 확인 사항

1. **정차 조작 연습만 구현했습니다.** 도로 상황 판단, 내비 연동, 음성 안내, 항목별 운전 숙련도 평가는 아직 없습니다. 5/5는 다섯 조작 관측의 완료 표시입니다.
2. SDK의 방향지시등 값은 OFF/LEFT/RIGHT입니다. 비상등, 차선 변경 의도, 실제 램프의 전기적 점멸, 거울 확인을 평가하지 않습니다.
3. 실습 시작 때 GET의 꺼짐 값은 준비 확인에 사용합니다. 이후 동작 완료는 변경 구독으로만 진행합니다. GET 갱신 자체로 실습을 완료시키지 않습니다.
4. 에뮬레이터는 방향지시등 활성화 시 시스템 카메라 미리보기를 앱 위에 표시합니다. 정차 상태에서 미리보기 ×로 닫거나 방향지시등을 끄면 안내를 볼 수 있습니다. 앱은 시스템 카메라를 강제로 숨기지 않습니다. 향후 음성 안내를 추가할 때 플랫폼 오디오 정책과 함께 확인해야 합니다.
5. 정차 여부는 CarUxRestrictions의 사용 제한으로 판단합니다. 이 이미지에서는 P가 허용되고 D의 대기·이동 상태는 제한됩니다. **실차의 권한, 신호 주기, 주행 제한 정책, 시스템 화면과의 관계는 별도 확인이 필요합니다.**
6. 기록은 해당 앱 전용 SQLite에 저장합니다. 차량 간 동기화·계정·서버 전송은 구현하지 않았습니다. 홈에는 최근 10개, 복습에는 중복을 제외한 최근 관측 30개를 표시하며 DB에는 전체 기록을 저장합니다.
7. 개발용 서명 APK입니다. 배포 서명·공모전 최종 제출 패키지·스토어 심사는 이번 구현 범위에 포함되지 않습니다.

## 확인한 플랫폼 구현 사항

현재 AAOS 14 이미지의 `android.car` API는 `/apex/com.android.car.framework/javalib/android.car-module.jar`에 있습니다. `android.car`를 필수 named shared library로 선언하면 설치가 실패하여 manifest의 `uses-library`를 `required=false`로 설정했습니다. Gradle의 컴파일용 `useLibrary("android.car")`는 유지합니다.

SDK 연결 근거와 권한은 [SDK_NOTES.md](SDK_NOTES.md)에 정리했습니다. 가상 콘솔 HTTP 경로는 공개 SDK 계약이 아니며 앱 코드에서 사용하지 않습니다.

macOS의 Qt 플러그인 파일에 `hidden` 플래그가 설정되면 에뮬레이터 창이 열리지 않는 문제도 재현했습니다. 실행 스크립트는 프로젝트 안 도구의 Qt 플러그인에 한해 이 플래그를 해제합니다. 에뮬레이터 진단 정보 자동 전송은 끄도록 실행 옵션을 설정했습니다.
