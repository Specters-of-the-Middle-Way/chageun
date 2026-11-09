# v0.9 · Pleos 공식 에셋 적용

기준일: 2026-11-09. v0.8의 준비·주행·주차 기능에 **Pleos Connect 공식 배포 Figma 파일의 벡터 에셋**을 적용했습니다. 기존 민트색 카드와 자체 제작 차량·사람·코스 일러스트를 정리하고, 공식 아이콘과 상태 그래픽을 사용하는 화면으로 바꿨습니다.

## 실제로 가져온 에셋

원본은 [Pleos Connect design assets 215.fig](https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig)이며, [공식 디자인 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)의 다운로드에서 제공하는 파일입니다.

앱에는 **아이콘 30개, 스위치 상태 2개, 체크박스 상태 2개로 총 34개 벡터 리소스**를 포함합니다. 이는 패키지에 포함한 개수이며, 모든 변형이 모든 화면에 표시된다는 뜻은 아닙니다.

| 항목 | 적용 방식 | 화면에서 사용하는 예 |
|---|---|---|
| 공식 아이콘 | 원본 path와 노드 변환을 읽어 Android VectorDrawable로 변환 | 프로필, 차량, 시트, 핸들, 확인, 이전·다음, 소리, 정보 |
| 공식 스위치 | 원본 on/off 그래픽에 Android `CompoundButton` 입력·체크 상태·접근성 연결 | 음성 안내, 기록 저장 동의, 차량 신호 연결 |
| 공식 체크박스 | 원본 선택·해제 상태 그래픽과 준비 상태 연결 | 출발 전 확인 항목 |
| 공식 색상·버튼 상태 | 원본 색상·곡률·눌림 토큰을 네이티브 버튼·배경·텍스트에 적용 | 밝은 배경, 흰 콘텐츠 영역, 회색 선택 버튼, 눌림·비활성 상태 |
| Asta Sans | 원본 가변 글꼴을 유지하고 Android family에서 굵기 지정 | 일반 400, 굵은 글씨 700 |

`pleos_seat`는 공식 열선 시트 에셋에서 **시트 모양 path만 분리한 파생 에셋**입니다. 아이콘은 표시 크기와 단색 tint를 앱에서 적용합니다. 원본 도형을 생성형 이미지로 만들거나 육안으로 다시 그린 방식은 사용하지 않았습니다.

원본 Figma의 SHA256은 `64ddc591e4a12f765c1d3b4b36473c22c3de312c78b241aab1bf9b680a228db6`입니다. 각 리소스의 원본 노드 ID·노드 경로·변환 방법·파일 해시는 [asset-provenance.json](../third_party/pleos/asset-provenance.json)에, 색상과 규격은 [design-tokens.json](../third_party/pleos/design-tokens.json)에 기록합니다.

## 네이티브 구현과 시뮬레이션의 구분

`PleosUi`와 `PleosSwitch`는 **공식 디자인 에셋을 앱에서 동작하게 연결한 네이티브 Android 코드**입니다. 현대차가 배포한 Android 위젯 SDK를 설치한 것은 아닙니다. 앱의 배치에 맞춰 표시 크기를 조정하며, 모든 컨트롤이 원본 컴포넌트의 높이·글자 크기와 동일한 것은 아닙니다. 버튼 클릭, 선택 상태, 접근성, 화면 전환은 앱 코드가 처리하고 표시용 벡터에는 공식 에셋을 사용합니다.

프로필·자세·연습 선택 화면의 자체 제작 차량, 사람 자세, 코스 소개 Canvas 그림을 제거했습니다. 차량 정보는 공식 차량 아이콘과 제원 텍스트로 표시하고, 자세 안내는 공식 시트·핸들·시야 아이콘과 단계별 안내로 제공합니다. 차량 아이콘은 차종 공용 심볼이며 IONIQ 3 외형을 재현한 3D 모델이 아닙니다.

주행·주차 시뮬레이션에서 **도로·주차선·가상 차량 윤곽·예상 경로는 계속 앱이 렌더링**합니다. 움직임과 조작 결과를 표현하는 교육용 화면입니다. 해당 도형을 공식 차량 그래픽이나 카메라 영상으로 표시하지 않습니다. 주행 화면의 장식적 배경은 줄이고 차로·주변 차량·미러 확인에 필요한 정보에 집중했습니다.

Asta Sans 원본은 기본 굵기가 300인 가변 글꼴입니다. 원본 파일을 `asta_sans_variable.ttf`로 보관하고 `asta_sans.xml`에서 일반 400·굵게 700을 지정해 본문이 지나치게 가늘게 보이던 부분을 조정했습니다. [글꼴 출처·라이선스](../third_party/asta-sans/README.md)를 함께 보관합니다.

