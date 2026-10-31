# Validation — 2026-10-30

The first table records the initial development build. The physical-phone follow-up below supersedes its endpoint/phone limitations; neither is a claim of complete Surge compatibility.

| Check | Result |
|---|---|
| `:app:assembleOtherDebug` | PASS; ARM64, x86_64 and universal APKs generated |
| Converter regression harness | PASS, 35 assertions and native sing-box configuration check |
| Supplied private Work.conf | All four remote rule sets fetched; conversion and native 1.14.4 validation passed; original file unchanged |
| Android import of private Work.conf | PASS via authenticated API; 12 explicit compatibility notices returned |
| Android API/storage/subscription harness | PASS, 30 assertions: auth/Origin/Host, import, source snapshot integrity, selection, invalid-update preservation, remote fetch, TTL rule refresh and HTTP failure preservation |
| Controlled native Snell v6 | PASS, 12 checks: default/unshaped/unsafe-raw × reuse false/true × TCP/UDP |
| Android TUN → Snell v6 → controlled local target | PASS, TCP and UDP; default mode; APK's ARM64 libbox, Android 16 emulator |
| Android VPN permission | First start rejected by API until system dialog approved through UI |
| Service lifecycle API | Observed Stopped → Started, reload accepted while Started, then Stopped |
| Actual supplied server endpoints | All three returned DNS NXDOMAIN using the host system resolver and the config's first encrypted DNS; no Snell handshake occurred |
| Physical Android phone | Deferred by user; not tested |

The real-endpoint failure is different from protocol support: the controlled tests establish working v6 transport, but do not establish reachability of the user's servers. The later physical-phone tests below established connectivity to these nodes; the initial host DNS failure was not a global statement about their availability.

Known differences affecting this supplied config: 7 remote rules containing IP-ASN (including one logical rule), 17 PROCESS-NAME rules and 65 USER-AGENT rules are omitted with warnings. Three redundant per-entry policy fields were normalized. DNS uses the first configured upstream, without Surge's racing/failover. GEOIP database and FINAL,dns-failed behavior differ. See compatibility.md.

Debug events omit raw config/URLs/credentials. The private `.private/` test outputs are intentionally untracked. APK signatures are development signatures; keep the project's debug keystore for in-place future upgrades. Test fixtures use reserved IPs and synthetic PSKs only.

## Physical phone follow-up — 1.14.4-surge.2

OPPO PKT110, Android 17, ARM64; authorized USB debugging. Original phone configuration was backed up privately. The supplied Surge source was not edited. All tests used the actual installed APK and the supplied Snell v6 nodes.

- Baseline: Google requests timed out (8–12 seconds); core logs showed `www.google.com` resolved to `31.13.92.37`, then sent as that IP through Snell. Earlier ChatGPT logs showed both wrong target resolution and DIRECT connections to its real endpoint IPs.
- Explicit Android per-profile overrides: Chrome/ChatGPT packages and Google/OpenAI domain suffixes use Proxy before IP/GEOIP rules; HTTPS DNS at 1.1.1.1 uses the proxy, retaining original node bootstrap resolution. Source updates preserve these settings. Other traffic follows the original route rules, although the default target DNS is changed.
- Initial Google recovery: 3/3 fresh HTTPS requests returned 204 with verified TLS; Snell received the hostname instead of the wrong IP.
- Three-node matrix: 27/27 requests passed (3 nodes × Google homepage / Google 204 / gstatic 204 × 3). Homepages returned 200 and Google content; certificates verified.
- Stability window: 12/12 repeated requests, 5/5 with Wi-Fi disabled and mobile data available, 5/5 after Wi-Fi restored. Wi-Fi was restored to its original enabled state. These finite checks do not establish long-term uptime.
- Chrome UI: Google homepage rendered on the unlocked phone.
- ChatGPT UI: signed-in Android app produced an actual response in a temporary test conversation. Captured package-correlated outbound records used Snell, with no DIRECT records observed. Plain command-line ChatGPT probes returned HTTP 403 (including Cloudflare challenges); these were not counted as app-functionality success.
- Same-source profile update retained both Android settings and identical runtime hash; invalid override policy returned 422 and preserved the last valid settings/runtime.
- Final APK upgrade retained all profile settings and the selected node; explicit VPN stop/start was followed by 3/3 Google HTTPS checks. The profile detail visibly displayed the Android override notices and removal button. ChatGPT then answered a second test after the upgrade (`2 + 2 = 4`); its captured package-correlated records contained 16 Snell outbounds and zero DIRECT outbounds.
- Converter regression: original 35 assertions plus 19 override assertions passed, and both generated configurations passed native sing-box validation.

