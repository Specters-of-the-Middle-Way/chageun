#!/usr/bin/env python3
"""Parked Pleos emulator integration for the fictional growth tutor.

Adds local simulation results and may finish pending setup; never clears existing
records. Reads SharedPreferences with run-as for verification only. UI decisions
are simulation inputs; the real SDK exercise is opened but is not completed here.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("growth_helpers", Path(__file__).with_name("verify-steering.py"))
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
m = s.m
PATHS = json.loads((ROOT / "docs/growth-test-paths.json").read_text())
CLIENT = "drivestep-growth-smoke"


def prefs():
    user = m.adb("shell", "am", "get-current-user")
    try:
        raw = subprocess.check_output([
            m.ADB, "exec-out", "run-as", m.APP, "--user", user,
            "cat", "shared_prefs/growth_tutor_v1.xml",
        ], stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return {"setup": False, "height": 1, "proportion": 0, "best": {}, "results": []}
    if b"No such file or directory" in raw:
        return {"setup": False, "height": 1, "proportion": 0, "best": {}, "results": []}
    values = {}
    for node in ET.fromstring(raw):
        name = node.get("name")
        if node.tag == "string": values[name] = node.text or ""
        elif node.tag == "boolean": values[name] = node.get("value") == "true"
        elif node.tag in {"int", "long"}: values[name] = int(node.get("value"))
    values["best"] = json.loads(values.get("best", "{}"))
    values["results"] = json.loads(values.get("results", "[]"))
    return values


def eventually(predicate, label):
    deadline = time.monotonic() + 15
    value = None
    while time.monotonic() < deadline:
        try:
            value = prefs()
            if predicate(value):
                print("PASS:", label, flush=True)
                return value
        except (ET.ParseError, json.JSONDecodeError):
            pass  # A preference publication can overlap this read-only copy.
        time.sleep(0.15)
    raise AssertionError(f"{label}: {value}")


def scene_description():
    descriptions = [n.get("content-desc", "") for n in m.ui().iter("node")]
    return next((d for d in descriptions if "설명용 애니메이션" in d), "")


def expect_scene(fragment):
    deadline = time.monotonic() + 15
    description = ""
    while time.monotonic() < deadline:
        description = scene_description()
        if fragment in description:
            print("PASS: scene accessibility", fragment, flush=True)
            return description
    raise AssertionError(f"Expected scene {fragment!r}; got {description!r}")


def virtual_steering():
    tree = m.ui()
    node = next(n for n in tree.iter("node") if n.get("content-desc") == "growth_virtual_steering")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    mid_y = str((y1 + y2) // 2)
    m.adb("shell", "input", "swipe", str((x1 + x2) // 2), mid_y,
          str(x1 + (x2 - x1) // 6), mid_y, "350")
    expect_scene("가상 조향 입력 왼쪽")
    # Restore the illustrative wheel, not any vehicle property.
    m.adb("shell", "input", "tap", str((x1 + x2) // 2), mid_y)
    expect_scene("가상 조향 입력 중앙")


def finish_lesson(path, make_mistake=False, demo=False):
    before = prefs()
    previous_best = dict(before["best"])
    previous_records = before["results"]
    s.tap("growth_lesson_" + path["id"])
    m.expect(path["title"])
    if demo and path["scene"] == "departure":
        virtual_steering()
    if demo and path["scene"] == "courtesy":
        lamps_before = m.request("/vehicle/state")["lights"]["turn_signal_mode"]
        s.tap("growth_thanks_demo")
        expect_scene("감사 표시 관습의 가상 예시 · 의무 아님")
        after_demo = prefs()
        assert after_demo["best"] == before["best"] and after_demo["results"] == before["results"]
        assert m.request("/vehicle/state")["lights"]["turn_signal_mode"] == lamps_before == "Off"
        print("PASS: fictional thanks demo changed neither real lamps nor scores", flush=True)
    for index, step in enumerate(path["steps"]):
        if index == 0 and make_mistake:
            s.audible(lambda: s.tap("growth_choice_" + step["wrong"]), step["wrongCue"])
            expect_scene("멈추고 확인해요")
            assert prefs()["best"] == previous_best, "An unfinished wrong answer earned points"
        s.tap("growth_choice_" + step["correct"])
        if index < len(path["steps"]) - 1:
            s.tap("growth_next")
    expected_score = 90 if make_mistake else 100
    m.expect(f"{expected_score}/100  ·  이번 가상 연습")
    # Await the newly added local record; an older record with the same score is insufficient.
    def recorded(value):
        rows = value["results"]
        if not rows or rows == previous_records:
            return False
        row = rows[0]
        return row.get("lesson") == path["id"] and row.get("score") == expected_score
    after = eventually(recorded, f"stored {path['id']} score={expected_score}")
    record = after["results"][0]
    assert record["kind"] == "SIMULATION"
    assert record["mistakes"] == int(make_mistake)
    assert len(record["attempts"]) == len(path["steps"]) + int(make_mistake)
    assert sum(not a["correct"] for a in record["attempts"]) == int(make_mistake)
    assert [a["choice"] for a in record["attempts"] if a["correct"]] == [st["correct"] for st in path["steps"]]
    expected_best = previous_best | {path["id"]: max(previous_best.get(path["id"], 0), expected_score)}
    assert after["best"] == expected_best, "Existing best score was lowered or another lesson changed"
    if path["id"] == "alley":
        expect_scene("후방 확인이 전제된 가상 후진 예시")
        m.screenshot("growth-alley-complete.png")
    if path["id"] == "ready":
        m.screenshot("growth-corrected-result.png")
    s.tap("growth_finish")
    return after


def main():
    serial = m.adb("get-serialno")
    if not serial.startswith("emulator-"):
        raise SystemExit("Only run this input test against an Android emulator")
    os.environ["ANDROID_SERIAL"] = serial
    m.adb("forward", "tcp:8088", "tcp:8088")
    assert "/vehicle/gear" in json.dumps(m.request("/vehicle/catalog"))
    stop = threading.Event()
    initial_voice = None

    def heartbeat():
        while not stop.is_set():
            try: m.request("/browser/session?client=" + CLIENT, "POST")
            except Exception as error: print("Heartbeat:", error, flush=True)
            stop.wait(0.8)

    m.request("/browser/session?client=" + CLIENT, "POST")
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        m.request("/vehicle/controls?speed=0&steering=0&accelerator=0&brake=0", "PUT")
        m.request("/vehicle/gear?gear=Park", "PUT")
        m.set_signal("Off")
        m.restart()
        tree = m.expect("성장 튜터   ·   가상 상황 연습")
        toggle = next(n for n in tree.iter("node") if n.get("content-desc") == "voice_toggle")
        initial_voice = toggle.get("checked") == "true"
        if not initial_voice: s.tap("voice_toggle")
        original = prefs()
        if not original.get("setup", False):
            s.tap("growth_profile")
            s.tap("growth_height_" + str(original.get("height", 1)))
            s.tap("growth_body_" + str(original.get("proportion", 0)))
            s.tap("growth_setup_start")
            expect_scene("발이 닿아도 무릎·팔꿈치에는 여유를")
            m.screenshot("growth-setup.png")
            for _ in range(6): s.tap("growth_setup_next")
            eventually(lambda p: p.get("setup") is True, "six-step self-check persisted")
        else:
            print("PASS: preserving previously confirmed setup/profile", flush=True)
        for index, path in enumerate(PATHS):
            finish_lesson(path, make_mistake=index == 0, demo=True)
        all_done = prefs()
        assert all(p["id"] in all_done["best"] for p in PATHS)
        assert all(all_done["best"].get(k, 0) >= v for k, v in original["best"].items())
        # Repeat a course already at 100: attempts increase, but best-only points do not.
        points_before = sum(all_done["best"].values())
        repeat = next(p for p in PATHS if p["id"] == "departure")
        repeated = finish_lesson(repeat)
        assert sum(repeated["best"].values()) == points_before
        print("PASS: repeated perfect completion does not farm points", flush=True)
        saved_best = dict(repeated["best"])
        saved_records = repeated["results"]
        m.restart()
        m.expect("성장 튜터   ·   가상 상황 연습")
        restored = prefs()
        assert restored["best"] == saved_best and restored["results"] == saved_records
        assert restored.get("setup") is True
        print("PASS: completed course history and best scores survived restart", flush=True)
        s.tap("growth_progress")
        m.expect("예시 순위 · 가상 참가자")
        m.expect("순위 화면 체험입니다. 다른 사용자와 연결되지 않았어요. 내 점수만 이 기기에 저장된 학습 점수입니다.")
        m.screenshot("growth-progress.png")
        s.tap("growth_back")
        s.tap("growth_sdk")
        m.expect("첫 번째 연습 · 방향지시등")
        s.tap("growth_home")
        m.expect("성장 튜터   ·   가상 상황 연습")
        print("ALL GROWTH SMOKE CHECKS PASSED", flush=True)
    finally:
        for path in ["/vehicle/controls?speed=0&steering=0&accelerator=0&brake=0",
                     "/vehicle/gear?gear=Park", "/vehicle/turn-signal?mode=Off"]:
            try: m.request(path, "PUT")
            except Exception as error: print("Fixture reset warning:", error, flush=True)
        if initial_voice is not None:
            try:
                m.restart()
                tree = m.expect("성장 튜터   ·   가상 상황 연습")
                toggle = next(n for n in tree.iter("node") if n.get("content-desc") == "voice_toggle")
                if (toggle.get("checked") == "true") != initial_voice: s.tap("voice_toggle")
            except Exception as error:
                print("Voice preference restore warning:", error, flush=True)
        stop.set()
        worker.join(timeout=3)
        try: m.request("/browser/session?client=" + CLIENT + "&closing=1", "POST")
        except Exception: pass


if __name__ == "__main__":
    main()
