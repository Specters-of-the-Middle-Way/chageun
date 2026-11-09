#!/usr/bin/env python3
"""v0.8 visible UI regression, on emulator only; retains existing records.
Adds virtual test records, toggles opt-in, and restores P/0 and recording-on.
No app-state or vehicle geometry injection. Deletion confirmation is cancelled.
"""
import importlib.util,json,re,subprocess,threading,time,xml.etree.ElementTree as ET
from pathlib import Path
spec=importlib.util.spec_from_file_location('journey',Path(__file__).with_name('journey-test-support.py'))
j=importlib.util.module_from_spec(spec);spec.loader.exec_module(j);s=j.s;m=j.m
CLIENT='drivestep-v08-validation'
def controls():return {n.get('content-desc'):list(map(int,re.findall(r'\d+',n.get('bounds','')))) for n in m.ui().iter('node') if n.get('content-desc','').startswith('park_')}
def tap(b):
 x1,y1,x2,y2=b;m.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def hold(b,ms):
 x1,y1,x2,y2=b;x=str((x1+x2)//2);y=str((y1+y2)//2);m.adb('shell','input','swipe',x,y,x,y,str(ms))
def steer(c,amount):
 x1,y1,x2,y2=c['park_steering'];m.adb('shell','input','tap',str(round(x1+16+(x2-x1-32)*(amount+1)/2)),str((y1+y2)//2))
def records():
 user=m.adb('shell','am','get-current-user');p=subprocess.run([m.ADB,'exec-out','run-as',m.APP,'--user',user,'cat','shared_prefs/drivestep_parking_simulator_v1.xml'],capture_output=True)
 if p.returncode:return []
 root=ET.fromstring(p.stdout);return json.loads(next((n.text for n in root if n.get('name')=='records'),'[]'))
def shot(name,key):j.wait_key(key);m.ui();m.screenshot(name)
def pulse(c,ms):
 tap(c['park_gear_r']);tap(c['park_brake_hold']);hold(c['park_gas'],ms);tap(c['park_brake_hold']);time.sleep(.6);tap(c['park_gear_p'])
def save():
 s.tap('park_feel_good');s.tap('park_save');m.ui()
def main():
 assert m.adb('get-serialno').startswith('emulator-')
 m.adb('forward','tcp:8088','tcp:8088');stop=threading.Event()
 def beat():
  while not stop.is_set():m.request('/browser/session?client='+CLIENT,'POST');stop.wait(.8)
 worker=threading.Thread(target=beat,daemon=True);worker.start()
 try:
  m.request('/vehicle/gear?gear=Park','PUT');m.request('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT');m.set_signal('Off');m.restart()
  j.prepare('parking');s.tap('park_course_reverse');shot('65-parking-courses.png','park_start')
  s.audible(lambda:s.tap('park_start'),'parking_sim_start_reverse');c=controls()
  assert all(c[k][3]<=940 for k in ('park_gas','park_brake','park_steering'))
  shot('62-parking-simulation.png','park_gear_r')
  s.audible(lambda:tap(c['park_gear_r']),'parking_sim_brake')
  tap(c['park_brake_hold']);pulse(c,4300)
  s.audible(lambda:tap(c['park_finish']),'parking_sim_complete');save();shot('63-parking-review.png','park_retry')
  assert records()[0]['insideTarget'] and records()[0]['source']=='SIMULATION'
  print('PASS reverse actual UI and audio, saved source label',flush=True)
  s.tap('park_courses');s.tap('park_course_parallel');s.tap('park_start');c=controls();shot('66-parallel-parking.png','park_gear_r')
  tap(c['park_brake_hold']);steer(c,1)
  pulse(c,2200);pulse(c,1100);steer(c,-1);pulse(c,2200);pulse(c,1100);tap(c['park_center'])
  s.audible(lambda:tap(c['park_finish']),'parking_sim_complete');save();shot('67-parallel-review.png','park_retry')
  assert records()[0]['insideTarget'] and records()[0]['angleErrorDegrees']<8
  print('PASS parallel two manual reverse arcs and save',flush=True)
  s.tap('park_courses');saved=records();s.tap('park_delete_history');m.expect('주차 연습 기록을 삭제할까요?')
  ns=j.nodes();s.tap_node(next(n for n in ns if n.get('text')=='취소'));assert records()==saved
  print('PASS scoped delete confirmation, cancel preserves history',flush=True)
  s.tap('park_start');s.tap('park_pause');s.voice.assert_silent();s.tap('park_resume')
  m.adb('shell','am','start','-W','--user','current','-a','android.settings.SETTINGS')
  assert any(n.get('package')=='com.android.car.settings' for n in j.nodes());s.voice.assert_silent();m.launch()
  j.wait_key('journey_revisit');s.tap('journey_revisit')
  ns=j.nodes();assert all('✓' not in n.get('text','') for n in ns if re.fullmatch('journey_preflight_[0-6]',n.get('content-desc','')))
  assert next(n for n in ns if n.get('content-desc')=='journey_preflight_continue').get('enabled')=='false'
  for i in range(7):s.tap('journey_preflight_'+str(i))
  s.tap('journey_preflight_continue');s.tap('journey_start_parking');j.wait_key('park_resume');s.tap('park_resume')
  m.request('/vehicle/gear?gear=Drive','PUT');time.sleep(.7);s.voice.assert_silent()
  assert not any(n.get('content-desc')=='park_gas' and n.get('enabled')=='true' for n in j.nodes())
  m.request('/vehicle/gear?gear=Park','PUT');m.launch()
  if j.has('journey_revisit'):j.revisit('parking')
  j.wait_key('park_resume');s.tap('park_exit');j.wait_key('park_feel_good');s.tap('park_exit');j.wait_key('journey_nav_profile')
  print('PASS lifecycle + fresh preflight + real driving UX gate',flush=True)
  saved=records();j.prepare('parking',consent=False);s.tap('park_start');s.tap('park_exit');s.tap('park_feel_hard');s.tap('park_save');assert records()==saved
  print('PASS record consent off leaves persisted parking records unchanged',flush=True)
  s.tap('park_exit');s.tap('journey_nav_profile');j.toggle('journey_consent_records',True)
  shot('60-ioniq3-profile.png','journey_profile_continue');s.tap('journey_profile_continue');shot('61-seat-setup.png','journey_setup_confirm')
  for _ in range(9):
   if j.has('journey_preflight_continue'):break
   s.tap('journey_setup_confirm')
  for i in range(7):
   n=next(n for n in j.nodes() if n.get('content-desc')=='journey_preflight_'+str(i))
   if '✓' not in n.get('text',''):s.tap_node(n)
  s.tap('journey_preflight_continue');shot('64-practice-home.png','journey_start_parking')
  actual=m.request('/vehicle/state');assert actual['gear']['actual']['value']=='Park' and actual['controls']['speed_kph']==0
  print('PASS actual gear/speed unchanged by all virtual controls',flush=True)
  print('PASS v0.8 integration',flush=True)
 finally:
  m.request('/vehicle/gear?gear=Park','PUT');m.request('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT');m.set_signal('Off');stop.set();worker.join(timeout=2);m.request('/browser/session?client='+CLIENT+'&closing=1','POST')
if __name__=='__main__':main()
