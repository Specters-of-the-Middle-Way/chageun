#!/usr/bin/env python3
"""Pleos 2.1.0 emulator smoke test. Adds practice records; never clears app data.

Uses the installed Virtual Console's observed HTTP implementation, not a public
Pleos API contract. The production APK does not use these endpoints.
"""
import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = shutil.which("adb") or str(ROOT / ".tooling/android-sdk/platform-tools/adb")
APP = "kr.drivestep.tutor"
BASE = "http://127.0.0.1:8088"
CLIENT = "drivestep-smoke-test"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True).strip()


def request(path, method="GET"):
    with opener.open(urllib.request.Request(BASE + path, method=method), timeout=5) as response:
        return json.load(response)


def set_signal(mode):
    state = request("/vehicle/turn-signal?mode=" + mode, "PUT")
    assert state["turn_signal_mode"] == mode


def latest_record():
    # Read-only inspection of this debug app's actual persisted data. The Pleos
    # EVS window can obscure UIAutomator's active root while a lamp is blinking.
    user = adb("shell", "am", "get-current-user")
    data = subprocess.check_output([ADB, "exec-out", "run-as", APP, "--user", user,
                                    "cat", "databases/drivestep-practice.db"])
    with sqlite3.connect(":memory:") as connection:
        connection.deserialize(data)
        row = connection.execute("SELECT snapshot_json FROM practice_sessions "
                                 "ORDER BY started_at DESC, session_id DESC LIMIT 1").fetchone()
    return json.loads(row[0]) if row else None


def expect_stage(stage, next_action=None):
    deadline = time.monotonic() + 15
    record = None
    while time.monotonic() < deadline:
        try:
            record = latest_record()
            if (record and record["stage"] == stage and
                    (next_action is None or record["nextAction"] == next_action)):
                print("PASS: persisted", stage, flush=True)
                return record
        except sqlite3.DatabaseError:
            pass  # A commit may overlap the read-only debug copy; retry.
        time.sleep(0.2)
    raise AssertionError(f"Expected persisted stage {stage!r}; got {record!r}")


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/drivestep-smoke.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/drivestep-smoke.xml"))


def app_texts(tree):
    return [node.get("text", "") for node in tree.iter("node")
            if node.get("package") == APP and node.get("text")]


def expect(text):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        tree = ui()
        if text in app_texts(tree):
            print("PASS:", text, flush=True)
            return tree
    raise AssertionError(f"Expected {text!r}; got {app_texts(tree)!r}")


def click(key):
    tree = ui()
    node = next(node for node in tree.iter("node") if node.get("content-desc") == key)
    assert node.get("enabled") == "true", key
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def screenshot(name):
    path = ROOT / "docs/screenshots" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as output:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=output, check=True)


def launch():
    adb("shell", "am", "start", "--user", "current", "-n", APP + "/.MainActivity")


def restart():
    adb("shell", "am", "force-stop", "--user", "current", APP)
    launch()



def enter_sdk_home():
    """Opt in to SDK screens; launch/restart keep the current coach default."""
    deadline = time.monotonic() + 20
    legacy_keys = {"start_practice", "operation_guide", "guide_start", "stop_practice",
                   "steering_setup", "steering_start", "stop_steering", "home",
                   "parking_next", "parking_finish", "parking_restart"}
    legacy_titles = {"기어·조향 실습 복습", "다섯 가지 상황을 확인했어요",
                     "이번에는 여기까지 했어요", "한 번 더 익숙해졌어요"}
    while time.monotonic() < deadline:
        tree = ui()
        nodes = list(tree.iter("node"))
        coach_link = next((n for n in nodes if n.get("content-desc") == "coach_sdk"), None)
        if coach_link is not None and coach_link.get("enabled") == "true":
            click("coach_sdk")
            return
        growth_link = next((n for n in nodes if n.get("content-desc") == "growth_sdk"), None)
        if growth_link is not None and growth_link.get("enabled") == "true":
            click("growth_sdk")
            return
        if any(n.get("package") == APP and n.get("content-desc") in legacy_keys for n in nodes):
            return  # A resumed SDK lesson/review must stay on its current screen.
        if legacy_titles.intersection(app_texts(tree)):
            return
        if any(n.get("content-desc") == "coach_signal" for n in nodes):
            adb("shell", "input", "swipe", "1500", "850", "1500", "350", "250")
        time.sleep(0.15)
    raise AssertionError("Could not find the growth SDK link or a resumed SDK screen")


