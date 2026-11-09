#!/usr/bin/env python3
"""Emulator integration checks for Korean playback and parked situation lessons."""
import importlib.util
from pathlib import Path
import threading
import time

spec = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("verify-emulator.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def logs():
    pid = m.adb("shell", "pidof", m.APP)
    return m.adb("logcat", "-d", "--pid", pid, "-v", "brief", "-s", "VoiceCoach:I")


def wait_cue(cue_id, previous):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        lines = [line for line in logs().splitlines() if "Playback started:" in line and f"cue={cue_id}" in line]
        if len(lines) > previous:
            print("PASS:", lines[-1], flush=True)
            return
        time.sleep(0.25)
    raise AssertionError(f"No new audible playback for {cue_id}")


def cue_count(cue_id):
    return sum("Playback started:" in line and f"cue={cue_id}" in line for line in logs().splitlines())


def expect_audio(action, cue_id):
    before = cue_count(cue_id)
    action()
    wait_cue(cue_id, before)


def active_player():
    # The MediaPlayer/TTS onStart message alone is insufficient to test mute.
    # Confirm this app's actual AudioService playback configuration as well.
    pid = m.adb("shell", "pidof", m.APP)
    dump = m.adb("shell", "dumpsys", "audio")
    return any(f"/{pid}" in line and "state:started" in line for line in dump.splitlines())


def assert_silent():
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if not active_player():
            return
        time.sleep(0.2)
    raise AssertionError("App audio still active")


def main():
    assert m.adb("get-serialno").startswith("emulator-"), "Only run this test in an emulator"
    m.adb("forward", "tcp:8088", "tcp:8088")
    stop = threading.Event()

    def heartbeat():
        while not stop.is_set():
            m.request("/browser/session?client=drivestep-voice-test", "POST")
            stop.wait(0.8)

    m.request("/browser/session?client=drivestep-voice-test", "POST")
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        m.request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        m.request("/vehicle/gear?gear=Park", "PUT")
        m.set_signal("Off")
        m.restart()
        m.enter_sdk_home()
        tree = m.expect("●  차량 신호 연결됨")
        toggle = next(n for n in tree.iter("node") if n.get("content-desc") == "voice_toggle")
        if toggle.get("checked") != "true":
            m.click("voice_toggle")
        expect_audio(lambda: m.click("operation_guide"), "signal_how_to")
        assert active_player(), "No actual audio playback configuration"
        m.screenshot("08-operation-guide.png")
        m.click("home")
        assert_silent()

        m.click("start_practice")
        expect_audio(lambda: m.click("guide_start"), "signal_left_on")
        expect_audio(lambda: m.set_signal("BlinkRight"), "signal_wrong_right_for_left")
        m.expect_stage("WAIT_LEFT_ON", "TURN_OFF_ALL")
        expect_audio(lambda: m.set_signal("Off"), "signal_retry_left")
        m.screenshot("09-signal-retry.png")
        for mode, cue in [("BlinkLeft", "signal_left_off"), ("Off", "signal_right_on"),
                          ("BlinkRight", "signal_right_off"), ("Off", "signal_completed")]:
            expect_audio(lambda: m.set_signal(mode), cue)
        m.expect_stage("COMPLETED")
        m.expect("한 번 더 익숙해졌어요")
        m.click("home")
        assert_silent()

        expect_audio(lambda: m.click("parking_lesson"), "parking_space_intro")
        tree = m.ui()
        next_button = next(n for n in tree.iter("node") if n.get("content-desc") == "parking_next")
        assert next_button.get("enabled") == "false"
        expect_audio(lambda: m.click("parking_choice_0"), "parking_space_retry")
        expect_audio(lambda: m.click("parking_choice_1"), "parking_space_correct")
        m.screenshot("10-parking-feedback.png")
        m.click("voice_toggle")
        assert_silent()
        before = cue_count("parking_reverse_intro")
        m.click("parking_next")
        assert cue_count("parking_reverse_intro") == before, "Muted cue was spoken"
        m.click("voice_toggle")
        expect_audio(lambda: m.click("replay_voice"), "parking_reverse_intro")
        assert active_player()
        for i, (answer, prefix) in enumerate([(0, "reverse"), (1, "observe"), (0, "align"), (1, "finish")]):
            expect_audio(lambda: m.click(f"parking_choice_{answer}"), f"parking_{prefix}_correct")
            next_cue = ["parking_observe_intro", "parking_align_intro", "parking_finish_intro", "parking_complete"][i]
            expect_audio(lambda: m.click("parking_next"), next_cue)
        m.expect("다섯 가지 상황을 확인했어요")
        m.screenshot("11-parking-complete.png")
        expect_audio(lambda: m.click("replay_voice"), "parking_complete")
        # Pleos HOME may retain the embedded app; opening Settings actually
        # navigates away and exercises Activity.onStop.
        m.adb("shell", "am", "start", "--user", "current", "-a", "android.settings.SETTINGS")
        assert_silent()
        m.launch()
        m.enter_sdk_home()
        m.expect("다섯 가지 상황을 확인했어요")
        m.click("parking_finish")

        m.click("start_practice")
        expect_audio(lambda: m.click("guide_start"), "signal_left_on")
        m.request("/vehicle/gear?gear=Drive", "PUT")
        m.expect_stage("INTERRUPTED")
        assert_silent()
        m.request("/vehicle/gear?gear=Park", "PUT")
        m.launch()
        m.enter_sdk_home()
        m.expect("이번에는 여기까지 했어요")
        m.click("home")
        print("PASS: mute, explicit replay, screen exit and driving restriction stop audio", flush=True)
        print("ALL TUTOR CHECKS PASSED", flush=True)
    finally:
        m.request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        m.request("/vehicle/gear?gear=Park", "PUT")
        m.set_signal("Off")
        stop.set()
        worker.join(timeout=3)
        m.request("/browser/session?client=drivestep-voice-test&closing=1", "POST")


if __name__ == "__main__":
    main()
