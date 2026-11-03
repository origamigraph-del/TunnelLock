# TunnelLock — Architecture

This document explains how TunnelLock is put together, for engineers reading or
changing the code. For the release/CI/signing workflow see
[`DEVELOPING.md`](DEVELOPING.md).

## The two faces of one APK

TunnelLock is a single application that behaves differently depending on which
Android user it runs in:

- **Personal profile (the "normal" copy).** Creates and manages the secure space,
  lists and copies apps into it, runs the device-wide fake location, and hands
  links/toggles across to the secure space. UI: `SetupScreen`.
- **Secure space (the work-profile copy).** The same APK, now the **profile owner**
  of a managed profile. This is the real VPN client: servers, connect/disconnect,
  per-app routing, TunnelProbe integration. UI: `MainScreen`.

`MainActivity.onCreate` picks the face with `Policy.isProfileOwner(this)`.

### Modules

| Module | Contents |
|--------|----------|
| `core/` | Pure-Kotlin, unit-tested, no Android VPN deps: link parsers (`parser/`), the Xray config builder (`xray/XrayConfigBuilder`), OpenVPN config model (`openvpn/`), SSH link parsing (`parser/SshLink`), the update feed model (`update/UpdateFeed`), coordinate parsing (`geo/Coordinates`), and the log uploader (`report/LogUploader`). |
| `app/` | The Android app: `vpn/` (tunnel, engines, mock location), `strict/` (secure space), `ui/` (Compose), `data/` (`AppStore`, `AppLog`, `UpdateChecker`, `LogSender`, subscriptions). `minSdk 28`, `targetSdk 36`. |
| `probe/` | TunnelProbe, the standalone leak-tester. `minSdk 26`. |

## Secure-space model (`app/.../strict/`)

The secure space is an Android **managed profile** that TunnelLock owns, so it can
set policy on it. Key file: `strict/Policy.kt`.

- **Provisioning.** `Policy.provisioningIntent` launches the platform
  managed-profile flow; `AdminReceiver` (a `DeviceAdminReceiver`) receives
  `PROFILE_PROVISIONING_COMPLETE`, and `ProvisioningSuccessActivity` /
  `Policy.finalizeProfile` enable the profile (`setProfileEnabled`) and grant the
  in-profile copy its runtime permissions with `setPermissionGrantState`.
- **Lockdown — the real guarantee.** `Policy.enforceLockdown` calls
  `setAlwaysOnVpnPackage(admin, packageName, /*lockdownEnabled=*/true)`. From then
  on **Android itself**, not TunnelLock, blocks every packet from the profile's
  apps that is not inside TunnelLock's VPN — even if the TunnelLock process is
  dead. `isLockdownActive` verifies it.
- **User restrictions.** `ensurePolicies` adds `DISALLOW_INSTALL_UNKNOWN_SOURCES`,
  `DISALLOW_INSTALL_APPS`, `DISALLOW_UNINSTALL_APPS`, so apps inside the space
  can't be swapped out from within it.
- **App gating.** `AppGate` + `Policy.setPackagesSuspended` pause/resume the
  protected apps (`setSuspended`, `setAllSuspended`). A suspended app opens to a
  system dialog instead of running with no internet; `AppGate.closeIfDisconnected`
  is the "pause when disconnected" behaviour, and `neverPause`/`pruneNeverPause`
  track per-app exceptions. `PROBE_PACKAGE` is always kept un-suspended.
- **Location policy.** `Policy.applyLocationBlock` grants/denies
  `ACCESS_FINE/COARSE_LOCATION` to the protected packages via
  `setPermissionGrantState` — the "no location in the secure space" mode.

### Cross-profile hand-off (`strict/SecureSpace.kt`)

Getting an action from the personal side into the secure space uses two paths:

1. **Android's cross-profile forwarder** — `SecureSpace.forward` finds the
   forwarder activity (set up with `addCrossProfileIntentFilter` +
   `FLAG_PARENT_CAN_ACCESS_MANAGED` in `ensurePolicies`) and
   `CrossProfileApps.startMainActivity` opens the profile.
