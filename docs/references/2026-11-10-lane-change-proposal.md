# [작업지시서] Pleos Connect SDK 기반 차로 변경 실시간 혼잡도(%) 산출 모듈 개발

## 1. 프로젝트 개요 및 에이전트 역할
* **역할:** Android Automotive OS (AAOS) 및 현대차그룹 Pleos Connect SDK 전문 안드로이드(Kotlin) 아키텍트 및 개발자
* **목표:** 외부 카메라 영상 스트림에 의존하지 않고, `PRIVATE_CAR_SAFETY` 특권 권한을 포함한 Pleos Connect SDK 내부 센서·상태 데이터를 결합(Sensor Fusion)하여 **운전자가 방향지시등(깜빡이)을 켠 순간 변경하려는 차로의 예상 혼잡도 및 위험도를 $0\% \sim 100\%$ 수치로 실시간 산출하고 안내하는 모듈**을 구현한다.
* **작성 원칙:** 모든 차량 통신은 비동기 IPC 콜백(`onComplete`, `onFailed`)으로 동작하므로, Kotlin Coroutines(`Flow` / `StateFlow`)을 활용해 반응형 데이터 스트림으로 상태들을 통합 관리한다.

---

## 2. 개발 환경 및 필수 권한 명세

### 2.1 타깃 환경
* **OS:** Pleos Connect (Android Automotive OS 기반)
* **언어 및 비동기 처리:** Kotlin, Coroutines, StateFlow
* **사용 SDK 모듈:**
  1. `Vehicle SDK` (`Safety`, `TurnSignal`, `Brake`, `DrivingMode` 도메인)
  2. `NaviHelper SDK` (도로 구간 소통 원활/서행/정체 정보)
  3. `FusedLocation SDK` (실시간 주행 속도 추정 및 위치)
  4. `TextToSpeech SDK` (혼잡도 임계치 초과 시 음성 경고 출력)

### 2.2 `AndroidManifest.xml` 선언 필수 권한
에이전트는 매니페스트 및 권한 검증 로직에 아래 권한을 반드시 포함해야 한다.
* `pleos.car.permission.PRIVATE_CAR_SAFETY` (`signature|privileged` 보호 수준 - Telltales 조회 및 구독)
* `pleos.car.permission.CAR_SAFETY`
* `pleos.car.permission.CAR_BRAKE`
* `pleos.car.permission.CAR_DRIVING_MODE`
* `pleos.car.permission.CAR_TURN_SIGNAL`
* `pleos.car.permission.FUSED_LOCATION`

---

## 3. 데이터 수집 및 슬라이딩 윈도우(Sliding Window) 설계

실시간 이벤트와 최근 주행 이력을 결합하기 위해 백그라운드에서 아래 4가지 데이터를 상시 수집하고 시간 창(Time Window) 버퍼를 유지해야 한다.

1. **내비게이션 도로 소통 상태 (`NaviHelper`):**
   * `NaviHelperEventListener`를 통해 현재 주행 링크의 `TrafficCongestionType` 상태를 최신 값으로 유지한다.
2. **주행 속도 및 목표 속도 (`FusedLocation` & `DrivingMode`):**
   * `FusedLocationCallback`의 연속 위치 변화량(또는 속도 필드)으로부터 현재 차량의 실제 주행 속도 $V_{\text{actual}}$ (단위: $\text{m/s}$)을 갱신한다.
   * `DrivingMode.registerCruiseControlTargetSpeed()`로 목표 속도 $V_{\text{target}}$ (단위: $\text{m/s}$)을 수집한다.
3. **브레이크 조작 빈도 버퍼 (`Brake`):**
   * 브레이크 페달 조작 상태 리스너를 구독하여, **최근 $60\text{ s}$(1분) 동안 브레이크가 `OFF`에서 `ON`으로 전이된 횟수 $N_{\text{brake}}$**를 슬라이딩 윈도우 큐(Queue)로 관리한다.
4. **안전 경고등(Telltale) 상태 및 누적 빈도 버퍼 (`Safety` - `PRIVATE_CAR_SAFETY`):**
   * `Safety.registerTelltales()`를 등록하여 `Telltales` 내 `TelltaleData` 리스트를 수신한다.
   * 현재 활성화된 안전 관련 경고등의 상태(`state`), 색상(`color`), 우선순위(`priority`)를 추적한다.
   * 각 `TelltaleData`의 `warningCount` 필드를 모니터링하여, **최근 $180\text{ s}$(3분) 동안 증가한 경고 누적 횟수 변화량 $\Delta \text{warningCount}$**를 슬라이딩 윈도우로 관리한다.

