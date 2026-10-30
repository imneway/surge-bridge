#!/usr/bin/env python3
"""Exercise Snell v6 TCP + UDP against a local controlled server. No system VPN changes."""
import argparse
import http.server
import json
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('core', help='sing-box 1.14.4 executable')
args = parser.parse_args()
core = str(Path(args.core).resolve())

def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as s:
        s.bind(('127.0.0.1', 0)); return s.getsockname()[1]

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self): self.send_response(204); self.end_headers()
    def log_message(self, *_): pass

http = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=http.serve_forever, daemon=True).start()
echo = socket.socket(type=socket.SOCK_DGRAM); echo.bind(('127.0.0.1', 0)); echo.settimeout(.5)
running = True
def echo_loop():
    while running:
        try:
            data, address = echo.recvfrom(4096); echo.sendto(data, address)
        except socket.timeout: pass
threading.Thread(target=echo_loop, daemon=True).start()

def read_exact(s, count):
    result = b''
    while len(result) < count:
        part = s.recv(count - len(result))
        if not part: raise RuntimeError('Unexpected SOCKS EOF')
        result += part
    return result

passed = 0
try:
    with tempfile.TemporaryDirectory(prefix='snell-v6-') as temp:
        for mode in ['default', 'unshaped', 'unsafe-raw']:
            for reuse in [False, True]:
                snell_port, socks_port = port(), port()
                server = {'log': {'level': 'error'}, 'inbounds': [{'type': 'snell', 'listen': '127.0.0.1', 'listen_port': snell_port, 'version': 6, 'psk': 'local-integration-test-key', 'mode': mode}], 'outbounds': [{'type': 'direct', 'tag': 'direct'}]}
                client = {'log': {'level': 'error'}, 'inbounds': [{'type': 'mixed', 'listen': '127.0.0.1', 'listen_port': socks_port}], 'outbounds': [{'type': 'snell', 'tag': 'snell', 'server': '127.0.0.1', 'server_port': snell_port, 'version': 6, 'psk': 'local-integration-test-key', 'mode': mode, 'reuse': reuse}], 'route': {'final': 'snell'}}
                processes = []
                with open(Path(temp) / 'core.log', 'w') as log:
                    try:
                        for name, config in [('server', server), ('client', client)]:
                            config_path = Path(temp) / (name + '.json'); config_path.write_text(json.dumps(config))
                            processes.append(subprocess.Popen([core, 'run', '-c', str(config_path)], stdout=log, stderr=log))
                        for _ in range(50):
                            try:
                                with socket.create_connection(('127.0.0.1', socks_port), timeout=.2): break
                            except OSError: time.sleep(.1)
                        # curl is used because urllib bypasses localhost proxies on macOS.
                        result = subprocess.run(['curl', '--silent', '--noproxy', '', '--max-time', '5', '--proxy', f'http://127.0.0.1:{socks_port}', f'http://127.0.0.1:{http.server_port}/', '-o', '/dev/null', '-w', '%{http_code}'], capture_output=True, text=True)
                        assert result.returncode == 0 and result.stdout == '204', 'TCP failed'
                        passed += 1
                        with socket.create_connection(('127.0.0.1', socks_port), timeout=5) as control, socket.socket(type=socket.SOCK_DGRAM) as udp:
                            control.sendall(b'\x05\x01\x00'); assert read_exact(control, 2) == b'\x05\x00'
                            control.sendall(b'\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00')
                            header = read_exact(control, 4); assert header[:2] == b'\x05\x00'
                            if header[3] == 1: host = socket.inet_ntoa(read_exact(control, 4))
                            elif header[3] == 4: host = socket.inet_ntop(socket.AF_INET6, read_exact(control, 16))
                            else: host = read_exact(control, read_exact(control, 1)[0]).decode()
                            relay_port = struct.unpack('!H', read_exact(control, 2))[0]
                            udp.settimeout(5)
                            payload = b'snell-v6-udp-roundtrip'
                            request = b'\x00\x00\x00\x01' + socket.inet_aton('127.0.0.1') + struct.pack('!H', echo.getsockname()[1]) + payload
                            udp.sendto(request, ('127.0.0.1' if host in ('0.0.0.0', '::') else host, relay_port))
                            response = udp.recv(4096)
                            assert response.endswith(payload), 'UDP failed'
                            passed += 1
                        print(f'PASS: Snell v6 {mode}, reuse={reuse}: TCP + UDP', flush=True)
                    finally:
                        for process in processes:
                            process.terminate()
                            try: process.wait(timeout=5)
                            except subprocess.TimeoutExpired: process.kill(); process.wait()
        print(f'PASS: {passed} local protocol checks')
finally:
    running = False; http.shutdown(); echo.close()
