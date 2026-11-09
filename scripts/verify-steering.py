#!/usr/bin/env python3
"""Emulator-only SDK/UI/voice/persistence smoke test. Adds records; never clears data.

Actual vehicle inputs go through the emulator controls, never the app state machine.
The local Virtual Console endpoints are observed test-fixture implementation details.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


m = load("steering_smoke_helpers", "verify-emulator.py")
voice = load("steering_voice_helpers", "verify-tutor.py")
CLIENT = "drivestep-steering-smoke"


def records():
    user = m.adb("shell", "am", "get-current-user")
    raw = subprocess.check_output([
        m.ADB, "exec-out", "run-as", m.APP, "--user", user,
        "cat", "files/steering-practice.json",
    ], stderr=subprocess.DEVNULL)
    data = json.loads(raw)
    assert data["version"] == 1
    return data["sessions"]


def expect_record(stage, session_id=None, excluded=()):
    deadline = time.monotonic() + 15
    candidate = None
    while time.monotonic() < deadline:
        try:
            candidate = next((item for item in records()
                              if item["id"] not in excluded and
                              (session_id is None or item["id"] == session_id)), None)
            if candidate and candidate["stage"] == stage:
                print("PASS: persisted", stage, candidate["completedSteps"], "/4", flush=True)
                return candidate
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass  # AtomicFile publication or the first save may overlap the read.
        time.sleep(0.15)
    raise AssertionError(f"Expected {stage}; last persisted record: {candidate}")


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    assert x2 > x1 and y2 > y1, node.attrib
    m.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(key):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        tree = m.ui()
        node = next((n for n in tree.iter("node") if n.get("content-desc") == key), None)
        if node is not None and node.get("enabled") == "true":
            tap_node(node)
            return
        if node is None:
            scroll = next((n for n in tree.iter("node") if n.get("scrollable") == "true"), None)
            if scroll is not None:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds")))
                x = str((x1 + x2) // 2)
                m.adb("shell", "input", "swipe", x, str(y2 - 80), x, str(y1 + 120), "250")
        time.sleep(0.15)
    raise AssertionError(f"Missing or disabled control: {key}")


def cue_count(cue):
    # Exact ID matching: steering_right must not match steering_right_confirmed.
    pattern = re.compile(r"Playback started: (?:tts|bundle) cue=" + re.escape(cue) + r"(?:\s|$)")
    return sum(bool(pattern.search(line)) for line in voice.logs().splitlines())


def audible(action, cue):
    before = cue_count(cue)
    action()
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if cue_count(cue) > before:
            print("PASS: playback started", cue, flush=True)
            return
        time.sleep(0.2)
    raise AssertionError(f"No new TTS or bundled playback start for {cue}")


def start_session():
    m.request("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "PUT")
    tap("steering_setup")
    tree = m.ui()
    if any(n.get("content-desc") == "grant_car_speed" for n in tree.iter("node")):
        tap("grant_car_speed")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            allow = next((n for n in m.ui().iter("node") if n.get("resource-id", "").endswith(
                ("/permission_allow_button", "/permission_allow_foreground_only_button"))), None)
            if allow is not None:
                tap_node(allow)
                break
            time.sleep(0.2)
        else:
            raise AssertionError("CAR_SPEED permission dialog did not offer Allow")
    try:
        old_ids = {item["id"] for item in records()}
    except subprocess.CalledProcessError:
        old_ids = set()  # No file before the first-ever practice.
    audible(lambda: tap("steering_start"), "steering_left")
    result = expect_record("WAIT_LEFT", excluded=old_ids)
    assert result["completedSteps"] == 0 and result["gear"] == "P"
    return result["id"]


def main():
    serial = m.adb("get-serialno")
    if not serial.startswith("emulator-"):
        raise SystemExit("Only run against an emulator")
    os.environ["ANDROID_SERIAL"] = serial  # Pin all helper/subprocess calls to this emulator.
    m.adb("forward", "tcp:8088", "tcp:8088")
    catalog = m.request("/vehicle/catalog")
    steering = next(f for f in catalog["features"] if f.get("id") == "steering-angle")
    assert steering["write_endpoint"] == "/vehicle/controls"
    assert steering["control"]["min"] <= -8 and steering["control"]["max"] >= 8
    stop = threading.Event()

    def heartbeat():
        while not stop.is_set():
            try:
                m.request("/browser/session?client=" + CLIENT, "POST")
            except Exception as error:
                print("Heartbeat error:", error, flush=True)
            stop.wait(0.8)

    m.request("/browser/session?client=" + CLIENT, "POST")
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        m.request("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "PUT")
        m.request("/vehicle/gear?gear=Park", "PUT")
        m.set_signal("Off")
        m.restart()
        m.enter_sdk_home()
        tree = m.expect("●  차량 신호 연결됨")
        toggle = next(n for n in tree.iter("node") if n.get("content-desc") == "voice_toggle")
        if toggle.get("checked") != "true":
            tap("voice_toggle")

        happy_id = start_session()
        flow = [
            ("negative", "WAIT_CENTER_AFTER_LEFT", "steering_left_confirmed"),
            ("center", "WAIT_RIGHT", "steering_right"),
            ("positive", "WAIT_CENTER_AFTER_RIGHT", "steering_right_confirmed"),
            ("center", "COMPLETED", "steering_complete"),
        ]
        for step, (button, stage, cue) in enumerate(flow, start=1):
            audible(lambda b=button: tap("emulator_steering_" + b), cue)
            result = expect_record(stage, happy_id)
            assert result["completedSteps"] == step
        assert result["outcome"] == "COMPLETED" and result["interruptionReason"] is None
        events = result["events"][-4:]
        assert [e["stageAfter"] for e in events] == [stage for _, stage, _ in flow]
        assert all(e["field"] == "STEERING" for e in events)
        angles = [e["angleDegrees"] for e in events]
        assert angles[0] <= -5 and abs(angles[1]) <= 2 and angles[2] >= 5 and abs(angles[3]) <= 2
        m.expect("4/4 조작 확인")
        m.screenshot("12-steering-complete.png")
        tap("home")

        wrong_id = start_session()
        audible(lambda: tap("emulator_steering_positive"), "steering_wrong_right")
        wrong = expect_record("WAIT_CENTER_RETRY_LEFT", wrong_id)
        assert wrong["completedSteps"] == 0 and wrong["nextAction"] == "CENTER"
        audible(lambda: tap("emulator_steering_center"), "steering_retry_left")
        expect_record("WAIT_LEFT", wrong_id)
        audible(lambda: tap("emulator_steering_negative"), "steering_left_confirmed")
        expect_record("WAIT_CENTER_AFTER_LEFT", wrong_id)
        tap("stop_steering")
        assert expect_record("INTERRUPTED", wrong_id)["interruptionReason"] == "USER_STOPPED"
        tap("home")

        reverse_id = start_session()
        m.request("/vehicle/gear?gear=Reverse", "PUT")
        stopped = expect_record("INTERRUPTED", reverse_id)
        assert stopped["interruptionReason"] in {"GEAR_NOT_P", "DRIVING_RESTRICTION", "SCREEN_LEFT"}
        voice.assert_silent()
        m.request("/vehicle/gear?gear=Park", "PUT")
        m.launch()
        m.enter_sdk_home()
        tap("home")
        print("PASS: R interrupted; P restored without resuming the lesson", flush=True)

        crash_id = start_session()
        audible(lambda: tap("emulator_steering_negative"), "steering_left_confirmed")
        active = expect_record("WAIT_CENTER_AFTER_LEFT", crash_id)
        m.adb("shell", "am", "force-stop", "--user", "current", m.APP)
        m.launch()
        m.enter_sdk_home()
        recovered = expect_record("INTERRUPTED", crash_id)
        assert recovered["interruptionReason"] == "PROCESS_RESTART"
        assert recovered["events"] == active["events"] and recovered["completedSteps"] == 1
        assert next(r for r in records() if r["id"] == happy_id)["outcome"] == "COMPLETED"
        tap("steering_review_" + crash_id)
        m.expect("기어·조향 실습 복습")
        m.screenshot("13-steering-recovered.png")
        tap("home")
        print("ALL STEERING SMOKE CHECKS PASSED", flush=True)
    finally:
        for path in ["/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0",
                     "/vehicle/gear?gear=Park", "/vehicle/turn-signal?mode=Off"]:
            try:
                m.request(path, "PUT")
            except Exception as error:
                print("Fixture reset warning:", error, flush=True)
        stop.set()
        worker.join(timeout=3)
        try:
            m.request("/browser/session?client=" + CLIENT + "&closing=1", "POST")
        except Exception:
            pass


if __name__ == "__main__":
    main()
