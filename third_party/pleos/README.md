# Pleos Connect 공식 디자인 에셋 적용

원본: https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig
공식 제공 페이지: https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro
확인일: 2026-11-09
권리자: Hyundai Motor Group. 원본에 대한 권리를 변경하거나 오픈소스 라이선스를 부여하지 않습니다.

`app/src/main/res/drawable/pleos_*.xml`은 공식 Figma 파일의 원본 벡터 path와 노드 변환을 읽어 Android VectorDrawable 형식으로 변환한 것입니다. 생성형 이미지나 육안으로 따라 그린 아이콘이 아닙니다. 원본 노드·경로·파일 SHA256은 `asset-provenance.json`에서 확인합니다. 앱에서 크기와 단색 tint를 적용합니다. `pleos_seat`는 원본 열선 시트 구성 요소의 시트 모양 path만 사용한 파생 에셋입니다.

색상·버튼 규격은 `design-tokens.json`에 원본 모드와 함께 기록합니다. `PleosUi.kt`는 디자인 에셋을 네이티브 Android 컨트롤에 적용하는 앱 코드이며, 현대차가 배포한 Android 위젯 라이브러리라고 주장하지 않습니다.

원본 .fig 전체와 원본에 포함된 타사 앱 로고·인물 사진은 앱에 포함하지 않습니다. 가상 도로와 주차장의 좌표·차체 윤곽은 시뮬레이션 렌더링이며 공식 카메라 영상·차량 3D 모델 에셋이 아닙니다.

이 디자인 적용은 공모전 프로토타입용입니다. 원본 자료의 공개 재배포·상용 배포 범위는 Pleos 서비스 약관과 별도 이용허락을 확인해야 합니다. 공식 가이드가 제공하는 앱 디자인 활용과 에셋 원본 자체의 재배포를 구분합니다.