Private evidence: `.private/google-node-matrix.json`, `.private/google-stability.json`, `.private/chatgpt-sent.png`, `.private/chatgpt-response-logs.json`, `.private/phone-persistence.json`, `.private/chatgpt-final-response.png`, `.private/chatgpt-final-routing-counts.json`. Evidence includes sensitive browsing/configuration metadata and is intentionally excluded from Git. No user credentials or server administrator access were required.

Remaining scope: arbitrary Surge configurations, prolonged background uptime, every ChatGPT feature, physical-phone UDP/QUIC, and all carrier conditions have not been exhaustively tested. The earlier controlled Snell TCP/UDP tests remain separate evidence.


## Daily-use features — 2026-10-31, 1.14.4-surge.3

Implemented and installed version 748 on the OPPO Android 17 phone and Android 16 ARM64 emulator. Native transport remains pinned to sing-box 1.14.4.

| Check | Observed result |
|---|---|
| Build and formatting | `assembleOtherDebug` passed; scoped Kotlin formatting check passed |
| Converter / routing / decision regressions | 80 assertions: 35 converter, 19 Android override, 26 modes/failure-only; three generated configs passed native validation |
| API/storage/subscription regression | 29 assertions passed on emulator |
| Multi-profile update and live routing/failover | 17 assertions passed, including a selector containing optional manual DIRECT |
| Runtime rollback and actual reboot | 5 assertions passed on emulator: occupied-port load failure restores last running config; restore enabled, disabled, and manual-stop cases |
| Remote Surge editor UI | URL and manual Update button visible; toggling automatic update and saving changed only that profile, verified via API |
| Physical phone UI | Failure-only Proxy switch enabled from the profile editor; healthy current node retained; source text and original selected node preserved |
| Physical phone HTTPS | Google and gstatic returned verified-TLS HTTP 204 throughout the upgrade checks; latest APK repeated both successfully |
| Physical phone ChatGPT | Temporary test conversation answered `3 + 3 = 6`; package-correlated captured records contained 22 Snell outbounds and zero DIRECT outbounds |

The update fixture used two remote profiles with different enable/interval settings: only the enabled, overdue profile refreshed automatically; disabled profile still refreshed manually. A failing HTTP response preserved the runtime hash and exposed failure status. Direct and Global were tested against a source containing a catch-all DIRECT rule, with actual traffic checked at controlled local targets.

The failover fixture used three local Snell v6 servers and verified HTTPS probes. Healthy A stayed selected. Stopping A caused no switch after the first failed round, then selected B after the second. Restarting A did not cause failback. Global mode and selected B survived VPN stop/start. The fixture's ephemeral TLS root was trusted only by that synthetic profile, with certificate verification enabled; no device-wide CA was installed. Pure decision tests also verify that DIRECT cannot become an automatic backup and manual DIRECT pauses switching.

The per-profile cache now isolates selected nodes, mode and fake-IP state; repeated fixture runs exposed and verified the fix for cross-profile selection leakage. Original phone source content was compared against the pre-upgrade private backup and remained identical. The phone was left connected in Rule mode with its original node and opt-in failure-only switching enabled for Proxy.

Private evidence: `.local-tools/features-mixed-direct.log`, `.local-tools/api-final.log`, `.local-tools/boot-test.log`, `.private/phone-surge3-final-apk-google.json`, `.private/chatgpt3-final-reply.png`, `.private/phone-surge3-chatgpt-routing-counts.json`, `.private/phone-surge3-final-failover.json`. These files are ignored and are not included in Git.

Limits: reboot recovery was exercised on the emulator, not by rebooting the user's OPPO phone. Manufacturer background restrictions, long-duration scheduling/uptime and exact periodic execution times remain outside this finite verification. WorkManager schedules best-effort work; opening the app or starting VPN also checks enabled overdue profiles. Generic HTTPS health does not guarantee ChatGPT accepts an exit IP. Existing Surge `url-test` semantics are unchanged; failure-only selection applies to eligible `select` groups.