2. **Clipboard fallback** — the forwarder **does not work on Samsung**, so
   app-copying, link opening (`OpenLinkActivity`/`ShareLinkActivity` +
   `PhoneApps`), and the quick-settings toggle fall back to handing the payload
   over the clipboard and launching the secure-space `MainActivity`, which reads
   it on focus. `CloneActivity`/`CloneQueue` drive the actual app installs into
   the profile (`REQUEST_INSTALL_PACKAGES`); removing the unprotected outside copy
   uses `REQUEST_DELETE_PACKAGES`.

`BootReceiver` re-applies the gate and restarts the mock-location service after a
reboot.

## Tunnel and kill-switch (`app/.../vpn/TunnelVpnService.kt`)

- A `VpnService` whose TUN interface is **always up** while the user wants to be
  connected. Addresses: `TUN_IPV4 = 10.233.97.1/30`, `TUN_IPV6 = fd8e:6d17:21c0::1`.
- The address range is deliberately **not** sing-box's default (which Hiddify /
  NekoBox use), so TunnelLock and a phone-wide VPN in the personal profile don't
  clash; a network that looks like TunnelLock's own interface is ignored (see the
  `NetworkCallback` handling in `onAvailable`/`onLost`).
- **Kill-switch.** When the proxy is not working, the tunnel stays up and packets
  are discarded inside it; there is no direct outbound in the Xray config. Combined
  with Android lockdown (above), the protected apps cannot reach the network when
  disconnected. When the user disconnects, the interface is torn down and lockdown
  alone blocks everything (no lingering VPN key icon).
- Session lifecycle lives in `handle`/`startSession`/`runSession`/`stopSession`,
  with `findFallback` for multi-server failover, `enterGuard` for the blocked
  state, and `checkInterval`/`checkConnectivity` for the screen-off (1 min) vs
  screen-on polling. `BatteryLog` records periodic battery/CPU samples.

## Per-app routing — Proxifier-style (`core/.../xray/XrayConfigBuilder.kt`)

- Android tells TunnelLock which app owns each connection:
  `ConnectivityManager.getConnectionOwnerUid(...)`, wired through the Xray core's
  `ProcessFinder` callback in `vpn/XrayCore.kt` (`findProcessByConnection`),
  returning the owning UID (cached, LRU of 256).
- `XrayConfigBuilder.addAppRoutes` emits one Xray outbound per per-app server
  (`APP_TAG_PREFIX`) and a routing rule matching `"process"` on the app's UID(s)
  → that outbound. Connections whose owner is unknown match `UNKNOWN_APP` and go to
  a `blackhole` outbound (`APP_BLOCK_TAG`), so nothing leaks via the wrong exit —
  DNS excepted.
- Sniffing is set to `routeOnly` so the sniffed domain is used only for routing and
  never rewrites the destination (the app of each connection is identified by its
  real address, which sniffing must not change).
- `canRouteApps` / `canCarryAppRoutes` gate the feature: per-app servers need a
  plain Xray outbound (not a full custom JSON config, not FakeDNS, which has no real
  addresses to attribute). When the main server can't carry app routes (OpenVPN,
  custom JSON with FakeDNS, Android 9), those apps are left out of the tunnel so
  lockdown cuts them off.
- `buildStandard` builds the normal config (TUN inbound, server outbound, a `dns`
  outbound, a `block` blackhole, `domainStrategy "AsIs"`); `buildCustom` keeps a
  user-supplied JSON config's outbounds/routing/DNS and only replaces its inbounds
  with the TUN inbound.

## VPN engines (`app/.../vpn/`)

- **Xray** — `XrayCore.kt` wraps `libv2ray` (the `libv2ray.aar` from
  AndroidLibXrayLite, the same core as v2rayNG): `initCoreEnv`, `newCoreController`,
  `startLoop(config, tunFd)`, `measureOutboundDelay` for latency ranking, plus the
  `ProcessFinder` callback above.
