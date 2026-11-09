#!/usr/bin/env python3
"""Emulator-only action-triggered coaching integration; preserves existing records."""
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
sp=importlib.util.spec_from_file_location('s',Path(__file__).with_name('verify-steering.py'))
s=importlib.util.module_from_spec(sp);sp.loader.exec_module(s);m=s.m
CLIENT='drivestep-situation-v06'


def records():
    user=m.adb('shell','am','get-current-user')
    raw=subprocess.check_output([m.ADB,'exec-out','run-as',m.APP,'--user',user,'cat','shared_prefs/drivestep_situation_coach_v1.xml'])
    if b'No such file' in raw:return []
    root=ET.fromstring(raw)
    return json.loads(next((n.text for n in root if n.get('name')=='records'),'[]'))


def new_event(kind, old_id=None, muted=False):
    deadline=time.monotonic()+12
    while time.monotonic()<deadline:
        rows=records()
        if rows and rows[0]['id']!=old_id and rows[0]['kind']==kind:
            r=rows[0]
            assert r['source']=='SIMULATION' and r['speechSuppressed']==muted,r
            assert r['observations'] and all(x['source']=='SIMULATION' for x in r['observations'])
            print('PASS: auto-triggered',kind,'muted=',muted,'samples=',len(r['observations']),flush=True)
            return r
        time.sleep(.15)
    raise AssertionError((kind,records()[:1]))


def main():
    assert m.adb('get-serialno').startswith('emulator-')
    m.adb('forward','tcp:8088','tcp:8088')
    stop=threading.Event()
    def heartbeat():
        while not stop.is_set():
            m.request('/browser/session?client='+CLIENT,'POST');stop.wait(.8)
    m.request('/browser/session?client='+CLIENT,'POST')
    worker=threading.Thread(target=heartbeat,daemon=True);worker.start()
    try:
        m.request('/vehicle/gear?gear=Park','PUT')
        m.request('/vehicle/controls?speed=0&accelerator=0&brake=0','PUT')
        m.set_signal('Off');m.restart()
        s.tap('coach_situation')
        m.expect('상황이 생기면 먼저 도와주는 코치')
        before=records()
        old=before[0]['id'] if before else None
        s.audible(lambda:s.tap('situation_demo_decelerate'),'situation_speed_drop')
        drop=new_event('SPEED_DROP',old)
        assert all(r['signal']=='LEFT' for r in drop['observations'])
        assert drop['observations'][0]['speed']-drop['observations'][-1]['speed']>=12
        assert drop['observations'][-1]['brake'] is True
        m.screenshot('41-situation-detected.png')
        time.sleep(3)
        assert records()[0]['id']==drop['id']
        print('PASS: repeated same situation produces no duplicate event',flush=True)
        s.audible(lambda:s.tap('situation_demo_accelerate'),'situation_speed_rise')
        rise=new_event('SPEED_RISE',drop['id'])
        assert all(r['signal']=='RIGHT' for r in rise['observations'])
        s.tap('situation_warning_audio')
        count=s.cue_count('situation_lane_warning')
        s.tap('situation_demo_warning')
        suppressed=new_event('LANE_WARNING',rise['id'],True)
        s.voice.assert_silent()
        assert s.cue_count('situation_lane_warning')==count
        s.tap('situation_warning_audio')
        time.sleep(1)
        assert s.cue_count('situation_lane_warning')==count
        print('PASS: OEM warning priority; suppressed speech is not queued',flush=True)
        s.audible(lambda:s.tap('situation_demo_warning'),'situation_lane_warning')
        event=new_event('LANE_WARNING',suppressed['id'])
        s.tap('situation_review_last')
        m.expect('이때, 코치가 왜 반응했을까요?')
        s.tap('situation_replay')
        m.expect('재현을 마쳤어요. 아래 기록에서 코치가 반응한 근거를 다시 볼 수 있어요.')
        m.screenshot('42-situation-review.png')
        assert records()[0]['id']==event['id']
        s.tap('situation_review_back')
        s.tap('situation_resume')
        m.adb('shell','am','start','--user','current','-a','android.settings.SETTINGS')
        s.voice.assert_silent();m.launch()
        assert any(n.get('content-desc')=='situation_resume' for n in m.ui().iter('node'))
        print('PASS: background requires explicit resume',flush=True)
        s.tap('situation_resume')
        m.request('/vehicle/gear?gear=Drive','PUT');time.sleep(1)
        s.voice.assert_silent()
        assert not any(n.get('content-desc')=='situation_demo_warning' for n in m.ui().iter('node'))
        m.request('/vehicle/gear?gear=Park','PUT');m.launch()
        assert any(n.get('content-desc')=='situation_resume' for n in m.ui().iter('node'))
        print('PASS: real driving restrictions stop scenario input and speech',flush=True)
        saved=records();m.restart();s.tap('coach_situation')
        assert records()==saved
        s.tap('situation_review_last');m.expect('이때, 코치가 왜 반응했을까요?')
        s.tap('situation_exit')
        actual=m.request('/vehicle/state')
        assert actual['gear']['actual']['value']=='Park' and actual['controls']['speed_kph']==0
        print('PASS: cases survive restart; virtual inputs never changed actual gear/speed',flush=True)
        print('PASS: v0.6 situation coach integration',flush=True)
    finally:
        m.request('/vehicle/controls?speed=0&accelerator=0&brake=0','PUT')
        m.request('/vehicle/gear?gear=Park','PUT');m.set_signal('Off')
        m.request('/browser/session?client='+CLIENT+'&closing=1','POST')
        stop.set();worker.join(timeout=2)

if __name__=='__main__':main()
