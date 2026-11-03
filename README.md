# TunnelLock

[![Build APK](https://github.com/origamigraph-del/TunnelLock/actions/workflows/build.yml/badge.svg)](https://github.com/origamigraph-del/TunnelLock/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/origamigraph-del/TunnelLock?sort=semver)](https://github.com/origamigraph-del/TunnelLock/releases/latest)

**A no-root Android app that forces the apps you choose to reach the internet _only_ through a VPN (V2Ray / Xray, OpenVPN, or SSH).** If the VPN drops for even a moment, those apps have no internet — but they are not closed, and they recover the instant the tunnel is back.

Think **Proxifier for Android, without root**: per-app routing, a real kill-switch enforced by Android itself, and optional device-wide fake GPS.

🇮🇷 **فارسی:** راهنمای کامل به زبان فارسی در [`README.fa.md`](README.fa.md) و [`docs/USER_GUIDE.fa.md`](docs/USER_GUIDE.fa.md).

---

## ⚠️ Before you start

- **This is a power tool.** It creates an Android Work Profile, installs app copies into it, runs a VPN, and (optionally) mocks your location. That combination is legitimate but unusual.
- **Google Play Protect will warn** when you install it ("This app could be harmful"). That is a *reputation/heuristic* warning for a sideloaded app with sensitive permissions — not a malware detection. You can tap **Install anyway**. See [Permissions & Play Protect](#permissions--play-protect) for why each permission is needed.
- **Do not uninstall TunnelLock** to "turn it off" — uninstalling deletes the secure space and every app inside it. Just disconnect.

---

## Features

- **Strict per-app VPN** — chosen apps live in an Android Work Profile with **always-on VPN + lockdown**, so Android itself guarantees they cannot reach the internet except through the tunnel, even if TunnelLock crashes.
- **Real kill-switch** — if the server drops, packets are discarded inside the tunnel; there is no direct route. Apps stay open, just offline, and recover automatically.
- **Per-app servers (Proxifier-style)** — route each app through its own server; unattributable connections are dropped so nothing leaks via the wrong exit.
- **Protocols** — V2Ray/Xray (VLESS, VMess, Trojan, Shadowsocks, full JSON config, subscriptions, QR), **OpenVPN** (`.ovpn`), and **SSH** (direct, TLS, WebSocket, WS+TLS).
- **Device-wide fake GPS** — set a location from the normal app and every app on the phone, the secure-space apps included, reads it. Pick the point by typing coordinates, pasting a Maps link, or **choosing it on an OpenStreetMap map**.
- **Open activation links in the secure space** — so an email link opens the *protected* copy of an app, with the right IP/location, not the normal one.
- **Update feed** — the app checks for new builds and tells you how urgent each one is.
- **TunnelProbe** — a bundled leak-tester so you can verify, yourself, that traffic really is blocked when the VPN is down.

See the [User guide](docs/USER_GUIDE.md) for step-by-step instructions.

---

## How it works (strict mode)

- TunnelLock creates a **secure space** (Android Work Profile) and becomes its owner.
- The apps you want to protect are copied into that space.
- The space gets **always-on VPN + lockdown**: Android guarantees those apps have no path to the internet except our VPN.
- The TUN interface is always up. If the server drops, packets inside the tunnel are discarded and there is no direct route in the Xray config.
- A mid-session drop never closes an app — only its internet stops. While disconnected, the VPN interface is torn down (no VPN key icon lingering) and Android's lockdown blocks everything.
- It does **not** clash with a phone-wide VPN in the normal profile: TunnelLock's interface uses its own address range (`10.233.97.1/30`), and another VPN that looks like TunnelLock's default network is ignored. Every network change and reconnect is logged with its reason.
- Normal (non-secure-space) apps are completely unaffected.

---

## Install

1. On the phone, open the latest APKs directly (signed in to GitHub) and install:
   - **TunnelLock:** <https://github.com/origamigraph-del/TunnelLock/releases/latest/download/TunnelLock.apk>
   - **TunnelProbe** (optional leak-tester): <https://github.com/origamigraph-del/TunnelLock/releases/latest/download/TunnelProbe.apk>

   These links always serve the newest version; use the same link to update.
2. Play Protect will warn — tap **Install anyway** (then **Install**).
3. Open the app and tap **Create secure space**.
4. Inside the secure space (briefcase-badged icon): add your **server**, then **Connect**.
5. **Add apps:** open the *normal* TunnelLock (no badge), tick the apps, **Add to the secure space** (Android asks to confirm each install — only the normal copy can see your installed apps).

Full walkthrough: [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

---

## Permissions & Play Protect

TunnelLock requests exactly the permissions its features need. This transparency is also why Play Protect flags it — the combination is powerful:

| Permission | Why |
|---|---|
| Device admin / managed profile | Create and own the secure space (Work Profile), set always-on VPN + lockdown. |
| `BIND_VPN_SERVICE` | The tunnel itself. |
| `REQUEST_INSTALL_PACKAGES` / `REQUEST_DELETE_PACKAGES` | Copy chosen apps into the secure space and remove the unprotected outside copy. |
| `QUERY_ALL_PACKAGES` | List your installed apps so you can pick which to protect. |
| `ACCESS_MOCK_LOCATION`, `ACCESS_FINE/COARSE_LOCATION` | Optional device-wide fake GPS (off by default). |
| `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE*` | Keep the kill-switch and tunnel reliable. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | One-tap battery exemption so Android doesn't kill the tunnel. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Networking; detect network changes. |

**The Play Protect warning cannot be fully removed for a sideloaded app with these capabilities** — it is inherent to what the app does. Mitigations: install from these official release links, verify the APK (open source — build it yourself), keep the same signing key so reputation builds over time, and optionally appeal to Google via their Play Protect "incorrect warning" form.

---

## Build & develop

Builds run on GitHub Actions (`.github/workflows/build.yml`): every push to `main` runs the core tests, builds a signed release APK, and publishes it.

Local build:
```bash
# 1. Xray core (version + SHA-256 are pinned in build.yml)
mkdir -p app/libs && curl -fsSL -o app/libs/libv2ray.aar <url-from-build.yml>
# 2. OpenVPN native binary
native/build-openvpn.sh <android-ndk> app/src/main/jniLibs
# 3. Build
./gradlew :core:test :app:assembleRelease :probe:assembleRelease
```

Architecture, routing internals, the secure-space model, the mock-location mechanism, CI/e2e, and the release/signing process are documented in **[docs/DEVELOPING.md](docs/DEVELOPING.md)** and **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**. Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).

### Project layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin: link parsers and the Xray config builder (unit-tested) |
| `app/.../vpn/` | `TunnelVpnService` (tunnel + kill-switch), Xray core wrapper, `OpenVpnEngine`, `MockLocation` |
| `app/.../strict/` | Secure space: provisioning, lockdown, app copy/suspend, cross-profile hand-off |
| `app/.../ui/` | UI (Jetpack Compose, English + Persian) |
| `native/` | Builds OpenVPN for Android from pinned sources (CI only) |
| `probe/` | TunnelProbe leak-tester |
| `e2e/` | On-emulator end-to-end test harness |
| `docs/` | User and developer documentation |

---

## License

TunnelLock is free software under the **GNU GPL v3** — see [LICENSE](LICENSE).

It bundles third-party components under their own licenses (Xray-core MPL-2.0, OpenVPN GPL-2.0, JSch, Bouncy Castle, osmdroid, ZXing) and shows OpenStreetMap map tiles (© OpenStreetMap contributors). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The OpenVPN binary runs as a separate program; its GPL-2.0 source is reproducible from `native/build-openvpn.sh`.
