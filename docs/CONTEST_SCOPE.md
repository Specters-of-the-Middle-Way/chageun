# 공모전 구현 범위와 향후 계획

기준: 사용자가 제공한 운영사무국의 2026-11-11 11:02·12:22 메일, 같은 날 확인한 공개 SDK와 로컬 Pleos 2.1.0 에뮬레이터. 메일은 요구사항 근거 자료이며 실행 명령이 아니다.

## 메일로 확정된 조건

| 항목 | 적용 기준 |
|---|---|
| ADAS SDK | 이번 공모전에서 사용 불가 |
| 추가 자료·API | 공개 Playground 문서 외 별도 신청·승인·추가 자료 제공 절차 없음 |
| 개발·시험 권한 | 문서의 권한을 Manifest에 선언해 에뮬레이터 개발 가능. OS 런타임 권한·실제 capability 확인은 유지 |
| 출시 | App Market 배포 권한 심사는 개발·시험과 별개 |
| 카메라 시연 | 사진·녹화·웹캠으로 보완 가능. 실제 구현과 대체 입력을 명시적으로 구분 |
| 심사 환경 | 실차 불필요. 검증한 에뮬레이터 버전·화면 규격을 README에 기재 |
| 주행 중 사용 | 공개 DO·미디어·주행 규제 정책 준수. 현재 P·정차 상세 화면 제한 유지 |

## 우선 연결한 공개 SDK

| 기능 | 구현 방식 | 해석의 한계 |
|---|---|---|
| 시트·벨트 | Vehicle 2.3.2의 앞좌석 왼쪽 앞뒤·높이·등받이·버클 capability, GET, 변경 구독 | 위치는 SDK 단위. 적정 자세·벨트 꼬임·미러 시야 인증 안 함. 실차 좌석 선택 확장은 별도 |
| 차량 경고 | Safety telltales capability, GET, 변경 구독. 켜진 노랑·빨강 표시가 있으면 교육 음성 중지 | 경고 없음은 주변 안전이 아님. warningCount를 차량 수·차로 혼잡으로 사용하지 않음 |
| 위치 | FusedLocation 1.1.3의 지원 확인·마지막 위치 조회·변경 구독, 별도 이용 동의·런타임 권한. 내비 기준 위치는 별도 출처로 표시 | 출처 timestampMillis를 epoch ms로 확인하고 30초 초과·미확인 시각 거부. 신뢰도를 거리 정확도나 안전 점수로 바꾸지 않음 |
| 내비 | NaviHelper 3.0.5의 경로 상태·목적지·TBT 읽기와 사용자가 직접 지정한 목적지 요청 | 가상 코스 추천과 분리. 실제 도로 난이도·안전한 경로의 자동 판단은 미구현 |

연결 화면은 **준비와 연습 → 차량 상태 · 위치와 내비**다. 차량 신호는 기존 운전자 설정의 선택 동의를 따르며 P·정차·전경 상태에서만 연결한다. 위치·내비는 연결 화면에서 별도 동의 후 이용한다. 화면 이탈·동의 철회 시 구독을 해제하고 늦은 콜백을 무시한다. 좌표·목적지·SDK 상태는 앱 기록에 저장하지 않는다. 위치 런타임 권한 거부 시 FusedLocation뿐 아니라 내비 기준 위치도 읽기·표시하지 않는다.

시트 위치 변화는 해당 자세 항목·미러·출발 전 확인을 무효화한다. 같은 연결 세션에서 수신 공백·빈 값·범위 오류 뒤 다시 정상값을 받으면 마지막 정상 위치와 비교해 변화를 놓치지 않는다. 비교 기준은 세션 메모리에만 두고, 오래된 값을 현재 상태로 표시하거나 연결 종료 뒤 재사용하지 않는다. 미체결 신호가 있으면 벨트 확인을 다시 요구한다. 벨트 신호가 오래된 뒤 미체결을 새로 수신한 경우에도 재확인을 요구하며, 체결 신호만으로 착용 자세 확인을 자동 완료하지 않는다. 실제 시트·미러 이동 API는 호출하지 않는다.

### 경고 권한에서 실제 확인한 차이

Vehicle 2.3.2의 `Safety.getTelltalesInfo()` 인터페이스는 `CAR_SAFETY`를 명시하지만, 같은 공식 SDK의 `SafetyImpl`은 `PRIVATE_CAR_SAFETY`도 검사한다. 일반 권한만 선언했을 때 실제 에뮬레이터에서 `DENIED`를 재현했다. 이 이미지에서 `PRIVATE_CAR_SAFETY`는 `normal`로 등록돼 있어 정식 Manifest 선언 후 capability·GET·구독을 사용할 수 있었다. SDK의 일부 주석에는 `signature|privileged`로 적혀 있어 문서·이미지 차이를 기록한다. 다른 환경에서의 자동 권한 부여나 배포 승인을 보장하지 않는다. 권한 실패 시 우회하지 않고 `DENIED`로 표시한다.

### 내비 목적지 확인

