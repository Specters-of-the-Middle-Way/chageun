# Pleos 기본 내비게이션 진단 — 2026-11-11

대상: Windows Android Emulator 36.5.11, DriveStep_Pleos, Pleos 2.1.0 / API 34, 사용자 10, 기본 내비 3.3.2-rc5-hotfix.3 (ai.umos.maps.android.navigation.app).

**흰 지도는 배포 앱이 도로가 없는 connect_empty 스타일을 선택하는 것으로 원인을 확인했다. GPS NMEA 체크섬 오류는 에뮬레이터 실행·입력 보정으로 제거했다. 그러나 기본 내비의 지도와 현재 위치 마커·경로 안내는 정상화되지 않았다.**

## 결과 요약

| 항목 | 최종 결과 | 근거 |
|---|---|---|
| 흰 지도 | 원인 확인, 미해결 | coral 빌드가 connect_empty 선택. 실제 응답에 도로·건물·POI 레이어가 없다. 토큰 승인 후 일반 도로 타일은 200 및 실제 도형 확인. 내비 화면은 여전히 흰색이다. |
| GPS NMEA 체크섬 | 보정 모드에서 해결 | 정상 문장 25쌍 시험 오류 0건. 오류 문장 1개 대조 시험은 오류 1건. 보정 도구 실행 후 추가 30초 검사도 오류 0건. |
| Android 위치 공급자 | 복정동 시험 위치 수신 | dumpsys location과 FusedLocation에서 시험 좌표 수신 확인. 실제 PC/실차 GPS가 아니다. |
| 기본 내비 위치 마커 | 미해결 | 입력은 복정동인데 마커는 ‘수정구 시흥동’. 자동차 환경의 기본 GPS 경로가 좌표를 기본값으로 교체한다. |
| 차근 현재 위치 날씨 | 시험 위치로 조회 성공 | 재시작 후 자동 날씨 조회에서 6.1°C 확인. 지정 지역 대체 조회와 구분한다. |
| 정상 경로 안내 | 미확인 | 지도·위치가 복구되지 않아 정상 내비 동작 판정 불가. |

## 흰 지도 원인

