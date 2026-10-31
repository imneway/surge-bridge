#!/usr/bin/env python3
"""Exercise actual cold rule-set downloads, which `sing-box check` does not do."""
import copy
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
CORE = Path(os.environ.get("SING_BOX", ROOT / ".local-tools/sing-box-1.14.4-darwin-arm64/sing-box")).resolve()


def main():
    with tempfile.TemporaryDirectory(prefix="surge-ruleset-") as directory:
        work = Path(directory)
        source = work / "rules.json"
        source.write_text(json.dumps({"version": 3, "rules": [{"ip_cidr": ["192.0.2.0/24"]}]}))
        binary = work / "rules.srs"
        subprocess.run([str(CORE), "rule-set", "compile", "--output", str(binary), str(source)], check=True, capture_output=True)
        requests = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                payload = binary.read_bytes()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def run(config, name, failure=False):
            case = work / name
            case.mkdir()
            config["experimental"] = {"cache_file": {"enabled": True, "path": str(case / "cache.db")}}
            path = case / "config.json"
            path.write_text(json.dumps(config))
            subprocess.run([str(CORE), "check", "-c", str(path)], check=True, capture_output=True)
            log = case / "run.log"
            before = len(requests)
            with log.open("w") as output:
                process = subprocess.Popen([str(CORE), "run", "-c", str(path)], cwd=case, stdout=output, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 15
                    while time.monotonic() < deadline:
                        text = log.read_text()
                        if process.poll() is not None or "sing-box started" in text:
                            break
                        time.sleep(0.1)
                    text = log.read_text()
                    if failure:
                        assert process.poll() is not None and "detour to an empty direct outbound" in text, text
                        assert len(requests) == before, "Rejected detour must fail before download"
                    else:
                        assert process.poll() is None and "sing-box started" in text, text
                        assert len(requests) > before, "Must actually download with an empty cache"
                        assert "deprecated" not in text.lower(), text
                finally:
                    if process.poll() is None:
                        process.terminate()
                    process.wait(timeout=5)

        try:
            for name in ["test-converted", "migrated-runtime-test"]:
                generated = json.loads((ROOT / ".local-tools" / (name + ".json")).read_text())
                rule_set = next(item for item in generated["route"]["rule_set"] if item["type"] == "remote")
                rule_set["url"] = f"http://localhost:{server.server_port}/geoip-cn.srs"
                # No TUN privileges or real nodes needed. An unreachable default proxy
                # proves the downloader has its own direct dialer, not default routing.
                config = {
                    "log": {"level": "info", "disabled": False},
                    "dns": {"servers": [{"type": "local", "tag": "local"}]},
                    "outbounds": [
                        {"type": "direct", "tag": "DIRECT"},
                        {"type": "http", "tag": "unavailable", "server": "127.0.0.1", "server_port": 1},
                    ],
                    "route": {"default_domain_resolver": {"server": "local", "strategy": "ipv4_only"},
                              "final": "unavailable", "rule_set": [rule_set]},
                }
                bad = copy.deepcopy(config)
                bad["route"]["rule_set"][0]["http_client"] = {"detour": "DIRECT"}
                run(bad, name + "-old", failure=True)
                run(config, name + "-fixed")
                print(f"PASS: {name}: old download failure reproduced; fixed cold download/start succeeded")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
