# Debug API v1

Debug APK only. Android loopback `127.0.0.1:19090`; HTTP/1.1 JSON. Every request requires `Authorization: Bearer <token>`. Retrieve token over authorized USB debugging using `adb shell run-as io.github.liuw.surgebridge.debug cat files/debug-api-token`. Never paste it into logs/issues. The CLI handles this in memory.

Remote subscriptions/rule sets should use HTTPS. The debug variant additionally permits plain HTTP to localhost/127.0.0.1 for `adb reverse` fixture servers; arbitrary cleartext HTTP is blocked by Android policy.

Use `adb forward tcp:0 tcp:19090`, then connect to the allocated computer port with `Host: 127.0.0.1:19090`. The server rejects Origin headers, non-loopback Host, duplicate headers, chunked bodies, headers >16 KiB and bodies >16 MiB. It closes every response. There are two workers and a bounded queue; writes serialize. This is a local development interface, not a remote management service.

| Method/path | Body | Result |
|---|---|---|
| GET /v1/health | — | API/app/core versions |
| GET /v1/status | — | Observed service state, selected profile ID, VPN permission |
| GET /v1/profiles | — | IDs, types, update time, selection, conversion warnings/hash; no names/URLs/credentials |
| GET /v1/core-logs | — | Last 2500 runtime log entries; sequence/level/message; sensitive destination metadata |
| GET /v1/groups | — | Group tags, selected member, members, selectable flag |
| POST /v1/core-logs/clear | `{}` | Clear collected runtime log buffer |
| POST /v1/groups/select | `{group, outbound}` | Select a node while Started; verify subsequent traffic |
| POST /v1/profiles/overrides | `{id, overrides}` | Validate and save Android settings; changed/reloadRequired/report; `{}` removes settings |
| GET /v1/logs | — | Last 200 structural API/lifecycle events; not browsing logs |
| POST /v1/profiles/import | `{name?, source}` or `{name?, url, intervalMinutes?}` | 201 + ID + conversion report; validates before create; does not select |
| POST /v1/profiles/select | `{id}` | 200; requires stopped service |
| POST /v1/profiles/update | `{id, source}` for local; `{id}` for remote URL refresh | 200 + changed/reloadRequired/report; does not silently reload |
| POST /v1/service/start | `{}` | 202 accepted; requires selected profile and Android VPN permission |
| POST /v1/service/stop | `{}` | 202 accepted |
| POST /v1/service/reload | `{}` | 202 accepted; requires Started |

Errors: 400 bad input; 401 authentication; 403 Host/Origin/transfer rules; 404 missing endpoint/profile; 409 lifecycle/permission conflict; 413 body bound; 422 conversion/download/native validation failure; 431 oversized/malformed headers. No raw native error strings are returned because they can contain credentials.

202 is acceptance only. Poll status, inspect events, then verify actual routed traffic. The helper polls start/stop; reload may remain Started throughout and requires an independent traffic check to confirm the new behavior.

Android's initial VPN permission must be granted through its UI. The API does not bypass it. A `vpn_permission_required_open_app` response is actionable, not a successful start.

`tools/android-debug.py logcat .private/device.log` captures app process logs into a 0600 local file; the application’s built-in log screen also shows core logs. Those logs may contain destinations/identifiers and need redaction before sharing. Crash-report/config exports inherited from SFA can contain full configuration; the debug API deliberately does not expose them.

Release source set contains only a no-op DebugBridge, without listener or token generation. Keep the private debug build on trusted devices.

## Android settings and phone diagnostics

Overrides are explicit per-profile settings stored beside the original Surge source. A same-profile edit, local update or subscription refresh preserves them; a newly imported profile starts without them. Only Surge profiles accept nonempty overrides. Apply with `tools/android-debug.py overrides ID settings.json`, then `reload` if the selected VPN is running. The profile detail shows each override and offers a remove button.

Example (policy must exist in the imported configuration):

```json
{
  "policy": "Proxy",
  "packages": ["com.android.chrome", "com.openai.chatgpt"],
  "domains": ["google.com", "gstatic.com", "googleapis.com", "googleusercontent.com", "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com"],
  "dns": "https://1.1.1.1/dns-query"
}
```

DNS interception remains first; package and domain overrides then precede original IP/GEOIP rules. Package overrides include IP-only traffic and all destinations of the named apps. Domain matches keep the destination hostname for Snell, avoiding premature local resolution. Optional DNS uses HTTPS through the chosen policy and requires an IPv4 literal endpoint to avoid bootstrap recursion. The original node/default route resolver and explicit DNS rules remain; this is not universal replacement of every DNS lookup. A selector still follows its currently selected member.