---

## 4. 차로 혼잡도($\%$) 산출 수학적 명세

운전자가 좌측(`TurnSignalValue.LEFT`) 또는 우측(`TurnSignalValue.RIGHT`) 방향지시등을 점등하면 즉시 아래 수식을 평가하여 최종 혼잡도 $S_{\text{total}}$ ($0\% \sim 100\%$)을 계산한다.

### 4.1 최종 혼잡도 통합 수식
$$S_{\text{total}} = \text{clamp}\left( 0.35 \times S_{\text{navi}} + 0.35 \times S_{\text{drive}} + 0.30 \times S_{\text{safety}}, \; 0.0, \; 100.0 \right)$$

### 4.2 세부 항목별 산출 규칙

#### ① 도로 기본 혼잡도 점수 ($S_{\text{navi}} \in [0, 100]$)
현재 도로 구간의 `TrafficCongestionType` 값을 아래 상수로 매핑한다.
* 원활 (Smooth): $S_{\text{navi}} = 15.0$
* 서행 (Slow): $S_{\text{navi}} = 55.0$
* 정체 (Congested): $S_{\text{navi}} = 90.0$
* 정보 없음 / 미수신 (Unknown): 기본값 $S_{\text{navi}} = 30.0$ 적용

#### ② 주행 저항 및 정체 지수 ($S_{\text{drive}} \in [0, 100]$)
희망 목표 속도 대비 실제 속도 저하율과 최근 $60\text{ s}$ 브레이크 조작 횟수 $N_{\text{brake}}$를 결합한다.
$$S_{\text{drive}} = \left( 0.6 \times \text{clamp}\left(1.0 - \frac{V_{\text{actual}}}{V_{\text{target}}}, \; 0.0, \; 1.0\right) + 0.4 \times \min\left(\frac{N_{\text{brake}}}{10.0}, \; 1.0\right) \right) \times 100.0$$
* **예외 처리:** 크루즈 컨트롤 미설정 등으로 $V_{\text{target}} \le 0$인 경우, 기본 기준 속도 상수 $V_{\text{default}} = 22.22\text{ m/s}$ ($80\text{ km/h}$)로 대체하여 $0$으로 나누기(Division by Zero) 오류를 방지한다.

#### ③ `PRIVATE_CAR_SAFETY` 경고 밀도 지수 ($S_{\text{safety}} \in [0, 100]$)
방향지시등 점등 시점의 경고등 활성 강도 가중치 $W_{\text{state}}$와 최근 $180\text{ s}$ 동안의 경고 발생 증가량 $\Delta \text{warningCount}$를 결합한다.
$$S_{\text{safety}} = \left( 0.7 \times W_{\text{state}} + 0.3 \times \min\left(\frac{\Delta \text{warningCount}}{5.0}, \; 1.0\right) \right) \times 100.0$$
* **$W_{\text{state}}$ 매핑 규칙:**
  * 모든 안전 관련 Telltale이 꺼짐(`OFF`): $W_{\text{state}} = 0.0$
  * 황색/일반 경고등 단순 점등(`ON`): $W_{\text{state}} = 0.6$
  * 적색 경고등 점등, 고우선순위(`priority`) 경고 활성, 또는 점멸(`BLINKING`) 상태: $W_{\text{state}} = 1.0$

---

## 5. 단계별 구현 과업 (Agent Tasks)

에이전트는 아래 순서에 따라 모듈을 설계하고 구현해야 한다.

* **Task 1: 데이터 모델 및 상태 정의 (`model/`)**
  * 수집된 센서 원시 상태(`RawVehicleSensorState`)와 계산된 결과(`LaneCongestionResult`: 방향, 최종 퍼센트, 하위 점수 3종, 위험 등급, 타임스탬프) 데이터 클래스를 정의한다.
