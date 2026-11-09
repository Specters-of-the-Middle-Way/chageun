#!/usr/bin/env python3
"""디버그 전용: PC에서 Pleos 에뮬레이터 신호를 입력하는 한국어 패널.

Run `adb forward tcp:8088 tcp:8088`, then `python3 scripts/control-panel.py`.
Open http://127.0.0.1:8090/. This is a local testing helper, not a product API.
It never calls app practice state, broadcasts fabricated SDK events, or scores input.
The upstream HTTP routes are observed Virtual Console implementation details.
"""
import argparse
import json
import math
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid


UPSTREAM = "http://127.0.0.1:8088"
CLIENT = "drivestep-pc-panel-" + uuid.uuid4().hex


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
WRITE_LOCK = threading.Lock()
SIGNALS = {"Off", "BlinkLeft", "BlinkRight"}
GEARS = {"Park", "Reverse", "Neutral", "Drive"}


def upstream(path, method="GET"):
    request = urllib.request.Request(UPSTREAM + path, method=method)
    with OPENER.open(request, timeout=3) as response:
        return json.load(response)


def heartbeat(closing=False):
    return upstream("/browser/session?client=" + CLIENT + ("&closing=1" if closing else ""), "POST")


def command_path(route, payload, steering_bounds):
    """Only explicit fixture actions can become console writes; no generic proxy."""
    if not isinstance(payload, dict):
        raise ValueError("JSON 객체가 필요합니다.")
    if route == "/api/signal" and set(payload) == {"mode"}:
        value = payload["mode"]
        if isinstance(value, str) and value in SIGNALS:
            return "/vehicle/turn-signal?" + urllib.parse.urlencode({"mode": value})
    elif route == "/api/gear" and set(payload) == {"gear"}:
        value = payload["gear"]
        if isinstance(value, str) and value in GEARS:
            return "/vehicle/gear?" + urllib.parse.urlencode({"gear": value})
    elif route == "/api/steering" and set(payload) == {"angle"}:
        value = payload["angle"]
        if type(value) in (int, float) and math.isfinite(value) and value == int(value):
            low, high = steering_bounds
            if low <= value <= high:
                return "/vehicle/controls?" + urllib.parse.urlencode({"steering": int(value)})
    raise ValueError("허용되지 않은 명령 또는 입력값입니다.")


def steering_bounds():
    catalog = upstream("/vehicle/catalog")
    feature = next((f for f in catalog.get("features", []) if f.get("id") == "steering-angle"), None)
    if not feature or feature.get("write_endpoint") != "/vehicle/controls":
        raise ValueError("조향 입력 카탈로그를 확인할 수 없습니다.")
    control = feature.get("control", {})
    if control.get("field") != "steering":
        raise ValueError("조향 입력 형식이 지원되지 않습니다.")
    low, high = control.get("min"), control.get("max")
    if type(low) not in (int, float) or type(high) not in (int, float) or not (math.isfinite(low) and math.isfinite(high) and low <= 0 <= high):
        raise ValueError("조향 입력 범위를 확인할 수 없습니다.")
    return (max(-540, low), min(540, high))


def state_summary():
    state = upstream("/vehicle/state")
    controls = state.get("controls", {})
    gear = state.get("gear", {})
    return {
        "gear": gear.get("actual", {}).get("value"),
        "signal": state.get("lights", {}).get("turn_signal_mode"),
        "steering": controls.get("steering_deg"),
        "speed": controls.get("speed_kph"),
        "browserActive": state.get("browser", {}).get("active", False),
        "gearBridgeReady": gear.get("bridge_ready", False),
        "steeringBridgeReady": controls.get("dds_ready", {}).get("steering", False),
        "timestamp": controls.get("timestamp_ms"),
    }


