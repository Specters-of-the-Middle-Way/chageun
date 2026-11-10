#!/usr/bin/env python3
"""Pleos emulator transport/UI test. Fixture output is explicitly NOT live OpenAI output."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from server import RelayServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    args = parser.parse_args()
    adb = str(ROOT / ".tooling/android-sdk/platform-tools/adb")
    def call(*a): return subprocess.check_output([adb, *a], text=True)
    if not call("get-serialno").strip().startswith("emulator-"):
        raise SystemExit("Emulator only. Keep Pleos in P at zero speed.")
    user = call("shell", "am", "get-current-user").strip()
    if not user.isdigit(): raise SystemExit("No Android user")
    for apk in [args.apk, args.test_apk]: subprocess.run([adb, "install", "-r", str(apk)], check=True)
    relay = RelayServer(0, fixture=True)
    thread = threading.Thread(target=relay.serve_forever, daemon=True); thread.start()
    mapped = False
    try:
        call("reverse", "--no-rebind", "tcp:18765", f"tcp:{relay.server_port}")
        mapped = True
        result = subprocess.run([adb, "shell", "am", "instrument", "--user", user, "-w", "-r", "-e", "llmFixture", "true", "-e", "class",
            "kr.drivestep.tutor.lessoncoach.TutorLlmFlowTest", "kr.drivestep.tutor.test/androidx.test.runner.AndroidJUnitRunner"],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=150)
        path = ROOT / "docs/test-results/llm-fixture-device.txt"; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(result.stdout)
        print(result.stdout)
        if result.returncode or not re.search(r"OK \(1 test\)", result.stdout) or "INSTRUMENTATION_STATUS_CODE: -3" in result.stdout:
            raise SystemExit("LLM fixture device validation FAILED")
        print("PASS: HTTP context, fallback, cancellation and persisted review. LIVE_OPENAI_NOT_TESTED.")
    finally:
        try:
            if mapped: call("reverse", "--remove", "tcp:18765")
        finally:
            relay.shutdown(); relay.server_close(); thread.join()


if __name__ == "__main__": main()
