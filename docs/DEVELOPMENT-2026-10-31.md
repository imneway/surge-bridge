# Development plan — 2026-10-31

Scope approved: visible URL import; independent automatic update choice and interval for each remote profile plus manual refresh; boot recovery setting; Rule/Global/Direct modes; failure-only failover. Do not add latency-based selection. Keep the Surge source unchanged and Android options persistent across updates.

## Delivery and acceptance

1. URL and updates: direct URL entry; keep profile-specific switches/intervals; reschedule on interval, enable or disable changes; manual update works with auto update off. Background work only downloads enabled remote profiles; show last attempt/result. Failed validation retains last valid configuration. Cover two profiles with different choices.
2. Boot: explicit restore-on-boot switch gates the existing last-running-state recovery; link to Android VPN settings for always-on management. No boot-time subscription download prerequisite. Physical reboot is a separate validation step.
3. Modes: generate native mode rules for Surge; DNS interception stays first, Direct/Global override app/domain rules. Direct DNS must not use the proxy; Global uses the selected proxy group. UI and debug API switch mode, clear old connections, retain mode across restart. Existing TUN exclusions remain outside mode control.
4. Failover: opt-in per profile and eligible selector group; HTTPS probes through native nodes, two consecutive failed rounds, select the first healthy backup in configured order. Keep a healthy current node regardless of latency; no automatic failback or DIRECT fallback. Ignore stale results, reset on manual selection, stop with VPN, and suspend in Direct mode. Expose state/reason to UI and debug API. Do not turn existing Surge selectors into url-test groups. Preserve optional manual DIRECT members but exclude them from automatic candidates and pause the group when DIRECT is manually selected.
5. Validate converter and decision regressions, build APK, exercise URL/update/API fixtures, mode routes and controlled failures. Install on the connected phone and confirm Google/ChatGPT still work. Record observed vs unverified results, commit scoped changes without private data.

## Design constraints

- Remote profile update settings belong to each profile, never a global toggle. Periodic WorkManager timing is best-effort (minimum 15 minutes).
- Mode, failover and Android routing settings are separate from subscription text; download does not rewrite the original Surge file.
- Successful generic HTTPS health checks do not guarantee a particular service accepts the exit IP. Established streams can require reconnecting after a switch.
- Reuse the pinned sing-box native API; do not fork its transport implementation for failure-only selection.


## Delivered

- URL import, per-profile automatic update/interval, manual refresh and last-attempt feedback implemented.
- Restore-on-boot preference and Android VPN settings shortcut implemented.
- Rule/Global/Direct, per-profile mode/node cache and live connection reset implemented.
- Opt-in failure-only switching and observable state implemented; optional manual DIRECT retained and excluded from automatic candidates.
- Debug API and CLI cover scheduling, due checks, modes, boot preference and failover status.
- Version 1.14.4-surge.3 built and installed. Converter/native checks, controlled Android routing/failure tests, emulator reboot/rollback tests and physical Google/ChatGPT checks passed. Details and limitations: [validation.md](validation.md).