- **OpenVPN** — `OpenVpnEngine.kt` drives the bundled `libovpnexec.so` over its
  **management socket** (a UNIX socket in the app's private dir). The app builds the
  TUN (`openTun`) and protects sockets (`protect`) on OpenVPN's request; the
  username/password are sent over the socket **only when asked** and never written
  to the config or log. Certs/keys must be **inline** in the `.ovpn`; TAP is not
  supported. Events: `Connected`, `Reconnecting`, `Stopped(loginRejected)`,
  `Stalled(serverAnswered)` — the last distinguishes "server never answered" from
  "handshake filtered mid-way" for a clear user message.
- **SSH** — `SshTunnel.kt` (JSch fork + Bouncy Castle for Ed25519/Curve25519/
  ChaCha20). TunnelLock holds the SSH connection and serves a local **SOCKS5** proxy
  bound to a **random username/password** so no other app can use it; Xray sends app
  traffic there and each connection becomes a `direct-tcpip` channel. Transports:
  direct, inside TLS (custom SNI), WebSocket, and WebSocket+TLS (for CDN-fronted
  servers). SSH carries **TCP only** (UDP refused; DNS goes over Xray/TCP). The host
  key is **trust-on-first-use** (or pinned via `hostkey=SHA256:...`); a changed key
  is refused — TLS/WS are only disguises, the host key is what authenticates the
  server.

## Mock location (`app/.../vpn/MockLocation.kt`, `MockLocationService.kt`)

- The `android:mock_location` app-op is **per Android user**, and Developer Options
  can grant it only to the **personal** copy — a work-profile app cannot be picked
  as the mock app. So the fake runs from the personal profile.
- `MockLocation` installs **test providers** for `gps`, `network`, and (API 31+)
  `fused`, and re-pushes the location every second. It deliberately does **not**
  mock `passive`: that throws before Android 12 and, from 12 on, replacing it breaks
  passive delivery device-wide — and it's unnecessary, because passive listeners
  receive the other providers' fixes automatically.
- A test provider **replaces the real provider device-wide**, so on a real device
  every app — the secure-space apps included — reads the fake. (The emulator installs
  the mock device-wide, visible in `dumpsys location`, but does **not** deliver
  location across users, which is why the e2e treats the secure-space read as
  informational and gates instead on "mock is set device-wide".)
- `MockLocationService` is a personal-profile foreground service that follows
  `AppStore.mockLocation` and is resumed on app open (`MainActivity`) and on boot
  (`BootReceiver`). The **map picker** (`SetupScreen.MapPickerDialog`) uses osmdroid
  (OpenStreetMap, no API key) with a center crosshair and the required
  `CopyrightOverlay` attribution; tiles cache to app-internal storage.

## Update feed and logs (`app/.../data/`, `core/.../update/`, `core/.../report/`)

- `UpdateChecker` polls every 12 h (`CHECK_EVERY_MS`); the secure-space copy on a
  timer, the personal copy on each open. It reads the **update feed** — comments by
  `github-actions[bot]` on issue #2 (`UPDATE_ISSUE`) — and parses them with
  `core/.../update/UpdateFeed`. Priorities: `optional`, `recommended`, `important`,
  `critical`; the highest among missed versions wins, and servers using a
  protocol/option this build doesn't understand bump the effective priority to at
  least `important`.
- Releases are announced by `.github/scripts/announce-update.sh`, which posts
  `release/notes.json` (`id`, `priority`, `fa`, `en`) to issue #2. A note is skipped
  if its `id` was already announced.
- `LogSender` (and TunnelProbe automatically) post logs as comments on issue #1 via
  `core/.../report/LogUploader`, using `BuildConfig.LOG_TOKEN` — a token baked in
  **only for official builds** (see `DEVELOPING.md`). `AppLog` must never log
  secrets or one-time tokens (e.g. the activation token inside an opened link).