배포 APK를 읽기 전용으로 추출해 확인한 MapStyleRemoteDataSourceImpl의 getMapStyles 계열 메서드는 빌드 종류가 coral이면 [connect_empty](https://a.tile.static.maps-coral.umos.ai/styles/42dot/connect_empty) 스타일을 반환한다. 2026-11-11에 해당 공개 주소와 같은 호스트의 [일반 connect_v2 스타일](https://a.tile.static.maps-coral.umos.ai/styles/42dot/connect_v2)을 요청해 실제 응답을 비교했다. 인증 토큰은 사용하지 않았다.

| 스타일 | HTTP | 데이터 소스 | 레이어 | 내용 |
|---|---|---:|---:|---|
| connect_empty | 200 | 1 | 4 | 바탕, coastline, driving_carpet, driving_carvatar. 낮 배경 #F6F8FC. 도로·건물·POI 없음. |
| connect_v2 | 200 | 17 | 263 | 도로·건물·POI 등을 포함한 일반 지도 스타일. |

선택된 스타일 자체가 빈 지도인 것은 확인됐다. 일반 스타일 문서의 HTTP 200만으로 그 스타일의 모든 타일 인증·표시까지 검증됐다고 보지는 않는다. 기존 OpenGL 오류나 레이어 누락 경고만으로 원인을 확정하지 못했던 이전 판단을 이 결과로 갱신한다.

일반 설정에는 스타일 변경 항목이 없었다. 개발 메뉴에는 비밀번호 입력창이 있었고, 사용자는 제공받은 비밀번호가 없다고 답했다. 해당 창을 닫았으며 비밀번호 추측이나 보호 절차 우회는 하지 않았다. APK는 공급사 서명으로 배포되어 현재 프로젝트의 키로 수정 APK를 기존 시스템 앱에 업데이트할 수 없다. 원본 APK·시스템 이미지·서명은 변경하지 않았다.

## GPS 보정과 남은 위치 문제

기존 자동 NMEA에서는 체크섬 불일치(expected=47, actual=…)가 반복됐다. 올바른 문장을 추가로 보내도 자동 입력의 잘못된 문장과 섞였다.

에뮬레이터를 **-no-passive-gps**로 다시 실행해 자동 입력을 끄고, 복정동 시험 위치 **위도 37.46619867 / 경도 127.126595**의 GGA/RMC 문장을 올바른 체크섬으로 1초 간격으로 전달했다. 사용자 데이터 초기화는 하지 않았다.

- 정상 NMEA 25쌍: 체크섬·파싱 오류 **0건**.
- 의도적으로 체크섬이 틀린 문장 1개를 보낸 대조 검사: 오류 **1건**. 수신기가 중지되어 오류가 사라진 것은 아니다. 이후 정상 입력으로 복원했다.
- 보정 도구 실행 후 최종 30초 검사: 로그 1,263줄에서 체크섬·파싱 오류 **0건**, 보정 프로세스 **1개**.
- Android GPS/FusedLocation은 복정동 시험 좌표를 받았다. 차근 자동 날씨 조회도 성공했다.
- 기본 내비 마커는 여전히 시흥동이었다. 배포 코드의 GpsManager.getLastLocation()은 ro.hardware.type이 automotive이면 위도·경도를 getDefaultPoint(countryCode) 값으로 바꾼다. GpsLocationSourceRepositoryImpl의 시작 루프가 이 경로를 반복 호출한다. 이 AVD의 실제 속성도 automotive다. 차량 VNet 등 다른 입력 경로까지 정상이라고 검증한 것은 아니다.

### 다음 실행 방법

프로젝트 루트의 PowerShell에서 **./scripts/start-pleos-windows.ps1**을 실행한다.

기존 AVD가 다른 옵션으로 켜져 있으면 정상 종료한 뒤 실행한다. 스크립트는 기존 데이터를 지우지 않으며 host GPU와 -no-passive-gps를 사용한다. 부팅 후 숨겨진 Python 보조 프로세스가 정상 NMEA를 공급하고 상태 파일 갱신을 확인한다. 같은 에뮬레이터의 중복 보조 프로세스는 잠금으로 막는다. 현재 이 방식으로 실행 중이다.

이 모드는 **복정동에 정지한 시험 위치**다. 에뮬레이터 위치 UI의 자동 경로 재생과 동시에 사용하는 모드가 아니며 실제 주행·PC 위치를 나타내지 않는다. 보조 도구 자체는 로컬 ADB만 사용한다. 위치를 이용하는 앱은 기존 권한·동의에 따라 날씨·지도 서버로 요청할 수 있다.

일반 모드로 돌아가려면 에뮬레이터를 종료하고 평소처럼 -no-passive-gps 없이 실행한다. 보조 프로세스는 에뮬레이터 연결이 끊기면 종료한다. 일반 모드에서는 기존 NMEA 오류가 재발할 수 있다.

도구: [Windows 실행 스크립트](../scripts/start-pleos-windows.ps1), [NMEA 입력 보조 도구](../scripts/pleos-gps-feed.py). Python 경로가 다르면 실행 스크립트에 -PythonPath를 지정한다. 현재 PC의 기존 .tooling/run-local.ps1 실행에도 같은 보정을 연결했다.

## 서버·복구 조사 범위

- 사용자의 지도 서버 좌표 전송 승인 후 지원 확대 수준인 zoom 16의 복정동 타일을 검사했다. 공개 TileJSON은 200, 인증 없는 별도 stage 타일 요청은 401이었다. **별도 무인증 요청의 401을 앱 자체의 인증 실패로 동일시하지 않는다.**
- 기본 내비 초기 인증 성공 로그가 있다. 별도 EV API의 401은 지도 타일 전체 인증 실패의 증거가 아니다.
- 기본 내비 재실행과 소프트웨어 그래픽 비교로 복구되지 않았다. 최종 그래픽 모드는 host다.
- 공식 Connect AVD 저장소와 설치 이미지 모두 revision 2 / Pleos 2.1.0이었다. 동일 이미지 재설치는 하지 않았다.
- 앞선 -gps 옵션 시도는 설치된 QEMU가 거절했다. 이번에는 설치 실행 파일이 지원하는 -no-passive-gps 방식으로 보정했다.

초기 토큰 재사용 시도는 자동 승인 검토에서 거절되어 실행하지 않았다. 이후 사용자가 **인증 토큰 추출·재사용을 명시적으로 승인**하여 아래 검사를 수행했다. 이 항목은 더 이상 승인 대기 문제가 아니다.

### 토큰 승인 후 추가 검증 — 2026-11-11 23:35 이후 KST

기본 내비 재실행으로 발급된 유효 토큰 1개를 메모리에서 읽어 **a.tile.static.maps-coral.umos.ai**의 복정동 zoom 16 타일에만 재사용했다. 리디렉션을 따르지 않으며, 토큰을 다른 서버나 보고서에 전달하지 않았다. 같은 타일을 토큰 없이도 비교했다.

| 소스 | 토큰 없음 | 토큰 사용 | 해석 |
|---|---|---|---|
| transport_cnct | 200 / 12,593바이트 | 200 / 12,593바이트 | 일반 도로 타일을 두 경우 모두 받음. 압축 해제 후 벡터 구조도 정상적으로 해석됨. |
| sole_hd_map | 204 | 204 | 해당 시험 타일에는 응답 본문 없음. 인증 거절 401이 아님. |
| coastline | 204 | 204 | 해당 시험 타일에는 응답 본문 없음. 인증 거절 401이 아님. |

도로 타일은 9개 레이어·434개 도형을 포함했다. 그중 road_detail_l은 선 8개, road_overall_l은 선 66개다. 따라서 도로 데이터가 없는 빈 응답이나 단순 HTTP 상태만을 성공으로 센 것이 아니다. 다만 이 한 타일 검사로 전체 지도 영역·경로 서버의 정상 동작을 보장하지 않는다.

**위치 변경 대조 검사:** 복정동 A(37.46619867, 127.126595), 인근 B(37.466700, 127.127200)를 각 18초씩 입력했다. Android GPS의 마지막 좌표는 A → B로 갱신됐고 수집 로그의 NMEA 체크섬·파싱 오류는 모두 0건이었다. 그러나 B 입력 후에도 기본 내비 화면은 ‘수정구 시흥동’과 흰 지도였다. 검사 후 A의 고정 시험 입력을 복원했다. 따라서 Android GPS 갱신 성공을 기본 내비 위치 정상화로 해석하지 않는다.

공식 Android Emulator의 **-shell** 디버그 콘솔도 시험했다. 이 Pleos 사용자 빌드에서는 ro.debuggable=0이고 콘솔 서비스가 제공되지 않아 시스템 수정용 셸을 얻지 못했다. 시험 후 -shell 옵션을 해제하고 기존 실행·GPS 보정 상태로 복원했다. APK·시스템 이미지·보안 속성은 변경하지 않았다.

이 결과는 인증 토큰 부족을 지도 문제의 해결책으로 보는 가설을 배제하는 추가 근거다. 현재 확인한 수정 지점은 그대로 **빈 스타일 선택 코드와 자동차 GPS 좌표 치환 분기**다. 공개 설정과 현재 배포 이미지에서 원본 앱을 교체하지 않고 두 동작을 바꾸는 방법은 확인하지 못했다.

## 필요한 공급 측 조치

1. 에뮬레이터용 coral 배포본이 일반 지도 스타일을 선택하도록 수정하고, 해당 지도 타일 인증·로딩까지 검증한 빌드를 제공한다.
2. 자동차 환경에서 수신 GPS 좌표를 기본 좌표로 바꾸는 분기 또는 에뮬레이터 위치 소스 설정을 수정한다.
3. 그 빌드에서 실제 위치 이동·목적지 검색·경로 안내를 재검증한다.

현재 프로젝트에는 기본 내비 소스·배포 서명이 없고 개발 메뉴 접근 정보도 없어, 차근 코드 수정으로 위 두 배포 앱 동작을 바꿀 수 없다. 공급 측에 보낼 근거는 준비했지만 외부 문의나 로그 전송은 하지 않았다. 차근 Kotlin 코드와 APK는 이번 GPS 도구 작업에서 변경하지 않았다. 앞선 앱 테스트 359개·기기 검사 20개는 이전 자동 날씨 변경의 결과이며 이번에 다시 수행한 결과가 아니다. 기존 에뮬레이터 3D 차량도 그대로다.

## 로컬 근거

- [최종 기본 내비 화면](../.tooling/native-nav-feed-final.png), [최종 시스템 위치 상태](../.tooling/native-nav-feed-final-location.txt)
- [빈 스타일](../.tooling/native-nav-coral-connect_empty.json), [일반 스타일](../.tooling/native-nav-coral-connect_v2.json), [HTTP·레이어 비교](../.tooling/native-nav-coral-style-check.json), [스타일 선택 코드](../.tooling/native-nav-style-remote.smali)
- [기본 내비 위치 관리자](../.tooling/native-nav-gps-manager.smali), [위치 발행 루프](../.tooling/native-nav-gps-poller.smali)
- [정상 NMEA 25쌍 검사 요약](../.tooling/native-nmea-valid-probe-summary.json), [오류 문장 대조 검사 요약](../.tooling/native-nmea-control-summary.json)
- [차근 날씨 조회 UI 기록](../.tooling/native-repair-weather-ui.txt), [공식 이미지 저장소 응답](../.tooling/pleos-current-repository.xml)
- [승인된 토큰 비교 결과 — 비밀정보 없음](../.tooling/native-nav-token-authorized-results.json), [도로 벡터 해석 요약](../.tooling/native-nav-vector-tile-summary.json)
- [두 좌표 대조 검사 요약](../.tooling/native-nav-authorized-movement-summary.json), [승인 후 최종 내비 화면](../.tooling/native-nav-authorized-final.png)
- [Pleos 공식 에뮬레이터 설치 문서](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator), [공식 NaviHelper](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [Android Emulator 명령행 문서](https://developer.android.com/studio/run/emulator-commandline)

.tooling은 Git에서 제외된 로컬 진단 자료다. 일부 원본 내비 로그에는 인증 정보가 있어 공유 대상으로 삼지 않았다. 외부 전달 시에는 비밀정보를 제외한 요약과 위 화면·코드 근거를 사용해야 한다. 에뮬레이터 시각과 PC 한국 시각은 구분한다.
