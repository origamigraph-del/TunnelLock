# TunnelLock — User guide

A step‑by‑step guide to installing and using TunnelLock. Persian: [USER_GUIDE.fa.md](USER_GUIDE.fa.md).

## 1. Install

1. On your phone, open the APK link (signed in to GitHub) and install:
   - TunnelLock: <https://github.com/origamigraph-del/TunnelLock/releases/latest/download/TunnelLock.apk>
2. **Google Play Protect** will warn that the app "could be harmful." This is expected for a sideloaded app that creates a work profile and runs a VPN — it is a heuristic warning, not a malware detection. Tap **Install anyway → Install**. (See "Permissions & Play Protect" in the README for why each permission is needed.)
3. Use the same link later to **update** — install over the top; do not uninstall.

> ⚠️ **Never uninstall TunnelLock to turn it off.** Uninstalling deletes the secure space and every app inside it. To stop protection, just disconnect.

## 2. Create the secure space

1. Open TunnelLock and tap **Create secure space**. Android sets up a Work Profile that TunnelLock owns.
2. When it finishes you'll have a second, **briefcase‑badged** TunnelLock icon — that is the app *inside* the secure space (the real VPN client). The unbadged icon is the *normal* app, used to pick apps and to set the fake location.

## 3. Add a server

Open the **briefcase‑badged** TunnelLock → **Servers**.

- **V2Ray / Xray:** copy a `vless://`, `vmess://`, `trojan://` or `ss://` link and tap **From clipboard**. Subscription links, QR codes, and a full Xray JSON config are also supported.
- **OpenVPN:** tap **OpenVPN file** and open your `.ovpn` (or paste its text). Certificates/keys must be inline; TAP mode isn't supported. If the server needs a username/password you'll be asked; it's stored only inside the secure space.
- **SSH:** tap **SSH server** and enter host, port, username, and password and/or private key — or paste an `ssh://user:pass@host:port?transport=...#name` link. Transport can be direct, TLS/SSL, WebSocket, or WebSocket+TLS (for servers behind a CDN). The server's host key is remembered on first connect (trust‑on‑first‑use).

## 4. Connect

Tap the big **Connect** button. The tunnel comes up and your protected apps go online through it. If it can't connect, the screen explains why (e.g. the server isn't answering, or the connection stalled — often the ISP filtering it).

## 5. Add apps to protect

1. Open the **normal** (unbadged) TunnelLock.
2. Tick the apps you want protected and tap **Add to the secure space**. Android asks you to confirm each install.
3. The protected copy lives inside the secure space. If an app exists both inside and outside, the normal TunnelLock warns you: the outside copy is unprotected and can be removed with one tap.

> Only the normal copy can see your installed apps (that's what `QUERY_ALL_PACKAGES` is for).

## 6. Per‑app servers (Proxifier‑style)

In the Apps section, tap an app and choose **This app's server**. Default for all apps is the main server.

- The app then reaches the internet (and its DNS) **only** through its own server. If that server is down, the app has no internet and never falls back to the main server.
- Each app's server must be a V2Ray link (not OpenVPN or a full JSON config), and this needs Android 10+.

## 7. Fake GPS (whole phone)

Android only lets a **normal‑profile** app be the "mock location app," so the fake location runs from the **normal** TunnelLock and changes the whole phone's location — the secure‑space apps included.

1. In the normal TunnelLock, open **Fake location (whole phone)**.
2. Set the point: type coordinates (e.g. `35.6892, 51.3890`), paste a Google Maps link, or tap **Pick on map**, move/zoom the OpenStreetMap map so the center crosshair is on the spot, and tap **Use this point**.
3. Tap **Turn on**.
4. The first time, Android asks you to pick the mock‑location app: **Developer options → Select mock location app → TunnelLock**. (Enable Developer options via Settings → About phone → tap *Build number* several times.) Make sure the phone's **Location** is on.
5. GPS, network, and fused providers are faked (not passive — it follows the others automatically), so the real position doesn't leak.

Inside the secure space, the location setting is only **Real** or **No location** (block). To verify what apps actually read, run TunnelProbe inside the secure space and watch the `-- location:` line in its report.

## 8. Open an activation link in the secure space

When an app is both inside and outside, an email activation link opens the **normal** copy (wrong IP/location). Instead of tapping the link:

- Paste it into the normal TunnelLock's **Open a link in the secure space**, or
- Use Android's share menu → **Open in the secure space**.

TunnelLock forwards it so the *protected* copy opens it. On phones where Android's cross‑profile forwarder doesn't work (e.g. Samsung), it hands the link over via the clipboard automatically. The one‑time token inside the link is never written to the log.

## 9. Updates

- The secure‑space app checks for new versions every ~12 hours (Settings → Updates → *Check now*). The normal app checks each time you open it and shows a download button; installing in the normal profile updates both copies.
- Each release has a priority — **critical** (leak/security, daily reminder), **important** (needed for some servers, every 3 days), **recommended** (new feature, once), **optional** (settings only). If you're several versions behind, the highest priority among them applies.

## 10. Leak test with TunnelProbe

Every release includes `TunnelProbe.apk` so you can verify traffic is really blocked.

1. Install TunnelProbe and add it to the secure space with the normal TunnelLock.
2. Open the secure‑space copy, connect the VPN, and tap **Start test**. Every few seconds it tries four paths: HTTPS by IP, HTTPS by DNS, ipify (IPv4/IPv6), and UDP (STUN).
3. Drop the server, switch networks, or enable airplane mode. You should see only **BLOCKED**. "LEAK" means traffic escaped with your real IP.
4. Tap **Copy report** and send it.

**Precise mode** (optional): sends a 1‑second UDP ping from inside the tunnel and logs every gap over ~2.5 s with timestamps — good for an all‑day stability test at a 30–60 s interval.

## 11. Reliability & battery

- Grant the one‑tap **battery exemption** when asked, so Android doesn't kill the tunnel.
- With the screen off, the connection is checked once a minute (and immediately when the screen turns on); this is adjustable in Settings.
- If "Work apps" gets turned off, the normal TunnelLock warns you, and the log records how long TunnelLock wasn't running.

## 12. Troubleshooting

- **Can't connect:** read the message on the main screen. For OpenVPN it distinguishes "server not answering" from "stalled mid‑connection" (usually filtering).
- **An app has no internet:** check whether it has a per‑app server that's down; unattributable connections are dropped by design.
- **Fake location not applied:** confirm TunnelLock is selected as the mock‑location app in Developer options, the phone's Location is on, and you turned it on in the *normal* app.
- **Still stuck:** send a log — Settings → Logs → Send (and a TunnelProbe report for leaks).

## 13. Uninstalling (read first)

To stop using TunnelLock, first move any apps/data you care about out of the secure space, then remove the secure space from within the app. **Uninstalling the app deletes the secure space and everything in it.**
