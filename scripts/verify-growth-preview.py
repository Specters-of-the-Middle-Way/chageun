#!/usr/bin/env python3
"""Verify score-free preview and parked-only interruption without deleting progress."""
import importlib.util
from pathlib import Path
import threading
import time
spec = importlib.util.spec_from_file_location('growth', Path(__file__).with_name('verify-growth.py'))
g = importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
s = g.s; m = g.m
assert m.adb('get-serialno').startswith('emulator-')
client='drivestep-growth-preview'
stop=threading.Event()
def heartbeat():
 while not stop.is_set():
  try:m.request('/browser/session?client='+client,'POST')
  except Exception:pass
  stop.wait(.8)
m.request('/browser/session?client='+client,'POST')
worker=threading.Thread(target=heartbeat,daemon=True);worker.start()
try:
 m.request('/vehicle/gear?gear=Park','PUT');m.request('/vehicle/controls?speed=0&steering=0','PUT');m.set_signal('Off')
 m.restart();m.expect('성장 튜터   ·   가상 상황 연습')
 before=g.prefs()
 s.tap('growth_preview')
 s.tap('growth_lesson_alley')
 m.expect('미리보기 · 학습 기록과 포인트에 반영하지 않아요')
 path=next(p for p in g.PATHS if p['id']=='alley')
 for i,step in enumerate(path['steps']):
  s.tap('growth_choice_'+step['correct'])
  if i<len(path['steps'])-1:s.tap('growth_next')
 m.expect('100/100  ·  이번 가상 연습')
 time.sleep(.5)
 assert g.prefs()==before,'Preview changed persisted profile or progress'
 g.expect_scene('후방 확인이 전제된 가상 후진 예시')
 m.screenshot('growth-preview-alley.png')
 print('PASS: preview completed without changing persisted scores or history',flush=True)
 s.tap('growth_finish');s.tap('growth_lesson_right_turn')
 m.expect('우회전 신호 읽기')
 m.request('/vehicle/gear?gear=Reverse','PUT')
 # Reverse may show the system EVS camera above our blocked screen.
 # Verify the platform restriction and absence of interactive lesson controls,
 # rather than requiring app text to remain visible through that system window.
 time.sleep(1)
 ux=m.adb('shell','dumpsys','car_service','--services','CarUxRestrictionsManagerService')
 assert 'No DO -> DO' in ux, 'No platform driving restriction observed'
 s.voice.assert_silent()
 assert not any(n.get('content-desc','').startswith('growth_choice_') for n in m.ui().iter('node'))
 assert g.prefs()==before
 print('PASS: driving restriction hides simulation controls and silences guidance',flush=True)
 m.request('/vehicle/gear?gear=Park','PUT');m.restart();m.expect('성장 튜터   ·   가상 상황 연습')
 print('ALL PREVIEW CHECKS PASSED',flush=True)
finally:
 for path in ['/vehicle/controls?speed=0&steering=0','/vehicle/gear?gear=Park','/vehicle/turn-signal?mode=Off']:
  try:m.request(path,'PUT')
  except Exception:pass
 stop.set();worker.join(timeout=3)
 m.request('/browser/session?client='+client+'&closing=1','POST')