* **Task 2: 시간 창 버퍼 매니저 구현 (`buffer/SlidingWindowTracker`)**
  * 현재 시각 기준 만료된 이벤트($60\text{ s}$ 경과 브레이크 이벤트, $180\text{ s}$ 경과 Telltale 카운트 스냅샷)를 자동 제거(Pruning)하는 스레드 안전(Thread-safe) 버퍼를 구현한다.
* **Task 3: Pleos SDK 래퍼 및 리스너 매니저 구현 (`data/PleosSensorRepository`)**
  * `Vehicle.initialize()` 이후 `getSafety()`, `getTurnSignal()`, `getBrake()`, `getDrivingMode()` 인스턴스를 획득한다.
  * 제어 및 조회 전 반드시 각 도메인의 Capability 확인 함수(예: `checkTelltalesCapabilities`)를 먼저 호출하여 하드웨어 지원 여부를 검증한다.
  * 콜백 리스너들을 `callbackFlow`로 변환하여 단일 상태 스트림으로 병합한다.
* **Task 4: 혼잡도 산출 엔진 구현 (`domain/CalculateLaneCongestionUseCase`)**
  * 제4장에 명시된 수식을 순수 함수(Pure Function) 형태로 구현하여 단위 테스트(Unit Test)가 가능하도록 분리한다.
* **Task 5: 트리거 제어 및 TTS/UI 출력 (`presentation/LaneChangeMonitorViewModel` & `TtsAlertManager`)**
  * `TurnSignalState`가 `LEFT` 또는 `RIGHT`로 변경되는 시점에 계산 엔진을 즉시 트리거한다.
  * 산출된 $S_{\text{total}}$ 값을 UI 상태(`StateFlow`)로 발행한다.
  * $S_{\text{total}} \ge 70.0\%$인 경우, `TextToSpeech SDK`를 `HYBRID` 모드로 초기화하여 *"변경하려는 좌측(또는 우측) 차로의 예상 혼잡도가 $XX\%$로 높습니다. 주의하세요."* 음성을 출력한다.

---

## 6. 필수 제약 사항 및 엣지 케이스 처리 (Guardrails)

1. **TTS 중복 발화 방지 (Debounce & Cooldown):**
   * 방향지시등이 켜진 상태에서 센서 값이 미세하게 변하더라도 TTS 음성이 연속으로 겹쳐 나오지 않도록, 동일 방향 점등 세션당 1회만 발화하거나 최소 $10\text{ s}$의 쿨다운(Cooldown) 타이머를 적용한다.
2. **주행 안전 및 정차 중 필터링:**
   * 차량이 정차 중($V_{\text{actual}} < 0.5\text{ m/s}$)이거나 비상등(좌·우 동시 점멸) 상태일 때는 차로 변경 안내 TTS를 억제하고 화면 지표만 갱신한다.
3. **리소스 해제 (Lifecycle Management):**
   * 앱 종료 또는 서비스 해제 시 `unregisterTelltales()`, `unregisterTurnSignalState()`, `FusedLocation` 콜백 해제 및 `Vehicle.release()`, `TextToSpeech.release()`, `NaviHelper.release()`를 누락 없이 호출하여 메모리 누수와 IPC 좀비 리스너를 방지한다.
4. **에뮬레이터 및 권한 부재 시 폴백(Fallback) 모드:**
   * 일반 개발 환경(서명 권한 미보유 에뮬레이터)에서 `PRIVATE_CAR_SAFETY` 호출이 `onFailed`를 반환할 경우, 앱이 크래시되지 않고 가상 시뮬레이션 데이터(`MockTelltaleProvider`)로 자동 전환되어 UI 및 수식 검증이 가능하도록 인터페이스를 추상화한다.

---

## 7. 완료 기준 (Definition of Done)
* [ ] 모든 Pleos SDK 비동기 콜백이 `onComplete`와 `onFailed`를 모두 처리하며 예외 발생 시 기본값으로 안전하게 폴백하는가?
* [ ] 혼잡도 산출 수식의 결과가 어떤 극단적인 입력값($V_{\text{target}} = 0$, 음수 속도, 경고 횟수 폭증 등)에서도 항상 $0.0 \sim 100.0$ 범위로 수렴하는가?
* [ ] 실제 차량 하드웨어 없이도 시나리오별(원활/서행/정체 + 경고 발생) 결과를 검증할 수 있는 단위 테스트(Unit Test) 케이스가 포함되었는가?