Core logs remove ANSI escapes, redact HTTP URL query strings, and skip entries containing `psk` or `password`. This is not complete anonymization: destinations, node labels and addresses may remain. `core-logs .private/core.json` writes a private local file; review before sharing. The buffer may reset when the core reconnects, and sequence numbers need not be contiguous. Group tags can also contain user-chosen sensitive text.

The CLI foregrounds Surge Bridge on each invocation. During another app's UI test, use the loopback API directly so the test app retains focus. Never expose the adb forward on a public interface.

`tools/network-probe/main.go` builds a standalone Android shell probe:

```sh
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o .local-tools/network-probe tools/network-probe/main.go
adb -s SERIAL push .local-tools/network-probe /data/local/tmp/surge-network-probe
adb -s SERIAL shell chmod 755 /data/local/tmp/surge-network-probe
adb -s SERIAL shell /data/local/tmp/surge-network-probe -url https://www.google.com/generate_204 -count 3
```

It uses Android CA roots with certificate verification enabled and fresh HTTP connections, and reports status, TLS verification, duration and DNS results. Its shell UID tests domain rules; it does not prove another app's package routing. Correlate that app's package and outbound connection IDs, then test its actual UI. An HTTP 403 challenge establishes reachability, not successful ChatGPT operation.

## v1 additions in 1.14.4-surge.3

| Method/path | Body | Result |
|---|---|---|
| GET /v1/mode | — | Current mode and supported mode list; empty while stopped |
| POST /v1/mode | `{mode: "Rule" | "Global" | "Direct"}` | Requires Started; changes native mode and closes existing routed connections |
| GET /v1/failover | — | Active profile's group health/switch reason, selected member and observation time when available |
| GET /v1/boot | — | restoreOnBoot and lastRunning flags |
| POST /v1/boot | `{restoreOnBoot: boolean}` | Saves the app boot-recovery preference; does not change system always-on VPN |
| POST /v1/profiles/schedule | `{id, autoUpdate, intervalMinutes}` | Updates only this remote profile and reschedules background work; range 15–10080 minutes |
| POST /v1/profiles/check-due | `{}` | Runs the same due/enabled checks as the background worker; does not force disabled or fresh profiles to update |

GET /v1/profiles now includes each profile's `autoUpdate`, `intervalMinutes`, and `refresh` report (`attemptedAt`, `success`, optional `changed`/generic `message`). URL import accepts `autoUpdate` and `intervalMinutes`, defaulting to enabled and 360 minutes. Manual `profiles/update` works when autoUpdate is false. Existing intervals remain unchanged.

Examples:

```sh
python3 tools/android-debug.py schedule 1 --auto on --interval 360
python3 tools/android-debug.py schedule 2 --auto off --interval 1440
python3 tools/android-debug.py update 2
python3 tools/android-debug.py check-due
python3 tools/android-debug.py mode Global
python3 tools/android-debug.py mode Rule
python3 tools/android-debug.py boot on
python3 tools/android-debug.py failover
```

Failure-only switching is opt-in through the profile detail or `failoverGroups` in its Android overrides:

```json
{"policy":"Proxy","failoverGroups":["Proxy"]}
```

When using the API, merge this field into the existing overrides object; the endpoint replaces the whole object. The UI toggles preserve other Android routing/DNS settings. Selectors need at least two supported leaf proxy members; nested groups are rejected. An explicit DIRECT member is preserved for manual selection but never probed or selected automatically. Selecting it manually pauses failover for that group. Native HTTPS health probes run approximately once per minute with a 35-second result window. Two failed/timed-out rounds trigger the first freshly healthy backup in configuration order; measured latency is never read by the decision engine. There is a 120-second cooldown, no automatic failback, and no DIRECT fallback. Manual node selection resets consecutive-failure tracking. Direct mode suspends new probe rounds. Pending probes can finish after a mode change, but their results cannot trigger a switch. Native checks test TLS/HTTP reachability, not whether ChatGPT accepts an exit IP.

The controller lives in the VPN service. Core probe results must be newer than the round start; changes of mode/selection invalidate in-flight rounds. State is visible in the active profile's detail and GET /v1/failover. This does not reinterpret an explicitly imported Surge `url-test` group as failure-only; use a `select` group for this feature.