## Complete physical-phone regression — 2026-10-31, surge.3 → surge.4

OPPO PKT110 / Android 17, authorized USB connection. The final build is version 749 (`1.14.4-surge.4`). Tests used temporary configurations and local Snell v6 fixtures; original source, runtime hash, routing overrides, selected node and Rule mode were checked against a private pre-test backup. Temporary profiles were removed through the app UI and their synthetic files cleaned up.

| Check | Result |
|---|---|
| Converter / overrides / modes | 80 assertions passed; generated configs passed native validation |
| URL import on phone UI | Created a remote Surge profile with its own enabled switch and 15-minute interval |
| Independent update choices | A enabled, B disabled; B could still refresh manually; HTTP 503 retained its previous runtime and exposed failure |
| Native routing / failure-only controller | 12 assertions passed: Rule/Global/Direct routing, Direct pause, healthy node retained, two failures before switching, no failback, node/mode persistence, manual DIRECT pause, all unavailable never selects DIRECT |
| Runtime load rollback | Separate native fixture caused a real occupied-port load failure; last-running runtime restored and failure status recorded |
| Actual nodes and network transitions | 13/13 verified-TLS requests passed across three real Snell nodes, Google homepage/204/gstatic, cellular transition and Wi-Fi restoration |
| Background stability | 9/9 additional verified-TLS Google requests passed at 20-second intervals |
| Chrome / ChatGPT UI | Google page rendered; a temporary ChatGPT conversation answered `7 + 8 = 15`; correlated logs contained 26 Snell outbounds and zero DIRECT outbounds |
| Debug API boundaries | Missing token rejected with 401; untrusted Origin and Host rejected with 403 |
| Periodic worker regression on surge.4 | Rescheduled deadline was 1 ms after the profile's actual due time; after further profile deletions/reconfiguration, A updated its saved source at 900.7 seconds while B stayed unchanged. No forced job execution or timestamp ageing was used |
| Delete / cleanup on surge.4 | Deleting the last enabled remote profile left zero enqueued/running periodic update jobs; only original profile ID 1 remained |

Two scheduling issues were corrected during this run. `ExistingPeriodicWorkPolicy.UPDATE` preserves the original enqueue timestamp, so applying a newly computed remaining delay made a job run before its profile was due, skip that profile, then wait another interval. The fix re-enqueues relative to the current time and uses millisecond precision. Deleting a profile also now reconfigures or cancels the updater.

The overnight interruption was separately investigated. The phone entered charger-only boot mode, with a previous shutdown reason. System alarms showed scheduled power-off at 01:00 and power-on at 07:00; timing is consistent with scheduled shutdown. Available system crash/watchdog/last-kernel-log queries contained no matching entries. Repeating the all-nodes-unavailable case did not reproduce the shutdown. System power schedules were left unchanged.

Private evidence is in `.private/full-phone-2026-10-31/`, including `periodic-result.json`, `schedule-deadline-regression.json`, `delete-schedule-result.json`, `controlled-resume.log`, `runtime-rollback.json`, `real-network.json`, `background-stability.json`, `chatgpt-routing.json`, and `scheduled-power-evidence.txt`. The controlled log's attempted cross-format rollback update was rejected at validation; the successful runtime rollback used the separate native fixture. Credentials, endpoints and raw logs are excluded from Git.


### App-only boot recovery on OPPO

With the original profile active and app boot recovery enabled, a real reboot was followed by user unlock without opening Surge Bridge. The app process did not start during an observation window exceeding five minutes. Android broadcast diagnostics showed its `BOOT_COMPLETED` receiver as `DEFER_BY_OPLUS SUB_REASON: PRESSURE` / `mBroadcastConsumerDeferApply`; the package was enabled, not force-stopped, and held `RECEIVE_BOOT_COMPLETED`. This is not counted as successful automatic recovery. Emulator boot tests do not override this device-specific result.

Android's separate [always-on VPN mode](https://developer.android.com/develop/connectivity/vpn#always-on) lets the system maintain the VPN service lifecycle. It changes manual-stop behavior and is a separate device preference, not equivalent to the app's last-running-state restore switch. The system's optional non-VPN traffic blocking setting is separate as well.


