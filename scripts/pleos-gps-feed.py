#!/usr/bin/env python3
"""Feed checksummed, stationary test GPS to DriveStep_Pleos started with -no-passive-gps.

This repairs emulator NMEA input, not the native navigation app's fixed-location
branch or map style. No real vehicle or external server is contacted.
"""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import shutil
import subprocess
import time


def nmea(body):
    checksum = 0
    for value in body.encode('ascii'):
        checksum ^= value
    return f'${body}*{checksum:02X}'


def coordinate(value, latitude):
    limit = 90 if latitude else 180
    if not math.isfinite(value) or abs(value) > limit:
        raise ValueError('Invalid test coordinate')
    degrees, minutes = divmod(round(abs(value) * 600_000), 600_000)
    return f'{degrees:0{2 if latitude else 3}d}{minutes / 10_000:07.4f}', ('N' if value >= 0 else 'S') if latitude else ('E' if value >= 0 else 'W')


def sentences(latitude, longitude, now):
    lat, ns = coordinate(latitude, True)
    lon, ew = coordinate(longitude, False)
    return (
        nmea(f'GPGGA,{now:%H%M%S},{lat},{ns},{lon},{ew},1,08,0.9,50.0,M,0.0,M,,'),
        nmea(f'GPRMC,{now:%H%M%S},A,{lat},{ns},{lon},{ew},0.0,0.0,{now:%d%m%y},0.0,E'),
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--latitude', type=float, default=37.46619867)
    parser.add_argument('--longitude', type=float, default=127.126595)
    parser.add_argument('--seconds', type=float, default=0, help='0: until emulator disconnects')
    args = parser.parse_args()
    if not args.adb or not args.serial.startswith('emulator-'):
        parser.error('An adb executable and local emulator serial are required')
    sentences(args.latitude, args.longitude, datetime.now(timezone.utc))
    if not math.isfinite(args.seconds) or args.seconds < 0:
        parser.error('--seconds must be finite and nonnegative')
    base = [args.adb, '-s', args.serial]

    def run(*command):
        return subprocess.check_output(base + list(command), timeout=10, stderr=subprocess.STDOUT).decode(errors='replace')

    if run('emu', 'avd', 'name').splitlines()[0].strip() != 'DriveStep_Pleos':
        parser.error('Only DriveStep_Pleos is supported')
    if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        parser.error('Refusing a non-emulator device')
    state_dir = Path(__file__).resolve().parents[1] / '.tooling'
    state_dir.mkdir(exist_ok=True)
    with (state_dir / f'gps-feed-{args.serial}.lock').open('a+b') as lock:
        lock.seek(0, 2)
        if not lock.tell():
            lock.write(b'0'); lock.flush()
        lock.seek(0)
        try:
            if __import__('os').name == 'nt':
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            print('GPS feed already running for this emulator.')
            return
        print('Feeding stationary Bokjeong/test coordinates with valid checksums.', flush=True)
        started = time.monotonic()
        count = 0
        try:
            while args.seconds == 0 or time.monotonic() - started < args.seconds:
                now = datetime.now(timezone.utc)
                for message in sentences(args.latitude, args.longitude, now):
                    if 'OK' not in run('emu', 'geo', 'nmea', message):
                        raise RuntimeError('Emulator rejected NMEA input')
                count += 1
                (state_dir / f'gps-feed-{args.serial}.json').write_text(json.dumps({
                    'mode': 'stationary_test_location', 'latitude': args.latitude,
                    'longitude': args.longitude, 'sent_pairs': count, 'last_sent_utc': now.isoformat(),
                }, indent=2), encoding='utf-8')
                time.sleep(1)
        except (subprocess.SubprocessError, RuntimeError, KeyboardInterrupt) as error:
            print(f'GPS feed stopped: {type(error).__name__}', flush=True)


if __name__ == '__main__':
    main()
