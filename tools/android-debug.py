#!/usr/bin/env python3
"""Install/drive a debug build over authenticated adb loopback. Never prints tokens/configs."""
import argparse
import json
import http.client
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

PACKAGE = 'io.github.liuw.surgebridge.debug'
PORT = 19090
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', default=str(ROOT / '.local-tools/android-sdk/platform-tools/adb'))
p.add_argument('--serial', help='Required when more than one device is connected')
sub = p.add_subparsers(dest='command', required=True)
install = sub.add_parser('install'); install.add_argument('apk')
sub.add_parser('status'); sub.add_parser('profiles'); sub.add_parser('logs'); sub.add_parser('groups')
core_logs = sub.add_parser('core-logs'); core_logs.add_argument('output')
conns = sub.add_parser('connections'); conns.add_argument('output', help='Private local destination; live/closed connections with byte counters (browsing metadata)')
overrides = sub.add_parser('overrides'); overrides.add_argument('id', type=int); overrides.add_argument('file', help='JSON override settings; {} removes overrides')
node = sub.add_parser('node'); node.add_argument('group'); node.add_argument('outbound')
sub.add_parser('start'); sub.add_parser('stop'); sub.add_parser('reload')
mode = sub.add_parser('mode'); mode.add_argument('mode', nargs='?', choices=['Rule','Global','Direct'])
boot = sub.add_parser('boot'); boot.add_argument('enabled', nargs='?', choices=['on','off'])
sub.add_parser('failover'); sub.add_parser('check-due')
schedule = sub.add_parser('schedule'); schedule.add_argument('id', type=int); schedule.add_argument('--auto', required=True, choices=['on','off']); schedule.add_argument('--interval', type=int, required=True)
select = sub.add_parser('select'); select.add_argument('id', type=int)
imp = sub.add_parser('import'); imp.add_argument('file'); imp.add_argument('--name', default='Work')
remote = sub.add_parser('subscribe'); remote.add_argument('url_file', help='Private text file containing subscription URL'); remote.add_argument('--name', default='Work')
update = sub.add_parser('update'); update.add_argument('id', type=int); update.add_argument('--file')
logcat = sub.add_parser('logcat'); logcat.add_argument('output', help='Private local destination; may contain browsing metadata')
args = p.parse_args()

def run(*cmd, **kw):
    return subprocess.run([args.adb, *cmd], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw).stdout

try:
    devices = [x.split()[0] for x in run('devices').decode().splitlines()[1:] if len(x.split()) >= 2 and x.split()[1] == 'device']
    serial = args.serial or (devices[0] if len(devices) == 1 else None)
    if serial not in devices:
        p.error('Connect and authorize one Android device, or choose an authorized device with --serial.')
    def adb(*cmd, **kw): return run('-s', serial, *cmd, **kw)
    if args.command == 'install':
        adb('install', '-r', str(Path(args.apk).resolve()))
    # Foreground launch allows the user to approve Android's VPN dialog; API never bypasses it.
    adb('shell', 'am', 'start', '-n', PACKAGE + '/io.nekohasekai.sfa.compose.MainActivity')
    if args.command == 'logcat':
        pid = adb('shell', 'pidof', PACKAGE).decode().strip().split()[0]
        data = adb('logcat', '-d', '--pid=' + pid, '-v', 'threadtime')
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'wb') as f: f.write(data)
        print('App logcat saved locally. Review/redact before sharing.')
        raise SystemExit(0)
    local_port = int(adb('forward', 'tcp:0', f'tcp:{PORT}').decode().strip())
    try:
        token = None
        for _ in range(50):
            try:
                token = adb('shell', 'run-as', PACKAGE, 'cat', 'files/debug-api-token').decode().strip()
                if len(token) == 64: break
            except subprocess.CalledProcessError: pass
            time.sleep(.2)
        if not token: raise RuntimeError('Debug API did not start')
        def api(path, data=None):
            request = urllib.request.Request(f'http://127.0.0.1:{local_port}/v1/{path}',
                data=json.dumps(data).encode() if data is not None else None,
                headers={'Host': f'127.0.0.1:{PORT}', 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            try:
                with opener.open(request, timeout=180) as response: return json.load(response)
            except urllib.error.HTTPError as e:
                result = json.load(e)
                raise RuntimeError(f"API {e.code}: {result.get('error', 'failed')}") from None
        for attempt in range(30):
            try:
                api('health')
                break
            except (urllib.error.URLError, RuntimeError, OSError, http.client.HTTPException):
                if attempt == 29: raise
                time.sleep(.2)
                token = adb('shell', 'run-as', PACKAGE, 'cat', 'files/debug-api-token').decode().strip()
        command = args.command
        if command in ['start', 'stop', 'reload']:
            result = api('service/' + command, {})
            expected = 'Stopped' if command == 'stop' else 'Started'
            for _ in range(60):
                time.sleep(.5); result = api('status')
                if result.get('service') == expected: break
            if result.get('service') != expected: raise RuntimeError('Service did not reach expected state; inspect logs')
        elif command == 'mode': result = api('mode', {'mode': args.mode} if args.mode else None)
        elif command == 'boot': result = api('boot', {'restoreOnBoot': args.enabled == 'on'} if args.enabled else None)
        elif command == 'failover': result = api('failover')
        elif command == 'check-due': result = api('profiles/check-due', {})
        elif command == 'schedule': result = api('profiles/schedule', {'id': args.id, 'autoUpdate': args.auto == 'on', 'intervalMinutes': args.interval})
        elif command == 'core-logs':
            data = json.dumps(api('core-logs'), ensure_ascii=False, indent=2).encode()
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'wb') as f: f.write(data)
            result = {'saved': True, 'entriesMayContainBrowsingMetadata': True}
        elif command == 'connections':
            data = json.dumps(api('connections'), ensure_ascii=False, indent=1).encode()
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'wb') as f: f.write(data)
            result = {'saved': True, 'entriesMayContainBrowsingMetadata': True}
        elif command == 'overrides': result = api('profiles/overrides', {'id': args.id, 'overrides': json.loads(Path(args.file).read_text())})
        elif command == 'node': result = api('groups/select', {'group': args.group, 'outbound': args.outbound})
        elif command == 'select': result = api('profiles/select', {'id': args.id})
        elif command == 'import': result = api('profiles/import', {'name': args.name, 'source': Path(args.file).read_text()})
        elif command == 'subscribe': result = api('profiles/import', {'name': args.name, 'url': Path(args.url_file).read_text().strip()})
        elif command == 'update':
            body = {'id': args.id}
            if args.file: body['source'] = Path(args.file).read_text()
            result = api('profiles/update', body)
        else: result = api('health' if command == 'install' else command)
        print(json.dumps(result, indent=2, ensure_ascii=False))
    finally: adb('forward', '--remove', f'tcp:{local_port}')
except (subprocess.CalledProcessError, urllib.error.URLError, RuntimeError, OSError) as e:
    # Do not echo subprocess arguments, URLs, request bodies or bearer tokens.
    print(str(e) if isinstance(e, RuntimeError) else f'Operation failed ({type(e).__name__}); check device/build connectivity.')
    raise SystemExit(1)
