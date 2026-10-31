#!/usr/bin/env python3
"""Synthetic offline generation migration and slow-download isolation checks; emulator only."""
import argparse
import concurrent.futures
import hashlib
import http.server
import json
from pathlib import Path
import subprocess
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Disposable emulator only'
PACKAGE = 'io.github.liuw.surgebridge.debug'


def adb(*command, **kwargs):
    return subprocess.run([str(ROOT / '.local-tools/android-sdk/platform-tools/adb'), '-s', args.serial, *command],
                          check=True, capture_output=True, timeout=30, **kwargs).stdout


token = adb('shell', 'run-as', PACKAGE, 'cat', 'files/debug-api-token').decode().strip()
port = int(adb('forward', 'tcp:0', 'tcp:19090'))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def api(path, body=None):
    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/{path}',
        data=None if body is None else json.dumps(body).encode(),
        headers={'Host': '127.0.0.1:19090', 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with opener.open(req, timeout=30) as response:
        return json.load(response)


def wait(state):
    for _ in range(80):
        if api('status')['service'] == state:
            return
        time.sleep(.25)
    raise AssertionError('Service did not reach ' + state)


def put(path, value):
    assert path.startswith('files/') and all(c.isalnum() or c in '/._-' for c in path)
    adb('shell', f"run-as {PACKAGE} sh -c 'cat > {path}'", input=value.encode())


entered = threading.Event()
release = threading.Event()
requests = []


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        if self.path == '/slow':
            entered.set()
            release.wait(10)
        body = b'DOMAIN-SUFFIX,fixture.invalid\n'
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *unused):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
reverse = int(adb('reverse', 'tcp:0', f'tcp:{server.server_port}'))
previous = api('status')['selectedProfileId']
checks = 0


def expect(condition, label):
    global checks
    assert condition, label
    checks += 1
    print('PASS', label, flush=True)


try:
    api('service/stop', {})
    wait('Stopped')
    url = f'http://127.0.0.1:{reverse}/rules'
    source = f'[Proxy]\nP=snell,192.0.2.1,443,version=6,psk=synthetic-test-only\n[Rule]\nRULE-SET,{url},DIRECT\nIP-CIDR,192.0.2.0/24,DIRECT\nFINAL,P'
    profile = api('profiles/import', {'name': 'Store generation fixture', 'source': source})
    digest = profile['conversion']['runtimeSha256']
    runtime_path = None
    for sidecar in adb('shell', 'run-as', PACKAGE, 'find', 'files/configs', '-name', '*.source').decode().splitlines():
        snapshots = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', sidecar))
        if digest in snapshots:
            runtime_path = sidecar[:-7]
            snapshot = snapshots[digest]
            break
    assert runtime_path is not None
    original = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', runtime_path))
    legacy = json.loads(json.dumps(original))
    legacy['route']['rules'] = [r for r in legacy['route']['rules'] if not r.get('server', '').startswith('surge-proxy-dns')]
    legacy['dns']['servers'] = [s for s in legacy['dns']['servers'] if not s.get('tag', '').startswith('surge-proxy-dns')]

    def stage_old():
        raw = json.dumps(legacy)
        old = dict(snapshot, generation=1)
        put(runtime_path + '.source', json.dumps({hashlib.sha256(raw.encode()).hexdigest(): old}))
        put(runtime_path, raw)

    def report():
        return next(p for p in api('profiles')['profiles'] if p['id'] == profile['id'])['conversion']

    stage_old()
    api('profiles/select', {'id': profile['id']})
    before = len(requests)
    api('service/start', {})
    wait('Started')
    expect(report()['generation'] == 2, 'old cached profile regenerated to generation 2')
    expect(len(requests) == before, 'startup regeneration performs no subscription/rule downloads')
    upgraded = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', runtime_path))
    expect(upgraded == original, 'offline regeneration restores the current staged DNS runtime exactly')
    current = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', runtime_path + '.source'))[report()['runtimeSha256']]
    expect(current['source'] == source and current['androidOverrides'] == snapshot['androidOverrides'], 'source and overrides survive generation upgrade')
    api('service/stop', {})
    wait('Stopped')
    stage_old()
    cache = 'files/surge-rule-cache/' + hashlib.sha256(url.encode()).hexdigest()
    adb('shell', 'run-as', PACKAGE, 'rm', '-f', cache)
    api('service/start', {})
    wait('Started')
    expect(report()['generation'] == 1 and len(requests) == before, 'missing cached rule list falls back without blocking startup or fetching')
    expect(json.loads(adb('shell', 'run-as', PACKAGE, 'cat', runtime_path)) == legacy, 'fallback retains working runtime')
    api('service/stop', {})
    wait('Stopped')
    changed = source.replace('/rules', '/slow')
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(api, 'profiles/update', {'id': profile['id'], 'source': changed})
        try:
            expect(entered.wait(5), 'slow rule-list fetch is in progress')
            start = time.monotonic()
            api('profiles')
            expect(time.monotonic() - start < 2 and not pending.done(), 'profile reads remain responsive during slow subscription download')
        finally:
            release.set()
        expect(pending.result()['changed'], 'download completes and publishes validated runtime')
    print(f'PASS: {checks} Android store/migration assertions', flush=True)
finally:
    release.set()
    try:
        api('service/stop', {})
        wait('Stopped')
        if previous > 0:
            api('profiles/select', {'id': previous})
    finally:
        adb('reverse', '--remove', f'tcp:{reverse}')
        adb('forward', '--remove', f'tcp:{port}')
        server.shutdown()
        server.server_close()
