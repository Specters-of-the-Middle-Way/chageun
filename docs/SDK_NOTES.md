# Pleos 방향지시등 연동 확인 사항

2026-11-07에 공식 문서·공식 샘플과 공개 SDK 원본을 대조했습니다.

- Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/`
- 의존성: `implementation("ai.pleos.playground:Vehicle:2.3.2")`
- 읽기 권한: `pleos.car.permission.CAR_LIGHTS` (`normal`). 제어 권한은 사용하지 않습니다.
- 초기화: `Vehicle(applicationContext).initialize()` → `getTurnSignal()` → `checkTurnSignalStateCapabilities`의 실제 응답 확인.
- 읽기: `getCurrentTurnSignalState(onComplete, onFailed)`.
- 변경 수신: `registerTurnSignalState(TurnSignalStateListener)`와 `onTurnSignalStateUpdated`.
- 종료: 같은 리스너로 `unregisterTurnSignalState` 후 `Vehicle.release()`.

`initialize()`나 리스너 등록 호출의 반환은 실제 데이터 수신을 뜻하지 않습니다. 지원 여부를 확인하고, 실제 GET/리스너 값이 도착해야 `READY`로 표시합니다. 콜백은 앱의 메인 스레드로 전달하고, 연결을 종료한 후 도착한 이전 콜백은 무시합니다. 기능 확인과 GET에는 10초 응답 제한을 두며 오류 시 연결을 닫습니다. 활성 연결에서는 3초 간격으로 GET 확인을 하되 GET 스냅샷으로 실습 단계를 완료하지 않습니다.

SDK의 `TurnSignalValue`는 **OFF / LEFT / RIGHT** 세 값만 제공합니다. 가상 콘솔에 Blink/Flash가 표시되어도 이 API에서 해당 모드를 구별할 수 있다고 주장하지 않습니다. 비상등의 양쪽 점등을 판별하는 정보도 이 소스에는 없습니다. `null`은 꺼짐이 아니라 `UNKNOWN`입니다. 새 세션의 초기값·상태 확인 GET은 `isInitial=true`, 변경 리스너 값은 `false`로 전달합니다. 이 표시는 사건의 원인을 증명하지 않으며, 실습은 명시적인 시작과 요구한 순서의 변화로 판정해야 합니다.

공식 AAR와 소스 JAR는 인증 없이 다운로드됨을 확인했습니다. 이것은 SDK 의존성 확보 확인이며 에뮬레이터·실차 동작 검증과는 별개입니다.

- [공식 SDK 의존성 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
- [공식 샘플 저장소](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)
- [공식 샘플 사용 안내](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
- [사용한 SDK 소스 JAR](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar)


## v0.3 기어·속도·조향

`PleosParkingSignalSource`는 Pleos CAR_DRIVE(조향·브레이크)와 Android CAR_POWERTRAIN(기어), 런타임 CAR_SPEED(실제 속도) 권한을 사용합니다. SDK의 deprecated Gear 대신 AAOS GEAR_SELECTION을 읽습니다. cruise target은 실제 속도로 쓰지 않습니다. 변경 구독과 GET을 구분하며 원본 속도 timestamp를 보존합니다.

SDK SteeringWheelAngle 소스 문서는 전륜/bicycle-model 각도이고 좌측 음수입니다. 콘솔의 원본 입력과는 부호·설명 차이가 실제 관찰됐습니다. 에뮬레이터 시험 버튼만 해당 버전의 SDK 부호에 맞춰 매핑하며 실차 원시값은 변환하지 않습니다. 상세 근거와 검증은 [기어·조향 검증](STEERING_VALIDATION.md)을 참고하세요.