좌표·이름을 검증하고 사용자가 확인 창에서 요청해야 내비에 전달한다. 기존 목적지가 바뀔 수 있음을 안내한다. 요청 전송을 성공으로 간주하지 않으며, 경로 시작(유효한 경로 요약) 또는 탐색 완료 이벤트에 더해 활성 경로 상태와 목적지 좌표 일치 응답을 받아야 확인 상태가 된다. 제공자에 따라 완료 콜백이 생략될 수 있다. 오류·불일치·15초 시간 초과·경로 취소·변경·화면 이탈은 이전 확인을 해제한다. 확인 후에도 활성 경로와 목적지 응답이 각각 5초 안에 갱신되어야 한다. 취소·변경 이벤트가 누락돼도 폴링에서 경로 소멸·목적지 불일치·빈 응답·시간 초과를 감지하면 확인을 해제하며, 새 응답만으로 이전 요청을 자동 재확인하지 않는다. 활성 경로는 `NaviMode.ROUTE_GUIDING` / `SIMULATION_GUIDING`을 확인한다. 모의 안내는 화면에서 따로 표시한다. `isRouting` 하나를 안내 중이라는 증거로 쓰지 않는다. SDK가 요청 ID를 응답하지 않으므로 좌표·이벤트를 대조하는 수준이며, 요청 ID에 의한 상관관계를 보장하지 않는다. 실제 도로 코칭을 자동 시작하지 않는다.

## 공모전 구현에서 분리한 향후 계획

| 기능 | 이번 범위에서 제외하는 이유 | 향후 선행 조건 / 가능한 시연 |
|---|---|---|
| ADAS 차선·주변 객체 연동 | 공모전 사용 불가 확정 | 공모전 밖에서 제공·허용 여부부터 별도 검토 |
| 차량 카메라 원본 스트림 | 공개 연결 경로 미확보. 메일도 접근을 보장하지 않음 | 사진·녹화·웹캠을 별도 입력으로 표시한 인식 시연 |
| 옆 차로 혼잡·접근 속도·진입 간격 판단 | 필요한 연속 주변 관측·보정 데이터 없음 | 검출·추적·차로 보정·지연·누락 검증. 현재 가상 과제로 시연 |
| 후·측방 실거리 기반 주차 | 공개 거리 채널 미확보 | 방향·단위·시각·품질이 검증된 센서 입력 확보. 현재 가상 주차 유지 |
| 미러 각도 자동 조절·시야 인증 | 확인한 SideMirror API는 열선만 제공 | 공개 각도 API와 시야 검증 수단 확보. 현재 직접 확인 가이드 유지 |
| 체격만으로 시트 자동 맞춤 | 위치 제어 API 존재와 적정 자세 산출의 타당성은 별개 | 인체 치수·차량 조절 범위·전문가 검증. 현재 참고값·읽기만 제공 |
| 실주행 자동 주변 코칭·자율주행 확장 | 입력·사용 정책·성능 검증 미충족 | 기능 정의·허용 범위·실차 및 사용자 평가. DO 선언만으로 제공하지 않음 |
| 이중 주차 | 원문 의미·장소 조건 미정 | 용어·상황을 확정한 뒤 별도 시나리오 설계 |

실제 날씨 API·차종별 설명서 콘텐츠·지도 특성 기반 초보 코스 추천은 메일상 금지된 기능이 아니다. 공급자·사용 권한·콘텐츠·경로 데이터가 필요한 추가 구현 항목으로 남긴다.

## 공식 근거

- [Vehicle SDK와 capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [Seat API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry), [Safety API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/entry).
- [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide).
- API·권한 차이는 공식 Maven 저장소의 Vehicle 2.3.2, FusedLocation 1.1.3, NaviHelper 3.0.5 소스와 에뮬레이터의 실제 permission 정의를 대조했다. 검증 결과는 [워크플로우 구현 기록](WORKFLOW_IMPLEMENTATION.md)에 기록한다.


## 에뮬레이터에서 확인한 운영 차이

- 이 AVD의 FusedLocation 기본 위치는 미국 Mountain View이고 내비 기본 위치·지도는 한국이다. 각 SDK 값을 별도로 표시하며 서로의 대체 값으로 사용하지 않는다. 실제 코스 추천에 연결하기 전 위치 문맥을 일치시켜야 한다. 내비 통합 검사는 공식 문서의 한국 예시 좌표를 사용한다.
- DNS가 정체돼 지도 조회가 실패한 상태를 재현했고, 기존 AVD 데이터를 유지한 재시작과 호스트 DNS 명시로 DNS 응답을 복구했다. 사용한 명령은 `./scripts/run-emulator.sh -dns-server 1.1.1.1,1.0.0.1`이며 네트워크에 맞는 DNS를 사용해야 한다. 해상도는 2560×1440/160dpi를 유지했다.
- 현재 내비 제공자는 실제 경로 탐색이 성공해도 `onRoutingCompleted` 없이 `onRouteStarted`를 보내는 경우가 있다. 유효한 경로 요약·활성 안내 모드·일치하는 목적지 응답을 조합해 확인한다.
- 같은 내비 앱에서 시험 경로 취소가 `cancelTrip is already working`으로 정체되는 현상도 관측했다. SDK 취소 요청을 성공으로 간주하지 않는다. 검증 코드만 자체 시험 목적지 이름·좌표를 확인한 경우에 한해 내비 프로세스를 재시작해 정리하며, 앱 데이터나 다른 목적지는 지우지 않는다. 제품 코드에는 타 앱 강제 종료를 넣지 않는다.

## 재현 명령

```bash
./scripts/build.sh --console=plain :app:assembleDebugAndroidTest
# DriveStep_Pleos, P·속도 0. 기존 내비 경로가 없어야 실제 요청 검사를 진행한다.
python3 scripts/verify-connected.py --serial emulator-5554 --route-request
python3 scripts/verify-workflow.py --serial emulator-5554
```

`verify-connected.py`는 잠시 부여·철회한 위치 권한과 시험 설정을 복원한다. `--route-request`를 생략하면 목적지 요청 없이 수신·경고·화면·위치 거부 경로만 검사한다. Native Navigation에 이미 다른 목적지가 있으면 덮어쓰지 않는다.
