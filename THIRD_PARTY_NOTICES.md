# Third-party notices

TunnelLock is licensed under the GNU GPL v3 (see `LICENSE`). It uses and/or
distributes the third-party components below, each under its own license.
Full license texts are available from the upstream projects.

## Bundled at runtime (shipped inside the app)

| Component | Used for | License | Source |
|-----------|----------|---------|--------|
| **Xray-core** (via [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), `libv2ray.aar`) | VLESS/VMess/Trojan/Shadowsocks transport | MPL-2.0 | Prebuilt `.aar` pinned in `.github/workflows/build.yml` (`XRAY_LIB_VERSION` + SHA-256). |
| **OpenVPN** | OpenVPN engine (native binary) | GPL-2.0 (with the OpenSSL linking exception) | Built from pinned upstream source by `native/build-openvpn.sh`. The exact version and build steps are in that script; the corresponding source is therefore available to every recipient, as GPL-2.0 requires. |
| **JSch** ([mwiede fork](https://github.com/mwiede/jsch)) | SSH transport | BSD-3-Clause-style (JSch license) | Maven Central. |
| **Bouncy Castle** (`bcprov-jdk18on`) | Ed25519 / Curve25519 / ChaCha20 for SSH | Bouncy Castle License (MIT-style) | Maven Central. |
| **osmdroid** (`osmdroid-android`) | OpenStreetMap map for the fake-location picker | Apache-2.0 | Maven Central. |
| **ZXing Android Embedded** | QR scanning of server links | Apache-2.0 | Maven Central. |
| **AndroidX, Jetpack Compose, Material 3, Kotlin, kotlinx.coroutines** | App framework / UI | Apache-2.0 | Google / JetBrains Maven. |

## Map data

The map in the fake-location picker shows tiles from **OpenStreetMap**.
Map data © OpenStreetMap contributors, available under the Open Database
License (ODbL). Tiles are fetched only while the picker is open and are
cached in the app's internal storage.

## Notes

- The OpenVPN binary runs as a **separate process** that TunnelLock invokes;
  it is aggregated with, not linked into, the app. Its GPL-2.0 source is
  reproducible from `native/build-openvpn.sh`.
- Official release APKs embed a GitHub token scoped **only** to opening issues
  on this repository (used by the optional "send logs to the developer"
  feature). Community builds have no token, so that feature is simply off.
  See `docs/DEVELOPING.md`.
