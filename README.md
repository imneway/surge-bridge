# Surge Bridge

An experimental Android bridge for a subset of Surge `.conf` files, built on SFA / sing-box 1.14.4. It converts supported configuration into sing-box JSON while retaining the original source for editing and subscription updates.

This is an independent community project, not an official Surge or SagerNet client, and not a full Android port of Surge.

**Only Snell v6 has been tested end to end as a proxy protocol in this project.** Other protocol conversion paths exist, but accepting a configuration does not mean its connectivity has been verified.

[中文使用与限制](docs/README.zh-CN.md) · [Detailed compatibility](docs/compatibility.md) · [Validation evidence](docs/validation.md) · [Debug API](docs/debug-api.md)

## Tested scope and limitations

| Area | Current scope |
|---|---|
| Snell v6 | Controlled TCP/UDP tests cover default, unshaped and unsafe-raw modes, with connection reuse on/off. Physical Android connectivity was also tested with Snell v6 nodes. This does not certify every server or provider. |
| Other proxy protocols | Snell v4/v5, Shadowsocks, Trojan, HTTP(S), SOCKS5 and AnyTLS have conversion paths, but have **not** undergone end-to-end proxy-server validation here. Treat them as unverified. |
| Android devices | Validation primarily uses one OPPO phone and an Android emulator. Other devices, OEM background policies and network environments are unverified. |

- **Partial Surge compatibility:** MITM, scripts, URL/header rewrites and other unsupported sections are not implemented. Some unsupported rules/options are skipped with warnings; others reject import. Inspect the compatibility notices—successful import is not proof of equivalent behavior.
- **Routing and DNS differences:** GeoIP data, DNS resolution order/failure handling and some rule semantics differ from Surge. A working iOS profile can need Android-specific package/domain overrides. See the documented Google Play example and compatibility notes.
- **UDP is opt-in:** supported proxy nodes carry TCP only unless the Surge configuration explicitly enables `udp-relay=true`.
- **Background behavior is best effort:** scheduled updates and boot recovery depend on Android/OEM restrictions. App-only startup has not been shown to recover reliably on the tested OPPO device; universal automatic reconnection is not claimed.
- **Build scope:** ARM64 and x86_64 only. Naive, OpenVPN, OpenConnect, Tailscale and USB/IP are not enabled in this fork's core build, even where upstream UI remains visible.

Use this as an experimental compatibility tool. Keep your original configuration and review the [tested behaviors and remaining gaps](docs/validation.md) before relying on it.

## Public source and private files

This repository contains source code and synthetic test fixtures only. Supply your own subscription/configuration and local signing credentials. The fixture in `test/surge/example.conf` uses reserved example addresses and dummy test keys; it is not a usable subscription.

Private profiles, signing keys/passwords, device logs, chat attachments, local toolchains and APKs are excluded. Keep them in ignored local directories and never force-add them. See [publication and credential handling](docs/publication.md).

---

## Upstream: SFA

Experimental Android client for sing-box, the universal proxy platform.

## Documentation

https://sing-box.sagernet.org/installation/clients/sfa/

## License

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Under the license, that forks of the app are not allowed to be listed on F-Droid or other app stores
under the original name.