PAGE = """<!doctype html><html lang="ko"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>드라이브스텝 · 에뮬레이터 시험 패널</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f3f6f5;color:#182d32;font:18px system-ui,sans-serif}
main{max-width:940px;margin:32px auto;padding:0 22px}h1{font-size:30px}h2{font-size:21px;margin:0 0 14px}
.badge{display:inline-block;border-radius:20px;background:#e4eaf7;padding:7px 14px;font-weight:700}
.card{background:white;padding:22px;border:1px solid #d5e0dc;border-radius:20px;margin:18px 0}
.row{display:flex;gap:12px;flex-wrap:wrap}button,input{font:inherit}button{min-height:62px;flex:1;min-width:120px;border:1px solid #bfd0c9;border-radius:13px;background:#e1f2eb;color:#182d32;cursor:pointer;padding:12px}
button:disabled{opacity:.5;cursor:wait}button.selected{background:#087f71;color:white}input{width:120px;padding:10px;border:1px solid #bfd0c9;border-radius:8px}
p{line-height:1.55}.hint{color:#596f71;font-size:15px}.state{display:flex;gap:20px;flex-wrap:wrap;line-height:1.8}.state strong{font-size:23px}
#status{min-height:48px;font-weight:600}#status.error{color:#a43b20}#range{margin-bottom:12px}
</style><main>
<span class="badge">디버그 전용 · PC 에뮬레이터 시험 도구</span>
<h1>차량 신호를 간단하게 입력해요</h1>
<p>이 패널은 Pleos 가상 콘솔로 신호를 요청합니다. 실습 결과는 차량 앱이 실제 SDK 신호를 받은 뒤 판단해요.</p>
<section class="card"><div class="state">
<span>기어 <strong id="gear">—</strong></span><span>방향지시등 <strong id="signal">—</strong></span>
<span>조향 입력 <strong id="steering">—</strong></span><span>속도 <strong id="speed">—</strong></span></div>
<p class="hint" id="bridge">콘솔 연결을 확인하고 있어요.</p><div id="status" role="status">앱의 실습 화면을 함께 열어 주세요.</div></section>
<section class="card"><h2>방향지시등</h2><div class="row">
<button data-action="signal" data-value="BlinkLeft">← 왼쪽</button><button data-action="signal" data-value="Off">끄기</button><button data-action="signal" data-value="BlinkRight">오른쪽 →</button></div></section>
<section class="card"><h2>시험용 조향 입력</h2>
<p class="hint">이곳은 콘솔 원본 입력값입니다. 확인한 에뮬레이터 2.1.0에서는 SDK 수신값과 부호가 반대예요. 앱 안의 좌우 버튼은 SDK 기준으로 보정되어 있습니다. 핸들 회전 수로 환산하지 않아요.</p>
<div class="row"><button data-action="steering" data-value="-8">−8°</button><button data-action="steering" data-value="0">0°</button><button data-action="steering" data-value="8">+8°</button></div>
<p class="hint" id="range">현재 카탈로그 범위를 확인하고 있어요.</p><div class="row"><label>직접 입력 <input id="angle" type="number" step="1" value="0" aria-label="시험용 조향 입력 각도"></label><button id="applyAngle">각도 요청</button></div></section>
<section class="card"><h2>기어 · 앱의 제한 동작 확인</h2><p class="hint">R/N/D에서는 차량 앱 실습이 중단되거나 화면이 제한될 수 있어요. P 복귀는 이 PC 패널에서 해 주세요. 기어 버튼은 속도·페달 값을 바꾸지 않습니다.</p>
<div class="row"><button data-action="gear" data-value="Park">P · 주차</button><button data-action="gear" data-value="Reverse">R · 후진</button><button data-action="gear" data-value="Neutral">N · 중립</button><button data-action="gear" data-value="Drive">D · 주행</button></div></section>
<p class="hint">이 화면의 값은 콘솔 확인값입니다. 앱 SDK 수신 확인이나 실습 점수가 아닙니다. 다른 가상 콘솔과 동시에 조작하지 마세요. 창을 닫아도 P·조향값을 자동 변경하지 않습니다.</p>
</main><script>
const status=document.querySelector('#status');let busy=false,heartbeatBusy=false,pollBusy=false,closed=false;
const labels={Park:'P',Reverse:'R',Neutral:'N',Drive:'D',Off:'꺼짐',BlinkLeft:'왼쪽',BlinkRight:'오른쪽',FlashLeft:'왼쪽 Flash',FlashRight:'오른쪽 Flash'};
function show(message,error=false){status.textContent=message;status.className=error?'error':''}
async function api(path,payload){const options={cache:'no-store'};if(payload!==undefined){options.method='POST';options.headers={'Content-Type':'application/json'};options.body=JSON.stringify(payload)}const response=await fetch(path,options);const data=await response.json();if(!response.ok)throw Error(data.error||'연결 실패');return data}
async function refresh(){if(pollBusy||closed)return;pollBusy=true;try{const s=await api('/api/state');document.querySelector('#gear').textContent=labels[s.gear]||s.gear||'—';document.querySelector('#signal').textContent=labels[s.signal]||s.signal||'—';document.querySelector('#steering').textContent=s.steering===null||s.steering===undefined?'—':s.steering+'°';document.querySelector('#speed').textContent=s.speed===null||s.speed===undefined?'—':s.speed+' km/h';document.querySelector('#bridge').textContent='콘솔 세션 '+(s.browserActive?'활성':'대기')+' · 기어 브리지 '+(s.gearBridgeReady?'연결':'대기')+' · 조향 브리지 '+(s.steeringBridgeReady?'연결':'대기')+' · SDK 수신 여부는 차량 앱에서 확인';document.querySelectorAll('[data-action]').forEach(b=>b.classList.toggle('selected',b.dataset.action==='gear'&&b.dataset.value===s.gear||b.dataset.action==='signal'&&b.dataset.value===s.signal))}catch(e){show('연결 실패: 에뮬레이터와 adb forward tcp:8088 tcp:8088을 확인해 주세요.',true)}finally{pollBusy=false}}
async function send(kind,value){if(busy)return;busy=true;document.querySelectorAll('button').forEach(b=>b.disabled=true);show('가상 차량에 시험 신호를 요청하고 있어요.');try{const key=kind==='signal'?'mode':kind==='steering'?'angle':'gear';await api('/api/'+kind,{[key]:value});show('시험 신호 요청 완료 · 앱 SDK 수신 대기. 요청 성공은 실습 성공이 아니에요.');await refresh()}catch(e){show(e.message,true)}finally{busy=false;document.querySelectorAll('button').forEach(b=>b.disabled=false)}}
document.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>send(b.dataset.action,b.dataset.action==='steering'?Number(b.dataset.value):b.dataset.value));
document.querySelector('#applyAngle').onclick=()=>{const input=document.querySelector('#angle');if(!input.value.trim()||!input.checkValidity()){show('카탈로그 범위 안의 정수 각도를 입력해 주세요.',true);return}send('steering',Number(input.value))};
async function pulse(){if(heartbeatBusy||closed)return;heartbeatBusy=true;try{await api('/api/session',{})}catch(e){show('콘솔 세션 연결 실패 · 에뮬레이터 연결을 확인해 주세요.',true)}finally{heartbeatBusy=false}}
api('/api/catalog').then(c=>{document.querySelector('#angle').min=c.min;document.querySelector('#angle').max=c.max;document.querySelector('#range').textContent='현재 콘솔 카탈로그 범위: '+c.min+'° ~ '+c.max+'° (1° 간격)'}).catch(e=>show(e.message,true));
pulse().then(refresh);setInterval(pulse,1000);setInterval(refresh,1000);
window.addEventListener('pagehide',()=>{closed=true;fetch('/api/session-close',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}',keepalive:true}).catch(()=>{})});
</script></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Avoid a line for every poll; failures are displayed in the panel.
        if str(args[1]) not in ("200", "204", "304"):
            super().log_message(format, *args)

    def permitted_host(self):
        return self.headers.get("Host") in self.server.allowed_hosts

    def send_json(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self.permitted_host():
            return self.send_json(403, {"error": "로컬 주소만 사용할 수 있습니다."})
        try:
            if self.path == "/":
                data = PAGE.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")
                self.end_headers()
                return self.wfile.write(data)
            if self.path == "/api/state":
                return self.send_json(200, state_summary())
            if self.path == "/api/catalog":
                low, high = steering_bounds()
                return self.send_json(200, {"min": low, "max": high})
            return self.send_json(404, {"error": "지원하지 않는 경로입니다."})
        except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
            self.send_json(502, {"error": "가상 콘솔 연결 또는 응답 확인에 실패했습니다.", "detail": str(error)[:200]})

    def do_POST(self):
        if not self.permitted_host() or self.headers.get("Origin") not in self.server.allowed_origins:
            return self.send_json(403, {"error": "패널과 같은 로컬 출처에서만 요청할 수 있습니다."})
        if self.headers.get_content_type() != "application/json":
            return self.send_json(415, {"error": "JSON 요청만 지원합니다."})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1024:
                return self.send_json(413, {"error": "입력 크기가 올바르지 않습니다."})
            payload = json.loads(self.rfile.read(length))
            with WRITE_LOCK:
                if self.path in ("/api/session", "/api/session-close"):
                    if payload != {}:
                        raise ValueError("세션 요청은 빈 객체여야 합니다.")
                    heartbeat(self.path.endswith("-close"))
                    return self.send_json(200, {"ok": True})
                if self.path not in ("/api/signal", "/api/gear", "/api/steering"):
                    return self.send_json(404, {"error": "지원하지 않는 조작입니다."})
                bounds = steering_bounds() if self.path == "/api/steering" else (0, 0)
                path = command_path(self.path, payload, bounds)
                heartbeat()
                upstream(path, "PUT")
                return self.send_json(200, {"requested": True, "sdkReceived": None, "console": state_summary()})
        except ValueError as error:
            self.send_json(400, {"error": str(error)[:200]})
        except (urllib.error.URLError, OSError, KeyError) as error:
            self.send_json(502, {"error": "가상 콘솔 요청에 실패했습니다.", "detail": str(error)[:200]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.allowed_hosts = {f"127.0.0.1:{args.port}", f"localhost:{args.port}"}
    server.allowed_origins = {"http://" + host for host in server.allowed_hosts}
    print(f"디버그 전용 Pleos 시험 패널: http://127.0.0.1:{args.port}/", flush=True)
    print("가상 콘솔 연결: adb forward tcp:8088 tcp:8088", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        # Do not change gear, lamps, or steering as a side effect of shutdown.


if __name__ == "__main__":
    main()
