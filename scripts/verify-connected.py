#!/usr/bin/env python3
"""Verify public SDK inputs on DriveStep_Pleos; restore temporary permissions and settings.

--route-request also requests the official Korean example destination, then cancels
only that verification route. It requires Navigation to have no existing route.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
APP = 'kr.drivestep.tutor'
PERMISSION = 'pleos.car.permission.FUSED_LOCATION'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--route-request', action='store_true')
    args = parser.parse_args()
    adb = shutil.which('adb') or str(ROOT/'.tooling/android-sdk/platform-tools/adb')
    base = [adb, '-s', args.serial]
    def run(*command):
        return subprocess.check_output(base+list(command), text=True, timeout=60).strip()
    if not args.serial.startswith('emulator-') or run('emu', 'avd', 'name').splitlines()[0] != 'DriveStep_Pleos':
        raise SystemExit('Use only the DriveStep_Pleos verification emulator in P at zero speed.')
    user = run('shell', 'am', 'get-current-user')
    if not user.isdigit():
        raise SystemExit('Cannot determine Android user.')
    for path in ['debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk']:
        run('install', '-r', str(ROOT/'app/build/outputs/apk'/path))
    run('forward', 'tcp:8088', 'tcp:8088')
    package = run('shell', 'dumpsys', 'package', APP)
    section = package.split('User '+user+':', 1)[-1].split('User ', 1)[0]
    originally_granted = bool(re.search(re.escape(PERMISSION)+r': granted=true', section))
    report = ROOT/'.tooling/connected-verification.log'
    report.write_text('Public SDK, UI, warning, permission and navigation verification\n', encoding='utf-8')
    def test(label, classes, count):
        result = subprocess.run(base+['shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'class', classes,
            APP+'.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=240)
        output = result.stdout+result.stderr
        with report.open('a', encoding='utf-8') as stream:
            stream.write('\n'+label+'\n'+output)
        match = re.search(r'OK\s+\((\d+) tests?\)', output)
        if result.returncode or not match or int(match[1]) != count or any(marker in output for marker in ['FAILURES', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_STATUS_CODE: -3']):
            print(output, flush=True)
            raise RuntimeError(label+' failed; see '+str(report))
        print('PASS:', label, '('+str(count)+')', flush=True)
    prefix = APP+'.connected.'
    sdk = prefix+'ConnectedSdkTest#'
    methods = [sdk+'publicVehicleReadsReceiveRealSdkValuesAndStopCleanly',
        sdk+'publicLocationAndNavigationReceiveAndReleaseWithoutSendingRoutes',
        sdk+'consoleWarningReachesTheRealSdkListener', prefix+'ConnectedFlowTest']
    if args.route_request:
        methods += [sdk+'destinationRequestConfirmsActiveMatchingRoute']
    try:
        run('shell', 'pm', 'grant', '--user', user, APP, PERMISSION)
        if args.route_request:
            test('Restore only an unfinished verification navigation route', sdk+'restoreVerificationRouteIfPresent', 1)
        test('Actual SDK receipts, warning changes, UI consent and cleanup', ','.join(methods), len(methods))
        run('shell', 'pm', 'revoke', '--user', user, APP, PERMISSION)
        test('Real location permission denial without a navigation position fallback', prefix+'ConnectedDeniedPermissionTest', 1)
    finally:
        try:
            if args.route_request:
                test('Restore verification navigation without clearing data', sdk+'restoreVerificationRouteIfPresent', 1)
        finally:
            run('shell', 'pm', 'grant' if originally_granted else 'revoke', '--user', user, APP, PERMISSION)
    print('PASS:', len(methods)+1, 'functional tests. Original permission and test settings restored.', flush=True)
    print('Report:', report, flush=True)


if __name__ == '__main__':
    main()
