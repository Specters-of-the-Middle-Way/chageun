#!/usr/bin/env python3
"""Live on-screen automated demo for Posture Guide and Mirror Invalidation.

Controls the running Pleos emulator UI via ADB input events with visible delays.
"""

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
SERIAL = "emulator-5554"


def adb(*args):
    cmd = [ADB, "-s", SERIAL] + list(args)
    return subprocess.check_output(cmd).decode("utf-8", errors="ignore")


def get_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/live_demo.xml")
    xml_str = adb("shell", "cat", "/sdcard/live_demo.xml")
    return ET.fromstring(xml_str)


def find_node(predicate):
    tree = get_ui()
    for n in tree.iter("node"):
        if predicate(n):
            return n
    return None


def tap_node(node, label=""):
    bounds = node.get("bounds", "")
    coords = list(map(int, re.findall(r"\d+", bounds)))
    if len(coords) == 4:
        x1, y1, x2, y2 = coords
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        print(f"👉 [터치] {label} (좌표: {cx}, {cy})", flush=True)
        adb("shell", "input", "tap", str(cx), str(cy))
        time.sleep(1.2)


def swipe(x1, y1, x2, y2, duration_ms=600, label=""):
    print(f"👉 [슬라이더 드래그] {label} ({x1}, {y1}) -> ({x2}, {y2})", flush=True)
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms))
    time.sleep(1.5)


def tap_by_desc(desc, label=""):
    node = find_node(lambda n: n.get("content-desc") == desc)
    if node is not None:
        tap_node(node, label or desc)
        return True
    return False


def main():
    print("================================================================================")
    print(" 🎬 [실시간 에뮬레이터 UI 시연 시작] 자세 가이드 조작 & 사이드미러 무효화")
    print("================================================================================\n")

    # 1. 만약 프로필 화면에 있다면 '이 설정으로 운전 환경 확인' 터치
    profile_btn = find_node(lambda n: n.get("content-desc") == "journey_profile_continue")
    if profile_btn is not None:
        print("[1단계] 운전자 설정 화면 -> '이 설정으로 운전 환경 확인' 터치하여 진입")
        tap_node(profile_btn, "이 설정으로 운전 환경 확인")

    # 2. 1번 '시트 앞뒤' 항목 터치
    print("\n[2단계] 1번 '시트 앞뒤' 점검 단계 선택")
    tap_by_desc("journey_setup_step_0", "1 시트 앞뒤 항목")

    # 3. 자세 가이드 슬라이더 직접 좌우로 움직이기
    slider = find_node(lambda n: n.get("content-desc") == "journey_calibration_fore_aft")
    if slider is not None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", slider.get("bounds", "")))
        cy = (y1 + y2) // 2
        print("\n[3단계] 자세 가이드(PostureGuideView) 실시간 움직이기 시연")
        # 50 -> 25 (앞으로 당기기)
        mid_x = (x1 + x2) // 2
        left_x = x1 + int((x2 - x1) * 0.25)
        right_x = x1 + int((x2 - x1) * 0.75)

        swipe(mid_x, cy, left_x, cy, 600, "시트 앞으로 당기기 (슬라이더 25로 이동)")
        time.sleep(1.0)
        swipe(left_x, cy, right_x, cy, 600, "시트 뒤로 밀기 (슬라이더 75로 이동)")
        time.sleep(1.0)

    # 4. '체격 참고 시작값 적용' 버튼 터치
    print("\n[4단계] '체격 참고 시작값 적용' 버튼 터치 (체형 맞춤 권장값으로 자동 이동)")
    tap_by_desc("journey_apply_recommended_preset", "체격 참고 시작값 적용")
    time.sleep(1.5)

    # 5. 시트 확인 완료
    print("\n[5단계] 시트 앞뒤 '조절 및 확인 완료' 터치")
    tap_by_desc("journey_setup_confirm", "조절 및 확인 완료")

    # 6. 미러 3종 (룸미러, 왼쪽 미러, 오른쪽 미러) 직접 선택하여 확인 완료 체크
    print("\n[6단계] 미러 3종(5: 룸미러, 6: 왼쪽 미러, 7: 오른쪽 미러) 점검 및 체크")
    for step_idx, name in [(5, "6 룸미러"), (6, "7 왼쪽 미러"), (7, "8 오른쪽 미러")]:
        tap_by_desc(f"journey_setup_step_{step_idx}", name)
        time.sleep(0.8)
        tap_by_desc("journey_setup_confirm", f"{name} 조절 및 확인 완료 (체크)")
        time.sleep(0.8)

    print("\n>>> 현재 상태: 룸미러, 왼쪽 미러, 오른쪽 미러가 모두 [확인 완료 ✓] 된 상태입니다.")
    time.sleep(2.0)

    # 7. 다시 1번 '시트 앞뒤'로 이동하여 슬라이더를 조금 움직임 -> 미러 무효화 발생!
    print("\n[7단계 핵심 검증] 1번 '시트 앞뒤'로 다시 돌아가 슬라이더를 조작합니다.")
    tap_by_desc("journey_setup_step_0", "1 시트 앞뒤 항목")
    time.sleep(1.0)

    slider = find_node(lambda n: n.get("content-desc") == "journey_calibration_fore_aft")
    if slider is not None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", slider.get("bounds", "")))
        cy = (y1 + y2) // 2
        mid_x = (x1 + x2) // 2
        target_x = mid_x + 80
        swipe(mid_x, cy, target_x, cy, 500, "시트 위치 재조정 (미세 변경)")
        time.sleep(1.5)

    print("\n⚡ [검증 결과 확인] 시트 위치가 바뀌어 운전자 시야각이 달라졌으므로,")
    print("   룸미러, 왼쪽 미러, 오른쪽 미러의 체크가 자동으로 풀렸는지(무효화) 확인합니다.\n")

    # UI 상태 점검
    tree = get_ui()
    for idx, name in [(5, "룸미러"), (6, "왼쪽 미러"), (7, "오른쪽 미러")]:
        node = next((n for n in tree.iter("node") if n.get("content-desc") == f"journey_setup_step_{idx}"), None)
        text = node.get("text", "") if node is not None else ""
        has_check = "✓" in text
        status_str = "완료 (✓)" if has_check else "미완료 / 자동 무효화됨 (초기화)"
        print(f"   • {name} 상태: {status_str}")

    print("\n================================================================================")
    print(" 🎉 [실시간 에뮬레이터 자동 시연 완료]")
    print(" 에뮬레이터 화면에서 자세 가이드의 실시간 움직임과 미러 무효화를 확인하실 수 있습니다!")
    print("================================================================================")


if __name__ == "__main__":
    main()

