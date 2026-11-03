# Security policy

TunnelLock's whole purpose is to keep chosen apps off the network whenever the VPN is down. A bug that lets a protected app's traffic leak outside the tunnel is a **security bug**, not just a defect.

## Reporting a vulnerability

Please report security issues **privately**, not as a public issue:

- Use GitHub's **private vulnerability reporting** (the repository's *Security* tab → *Report a vulnerability*).

Include what you observed, the device/Android version, the app version, and steps to reproduce. A TunnelProbe report (Settings → logs, or the probe's "copy report") is very helpful for leak reports.

Please give a reasonable window to fix before any public disclosure.

## What counts as a vulnerability

- Any traffic from a protected (secure‑space) app leaving the device while the VPN is disconnected ("leak").
- A protected app reaching the internet through the wrong exit (e.g. a per‑app server being bypassed).
- The real location leaking to the secure space while a fake location is set.
- Exposure of secrets the app holds (server credentials, SSH keys, the activation token handled by "open link in the secure space").

## Supported versions

Only the latest release is supported. Always update to the newest build (the fixed `releases/latest` links).

## Notes for reviewers

- Official release APKs embed a GitHub token **scoped only to opening issues** on this repository (for the optional "send logs to the developer" feature). It cannot read code or other repositories, and community builds have no token. Even so, logs must never contain passwords, private keys, or one‑time tokens.
- The release is signed with a key whose certificate the app pins; see `docs/DEVELOPING.md`.