def main():
    serial = adb("get-serialno")
    if not serial.startswith("emulator-"):
        raise SystemExit("This test only runs against an Android emulator.")
    adb("forward", "tcp:8088", "tcp:8088")
    catalog = request("/vehicle/catalog")
    if "/vehicle/turn-signal" not in json.dumps(catalog):
        raise SystemExit("Expected Pleos Virtual Console is not available.")
    stopped = threading.Event()

    def heartbeat():
        while not stopped.is_set():
            try:
                request("/browser/session?client=" + CLIENT, "POST")
            except Exception as error:
                print("Heartbeat:", error, flush=True)
            stopped.wait(0.8)

    request("/browser/session?client=" + CLIENT, "POST")
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")
        set_signal("Off")
        restart()
        enter_sdk_home()
        expect("현재 방향지시등: 꺼짐")
        screenshot("01-home.png")
        click("start_practice")
        click("guide_start")
        expect("왼쪽 방향지시등을 켜 주세요")
        screenshot("02-practice.png")
        for mode, stage in [
            ("BlinkLeft", "WAIT_LEFT_OFF"),
            ("Off", "WAIT_RIGHT_ON"),
            ("BlinkRight", "WAIT_RIGHT_OFF"),
            ("Off", "COMPLETED"),
        ]:
            set_signal(mode)
            expect_stage(stage)
        expect("실습 완료 · 5/5 확인")
        expect("차량 안에 기록을 저장했어요.")
        screenshot("03-review.png")
        restart()
        enter_sdk_home()
        tree = expect("실습 완료 · 5/5 확인")
        key = next(node.get("content-desc") for node in tree.iter("node")
                   if node.get("content-desc", "").startswith("review_"))
        click(key)
        expect("한 번 더 익숙해졌어요")
        print("PASS: completed review survived force-stop/relaunch", flush=True)

        click("home")
        click("start_practice")
        click("guide_start")
        expect("왼쪽 방향지시등을 켜 주세요")
        set_signal("BlinkRight")
        expect_stage("WAIT_LEFT_ON", "TURN_OFF_ALL")
        set_signal("Off")
        expect("왼쪽 방향지시등을 켜 주세요")
        expect("차량 안에 기록을 저장했어요.")
        restart()
        enter_sdk_home()
        tree = expect("실습 중단 · 1/5 확인")
        key = next(node.get("content-desc") for node in tree.iter("node")
                   if node.get("content-desc", "").startswith("review_"))
        click(key)
        expect("앱이 종료되어 진행 중이던 실습을 중단 기록으로 복구했어요.")
        screenshot("04-interrupted.png")
        print("PASS: wrong direction required reset; active record recovered as interrupted", flush=True)
        click("home")

        click("start_practice")
        click("guide_start")
        expect("왼쪽 방향지시등을 켜 주세요")
        request("/vehicle/gear?gear=Drive", "PUT")
        request("/vehicle/controls?speed=15&accelerator=0&brake=0", "PUT")
        record = expect_stage("INTERRUPTED")
        assert record["interruptionReason"] in ("DRIVING_RESTRICTION", "SCREEN_LEFT")
        assert not any(node.get("content-desc") == "start_practice" for node in ui().iter("node"))
        screenshot("05-driving-restricted.png")
        request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")
        launch()
        enter_sdk_home()
        expect("이번에는 여기까지 했어요")
        click("home")
        print("PASS: driving restriction interrupted practice and hid controls", flush=True)
        print("ALL CORE SMOKE CHECKS PASSED", flush=True)
    finally:
        # Return this test emulator to a stationary, lights-off state.
        for path in ["/vehicle/controls?speed=0&accelerator=0&brake=0",
                     "/vehicle/gear?gear=Park", "/vehicle/turn-signal?mode=Off"]:
            try:
                request(path, "PUT")
            except Exception:
                pass
        stopped.set()
        worker.join(timeout=3)
        request("/browser/session?client=" + CLIENT + "&closing=1", "POST")


if __name__ == "__main__":
    main()
