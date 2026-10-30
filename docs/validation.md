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
