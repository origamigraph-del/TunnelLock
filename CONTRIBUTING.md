# Contributing to TunnelLock

Thanks for your interest. TunnelLock is GPL‑v3 free software; by contributing you agree your contributions are licensed under the same terms.

## Before you start

- Read **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for how the pieces fit together and **[docs/DEVELOPING.md](docs/DEVELOPING.md)** for building, CI, and the release/signing flow.
- Open an issue to discuss anything non‑trivial before writing a lot of code.

## Building and testing

```bash
# Xray core (version + SHA-256 pinned in .github/workflows/build.yml)
mkdir -p app/libs && curl -fsSL -o app/libs/libv2ray.aar <url-from-build.yml>
# OpenVPN native binary
native/build-openvpn.sh <android-ndk> app/src/main/jniLibs
# Core unit tests + both APKs
./gradlew :core:test :app:assembleRelease :probe:assembleRelease
```

The emulator end‑to‑end suite lives in `e2e/` and runs via the **Emulator test** workflow (push to the `e2e` branch or run it manually). See `docs/DEVELOPING.md`.

## Pull requests

- Keep changes focused; one logical change per PR.
- **New or changed behaviour must not weaken the kill‑switch.** When the VPN is down, no packet from a protected app may leave the device. If you touch routing, the tunnel, or the secure‑space policy, add or update an `e2e/` scenario.
- New user‑facing features default **off**.
- Don't add dependencies or permissions without explaining why in the PR.
- Match the existing Kotlin/Compose style. UI strings go in both `values/` (Persian) and `values-en/` (English).

## Things that must never happen

- **Never commit secrets** — the signing keystore, passwords, or tokens. CI gets the keystore from the `KEYSTORE_BASE64` secret and the password from `SIGNING_PASSWORD`.
- **Logs must never contain secrets** — no passwords, private keys, or one‑time activation tokens in `AppLog`.
- **Don't change the signing key.** The app pins the release certificate; a new key breaks in‑place updates (which would force an uninstall and delete the secure space).
