#!/usr/bin/env python3
"""Run the real SDK and combined lesson tests on one Pleos emulator in P at zero speed.

Adds one consented demo history without clearing previous data. The UI test restores
recording/audio settings. Does not change gear, speed, permissions or microphone input.
"""
import argparse
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CLASSES = [
    "kr.drivestep.tutor.speech.PleosSpeechSmokeTest",
    "kr.drivestep.tutor.speech.TutorSpeechControllerSmokeTest",
    "kr.drivestep.tutor.lessoncoach.LessonInputCoordinatorSmokeTest",
    "kr.drivestep.tutor.lessoncoach.LessonCoachFlowTest",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    args = parser.parse_args()
    adb = shutil.which("adb") or str(ROOT / ".tooling/android-sdk/platform-tools/adb")
    serial = subprocess.check_output([adb, "get-serialno"], text=True).strip()
    if not serial.startswith("emulator-"):
        raise SystemExit("This test only runs on an emulator. Keep the Pleos vehicle in P at zero speed.")
    for apk in (args.apk, args.test_apk):
        if not apk.is_file():
            raise SystemExit(f"Build assembleDebug and assembleDebugAndroidTest first: {apk}")
        subprocess.run([adb, "install", "-r", str(apk)], check=True)
    user = subprocess.check_output([adb, "shell", "am", "get-current-user"], text=True).strip()
    if not user.isdigit():
        raise SystemExit("Cannot determine the active Android user.")
    command = [adb, "shell", "am", "instrument", "--user", user, "-w", "-r", "-e", "class",
               ",".join(CLASSES), "kr.drivestep.tutor.test/androidx.test.runner.AndroidJUnitRunner"]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    report = ROOT / "docs/test-results/interactive-lesson-device.txt"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(result.stdout, encoding="utf-8")
    print(result.stdout, flush=True)
    # am instrument may exit 0 even when JUnit fails; inspect the runner result explicitly.
    if result.returncode or not re.search(r"OK \(5 tests\)", result.stdout) or "FAILURES!!!" in result.stdout or "INSTRUMENTATION_STATUS_CODE: -3" in result.stdout:
        raise SystemExit(f"Emulator validation did not pass all five tests. Inspect {report}")
    print("PASS: five SDK/UI tests. This does not certify real microphone accuracy or real-road use.")


if __name__ == "__main__":
    main()
