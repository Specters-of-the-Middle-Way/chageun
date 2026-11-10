# 차근 (Chageun / DriveStep) — AI 에이전트 스킬 인덱스

이 `/skills` 디렉터리는 Pleos Connect / Android Automotive 기반 초보 운전자 코칭 프로토타입 **차근(`차근`, `kr.drivestep.tutor`, `v0.9.0+`)**의 아키텍처, 도메인 가드레일, SDK 연동, 로컬 저장소 및 QA 검증 명세를 담고 있습니다.

AI 코딩 에이전트(**Codex**, **Antigravity** 및 서브에이전트)는 작업 시작 전 이 인덱스를 먼저 확인하고, 현재 작업에 필요한 영문 스킬 파일(`01`~`06`)만 선택적으로 로드하여 토큰 효율성과 빌드 안정성(Zero-Warning Clean Lint)을 유지해야 합니다.

제품 목표와 입력 방식을 다룰 때는 [2026-11-10 갱신 제품 방향](../docs/COACH_PRODUCT_DIRECTION.md)을 함께 확인합니다. 최종 목표는 차량 신호·비전 기반 자동 연수 안내이며, 현재 외부 LLM은 카메라·주변 객체 데이터 확보 전의 임시 대화 경로입니다. 보관된 차로 혼잡도 지시서는 검증 전 참고 제안이며, 임의 퍼센트나 미확인 권한·API를 사실로 구현하지 않습니다.

---

## 프로젝트 핵심 메타데이터 요약

| 항목 | 설정값 |
|---|---|
| **앱 이름** | `차근` (`@string/app_name`) |
| **루트 패키지** | `kr.drivestep.tutor` |
| **Gradle 프로젝트명** | `DriveStep` |
| **SDK / 빌드 환경** | `minSdk = 28`, `targetSdk = 35`, `compileSdk = 35`, JDK 17, Kotlin 2.0.0, AGP 8.8.2 |
| **차량 라이브러리** | `useLibrary("android.car")` + `implementation("ai.pleos.playground:Vehicle:2.3.2")` |
| **UI 스택** | 프로그래매틱 Android `View` / `LinearLayout` / `Canvas` + Pleos 공식 벡터 어댑터 (`PleosUi`, `PleosSwitch`, `PracticeButton`) — **Jetpack Compose 미사용** |
| **기준 차량 제원** | 현대 IONIQ 3 유럽형 일반 차체 (`4,430 × 1,870 × 1,560 mm`, 축거 `2,740 mm`, 최소 회전반경 `5.32 m`) |

---

## 스킬 카탈로그 및 작업별 라우팅 가이드

코드를 수정하기 전에 담당 작업과 일치하는 스킬 문서를 먼저 읽고 규칙을 준수하세요. (개별 스킬 파일 `01`~`06`은 AI 에이전트가 파싱하기 용이하도록 영문으로 작성되어 있습니다.)

1. **[`01-architecture-and-clean-lint.md`](./01-architecture-and-clean-lint.md) — 아키텍처 & Clean Lint 8대 규칙**
   - **언제 읽어야 하는가:** Kotlin 클래스, Android 커스텀 `View`, `Canvas` 렌더링, 또는 XML 리소스를 생성·수정할 때
   - **포함 내용:** 패키지 구조(`design/`, `journey/`, `parking/`, `simulator/`, `contextcoach/`, `vehicle/`, `practice/`, `coach/`, `data/`), **3-File 모듈형 패턴(`*Session`/`*State` + `*View` + `*Test`)**, `:app:lintDebug` 경고 0건을 유지하기 위한 **8대 Clean Lint 철칙**

2. **[`02-domain-guardrails-and-flowchart.md`](./02-domain-guardrails-and-flowchart.md) — 도메인 안전 가드레일 & 11쪽 플로우차트 상태 머신**
   - **언제 읽어야 하는가:** 사용자 흐름, 정차 안전 게이트, 출발 전 체크리스트, 주행·주차 코칭, 도착 후 복습 로직을 구현·수정할 때
   - **포함 내용:** 5대 절대 안전 원칙, `ParkedGate` (`P` 단 + `속도 == 0` + 주행 중 제한 해제 확인), 11쪽 상세 플로우차트 상태 전이(`JourneyState`), **학습 완료 / 운전자 자신감 / 연습 관측 결과** 3대 지표 분리 원칙

3. **[`03-body-profile-and-posture-canvas.md`](./03-body-profile-and-posture-canvas.md) — 신체 조건 기반 가이드 구간 & 자세·미러 Canvas 시각화**
   - **언제 읽어야 하는가:** `DriverProfile`, `VirtualSeatCalibration`, `PostureGuideBand`, `PostureGuideView`, 또는 9단계 자세·미러·벨트 설정 화면을 다룰 때
   - **포함 내용:** 운전자 키·체중(`heightCm`, `weightKg`) 기반 참고 시작 구간 산출식, 실시간 `Canvas` 자세·미러 시각화(`PostureGuideView`), 중립 기본값(`50`) 유지 원칙, 시트·핸들 변경 시 미러 확인(`5..7`) 자동 무효화 규칙

4. **[`04-pleos-sdk-and-2tier-timeout.md`](./04-pleos-sdk-and-2tier-timeout.md) — Pleos Vehicle SDK 2.3.2 & 2-Tier 타임아웃 정책**
   - **언제 읽어야 하는가:** `vehicle/`, `practice/`, `contextcoach/`, 또는 `coach/VoiceCoach.kt`를 수정할 때
   - **포함 내용:** AAOS (`android.car`) vs Pleos Vehicle SDK (`2.3.2`) API 우선순위, **2-Tier 차량 I/O 타임아웃 정책**(`VehicleTimeoutPolicy` — 정차 중 초기 연결 10초 vs 주행·주차·실습 행동 중 1.5~2.5초), 초기 스냅샷(`isInitial = true`)과 실제 조작 이벤트 분리, 입력 출처(`SIMULATION` vs `SDK`) 격리

5. **[`05-local-storage-and-voice-assets.md`](./05-local-storage-and-voice-assets.md) — 로컬 저장소 스키마, 음성 에셋 동기화 & 한국어 문구 규칙**
   - **언제 읽어야 하는가:** 로컬 데이터 저장(`data/`, `SharedPreferences`, `SQLite`, `AtomicFile`), 한국어 UI 문구, 또는 `CoachCue` 음성 안내를 추가·수정할 때
   - **포함 내용:** 6종 로컬 저장소 스키마 및 보존 정책, 동의 철회와 범위별 삭제 분리, 194개 고정 한국어 음성 3자 동기화(`CoachCue` ↔ `res/raw/coach_*.wav` ↔ `res/values/coach_voice_resources.xml`), 초보 운전자 친화 한국어 표준 용어(`"백미러"` 금지 → `"룸미러"` 사용 등)

6. **[`06-qa-simulation-and-git-workflow.md`](./06-qa-simulation-and-git-workflow.md) — QA 시뮬레이션 파이프라인 & Git 워크플로우 (`main`)**
   - **언제 읽어야 하는가:** 테스트 실행, 변경 사항 검증, `/simulation/test` 시나리오 추가, 또는 `main` 브랜치 커밋·푸시를 수행할 때
   - **포함 내용:** `main` 브랜치 직접 통합 워크플로우, 푸시 전 필수 Gradle 검증 게이트(`assembleDebug`, `testDebugUnitTest`, `lintDebug`), `/simulation/test` 3단계 QA 파이프라인 및 공모전 시연 체크포인트 운영 가이드
