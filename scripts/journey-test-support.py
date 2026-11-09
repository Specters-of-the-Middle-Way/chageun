"""Visible-UI helpers for the v0.8 parked journey; no injected app state."""
import importlib.util
from pathlib import Path
import time
spec=importlib.util.spec_from_file_location('journey_steering',Path(__file__).with_name('verify-steering.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
m=s.m

def nodes(): return list(m.ui().iter('node'))
def has(key): return any(n.get('content-desc')==key for n in nodes())
def toggle(key,value):
    for n in nodes():
        if n.get('content-desc')==key:
            if (n.get('checked')=='true')!=value: s.tap_node(n)
            return
    raise AssertionError('Missing '+key)
def wait_key(key):
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        if has(key):return
    raise AssertionError('Missing '+key)
def prepare(destination='drive',consent=True):
    # Re-enter a profile to set explicit test consent; previous records are retained.
    s.tap('journey_nav_profile')
    toggle('journey_consent_records',consent)
    s.tap('journey_profile_continue')
    for _ in range(9):
        ns=nodes()
        if any(n.get('content-desc','').startswith('journey_preflight_') for n in ns):break
        s.tap('journey_setup_confirm')
    for i in range(7):
        ns=nodes();n=next(n for n in ns if n.get('content-desc')=='journey_preflight_'+str(i))
        if '✓' not in n.get('text',''): s.tap_node(n)
    s.tap('journey_preflight_continue')
    wait_key('journey_start_'+destination)
    s.tap('journey_start_'+destination)
    wait_key('park_start' if destination=='parking' else 'sim_begin')
def revisit(destination):
    s.tap('journey_revisit')
    for i in range(7):s.tap('journey_preflight_'+str(i))
    s.tap('journey_preflight_continue');s.tap('journey_start_'+destination)
