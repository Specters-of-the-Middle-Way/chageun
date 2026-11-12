# 상황별 교육 화면 정리

작업일: 2026-11-12 · 대상: 준비와 연습 → 상황별 교육 · Pleos Connect 2.1 에뮬레이터

## 변경 내용

| 항목 | 반영 내용 |
|---|---|
| 주제 선택 | 7개 주제를 시선·차간 거리, 도로·교차로, 주차·겨울철의 세 그룹으로 묶고 선택한 주제를 녹색으로 표시한다. |
| 설명과 그림 | 주제 제목·현재 단계·핵심 설명·그림을 연한 녹색 카드에 모았다. 그림 안의 작은 글씨는 줄바꿈 가능한 일반 글자로 옮겼다. |
| 이해 확인 | 마지막 단계의 질문과 선택지, 정답·재시도 해설을 별도 카드로 구분한다. 답을 선택하면 해설이 보이도록 본문을 스크롤한다. |
| 단계 유지 | 같은 주제를 다시 눌러도 읽던 단계를 유지한다. 이전 단계로 돌아가면 기존 답을 초기화하는 학습 규칙은 그대로다. |
| 시간 간격 도구 | 속도·거리·비교 시간의 버튼과 계산 결과를 같은 카드 스타일로 정리하고 글씨·차량 그림의 설명을 키웠다. |
| 고정 조작부 | 이전·듣기·다음 및 관련 가상 연습 버튼을 하단에 고정했다. `MainActivity`에서 `WorkflowView`를 감싸던 중복 스크롤을 제거해 본문만 스크롤되도록 했다. |
| 가독성 | 화면 제목 30sp, 주제 제목 28sp, 핵심 설명 25sp, 문제·결과 21~24sp, 선택 버튼 20sp, 보조 설명 18~21sp. 버튼 최소 높이는 64dp다. 좁은 창은 카드가 세로로 배치된다. |

기존 교육 내용·출처, 정답과 이해 확인 기준, 시간 간격 계산식, 정차·준비 확인 조건은 변경하지 않았다. 교육 완료를 실제 운전 능력이나 안전 판정으로 표시하지 않는다. 음성 연수 화면의 별도 스크롤 구조는 유지한다.

## 검증 결과

- 빌드: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` 통과.
- 단위 테스트: 379개 통과. Android Lint 오류·경고 0개.
- 화면 캡처에서 확인한 중복 스크롤 문제를 보완한 뒤 최종 기기 검사 9개를 모두 통과했다.

| 기기 검사 | 확인 범위 | 결과 |
|---|---|---|
| 교육 배치 2개 | 7개 주제의 28단계, 선택 유지·정답/오답·준비 조건·차간 거리 계산. 1707×1018dp의 글꼴 배율 1.2/1.6 및 1100×650dp의 배율 1.2에서 글자 잘림·버튼 크기 확인 | 통과 |
| 실제 교육 화면 1개 | 7개 교육의 문제·해설, 관련 연습 4개 연결, 속도·거리·비교 시간 변경과 정차 예시. 상단 메뉴·하단 버튼·답변 결과가 실제 화면 안에 완전히 보이는지 확인 | 통과 |
| 기존 도로 교육 3개 | 이해 확인 후 연습 연결, 계산 갱신·비활성 조작, 교육 완료 후에도 출발 준비 조건 유지 | 통과 |
| 공통 화면 회귀 3개 | 차량 기능 학습, 가상 코스 연속 완료, 앱 전경 복귀 시 재준비·명시적 재개 | 통과 |

검사에서 변경한 설정은 복원했고 기존 연습 기록을 유지했다. 음성을 끈 상태로 화면·전환을 검사했으므로 이번 결과에 TTS 음질이나 실제 차량 주행 검증은 포함하지 않는다.

## 화면과 로컬 근거

- [주제와 그림](../.tooling/situation-education-screens/look_ahead-step-1.png) · [노면 표시](../.tooling/situation-education-screens/road_markings-step-2.png)
- [이해 확인 완료](../.tooling/situation-education-screens/look_ahead-completed.png) · [재시도 해설](../.tooling/situation-education-screens/try-again.png)
- [시간 간격 계산](../.tooling/situation-education-screens/gap-calculator.png) · [정차 예시](../.tooling/situation-education-screens/gap-stationary.png)
- [빌드 로그](../.tooling/situation-education-build.log) · [최종 기기 검사](../.tooling/situation-education-final-device.log)
- [최종 APK](../.tooling/situation-education-build/app/outputs/apk/debug/app-debug.apk) · [Lint 결과](../.tooling/situation-education-build/app/reports/lint-results-debug.xml)

`.tooling`은 Git에서 제외한 로컬 검사 자료다. 화면·로그·APK 링크는 이 PC에서 확인할 수 있다. 이번 변경은 교육 화면과 학습 전환의 개선이며 기획 대비 기능 구현 등급을 올리지는 않는다.
