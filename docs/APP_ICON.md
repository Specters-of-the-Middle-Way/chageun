# 차근 앱 아이콘

2026-11-11: 양방향 화살표를 흰색 차량·민트색 도로로 교체했다. 짙은 청록색 배경으로 작은 앱 목록에서도 형태를 구분한다. 제조사 로고나 실차 이미지를 사용하지 않는 자체 벡터다.

## 리소스

- `app/src/main/AndroidManifest.xml`: `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round` 연결.
- `app/src/main/res/drawable/ic_launcher_background.xml`: 청록색 그라데이션 배경.
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: 차량과 도로.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`: 단색 테마용 실루엣.
- `app/src/main/res/mipmap-anydpi/`: 기본·원형 adaptive icon. 앱 최소 API가 28이므로 별도 v26 폴더가 필요하지 않다.
- `app/src/main/res/mipmap-anydpi-v33/`: 단색 레이어를 포함한 API 33 이상 버전.

[Android 공식 adaptive icon 안내](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)의 108dp 레이어와 중앙 66dp 안전 영역 기준을 적용했다. 런처가 원형·둥근 사각형 등 최종 마스크를 정한다.

## 확인 방법

```bash
./scripts/build.sh --console=plain :app:assembleDebugAndroidTest
python3 scripts/verify-workflow.py --serial emulator-5554
```

`LauncherIconTest`가 설치된 리소스를 Android Canvas로 렌더링한다. 디버그 앱의 `files/verification-icons/`에 기본 512px·48px, 원형 192px, 단색 테마 192px 결과를 만든다. 단색 테스트는 해당 레이어 렌더링 검사이며 Pleos 런처가 테마 전환 UI를 제공한다는 뜻은 아니다.

실제 Pleos API 34 앱 목록에서 새 아이콘이 표시되고 해당 아이콘으로 앱이 열리는 것을 확인했다. 덮어 설치로 적용했으며 기존 앱 데이터를 초기화하지 않았다. 로컬 확인 이미지: `.tooling/icon-preview/`.
