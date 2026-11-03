# TunnelLock — Developing

How to build, test, sign, and release TunnelLock. For how the code is organised
see [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Prerequisites

- JDK 17, the Android SDK (`compileSdk 36`), and an Android NDK (CI pins
  `NDK_VERSION 27.2.12479018`) if you build the OpenVPN binary locally.
- Gradle wrapper is included (`./gradlew`). Kotlin 2.2.x, AGP 8.13, Jetpack Compose.

## Local build

Three inputs are fetched/built outside Gradle; the exact versions and checksums
are pinned in `.github/workflows/build.yml`.

```bash
# 1. Xray core (AndroidLibXrayLite). Version + SHA-256 are in build.yml
#    (XRAY_LIB_VERSION / XRAY_LIB_SHA256).
mkdir -p app/libs
curl -fsSL -o app/libs/libv2ray.aar \
  "https://github.com/2dust/AndroidLibXrayLite/releases/download/<XRAY_LIB_VERSION>/libv2ray.aar"

# 2. OpenVPN native binary -> app/src/main/jniLibs/<abi>/libovpnexec.so
#    Builds OpenVPN 2.6.x + OpenSSL + LZO/LZ4 from pinned sources for
#    arm64-v8a, armeabi-v7a, and x86_64 (the emulator). See native/build-openvpn.sh.
native/build-openvpn.sh <android-ndk-dir> app/src/main/jniLibs

# 3. Build + unit tests
./gradlew :core:test :app:assembleRelease :probe:assembleRelease
```

Without a signing secret (below) the APK is debug-signed, which is fine for local
testing but cannot update an installed release build.

`native/build-openvpn.sh` builds OpenVPN with `TARGET_ANDROID`: it never touches a
TUN device or the routing table itself and talks to the app over the management
socket instead. Every source is pinned (git commit, or SHA-256 for the LZO
tarball): `OPENVPN_TAG=v2.6.23`, `OPENSSL_TAG=openssl-3.5.9`, `LZ4_TAG=v1.10.0`,
`LZO_VERSION=2.10`. The output is `libovpnexec.so` — an executable with a library
name, so Android unpacks it into the native lib dir, the only place an app may run
programs from.

## Continuous integration

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `build.yml` (**Build APK**) | every push (`branches: ["**"]`), PRs, manual | Downloads Xray, builds/caches OpenVPN, runs `:core:test`, assembles release APKs, verifies signatures, uploads an artifact. **Publishes a GitHub release only on `main`** and announces the update. |
| `e2e.yml` (**Emulator test**) | push to `e2e`, or `workflow_dispatch` | Boots an Android emulator, runs real Xray + OpenVPN servers next to it, and drives `e2e/run.sh`. |
| `upstream.yml` (**Upstream check**) | weekly | Checks whether Xray-core or OpenVPN have newer releases than the pinned ones (`.github/scripts/upstream-check.sh`). |

## Signing & release

The release keystore is **not committed**. CI restores it from a repository secret:

- **`KEYSTORE_BASE64`** — base64 of the release keystore. The "Provide signing
  keystore" step writes `signing/release.jks` from it when the file is absent
  (`printf '%s' "$KEYSTORE_BASE64" | base64 -d > signing/release.jks`). Produce the
  value with `base64 -w0 signing/release.jks`.
- **`SIGNING_PASSWORD`** — the keystore/key password (alias `tunnellock`), read by
  `app/build.gradle.kts` from the environment.
- **`RELEASE_CERT_SHA256`** (env in `build.yml`) — the pinned certificate digest.
  The "Check APK signatures" step fails the build if the APK isn't signed with
  exactly this certificate.

**The same key must always be used.** The app pins the signing certificate, so a
release signed with a different key cannot update an installed build — the user
would have to uninstall, which **deletes the secure space and every app in it**.
Never rotate the key.

Version is derived from CI: `versionCode = GITHUB_RUN_NUMBER`,
`versionName = "0.1.$buildNumber"`.

### Cutting a release

1. On `dev`, update `release/notes.json` with a **new `id`**, a `priority`
   (`optional` | `recommended` | `important` | `critical`), and `fa` + `en` text.
   If the `id` is unchanged, the build won't announce it.
2. Fast-forward merge `dev` → `main` and push. The `main` build:
   - publishes `TunnelLock.apk` and `TunnelProbe.apk` to a release tagged
     `build-<run>-<attempt>`, reachable at the stable
     `releases/latest/download/TunnelLock.apk` URL;
   - runs `announce-update.sh` to post the notes on issue #2, where the apps read
     new versions and their urgency.

## The `LOG_TOKEN` secret

- Optional. A fine-grained GitHub token scoped to **this repo's Issues only**,
  baked into official builds as `BuildConfig.LOG_TOKEN` so the "send logs" feature
  (TunnelLock Settings › Logs, and TunnelProbe automatically) can post to issue #1.
- **Community builds have no token** (`LOG_TOKEN` env empty → `BuildConfig.LOG_TOKEN`
  empty → `LogSender.available == false`), so the feature is simply off. The feature
  working is not required for anything else.
- Never commit secrets. `AppLog` must never record secrets or one-time tokens (for
  example the activation token inside a link opened in the secure space).

## Running the emulator e2e

- Push to the `e2e` branch, or run the **Emulator test** workflow via
  `workflow_dispatch` (e.g. `gh workflow run "Emulator test" --ref <branch>`), which
  uses that ref's `e2e/run.sh`.
- `e2e/run.sh` creates the work profile, installs both APKs, runs the probe inside
  the space, and drives numbered scenarios: V2Ray connect/online; a 5 s server stall
  (shorter than the 15 s check); server dies and returns; TunnelLock killed (Android
  lockdown must still block); manual disconnect; an app on its own server B (traffic
  must never use server A); SSH in TLS / over WebSocket / plain, with down / wrong
  password / changed host key; OpenVPN connect/online, an app with its own V2Ray
  server while the main is OpenVPN (cut off), OpenVPN dies and returns, OpenVPN
  filtered mid-handshake (clear message), wrong OpenVPN password; device-wide fake
  location; and opening a link in the secure space.
- **Reading results.** Each check calls `pass`/`fail`; the run ends with
  `::notice title=Passed (N)::` and, on failure, `::error title=Failed (M)::` plus
  trimmed TunnelLock/TunnelProbe log tails. The job fails iff any check failed. The
  core invariant: when the VPN is down **no** request may leave; when up, requests
  must traverse the test server. Some windows are timing-sensitive — a "too few
  samples" failure is a harness timing issue, not an app bug.

## Conventions

- **Module boundaries.** Put anything testable without Android VPN APIs in `core/`
  (it has unit tests under `core/src/test`); keep Android/VPN specifics in `app/`.
  `probe/` is independent of `app/`.
- **Language/UI.** Kotlin + coroutines; UI is Jetpack Compose, Material 3, with
  English (`values-en`) and Persian (default `values`) strings — add every
  user-facing string to both. Format coordinates and other machine values with
  `Locale.US` so Persian digits/separators don't leak into parseable text.
- **Features default OFF.** New opt-in behaviours (pause-when-disconnected, mock
  location, precise probe, etc.) ship disabled by default.
- **Secrets & logs.** No credentials, passwords, tokens, or model identifiers in
  commits, code, or anything pushed to the repo; keep them in CI secrets.
