# TunnelLock

اپ اندروید **بدون نیاز به روت** که اپ‌های انتخابی شما را فقط و فقط از طریق VPN (V2Ray / Xray) به اینترنت وصل می‌کند.
اگر VPN حتی یک لحظه قطع شود، اینترنت آن اپ‌ها قطع است؛ اپ بسته نمی‌شود و با برگشتن اتصال دوباره کار می‌کند.

## چطور کار می‌کند (حالت سخت‌گیرانه)

- TunnelLock یک **فضای امن** (Work Profile اندروید) می‌سازد و مالک آن می‌شود.
- اپ‌هایی که باید محافظت شوند داخل این فضا کپی می‌شوند.
- برای این فضا **Always-on VPN + Lockdown** فعال می‌شود. یعنی خود اندروید تضمین می‌کند اپ‌های داخل آن جز از طریق VPN ما هیچ راهی به اینترنت ندارند، حتی اگر TunnelLock کرش کند.
- تونل (TUN) همیشه برقرار است. اگر سرور قطع شود، بسته‌ها داخل تونل دور ریخته می‌شوند و هیچ مسیر مستقیمی در کانفیگ Xray وجود ندارد.
- قطع شدن اتصال وسط کار هیچ‌وقت اپ را نمی‌بندد؛ فقط اینترنتش قطع می‌شود.
- وقتی خودتان VPN را قطع می‌کنید (یا گوشی تازه روشن شده و هنوز وصل نیست)، اپ‌ها با `setPackagesSuspended` متوقف می‌شوند و باز نمی‌شوند. این رفتار در تنظیمات قابل خاموش کردن است.
- اپ‌های معمولی گوشی (بیرون از فضای امن) هیچ تغییری نمی‌کنند.

## نصب

1. آخرین نسخه را مستقیم در مرورگر گوشی (با حساب GitHub واردشده) باز و نصب کنید:
   - TunnelLock: https://github.com/origamigraph-del/TunnelLock/releases/latest/download/TunnelLock.apk
   - TunnelProbe: https://github.com/origamigraph-del/TunnelLock/releases/latest/download/TunnelProbe.apk

   این لینک‌ها همیشه جدیدترین نسخه را می‌دهند. برای آپدیت هم همین لینک را باز کنید.
2. اپ را باز کنید و «ساخت فضای امن» را بزنید.
3. TunnelLock داخل فضای امن (آیکون با نشان کیف) باز می‌شود:
   - **سرورها**: لینک `vless://`، `vmess://`، `trojan://` یا `ss://` را کپی کنید و «از کلیپ‌بورد» را بزنید. لینک اشتراک، QR و کانفیگ JSON کامل Xray هم پشتیبانی می‌شود.
   - **اتصال**: دکمه بزرگ را بزنید.
4. **افزودن اپ‌ها**: TunnelLock **بیرونی** (بدون نشان کیف) را باز کنید، اپ‌ها را تیک بزنید و «افزودن به فضای امن» را بزنید. اندروید برای هر اپ تأیید نصب می‌خواهد. (فقط نسخهٔ بیرونی می‌تواند اپ‌های گوشی را ببیند.)

## تست نشت با TunnelProbe

در هر Release یک `TunnelProbe.apk` هم هست: اپ کوچکی برای اینکه خودتان ببینید ترافیک واقعاً قطع می‌شود یا نه.

1. TunnelProbe را نصب کنید و با TunnelLock بیرونی به فضای امن اضافه کنید.
2. نسخهٔ داخل فضای امن را باز کنید، VPN را وصل کنید و «شروع تست» را بزنید. هر چند ثانیه چهار مسیر جدا را امتحان می‌کند: HTTPS با IP، HTTPS با DNS، ipify (IPv4/IPv6) و UDP (STUN).
3. سرور را قطع کنید، شبکه را عوض کنید یا حالت هواپیما بزنید. باید فقط «مسدود» ببینید. «نشت» یعنی ترافیک با IP ایران (یا IP واقعی‌ای که وارد کرده‌اید) خارج شده است.
4. «کپی گزارش» را بزنید و بفرستید.

> نسخه‌های بعدی را روی همین نسخه نصب کنید (آپدیت). **TunnelLock را حذف نکنید**؛ حذف آن فضای امن و اپ‌های داخلش را هم پاک می‌کند.

## ارسال مستقیم گزارش‌ها

TunnelLock (تنظیمات › گزارش‌ها › ارسال) و TunnelProbe (بعد از هر تست، خودکار) گزارش را به‌صورت کامنت در [issue #1](https://github.com/origamigraph-del/TunnelLock/issues/1) همین ریپو می‌فرستند. برای فعال شدن، سکرت `LOG_TOKEN` لازم است: یک fine-grained token که فقط به همین ریپو و فقط مجوز **Issues: Read and write** دارد.

## ساختار کد

| مسیر | توضیح |
|---|---|
| `core/` | Kotlin خالص: پارسر لینک‌ها و سازندهٔ کانفیگ Xray (تست‌شده) |
| `app/.../vpn/` | `TunnelVpnService` (تونل + kill switch)، wrapper هستهٔ Xray |
| `app/.../strict/` | فضای امن: provisioning، lockdown، تعلیق و کپی اپ‌ها |
| `app/.../ui/` | رابط کاربری (Jetpack Compose، فارسی/انگلیسی) |
| `probe/` | TunnelProbe: اپ تست نشت |
| `docs/PLAN.md` | پلن کامل پروژه |

هستهٔ Xray از [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) گرفته می‌شود (همان هستهٔ v2rayNG). CI آن را با checksum ثابت دانلود می‌کند.

## Build

Builds run on GitHub Actions (`.github/workflows/build.yml`): every push to `main` runs the core tests, builds a signed release APK and publishes it as a pre-release.

Locally: put `libv2ray.aar` (see the workflow for version and SHA-256) in `app/libs/`, then `./gradlew :core:test :app:assembleRelease`.

**Signing:** `signing/release.jks` is the release key, encrypted with a password that lives only in the `SIGNING_PASSWORD` repository secret (Settings › Secrets and variables › Actions). Every build must use this key so it can update the installed app in place; a new key would mean uninstalling, which deletes the secure space. Without the secret, CI still builds (debug-signed) but does not publish a release.

## License note

Xray-core is MPL-2.0. If OpenVPN support is added later (GPLv2), public distribution will require publishing the source under the GPL.