After the app-only boot observation, the app was explicitly opened to restore normal use; this is recorded as manual recovery, not a boot pass. On the final surge.4 APK, Google and gstatic returned verified-TLS 204 again (2/2). The installed APK SHA-256 matched the build artifact. Only the original profile remained, with its original runtime hash, node and Rule mode; temporary servers, probe binary and debug forwards were cleaned up. System always-on VPN and non-VPN blocking remained off pending the user's separate preference.


## Rule-set HTTP client migration — 2026-10-31, surge.5

Version 750 (`1.14.4-surge.5`) replaces generated GEOIP `download_detour` with inline `http_client.detour`, retaining DIRECT and the one-day refresh interval. Existing generated caches migrate before the mode-feature early return at VPN start/reload, including direct-only profiles. The existing ProfileStore native-validation/atomic-save path preserves the original source and Android overrides. Native JSON imports remain untouched.

- 88 assertions passed (37 converter, 19 routing overrides, 32 runtime/failover). Regression coverage includes already mode-enabled legacy caches, idempotence, direct-only profiles, existing explicit HTTP clients, native JSON passthrough and preservation of all unrelated runtime fields.
- Four generated configurations passed sing-box 1.14.4 validation, including a migrated legacy cache, without deprecation output.
- `:app:assembleOtherDebug` succeeded; the arm64 APK installed successfully over surge.4 on the OPPO device.
- Physical post-install checks completed after the user reconnected USB. On the real saved profile, parsed runtime JSON differed only by the intended downloader field replacement; the complete source/overrides snapshot stayed identical. Original profile ID, selected node and Rule mode were retained.
- The app dashboard was inspected after startup: no deprecation dialog, and the initial core-log snapshot contained no deprecation/download_detour entries. Warning suppression was not enabled.
- Google and gstatic each returned three verified-TLS HTTP 204 responses (6/6). A new ChatGPT temporary chat returned `9 + 6 = 15`; correlated app connections were 21 Snell and 0 DIRECT.
- Installed APK SHA-256 matched the surge.5 artifact. System always-on VPN and lockdown remained unset/off as requested. Final service state was Started with the sole original profile; temporary probe/XML files and the test API forward were removed.
- The initial USB interruption and private pre-install backup/diagnostics are retained only in ignored `.private/http-client-migration/`. No device reboot was needed.

Reference: https://sing-box.sagernet.org/configuration/rule-set/ and https://sing-box.sagernet.org/configuration/shared/http-client/


## Signed release build — 2026-10-31, surge.6

- Added the outlined link icon to the URL import list item, using the same primary tint and leading-content layout as the file/QR entries. Import behavior is unchanged.
- Version 751 (`1.14.4-surge.6`), `:app:assembleOtherRelease` passed, including R8 optimization and release vital lint checks. ARM64, x86_64 and universal APKs were produced.
- ARM64 artifact passed `apksigner verify`; APK v2 signature uses a dedicated 3072-bit RSA local release key. Certificate SHA-256: `263ad4668a51ad54d5780959a59d4845cb1d37474c0c106b39563ee2ca8ede51`.
- Artifact inspection confirms package `io.github.liuw.surgebridge`, versionCode 751, ARM64 native library and no debuggable marker. Release DEX contains none of the debug token/API endpoint markers; the release DebugBridge source is the no-op implementation.
- Signing credentials are excluded from Git under `.private/signing/`. The inherited repository keystore was not used. APK SHA-256 values are written alongside the artifacts in `SHA256SUMS`.
- This turn built and inspected the release APK; it did not install it or perform new release-device UI/network tests. The phone's previously verified surge.5 debug installation remains intact. Release uses a separate package and therefore requires its own configuration import and VPN authorization.


## Cold GEOIP download repair — 2026-10-31, surge.7

The surge.5/6 HTTP migration incorrectly carried `DIRECT` into a modern client detour. Native sing-box rejects detours to an empty direct outbound on the first dial. Static `check` passed, and prior physical testing used a previously downloaded rule-set cache, masking the cold-start error. The user reported the failure after importing a URL subscription into the fresh release package.

