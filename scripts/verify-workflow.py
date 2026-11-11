#!/usr/bin/env python3
"""Verify parked workflows, native launcher assets, and process recovery on one local Pleos AVD.

Uses production UI actions, temporarily force-stops only the emulator app, and always
runs the test's scoped configuration/checkpoint restoration. Never clears app data.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
APP = 'kr.drivestep.tutor'
RUNNER = APP + '.test/androidx.test.runner.AndroidJUnitRunner'
PROCESS = APP + '.workflow.WorkflowProcessRecoveryTest'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--apk', type=Path, default=ROOT/'app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--test-apk', type=Path, default=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    args = parser.parse_args()
    adb = shutil.which('adb') or str(ROOT/'.tooling/android-sdk/platform-tools/adb')
    if not args.serial:
        output = subprocess.check_output([adb, 'devices'], text=True)
        devices = [line.split()[0] for line in output.splitlines()[1:] if line.strip().endswith('\tdevice')]
        if len(devices) != 1:
            raise SystemExit('Select one local Pleos emulator with --serial. No device was changed.')
        args.serial = devices[0]
    if not args.serial.startswith('emulator-'):
        raise SystemExit('This verification refuses physical vehicles/devices.')
    base = [adb, '-s', args.serial]
    def read(*command):
        return subprocess.check_output(base+list(command), text=True, timeout=30).strip()
    if read('emu', 'avd', 'name').splitlines()[0] != 'DriveStep_Pleos':
        raise SystemExit('Use the DriveStep_Pleos verification AVD in P at zero speed.')
    if int(read('shell', 'getprop', 'ro.build.version.sdk')) < 33:
        raise SystemExit('The themed launcher verification requires API 33 or newer.')
    for apk in (args.apk, args.test_apk):
        if not apk.is_file():
            raise SystemExit('Build assembleDebug and assembleDebugAndroidTest first: '+str(apk))
    user = read('shell', 'am', 'get-current-user')
    if not user.isdigit():
        raise SystemExit('Cannot determine the active Android user.')
    report = ROOT/'.tooling/workflow-reverify-device.log'
    report.parent.mkdir(exist_ok=True)
    report.write_text('Pleos workflow / native icon / process recovery verification\n', encoding='utf-8')
    def test(label, classes, expected):
        command = base+['shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'class', classes, RUNNER]
        result = subprocess.run(command, capture_output=True, text=True, timeout=240)
        output = result.stdout+result.stderr
        with report.open('a', encoding='utf-8') as stream:
            stream.write('\n'+label+'\n'+output)
        match = re.search(r'OK\s+\((\d+) tests?\)', output)
        if (result.returncode or not match or int(match[1]) != expected or 'FAILURES' in output or
                'INSTRUMENTATION_FAILED' in output or 'INSTRUMENTATION_STATUS_CODE: -3' in output):
            print(output, flush=True)
            raise RuntimeError(label+' failed or skipped; see '+str(report))
        print('PASS:', label, '('+str(expected)+')', flush=True)
    for apk in (args.apk, args.test_apk):
        subprocess.run(base+['install', '-r', str(apk)], check=True, timeout=60)
    test('Restore unfinished verification if present', PROCESS+'#restoreOriginalState', 1)
    try:
        test('Workflow UI, consent store, and Android launcher rendering', ','.join([
            APP+'.workflow.WorkflowStoreTest', APP+'.workflow.WorkflowFlowTest', APP+'.branding.LauncherIconTest']), 4)
        test('Create a checkpoint with the production controls', PROCESS+'#createCheckpointForColdStart', 1)
        subprocess.run(base+['shell', 'am', 'force-stop', '--user', user, APP], check=True, timeout=30)
        test('Recover after real process termination without old motion or speech', PROCESS+'#verifyColdStartRecoveryAndExplicitPreparation', 1)
    finally:
        test('Restore original settings and checkpoint', PROCESS+'#restoreOriginalState', 1)
    print('PASS: 6 functional device tests. Cleanup checks are not counted as feature tests.', flush=True)
    print('Report:', report, flush=True)


if __name__ == '__main__':
    main()
