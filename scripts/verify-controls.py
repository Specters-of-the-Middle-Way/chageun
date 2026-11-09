#!/usr/bin/env python3
"""Native emulator indicator buttons → VehicleService → SDK → persisted record."""
import importlib.util
from pathlib import Path
import re
import time
spec=importlib.util.spec_from_file_location('steer',Path(__file__).with_name('verify-steering.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
m=s.m
assert m.adb('get-serialno').startswith('emulator-')
m.adb('forward','tcp:8088','tcp:8088')
m.request('/browser/session?client=drivestep-native-controls','POST')
try:
 m.request('/vehicle/controls?speed=0&steering=0','PUT')
 m.request('/vehicle/gear?gear=Park','PUT')
 m.set_signal('Off')
 m.restart();m.enter_sdk_home();m.expect('●  차량 신호 연결됨')
 s.tap('start_practice');s.tap('guide_start');m.expect('왼쪽 방향지시등을 켜 주세요')
 m.screenshot('14-native-signal-controls.png')
 tree=m.ui();points={}
 for key in ['left','off','right']:
  node=next(n for n in tree.iter('node') if n.get('content-desc')=='emulator_signal_'+key)
  x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')));points[key]=(str((x1+x2)//2),str((y1+y2)//2))
 # EVS briefly obscures UIAutomator, so reuse the on-screen buttons' observed positions.
 for key,stage in [('left','WAIT_LEFT_OFF'),('off','WAIT_RIGHT_ON'),('right','WAIT_RIGHT_OFF'),('off','COMPLETED')]:
  if key == 'off':
   # Observed Pleos 2.1.0 EVS close button at 1920x1080; do not disable the system camera.
   time.sleep(.8)
   assert '1920x1080' in m.adb('shell','wm','size')
   m.adb('shell','input','tap','688','138')
   time.sleep(.25)
  m.adb('shell','input','tap',*points[key]);m.expect_stage(stage);time.sleep(.3)
 m.expect('실습 완료 · 5/5 확인')
 print('ALL NATIVE INDICATOR BUTTON CHECKS PASSED',flush=True)
finally:
 m.request('/browser/session?client=drivestep-native-controls','POST')
 m.set_signal('Off')
 m.request('/browser/session?client=drivestep-native-controls&closing=1','POST')
