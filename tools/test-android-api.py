#!/usr/bin/env python3
"""Regression checks on a disposable emulator. Creates synthetic local profiles only."""
import argparse
import hashlib
import http.server
import threading
import time
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True, help='Disposable emulator serial; physical devices rejected')
p.add_argument('--adb', default=str(Path(__file__).resolve().parents[1] / '.local-tools/android-sdk/platform-tools/adb'))
args = p.parse_args()
assert args.serial.startswith('emulator-'), 'Use a disposable emulator'
package = 'io.github.liuw.surgebridge.debug'
def adb(*cmd):
    return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, capture_output=True).stdout
port = int(adb('forward', 'tcp:0', 'tcp:19090').decode())
token = adb('shell', 'run-as', package, 'cat', 'files/debug-api-token').decode().strip()
checks = 0
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(path, body=None, auth=True, extra=None):
    headers = {'Host': '127.0.0.1:19090', 'Content-Type': 'application/json'}
    if auth: headers['Authorization'] = 'Bearer ' + token
    if extra: headers.update(extra)
    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/{path}', headers=headers, data=json.dumps(body).encode() if body is not None else None)
    try:
        with opener.open(req, timeout=120) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as e: return e.code, json.load(e)
def expect(value, message):
    global checks
    assert value, message
    checks += 1

def profile(id):
    return next(x for x in request('profiles')[1]['profiles'] if x['id'] == id)

try:
    expect(request('health')[0] == 200, 'authenticated health')
    expect(request('health', auth=False)[0] == 401, 'missing token rejected')
    expect(request('health', extra={'Authorization': 'Bearer invalid'})[0] == 401, 'wrong token rejected')
    expect(request('health', extra={'Origin': 'https://example.invalid'})[0] == 403, 'browser origin rejected')
    expect(request('health', extra={'Host': 'example.invalid'})[0] == 403, 'host rejected')
    expect(request('missing')[0] == 404, 'unknown endpoint')
    state = request('status')[1]
    expect(state['service'] == 'Stopped', 'emulator service stopped')
    source = '[General]\nipv6=false\ndns-server=1.1.1.1\n[Proxy]\nNode=snell,192.0.2.1,443,psk=synthetic-test-only,version=6\n[Rule]\nFINAL,DIRECT\n'
    status, result = request('profiles/import', {'name': 'API Regression', 'source': source})
    expect(status == 201 and result['conversion']['format'] == 'surge', 'Surge import')
    id = result['id']
    expect(request('status')[1]['selectedProfileId'] == state['selectedProfileId'], 'import does not change selection')
    before = profile(id)['conversion']['runtimeSha256']
    expect(request('profiles/update', {'id': id, 'source': source.replace('version=6', 'version=1')})[0] == 422, 'invalid update fails')
    expect(profile(id)['conversion']['runtimeSha256'] == before, 'failed update preserves runtime')
    status, same = request('profiles/update', {'id': id, 'source': source})
    expect(status == 200 and not same['changed'], 'identical update is stable')
    changed = source.replace('dns-server=1.1.1.1', 'dns-server=9.9.9.9')
    expect(request('profiles/update', {'id': id, 'source': changed})[1]['changed'], 'valid update applied')
    digest = profile(id)['conversion']['runtimeSha256']
    expect(digest != before, 'runtime hash changes')
    # Check the source/private file relationship without emitting any source contents.
    paths = adb('shell', 'run-as', package, 'find', 'files/configs', '-name', '*.source').decode().splitlines()
    found = False
    for path in paths:
        snapshots = json.loads(adb('shell', 'run-as', package, 'cat', path))
        if digest in snapshots:
            found = snapshots[digest]['source'] == changed
            runtime = adb('shell', 'run-as', package, 'cat', path.removesuffix('.source'))
            expect(hashlib.sha256(runtime).hexdigest() == digest, 'snapshot bound to actual runtime')
            break
    expect(found, 'original Surge text preserved')
    expect(request('profiles/select', {'id': id})[0] == 200, 'select stopped profile')
    expect(request('status')[1]['selectedProfileId'] == id, 'selected state observable')
    if not request('status')[1]['vpnPermissionGranted']:
        expect(request('service/start', {})[0] == 409, 'VPN permission is enforced')
    expect(request('service/reload', {})[0] == 409, 'cannot reload stopped service')
    logs = request('logs')[1]
    expect('synthetic-test-only' not in json.dumps(logs), 'events contain no credential')
    expect('192.0.2.1' not in json.dumps(request('profiles')[1]), 'profiles omit endpoint')
    # A loopback fixture delivered via adb reverse tests actual Android downloads, cache
    # expiry, and failure preservation without any external account or subscription.
    fixtures = {'config': '', 'rules': 'DOMAIN-SUFFIX,example.org', 'status': 200, 'rule_fetches': 0}
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            is_rules = self.path == '/rules'
            if is_rules: fixtures['rule_fetches'] += 1
            data = fixtures['rules' if is_rules else 'config'].encode()
            self.send_response(fixtures['status']); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def log_message(self, *_): pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    device_port = int(adb('reverse', 'tcp:0', f'tcp:{server.server_port}').decode())
    try:
        fixtures['config'] = source.replace('FINAL,DIRECT', f'RULE-SET,http://127.0.0.1:{device_port}/rules,DIRECT,update-interval=1\nFINAL,DIRECT')
        code, remote = request('profiles/import', {'name': 'Subscription Regression', 'url': f'http://127.0.0.1:{device_port}/config'})
        expect(code == 201, 'remote Surge import')
        remote_id = remote['id']
        remote_hash = profile(remote_id)['conversion']['runtimeSha256']
        time.sleep(1.1)
        fixtures['rules'] = 'DOMAIN-SUFFIX,changed.example.org'
        refreshed = request('profiles/update', {'id': remote_id})
        expect(refreshed[0] == 200 and refreshed[1]['changed'], 'expired external rule set refreshes')
        expect(fixtures['rule_fetches'] >= 2, 'rule set downloaded again after TTL')
        new_hash = profile(remote_id)['conversion']['runtimeSha256']
        expect(new_hash != remote_hash, 'rule-only changes produce new runtime')
        fixtures['config'] = fixtures['config'].replace('version=6', 'version=1')
        expect(request('profiles/update', {'id': remote_id})[0] == 422, 'invalid remote content rejected')
        expect(profile(remote_id)['conversion']['runtimeSha256'] == new_hash, 'invalid remote content preserves previous config')
        fixtures['status'] = 503
        expect(request('profiles/update', {'id': remote_id})[0] == 422, 'HTTP download failure rejected')
        expect(profile(remote_id)['conversion']['runtimeSha256'] == new_hash, 'HTTP failure preserves previous config')
    finally:
        adb('reverse', '--remove', f'tcp:{device_port}'); server.shutdown(); server.server_close()
    print(f'PASS: {checks} Android API/storage/subscription assertions; synthetic profile ID {id}')
finally:
    adb('forward', '--remove', f'tcp:{port}')
