#!/usr/bin/env python3
"""Verify Android TUN -> Snell v6 -> controlled localhost TCP/UDP servers. Emulator only."""
import argparse
import http.server
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('core')
p.add_argument('--serial', required=True)
p.add_argument('--adb', default=str(Path(__file__).resolve().parents[1] / '.local-tools/android-sdk/platform-tools/adb'))
args = p.parse_args()
assert args.serial.startswith('emulator-'), 'Disposable emulator required'
package = 'io.github.liuw.surgebridge.debug'
def adb(*cmd, **kw):
    return subprocess.run([args.adb, '-s', args.serial, *cmd], capture_output=True, timeout=15, **kw)
local_port = int(adb('forward', 'tcp:0', 'tcp:19090', check=True).stdout.decode())
token = adb('shell', 'run-as', package, 'cat', 'files/debug-api-token', check=True).stdout.decode().strip()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(path, data=None):
    request = urllib.request.Request(f'http://127.0.0.1:{local_port}/v1/{path}', data=None if data is None else json.dumps(data).encode(), headers={'Host': '127.0.0.1:19090', 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with opener.open(request, timeout=30) as r: return json.load(r)
def wait_state(state):
    for _ in range(60):
        if api('status')['service'] == state: return
        time.sleep(.25)
    raise RuntimeError('Service state timeout')

class HTTP(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'ANDROID-SNELL-V6-TCP-OK')
    def log_message(self, *_): pass
http = http.server.ThreadingHTTPServer(('127.0.0.1', 0), HTTP)
threading.Thread(target=http.serve_forever, daemon=True).start()
udp = socket.socket(type=socket.SOCK_DGRAM); udp.bind(('127.0.0.1', 0)); udp.settimeout(.5)
running = True
def echo():
    while running:
        try:
            data, peer = udp.recvfrom(4096); udp.sendto(data, peer)
        except socket.timeout: pass
threading.Thread(target=echo, daemon=True).start()
with socket.socket() as s: s.bind(('127.0.0.1', 0)); snell_port = s.getsockname()[1]
previous = api('status')['selectedProfileId']
try:
    api('service/stop', {}); wait_state('Stopped')
    assert api('status')['vpnPermissionGranted'], 'Approve the emulator VPN dialog in the app first'
    with tempfile.TemporaryDirectory(prefix='android-snell-v6-') as temp:
        config = {'log': {'level': 'error'}, 'inbounds': [{'type': 'snell', 'listen': '127.0.0.1', 'listen_port': snell_port, 'version': 6, 'psk': 'android-test-only', 'mode': 'default'}], 'outbounds': [{'type': 'direct', 'tag': 'direct'}], 'route': {'rules': [
            {'network': 'tcp', 'action': 'route', 'outbound': 'direct', 'override_address': '127.0.0.1', 'override_port': http.server_port},
            {'network': 'udp', 'action': 'route', 'outbound': 'direct', 'override_address': '127.0.0.1', 'override_port': udp.getsockname()[1]}]}}
        path = Path(temp) / 'server.json'; path.write_text(json.dumps(config))
        with open(Path(temp) / 'core.log', 'w') as log:
            process = subprocess.Popen([str(Path(args.core).resolve()), 'run', '-c', str(path)], stdout=log, stderr=log)
            try:
                source = f'[General]\nipv6=false\n[Proxy]\nTest=snell,10.0.2.2,{snell_port},psk=android-test-only,version=6,udp-relay=true\n[Rule]\nFINAL,Test'
                profile = api('profiles/import', {'name': 'Android Snell Integration', 'source': source})
                api('profiles/select', {'id': profile['id']}); api('service/start', {}); wait_state('Started')
                response = adb('shell', 'toybox', 'nc', '-w', '5', '203.0.113.17', '18080', input=b'GET / HTTP/1.0\r\nHost: test\r\n\r\n').stdout
                assert b'ANDROID-SNELL-V6-TCP-OK' in response, 'Android TCP tunnel failed'
                response = adb('shell', 'toybox', 'nc', '-u', '-w', '3', '-W', '3', '-q', '3', '203.0.113.17', '18081', input=b'ANDROID-SNELL-V6-UDP-OK').stdout
                assert b'ANDROID-SNELL-V6-UDP-OK' in response, 'Android UDP tunnel failed'
                print('PASS: Android emulator TUN -> Snell v6 default -> localhost TCP and UDP')
            finally:
                api('service/stop', {}); wait_state('Stopped')
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
finally:
    if previous > 0: api('profiles/select', {'id': previous})
    running = False; http.shutdown(); http.server_close(); udp.close()
    adb('forward', '--remove', f'tcp:{local_port}')
