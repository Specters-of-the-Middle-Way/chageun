# 주행 연습 화면 정리

작업일: 2026-11-12 · 대상: 준비와 연습 → 주행 연습(D01~D18), Pleos 2.1 에뮬레이터

## 변경 내용

| 화면 | 변경 |
|---|---|
| 상황 선택 | 18개 상황을 기본 조작과 속도, 교차로와 회전, 차로 변경과 합류, 도로 상황과 돌발 대응으로 구분했다. 제목과 설명이 있는 큰 카드로 배치하고 기본 조작 바로 시작을 추가했다. |
| 연습 중 | 도로 장면과 현재 안내·가상 수치를 나란히 배치했다. 방향지시등, 미러 확인, 핸들, 페달을 각각 카드로 나누고 시작·일시정지·마치기는 위에 고정했다. |
| 돌아보기 | 결과 요약, 직접 남긴 소감, 다음에 더 익힐 것을 구분했다. 선택 상태를 녹색으로 표시하고 다음 연습 버튼을 아래에 고정했다. |
| 가독성 | 제목 23~30sp, 안내 23sp, 조작 버튼 20sp, 보조 설명 18~20sp를 사용한다. 버튼 최소 높이는 64dp다. 긴 안내는 줄바꿈하며 좁은 창은 세로 배치·스크롤로 대응한다. |

기존 준비·차로 변경·주차 화면과 같은 녹색 강조색, 흰색 카드와 둥근 버튼을 사용한다. 상황 제목을 굵게 표시하고 내부 과제 번호 대신 익숙한 상황 이름을 앞에 보여준다.

## 동작 보완

- 시작·이어하기를 누르는 즉시 화면 조작부를 활성화한다. 일시정지 중에는 페달·핸들·방향지시·미러 조작을 비활성화한다.
- 일시정지로 모델의 조향 입력이 해제되면 슬라이더도 중앙으로 맞춘다. 연습 중 음성 설정을 바꿀 때도 입력을 해제하고 명시적인 이어하기를 기다린다.
- 돌아보기에서 소감·어려움을 선택해도 현재 스크롤 위치를 유지하며, 새 과제를 시작하면 이전 저장 결과 문구를 지운다.
- 가상 과제의 이동·완료 조건, 준비 확인, 실제 차량 정차 조건, 기록 동의 규칙은 유지한다. 기록 비동의 상태에서는 저장 버튼을 사용할 수 없다.

## 검증 결과

| 검사 | 결과와 범위 |
|---|---|
| 빌드·단위 검사·Lint | `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` 통과. JVM 379개 통과, Lint 오류·경고 0개. |
| 전체 화면 계측 2개 | 18개 상황의 선택·시작·방향지시·미러 확인·일시정지·재개·취소·돌아보기·다음 연습, 준비 미완료 차단과 기록 비동의를 확인했다. |
| 글씨와 터치 영역 | 1,707×1,018dp / 글자 배율 1.2·1.6, 1,100×650dp / 배율 1.2에서 텍스트 잘림·말줄임과 터치 크기를 검사했다. |
| 실제 완료 흐름 1개 | 실제 MainActivity에서 D03 가속·감속·재출발, D05 교차로·신호, D15 재탐색, D18 경고 후 정차를 화면 조작으로 완료했다. 차량 위치나 완료 상태를 강제로 주입하지 않았다. |
| 전경 복귀 회귀 검사 1개 | 앱을 나갔다 돌아오면 정지 상태를 유지하고 출발 준비를 다시 확인한 뒤 사용자가 이어하기를 눌러야 재개하는 기존 흐름을 확인했다. |

기기 검사 총 **4개 통과**. 기본 창에서 현재 안내와 주요 조작부가 함께 보이는 실제 화면을 확인했다. 18개 과제 전부를 마지막 완료 조건까지 운전한 것은 아니며, 실제 시간 진행으로 완료한 과제는 위 4개다. 검사 후 동의·음성 설정과 기존 중단 지점을 복원했으며 기존 기록이 바뀌지 않은 것을 확인했다. 마이크 전달·글레오 연속 대화 설정은 변경하지 않았다.

## 화면과 근거

- [상황 선택](../.tooling/driving-practice-screens/task-selection.png) · [아래쪽 상황 목록](../.tooling/driving-practice-screens/task-selection-lower.png)
- [연습 조작부](../.tooling/driving-practice-screens/practice-controls.png) · [일시정지](../.tooling/driving-practice-screens/practice-paused.png)
- [완료 화면](../.tooling/driving-practice-screens/practice-completed.png) · [소감과 다음 과제 선택](../.tooling/driving-practice-screens/practice-reflection.png)
- [신호 대기](../.tooling/driving-practice-screens/signal-waiting.png) · [재탐색](../.tooling/driving-practice-screens/reroute.png) · [경고 대응 완료](../.tooling/driving-practice-screens/warning-completed.png)
- [빌드 로그](../.tooling/driving-practice-build.log) · [전체 화면 계측](../.tooling/driving-practice-layout.log) · [실제 조작 완료](../.tooling/driving-practice-visible.log) · [전경 복귀 검사](../.tooling/driving-practice-recovery.log)
- [최종 APK](../.tooling/driving-practice-build/app/outputs/apk/debug/app-debug.apk) · [단위 검사 결과](../.tooling/driving-practice-build/app/test-results/testDebugUnitTest) · [Lint 결과](../.tooling/driving-practice-build/app/reports/lint-results-debug.xml)

공유 빌드 출력과 충돌하지 않도록 `.tooling/driving-practice-build`에 별도로 빌드했다. `.tooling` 자료는 이 PC의 로컬 생성물이며 Git에 포함되지 않을 수 있다. 이번 변경으로 기획 대비 기능 구현 등급을 올리지는 않는다.