## 유지한 기능

프로필 → 운전 환경 9항목 → 출발 전 확인 7항목 → 가상 주행·주차 → 복습 흐름과 데이터 동의·삭제 처리는 유지합니다. 후진·평행주차는 화면의 기어·핸들·페달을 직접 조작하고, 잘못된 기어 조작의 음성 안내와 가상 완료·회고를 제공합니다.

대표 프로필은 **IONIQ 3 유럽형 일반 차체, 180cm·78kg(75~80kg 범위), 남성·동아시아인**입니다. 체격·성별·인종으로 실차 시트 위치를 자동 산출하지 않습니다. 시트·핸들 0~100 값은 실차 단위와 연결되지 않은 가상 수동 설정이며, 실제 자동 조절은 연결하지 않았습니다. 제원과 모델 가정은 [v0.8 구현 범위](V08_FLOWCHART_IMPLEMENTATION.md)를 참고하세요.

## 확인 상태

2026-11-09, Pleos 2.1.0 / Android 14 에뮬레이터에서 확인했습니다. 전체 화면은 1920×1080, 앱 사용 영역은 약 1255×850입니다.

- 최종 Debug APK와 Release unsigned APK 빌드 성공.
- JVM 단위 테스트 **205개 통과**, 실패·오류·건너뜀 0개.
- Android lint **오류 0개·경고 54개**. 원본 도형 보존에 따른 긴 벡터 path, 포함된 미사용 변형, 기존 커스텀 View 등의 경고가 남습니다. 경고를 숨기거나 원본 도형을 임의 단순화하지 않았습니다.
- 포함한 34개 벡터의 XML 구문·파일 해시·출처 목록 일치 확인. 표시가 불확실했던 radio 원본은 제외했습니다.
- 프로필·시트 설정·출발 확인·연습 선택·주차·주행 화면을 실제 에뮬레이터에서 캡처해 잘림과 대비를 확인했습니다.
- 기록 동의와 음성 스위치를 각각 껐다 켜며 화면 표시와 Android checked 상태가 일치하는지 확인했습니다.
- 7개 출발 항목 미확인 시 연습 선택이 비활성화되고, 모두 확인하면 열리는 것을 확인했습니다. 선택/해제 체크박스 원본을 한 화면에서 검토했습니다.
- 후진 주차: 브레이크 없이 R 선택 → 수정 음성 → 브레이크 유지 후 R → 화면 가속으로 후진 → 정지·P·수동 완료 → 자기평가·저장·복습. 저장 기록의 `source=SIMULATION`과 목표 구역 충족을 확인했습니다.
- 차로 변경: 잘못된 오른쪽 신호 → 교정 음성 → 왼쪽 신호 → 미러 확인 → 조향 안내를 확인하고 일시정지했습니다.

가상 상태를 성공 위치에 강제 주입하지 않고 화면의 실제 조작부를 사용했습니다. 최종 UI 실행 로그는 로컬 `.tooling/verify-v09-ui.log`, 빌드 로그는 `.tooling/build-v09.log`입니다. 이번에는 UI 교체 후 해당 경로를 다시 확인했으며, 평행 주차 완주·차로 변경 전체 완주·실제 D 제한의 전체 시나리오 재실행 결과와는 구분합니다. 해당 경로의 이전 검증은 [v0.8 기록](V08_VALIDATION.md)에 있습니다. 실차 검증 결과는 아닙니다.

## 화면 기록

- [프로필](screenshots/70-pleos-profile.png)
- [시트·자세 설정](screenshots/71-pleos-seat-setup.png)
- [출발 전 확인](screenshots/72-pleos-preflight.png)
- [연습 선택](screenshots/73-pleos-practice.png)
- [주차 시뮬레이션](screenshots/74-pleos-parking.png)
- [주행 시뮬레이션](screenshots/75-pleos-driving.png)
- [주차 완료·복습](screenshots/76-pleos-parking-review.png)

## 구현 위치와 출처

- [PleosUi.kt](../app/src/main/java/kr/drivestep/tutor/design/PleosUi.kt): 공식 토큰·벡터를 적용하는 네이티브 UI 공통 코드
- [PleosSwitch.kt](../app/src/main/java/kr/drivestep/tutor/design/PleosSwitch.kt): 공식 스위치 상태 그래픽과 Android 입력·접근성 연결
- `app/src/main/res/drawable/pleos_*.xml`: 원본에서 변환한 벡터 리소스
- `app/src/main/res/font/asta_sans.xml`: 글꼴 굵기 매핑
- [Pleos 에셋 출처·사용 범위](../third_party/pleos/README.md)
- [공식 색상 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors), [기본 버튼](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button), [아이콘](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/iconography), [Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography)