- Generated GEOIP clients now explicitly select `engine: go` with no detour. This is a nonempty independent direct HTTP client; an empty object would select the deprecated default-outbound fallback. URLs and refresh intervals remain unchanged.
- Startup migration handles both legacy `download_detour` and the broken surge.5/6 modern detour; raw source and other profile settings are retained. Explicit proxy clients and configured direct outbounds are preserved.
- 91 Kotlin assertions passed (37 converter, 19 routing overrides, 35 runtime/failover), and four generated configurations passed native validation.
- New `tools/test-ruleset-download.py`, included in `tools/test-converter.sh`, runs real sing-box instances with fresh temporary caches and a local binary rule server. Both old new-import and old migrated-cache clients reproduced the exact empty-direct error despite passing `check`. Both fixed clients downloaded and started, without deprecation output, even with an unreachable final proxy. No user cache is deleted.
- A separate real HTTPS cold-start test fetched the production GitHub GEOIP CN SRS and started the native core, with normal certificate verification and no deprecation output. Evidence is private in `.private/release-surge7/real-https-cold.log`.
- USB device inventory was empty during this fix. These are host native-core startup/download checks, not a new Android-device acceptance test. Existing release subscriptions migrate on next start after installing the update.
- Signed release build passed R8 and release vital lint. All three surge.7 APKs were copied to the configured Dropbox delivery directory and SHA-256 compared with build outputs. `apksigner` verified every delivered APK against the existing surge.6 certificate; ARM64 metadata confirms release package/versionCode 752, no debuggable marker and no debug API token/endpoint markers.


## Full review follow-up — 2026-10-31, surge.8

Version 753 (`1.14.4-surge.8`) applies the findings of a complete review of the branch. No device test was run in this turn; host-side checks only.

