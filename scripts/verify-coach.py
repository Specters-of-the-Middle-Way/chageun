#!/usr/bin/env python3
"""v0.5 emulator-only integration: real SDK practice + isolated virtual coaching.
Adds labelled local exercise records; never clears user/app data. Virtual pedals
are operated through UI touch, not by setting the internal lesson state.
"""
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('coach_helpers', Path(__file__).with_name('verify-steering.py'))
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
m = s.m
CLIENT = 'drivestep-coach-v05'


def node(key):
    return next(n for n in m.ui().iter('node') if n.get('content-desc') == key)


def point(key):
    n = node(key)
    assert n.get('enabled') == 'true', key
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds', '')))
    return ((x1+x2)//2, (y1+y2)//2)


def tap_at(p):
    m.adb('shell', 'input', 'tap', str(p[0]), str(p[1]))


def hold_at(p, seconds):
    m.adb('shell', 'input', 'swipe', str(p[0]), str(p[1]), str(p[0]), str(p[1]), str(int(seconds*1000)))


def phase():
    log = m.adb('logcat', '-d', '-s', 'DriveRide:D', '*:S')
    found = re.findall(r'phase=(\w+)', log)
    return found[-1] if found else None


def expect_phase(value, timeout=18):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if phase() == value:
            print('PASS: virtual action phase', value, flush=True)
            return
        time.sleep(.1)
    raise AssertionError(f'Expected {value}; got {phase()}')


def records():
    user = m.adb('shell', 'am', 'get-current-user')
    raw = subprocess.check_output([m.ADB, 'exec-out', 'run-as', m.APP, '--user', user,
                                   'cat', 'shared_prefs/drivestep_ride_preview_v1.xml'])
    if b'No such file' in raw: return []
    root = ET.fromstring(raw)
    text = next((n.text for n in root if n.get('name') == 'records'), '[]')
    return json.loads(text)


def start_sim(lesson):
    s.tap('ride_' + lesson)
    s.tap('ride_start')
    assert not any(n.get('content-desc', '').startswith('growth_choice_') for n in m.ui().iter('node'))


def main():
    assert m.adb('get-serialno').startswith('emulator-'), 'Emulator only'
    m.adb('forward', 'tcp:8088', 'tcp:8088')
    stop = threading.Event()
    def heartbeat():
        while not stop.is_set():
            try: m.request('/browser/session?client=' + CLIENT, 'POST')
            except Exception as exc: print('heartbeat:', exc, flush=True)
            stop.wait(.8)
    m.request('/browser/session?client=' + CLIENT, 'POST')
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        m.request('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0', 'PUT')
        m.request('/vehicle/gear?gear=Park', 'PUT')
        m.set_signal('Off')
        m.restart()
        m.expect('제가 알려드릴게요.\n직접 해 보면서 익혀요.')
        m.screenshot('30-coach-home.png')
        s.tap('coach_setup')
        m.expect('먼저, 내 몸에 맞는 운전자세')
        s.tap('growth_back')
        m.expect('제가 알려드릴게요.\n직접 해 보면서 익혀요.')
        s.tap('coach_signal')
        s.tap('guide_start')
        m.expect_stage('WAIT_LEFT_ON')
        s.audible(lambda: m.set_signal('BlinkRight'), 'signal_wrong_right_for_left')
        m.expect_stage('WAIT_LEFT_ON', 'TURN_OFF_ALL')
        m.set_signal('Off')
        m.expect_stage('WAIT_LEFT_ON', 'TURN_LEFT_ON')
        for mode, stage in [('BlinkLeft','WAIT_LEFT_OFF'),('Off','WAIT_RIGHT_ON'),('BlinkRight','WAIT_RIGHT_OFF'),('Off','COMPLETED')]:
            m.set_signal(mode); m.expect_stage(stage)
        s.tap('coach_continue_steering')
        m.expect('주차 전 · 기어와 조향 익히기')
        s.tap('home')
        s.tap('coach_ride')
        old = records()
        start_sim('start_stop')
        expect_phase('READY_BRAKE')
        s.audible(lambda: s.tap('ride_gear_d'), 'ride_brake_for_gear')
        s.tap('ride_brake_hold')
        expect_phase('READY_DRIVE')
        s.tap('ride_gear_d')
        expect_phase('DRIVE_TO_LINE')
        gas = point('ride_gas'); brake_hold = point('ride_brake_hold')
        tap_at(brake_hold)
        hold_at(gas, 10.0)
        tap_at(brake_hold)
        expect_phase('STOP_AND_PARK')
        s.tap('ride_gear_p')
        expect_phase('COMPLETED')
        m.expect('오늘은 여기까지 함께했어요')
        assert records()[0]['lesson'] == 'START_STOP' and records()[0]['outcome'] == '끝까지 함께함'
        assert records()[0]['interventions'] >= 1
        m.screenshot('31-coach-reflection.png')
        s.tap('ride_courses')
        start_sim('crosswalk')
        expect_phase('APPROACH_CROSSWALK')
        s.tap('ride_brake_hold')
        expect_phase('WAIT_FOR_PEDESTRIAN')
        m.screenshot('32-coach-crosswalk.png')
        expect_phase('WAIT_FOR_GREEN')
        expect_phase('RESUME_AFTER_GREEN')
        gas = point('ride_gas'); brake_hold = point('ride_brake_hold')
        tap_at(brake_hold)
        hold_at(gas, 5)
        expect_phase('COMPLETED')
        rows = records()
        assert rows[0]['lesson'] == 'CROSSWALK' and rows[0]['outcome'] == '끝까지 함께함'
        assert len(rows) == min(20, len(old) + 2)
        assert len(rows[0]['reflection']) >= 3
        actual = m.request('/vehicle/state')
        assert actual['gear']['actual']['value'] == 'Park', actual['gear']
        assert actual['controls']['speed_kph'] == 0 and actual['controls']['brake_pct'] == 0
        print('PASS: virtual gears and pedals never changed emulator vehicle state', flush=True)
        s.tap('ride_courses'); start_sim('start_stop')
        s.tap('ride_brake_hold'); expect_phase('READY_DRIVE')
        m.adb('shell', 'am', 'start', '--user', 'current', '-a', 'android.settings.SETTINGS')
        s.voice.assert_silent()
        m.launch()
        s.tap('ride_resume')
        assert node('ride_brake_hold').get('checked') == 'false'
        print('PASS: background pauses and clears held pedal; explicit resume', flush=True)
        m.request('/vehicle/gear?gear=Drive', 'PUT')
        time.sleep(1)
        s.voice.assert_silent()
        assert not any(n.get('content-desc') == 'ride_gas' for n in m.ui().iter('node'))
        m.request('/vehicle/gear?gear=Park', 'PUT')
        m.launch()
        s.tap('ride_resume')
        print('PASS: driving restriction stops virtual lesson; P does not auto-resume', flush=True)
        s.tap('ride_exit')
        rows = records()
        m.restart()
        s.tap('coach_ride')
        assert records() == rows
        assert not any(n.get('content-desc') == 'ride_gas' for n in m.ui().iter('node'))
        s.tap('ride_exit')
        print('PASS: saved reflections survive restart; current lesson never auto-starts', flush=True)
        print('PASS: v0.5 coach integration', flush=True)
    finally:
        try:
            m.request('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0', 'PUT')
            m.request('/vehicle/gear?gear=Park', 'PUT')
            m.set_signal('Off')
            m.request('/browser/session?client=' + CLIENT + '&closing=1', 'POST')
        finally:
            stop.set(); worker.join(timeout=2)

if __name__ == '__main__': main()
