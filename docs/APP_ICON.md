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

## 하단 바로가기에 예전 아이콘이 남는 현상 재확인

2026-11-11 후속 검사에서 설치된 앱 리소스와 앱 목록은 흰색 차량·민트색 도로였지만, 하단 바로가기는 예전 양방향 화살표를 표시했다. `dumpsys window`에서 이 하단바가 `com.android.systemui`의 `BottomCarSystemBar`임을 확인했다. 아이콘 파일이나 Manifest 연결 문제가 아니라 실행 중인 Pleos 하단바의 이전 아이콘 유지 현상으로 판단한다.

새 APK 덮어 설치 후 **에뮬레이터를 재시작하자 하단 바로가기도 새 차량 아이콘으로 갱신됐다.** 앱 삭제, 앱/런처 데이터 초기화, 시스템 앱 수정은 하지 않았다. 사용자 기록과 설정을 보존했다. 같은 현상이 재발하면 먼저 앱 목록을 확인하고, 앱 목록만 새 아이콘일 경우 데이터 초기화 없이 에뮬레이터 재시작으로 하단바를 다시 확인한다.

- 리소스 렌더링: `.tooling/icon-review/launcher-512.png`, `launcher-48.png`, `launcher-round.png`, `launcher-themed.png`
- 앱 목록과 하단바가 다르게 표시된 화면: `.tooling/icon-drawer-before.png`
- 재시작 후 하단바 갱신 화면: `.tooling/icon-after-reboot.png`
- 새 하단 아이콘을 눌러 실제 앱이 열린 화면: `.tooling/icon-final-home.png`, `.tooling/enhancements-manual-screen.png`
- 기존 `LauncherIconTest`도 최신 설치 APK에서 통과했다.