- Converter regression harness: 73 converter, 22 routing override and 35 runtime/failover assertions passed; a configuration using every supported protocol (HTTP, HTTPS, SOCKS5, Shadowsocks, Trojan, AnyTLS) also passed native validation; all generated configurations passed native `sing-box check` without deprecation output. New assertions cover the staged resolver (one local resolve for all IP rules, matcher-scoped re-resolve through each policy's own resolver directly before every proxy route that follows it, whole-traffic re-resolve only before a proxy FINAL, no re-resolve for a DIRECT FINAL, hostname passthrough when no IP rule matched), the `udp-relay` default, and every compatibility relaxation listed in the README.
- `tools/test-ruleset-download.py` still reproduces the old cold-download failure and starts the fixed configurations, now including the proxy-side DNS server with an unreachable detour.
- `:app:assembleOtherDebug` and the signed `:app:assembleOtherRelease` built; delivered APKs and their checksums are listed below.
- A Codex review of the working tree reported six defects (HTTP/AnyTLS rejecting `network`, global instead of scoped proxy re-resolution, one shared proxy resolver, silently dropped certificate pinning, unsynchronized cache writes, no fallback when offline regeneration fails); all were fixed and covered by the assertions above. A second Codex pass confirmed those fixes and found three more (BOM prefix lost in the rewrite, DIRECT fall-through keeping proxy-side addresses after a scoped re-resolve, regeneration validation outside the fallback), also fixed and asserted; it also noted that a selector manually switched to DIRECT still re-resolves through public DoH over the direct path, which is now disclosed as a conversion notice.
- Not verified on a device: staged resolution against real nodes, the new overrides card on the OPPO phone, the offline regeneration of surge.7 caches at first start after upgrade, and UDP behavior of nodes without `udp-relay=true`. Existing Android fixtures (`tools/test-android-snell.py`, `tools/test-android-features.py`) were updated to declare `udp-relay=true` and should be rerun on the emulator before the next physical install.

Known remaining difference: a failed `resolve` still drops the connection instead of applying `FINAL,dns-failed`; a proxy-side resolve needs the proxy to be reachable, which is also required for the connection itself.


## Independent surge.8 regression — 2026-10-31

Tested product commit `6551789` (version 753, `1.14.4-surge.8`). This round adds regression tests and records results; no application behavior was changed.

| Check | Observed result |
|---|---|
| Converter/routing/runtime | 73 + 22 + 35 = 130 assertions passed; five generated configs passed native validation |
| Cold rule-set downloads | Existing two old-client failures reproduced and both fixed new/migrated configs actually downloaded and started |
| Host Snell v6 | 12 TCP/UDP checks passed across default/unshaped/unsafe-raw with reuse on/off |
| Debug and signed release builds | Both Gradle assemble targets succeeded; all six APKs copied to the requested Dropbox delivery directory and hashes compared |
| Android API/storage/subscriptions | 29 emulator assertions passed, including real fixture downloads, invalid-update preservation and API authentication |
| Modes/updates/failover | 17 emulator assertions passed: per-profile automatic/manual updates, failure preservation, Rule/Global/Direct, two-round failover, no recovery failback and mode/node persistence |
| New generation/storage checks | 9 emulator assertions passed: generation 1 regenerated offline to generation 2, exact current runtime restored, source/overrides retained, missing rule cache fallback remains usable, slow downloads do not block profile reads |
| Android Snell forwarding | Real TUN→Snell→controlled TCP and UDP destinations passed with udp-relay=true; omitting the option retained TCP and did not deliver UDP to the server |
| New overrides card | Visible on the OPPO release app. On the emulator, saving HTTPS DNS persisted the value in the profile and re-opening the editor reloaded it correctly |
| Physical release upgrade | Installed signed .8 over .7 with existing remote subscription; initial service was stopped, original subscription started successfully after upgrade, Rule mode visible, no startup error dialog |
| Physical connectivity | 9/9 verified-TLS requests across Google home/Google204/gstatic; 2/2 more on cellular and 2/2 after restoring Wi-Fi (13/13 total) |
| Actual application UI | Chrome rendered the Google page; a new ChatGPT temporary chat returned `12 + 13 = 25` |
| Final checks | Installed APK SHA-256 matches the .8 release artifact. No FATAL EXCEPTION in the sampled app logcat. System always-on VPN/lockdown stayed unset; Wi-Fi restored enabled; VPN left Started |

### Non-blocking finding: successful setting save shown under Error

Reproduced on the selected emulator profile with VPN stopped: open Android routing overrides, enter `https://9.9.9.9/dns-query`, and press Save overrides. The profile is saved (verified through the API and by reopening the UI), but the dialog title says **Error** while its body says settings were saved and the VPN should be started/restarted.

`EditProfileViewModel.kt:134-138` attempts reload whenever the profile is selected, including while stopped, and assigns the restart notice to `errorMessage`. `EditProfileScreen.kt:118` renders that notice with `error_title`. Use a separate informational notice for successful saves; only attempt live reload when the service is running. This UI finding is recorded, not fixed in this test-only round.

### Test limits and cleanup

- Physical testing used the release package, which has no Debug API. It verifies successful upgrade/start and actual connectivity/UI; it does not expose the private generation/source hash or provide per-connection Snell/DIRECT counts. Generation and source preservation were checked independently in the emulator.
- No phone reboot or long-duration periodic worker cycle was repeated in this round. The known OPPO app-only boot deferral remains a separate limitation.
- The added negative UDP test initially hit toybox nc's own non-expiring wait when packets were dropped. The harness now bounds the remote process with toybox timeout and checks the destination server received no forbidden payload; the corrected run passed. This was a test-harness timeout, not an application crash.
- The phone probe and temporary UI XML were removed. All test forwards/reverses were closed and the disposable emulator stopped. Synthetic profiles remain only in that test emulator's data; the phone's subscription was not replaced with fixtures. Private logs/screenshots are under `.private/claude-surge8-regression/` and are excluded from Git.

Reproduce the added storage checks on an already prepared disposable emulator with:

```sh
python3 tools/test-android-store.py --serial emulator-5554
python3 tools/test-android-snell.py .local-tools/sing-box-1.14.4-darwin-arm64/sing-box --serial emulator-5554
```

## Google Play download diagnosis — 2026-10-31

Physical OPPO, release `1.14.4-surge.8`, existing remote profile, Wi-Fi. No APK rebuild or application-code change was needed for this diagnosis.

- Play could browse but its requested application download stayed at zero bytes. Finsky logged Cronet `ERR_CONNECTION_RESET` and `CANNOT_CONNECT`, then retried. Available storage was approximately 12 GB; the observed failure occurred before package installation.
- Exported core logs associated `com.android.vending` with both paths: `play.googleapis.com` and image requests used Snell, while installation CDN hosts under `xn--ngstr-lra8j.com` used `DIRECT`. Direct TCP connections to `113.108.239.196`, `113.108.239.132`, `113.108.239.133` and `58.63.233.69` repeatedly timed out after five seconds. INFO logs establish the selected outbound, not the exact matched source rule or DNS pollution.
- Temporarily selected Global, cancelled the stuck request and retried the same user-requested application. Play displayed **Open / Uninstall**. Package Manager independently confirmed installation of `com.anthropic.aibo`, version `1.261029.18`, at 17:07:02, with `com.android.vending` as installer. No account/cache reset or target-app uninstall was performed.
- Restored Rule and saved a per-profile Android override: policy `Proxy`, domain suffix `xn--ngstr-lra8j.com`, empty package and DNS overrides. Core logs confirmed requests to the previously direct CDN hosts now used Snell with the hostname. This leaves the subscription source unchanged.
- Two HTTPS requests to Google `/generate_204` passed with certificate verification in Rule mode after the override. Root-path probes of two old CDN hostnames still timed out (four attempts); an additional Global probe of one old hostname also timed out. A bare CDN root request is not equivalent to a signed APK download URL and does not prove CDN availability or unavailability for the actual artifact.

**Validation limit:** the completed installation was verified under Global, before the targeted override. No second full Play download was attempted under Rule with the override; the override is verified for persistence/routing, not as a complete download fix. Global plus retry is the verified workaround. Future failures require a new Play/core-log correlation because CDN selection can change; do not attribute all resets to DNS or claim that the exact source rule has been identified.

Private device logs and screenshots are retained only under ignored `.private/play-download-diagnostics/`. No credentials, subscription URL, account identifiers or signed download URLs are included here. Phone returned to Rule with the VPN started; temporary exported phone logs and the probe binary were removed. Always-on VPN settings were not changed.

### Follow-up: exact rule attribution and completed Rule-mode download

Exported the **actual phone runtime JSON**, and read the phone's saved Surge source through its configuration viewer. Recompiled that source using the current converter and cached remote rule lists: the entire generated `route` object was structurally identical to the exported runtime, including inline rule sets. This comparison used the domain-only override from the first investigation.

Replayed those route rules in the native core with debug logging, using the captured DNS answer (`113.108.239.196`) for the failing CDN hostname. The replay replaced TUN with a localhost mixed inbound, supplied deterministic DNS answers, removed real proxy credentials, and loaded the SagerNet CN rule set locally. This is controlled rule-attribution evidence, not a claim that the original phone INFO log contained a rule index. The core's FakeIP handling restores the domain before matching, which is consistent with the domain-based replay.

| Request | Matching source rule without the Android override | Evidence |
|---|---|---|
| `rr3---sn-j5o7dn7e.xn--ngstr-lra8j.com` with the captured CN address | Saved source line 44: `GEOIP,CN,DIRECT` | Native replay: resolve, then `rule_set=surge-geoip-cn => route(DIRECT)` |
| `redirector.xn--ngstr-lra8j.com` | Line 42 ChinaMax RULE-SET, exact `DOMAIN,redirector.xn--ngstr-lra8j.com` | Entry exists in exported inline set; replay selects `surge-list-42-literal => route(DIRECT)`. This host was a checked rule candidate, not observed in the original retained download log |
| `services.googleapis.cn` during the first Gmail attempt | Line 42 ChinaMax RULE-SET, `DOMAIN-SUFFIX,cn` | Exact suffix present in exported set; phone log associates this host and DIRECT with `com.android.vending` |

The earlier line 43 `IP-CIDR,0.0.0.0/0,DIRECT,no-resolve` did **not** catch the unresolved CDN domain in the replay. A foreign-address control also passed it and reached the proxy final path. This matches Surge's documented [rule order and no-resolve semantics](https://manual.nssurge.com/rules/overview.html). These observed routes reflect the source configuration's direct policies; no converter defect was found in these decisions. This does not establish equivalence for all Surge features or all GeoIP databases.

**Domain-only attempt:** Gmail was not installed initially. With only the `xn--ngstr-lra8j.com` override and Rule mode, its download still stalled. Core logs showed CDN connections going through Snell, but Play's `services.googleapis.cn` and connectivity probes still used DIRECT. Finsky recorded `CANNOT_CONNECT`. Thus the domain-only change was insufficient.

**Verified configuration fix:** retained the domain override and added `com.android.vending` to the same profile's Android package overrides, policy `Proxy`, with the DNS override left empty. This routes Play's request/selection/probe traffic as well as its APK downloads through the selected proxy; unrelated applications retain their existing rules. The original Surge source was not edited. No global mode switch, app-data clearing, account change, or APK rebuild was used during the Gmail test.

After saving/reloading, the pending Gmail download progressed and completed. Play showed **Open / Uninstall**, and Package Manager confirmed `com.google.android.gm`, version `2026.10.19.1005695425.Release`, installed at **2026-10-31 17:27:02**, installer `com.android.vending`. The captured post-reload core log contained **32 Play-associated outbound entries, all Snell and zero DIRECT**, including `services.googleapis.cn`, connectivity probes, and newly selected CDN hosts. No FATAL EXCEPTION appeared in the sampled logcat. This validates one complete Gmail installation in Rule mode; it is not a promise about every future app/CDN or a proof that one individual direct service alone caused the failure.

Final state: original remote profile, Rule mode, VPN Started; package and domain overrides verified by reopening the editor. Exported runtime/source and logs remain private and excluded from Git. Temporary phone JSON/log exports were removed. This follow-up supersedes the previous limitation about having no completed Rule-mode download.

## Outlook sync investigation and proxy-side resolver fix — 2026-10-31, surge.9

Device: OPPO PKT110 (Android 17), debug build beside the release build, user's three-node Snell v6 subscription, Rule mode, LAX 01 selected unless stated.

Ruled out: per-app bypass (Outlook uid inside the VPN uid ranges), IPv6 leak (`::/0` unreachable), Private DNS (null), system connectivity validation (the VPN network carries `VALIDATED`), TCP Fast Open (kernel counters all zero), and the staged resolution path itself (the shell probe follows the same `match[23] resolve -> match[25] resolve(surge-proxy-dns-0) -> snell` chain and completes in about one second).

Synthetic traffic through the Snell node from the phone shell (`tools/network-probe` plus a scratch POST/idle/parallel variant) all succeeded: GET, POST 200 KB and 2 MB, five parallel 100 KB POSTs over HTTP/2 and HTTP/1.1, a 5 MB download in 2.4 s, one keep-alive connection reused after 5/20/45/70/100 s idle gaps, and Microsoft authentication-protected endpoints (EWS, REST, Substrate, Graph) returning 401 identically through the proxy and direct.

Outlook itself, observed with the new `GET /v1/connections` byte counters while the app was in the foreground: through LAX 01 and LAX 03, in Rule mode and in Global mode (domain handed to Snell, no local resolution), every `outlook.office365.com` connection carried about 6.9 KB up and 7.5 KB down (TLS handshake plus a small response), stayed open without further traffic, and the app opened new connections in waves of four or five. In Direct mode the same app produced connections with 14.6 KB down and one with 60 KB up / 32 KB down. Outlook's own logcat is silent. The stall is therefore not a routing or resolution defect in this app; the evidence points to Microsoft rejecting or challenging the account's requests when they arrive from the node's US hosting address. Open questions for the user: what Outlook displays (re-sign-in banner or offline), and whether Outlook syncs on iOS/macOS Surge through the same nodes.

Defect found during the DIRECT control run: with the Proxy group switched to DIRECT, `surge-proxy-dns-0` (1.1.1.1 DoH, detour Proxy) dialed 1.1.1.1:443 directly from China and timed out after 5 s; every connection that reached rule 25 was dropped (`pre-match[25] => resolve(surge-proxy-dns-0,ipv4_only): ... dial tcp 1.1.1.1:443: i/o timeout`) and Outlook established zero connections.

Rejected fix, verified on the phone: pointing the proxy-side resolver at the profile's own DNS (AliDNS DoH 223.5.5.5) through the policy. The JSON API returned US answers through LAX, but the wire-format DoH used by sing-box returned a polluted answer for www.google.com (104.244.42.197) through LAX 01, the connection to that address timed out after 20 s, and the answer stayed in the per-server cache until its TTL expired.

Final fix: the resolver keeps 1.1.1.1 DoH, but its detour is a hidden `surge-resolver-<group>` selector that mirrors every DIRECT-capable group without DIRECT; the VPN service keeps the mirror on the node the user selected. Phone verification with the surge.9 debug build: generation-3 regeneration happened at service start, with Proxy switched to DIRECT the Outlook probe through the resolve path returned 200 and no `surge-proxy-dns` failure was logged, and after switching back to LAX 01 the proxy-side answers were unpolluted.

Converter tests: 82 converter, 22 override and 35 runtime-feature assertions pass; `sing-box check` passes for the generated fixtures. Debug and release APKs were rebuilt; the phone's group selection and Rule mode were restored to LAX 01 / Rule after the control runs.
