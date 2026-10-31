# Public source publication

This public repository starts with a clean source snapshot of Surge Bridge
`1.14.4-surge.9`. It does not publish the earlier local Git history, which
contained an inherited upstream keystore. The original development history
and local signing material remain on the maintainer's machine.

The Android base is [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android),
based on commit `a548a7903e96266a0046018562c73b192edbfc18`.
The reproducible core build is pinned to SagerNet/sing-box `v1.14.4`.
Upstream copyright notices and licenses are retained. This is an independent
fork, not an official Surge or SagerNet client.

## What must stay local

- Real Surge subscriptions, profile exports, subscription URLs and node credentials.
- Signing keystores, private keys, signing passwords and local build properties.
- Debug API bearer tokens, device logs, database exports and chat attachments.
- Downloaded toolchains, generated binaries and locally delivered APKs.

Use `.private/` for private configuration and signing material. Use
`.local-tools/` for toolchains. Both are ignored. Local `AGENTS.md` build delivery
instructions are also ignored so a developer's personal paths are not published.
Ignore rules are preventive defaults, not a substitute for checking staged
files: do not use `git add -f` to add private files.

`test/surge/example.conf` and inline test data contain synthetic credentials and
reserved example addresses only. They are required by the converter tests and
cannot provide a real proxy subscription.

## Release signing

The repository contains no signing key. Supply `KEYSTORE_FILE`, `KEYSTORE_PASS`,
`ALIAS_NAME` and `ALIAS_PASS` through ignored local build properties or the
existing `LOCAL_PROPERTIES` environment mechanism. Never put actual values in
source, issue reports or workflow files. Preserve the original private signing
key when upgrading an existing installation.

## Initial publication validation

The initial public snapshot is scanned with Gitleaks and separately checked
against the maintainer's known local profile/signing credentials without
printing their values. The public commit has no parents, so excluded files in
old local commits are not reachable through the published branch. Only `main`
is pushed; local archive branches and tags are not published.

Future work should start from the public `main` history. Do not merge or push
the older local development/archive branches, or use `--all`/`--mirror` when
pushing to the public remote. Re-scan staged changes and any history being
published. The initial checks do not certify future commits.
