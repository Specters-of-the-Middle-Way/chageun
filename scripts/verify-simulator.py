#!/usr/bin/env python3
"""Pleos emulator integration of the parked-only driver-view simulator.

Uses real visible UI controls and saved records; never injects simulator state.
Existing app data is retained. Vehicle console input is only a test fixture.
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
spec = importlib.util.spec_from_file_location('s', Path(__file__).with_name('verify-steering.py'))
s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)
m = s.m
CLIENT = 'drivestep-simulator-v08'
jspec = importlib.util.spec_from_file_location('journey', Path(__file__).with_name('journey-test-support.py'))
j = importlib.util.module_from_spec(jspec); jspec.loader.exec_module(j)


def records():
    user = m.adb('shell', 'am', 'get-current-user')
    raw = subprocess.check_output([m.ADB, 'exec-out', 'run-as', m.APP, '--user', user,
                                   'cat', 'shared_prefs/drivestep_driving_simulator_v1.xml'])
    if b'No such file' in raw: return []
    root = ET.fromstring(raw)
    return json.loads(next((n.text for n in root if n.get('name') == 'records'), '[]'))


def controls():
    return {n.get('content-desc'): list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
            for n in m.ui().iter('node') if n.get('content-desc', '').startswith('sim_')}


def tap(bounds):
    x1, y1, x2, y2 = bounds
    m.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def hold(bounds, duration):
    x1, y1, x2, y2 = bounds
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    m.adb('shell', 'input', 'swipe', x, y, x, y, str(duration))


def steer(bounds, amount):
    x1, y1, x2, y2 = bounds
    # The UI's 16dp track padding is 16px on this 160dpi Pleos image.
    x = x1 + 16 + (x2-x1-32)*(amount+1)/2
    m.adb('shell', 'input', 'tap', str(round(x)), str((y1+y2)//2))


def log():
    return m.adb('logcat', '-d', '-s', 'DrivingSim:I', '*:S')


def phase(expected, timeout=20):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        values = re.findall(r'scenario=\w+ phase=(\w+) speed=', log())
        if values and values[-1] == expected:
            print('PASS: phase', expected, flush=True); return
        time.sleep(.08)
    raise AssertionError((expected, log()[-2000:]))


def complete(c, direction):
    steer(c['sim_steering'], .23 if direction == 'right' else -.23)
    phase('CENTER', timeout=25)
    tap(c['sim_center'])
    tap(c['sim_signal_off'])
    phase('COMPLETE')
    m.expect('방금의 움직임을 돌아봐요')
    row = records()[0]
    assert row['phase'] == 'COMPLETE', row
    assert len(row['reflections']) >= 3 and row['phaseEvents'][-1]['phase'] == 'COMPLETE'
    return row


def main():
    assert m.adb('get-serialno').startswith('emulator-')
    m.adb('forward', 'tcp:8088', 'tcp:8088')
    stop = threading.Event()
    def heartbeat():
        while not stop.is_set():
            m.request('/browser/session?client='+CLIENT, 'POST'); stop.wait(.8)
    m.request('/browser/session?client='+CLIENT, 'POST')
    worker = threading.Thread(target=heartbeat, daemon=True); worker.start()
    try:
        m.request('/vehicle/gear?gear=Park', 'PUT')
        m.request('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0', 'PUT')
        m.set_signal('Off'); m.restart(); j.revisit('drive') if j.has('journey_revisit') else j.prepare('drive')
        s.tap('sim_start_left'); m.screenshot('50-simulator-courses.png')
        s.audible(lambda: s.tap('sim_begin'), 'simulation_start_left')
        c = controls()
        assert all(c[k][3] <= 940 for k in ('sim_brake', 'sim_gas', 'sim_steering'))
        m.screenshot('51-simulator-road.png')
        s.audible(lambda: tap(c['sim_signal_right']), 'simulation_wrong_signal')
        s.audible(lambda: tap(c['sim_signal_left']), 'simulation_mirror_left')
        s.audible(lambda: tap(c['sim_mirror_left']), 'simulation_steer_left')
        row = complete(c, 'left')
        assert any(x['kind'] == 'WRONG_SIGNAL' for x in row['interventions'])
        m.screenshot('53-simulator-reflection.png')
        print('PASS: left lane manual sequence, friendly correction, persisted reflection', flush=True)

        s.tap('sim_courses'); s.tap('sim_start_right'); s.tap('sim_begin')
        c = controls(); tap(c['sim_signal_right']); tap(c['sim_mirror_right'])
        complete(c, 'right')
        print('PASS: right lane manual sequence', flush=True)

        s.tap('sim_courses'); s.tap('sim_start_blindspot'); s.tap('sim_begin')
        c = controls(); tap(c['sim_signal_left'])
        s.audible(lambda: tap(c['sim_mirror_left']), 'simulation_wait')
        phase('WAIT_FOR_GAP'); m.screenshot('52-simulator-nearby-car.png')
        phase('STEER', timeout=20)
        complete(c, 'left')
        assert any(x['phase'] == 'WAIT_FOR_GAP' for x in records()[0]['phaseEvents'])
        print('PASS: traffic waits for learner and moves before manual lane change', flush=True)

        s.tap('sim_courses'); s.tap('sim_start_left'); s.tap('sim_begin')
        c = controls(); tap(c['sim_signal_left'])
        s.audible(lambda: hold(c['sim_gas'], 2600), 'simulation_speed_rise')
        tap(c['sim_pause']); s.voice.assert_silent()
        assert next(n for n in m.ui().iter('node') if n.get('content-desc') == 'sim_gas').get('enabled') == 'false'
        s.tap('sim_resume')
        m.adb('shell', 'am', 'start', '-W', '--user', 'current', '-a', 'android.settings.SETTINGS')
        assert any(n.get('package') == 'com.android.car.settings' for n in m.ui().iter('node'))
        s.voice.assert_silent(); m.launch(); j.revisit('drive')
        assert any(n.get('content-desc') == 'sim_resume' for n in m.ui().iter('node'))
        s.tap('sim_resume')
        m.request('/vehicle/gear?gear=Drive', 'PUT'); time.sleep(1)
        s.voice.assert_silent()
        assert not any(n.get('content-desc') == 'sim_gas' and n.get('enabled') == 'true' for n in m.ui().iter('node'))
        m.request('/vehicle/gear?gear=Park', 'PUT'); m.launch()
        if j.has('journey_revisit'): j.revisit('drive')
        assert any(n.get('content-desc') == 'sim_resume' for n in m.ui().iter('node'))
        print('PASS: pause, background, real driving restriction stop inputs and speech', flush=True)
        s.tap('sim_finish')
        assert any(x['kind'] == 'SPEED_RISE' for x in records()[0]['interventions'])
        saved = records(); m.restart(); j.revisit('drive') if j.has('journey_revisit') else j.prepare('drive')
        assert records() == saved
        assert any(n.get('content-desc') == 'sim_begin' for n in m.ui().iter('node'))
        actual = m.request('/vehicle/state')
        assert actual['gear']['actual']['value'] == 'Park' and actual['controls']['speed_kph'] == 0
        print('PASS: saved history survives restart; real gear/speed unaffected by simulation', flush=True)
        print('PASS: v0.8 driver-view simulator integration', flush=True)
    finally:
        m.request('/vehicle/controls?speed=0&accelerator=0&brake=0', 'PUT')
        m.request('/vehicle/gear?gear=Park', 'PUT'); m.set_signal('Off')
        m.request('/browser/session?client='+CLIENT+'&closing=1', 'POST')
        stop.set(); worker.join(timeout=2)


if __name__ == '__main__': main()
