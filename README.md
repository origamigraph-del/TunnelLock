# TunnelLock

اپ اندروید **بدون نیاز به روت** که اپ‌های انتخابی شما را فقط و فقط از طریق VPN (V2Ray / Xray یا OpenVPN) به اینترنت وصل می‌کند.
اگر VPN حتی یک لحظه قطع شود، اینترنت آن اپ‌ها قطع است؛ اپ بسته نمی‌شود و با برگشتن اتصال دوباره کار می‌کند.

## چطور کار می‌کند (حالت سخت‌گیرانه)

- TunnelLock یک **فضای امن** (Work Profile اندروید) می‌سازد و مالک آن می‌شود.
- اپ‌هایی که باید محافظت شوند داخل این فضا کپی می‌شوند.
- برای این فضا **Always-on VPN + Lockdown** فعال می‌شود. یعنی خود اندروید تضمین می‌کند اپ‌های داخل آن جز از طریق VPN ما هیچ راهی به اینترنت ندارند، حتی اگر TunnelLock کرش کند.
- تونل (TUN) همیشه برقرار است. اگر سرور قطع شود، بسته‌ها داخل تونل دور ریخته می‌شوند و هیچ مسیر مستقیمی در کانفیگ Xray وجود ندارد.
- قطع شدن اتصال وسط کار هیچ‌وقت اپ را نمی‌بندد؛ فقط اینترنتش قطع می‌شود.
- وقتی قطع هستید، رابط VPN بسته می‌شود (علامت VPN در نوار بالا نمی‌ماند) و خود اندروید با lockdown همه‌چیز را مسدود می‌کند.
- اگر اپی هم داخل و هم بیرون فضای امن نصب باشد، TunnelLock بیرونی هشدار می‌دهد: نسخهٔ بیرونی محافظت ندارد و با یک دکمه حذف می‌شود.
- لاگ برنامه هر ۱۵ دقیقه وضعیت باتری را ثبت می‌کند (درصد، جریان، دما، CPU خود برنامه و OpenVPN، حجم داده، روشن/خاموش بودن صفحه) تا مصرف باتری بهینه شود.
- وقتی خودتان VPN را قطع می‌کنید (یا گوشی تازه روشن شده و هنوز وصل نیست)، اپ‌ها با `setPackagesSuspended` متوقف می‌شوند و باز نمی‌شوند. این رفتار در تنظیمات قابل خاموش کردن است.
- اپ‌های معمولی گوشی (بیرون از فضای امن) هیچ تغییری نمی‌کنند.

### OpenVPN

- در بخش سرورها «فایل OpenVPN» را بزنید و فایل `.ovpn` را باز کنید (یا متن آن را کپی و «از کلیپ‌بورد» را بزنید).
- اگر سرور نام کاربری و رمز بخواهد، همان موقع پرسیده می‌شود و فقط داخل فضای امن ذخیره می‌شود. بعداً با دکمهٔ ✎ کنار سرور قابل ویرایش است.
- گواهی‌ها و کلیدها باید داخل خود فایل باشند (inline). حالت TAP پشتیبانی نمی‌شود.
- kill switch همان است: تا OpenVPN واقعاً وصل نشده، تونل خود TunnelLock همه‌چیز را مسدود نگه می‌دارد، و تونل OpenVPN هم کل IPv4 و IPv6 را می‌گیرد.

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

**حالت دقیق** (اختیاری، پیش‌فرض خاموش): کنار تست‌های بالا، هر ثانیه یک پینگ کوچک UDP (STUN) از داخل تونل می‌فرستد. هر وقت بیش از ۲٫۵ ثانیه جوابی نیاید، در گزارش یک خط `~~ BREAK` با ساعت شروع و پایان قطعی ثبت می‌شود؛ تک‌پینگ‌های گم‌شده، کندترین جواب و تعداد قطعی‌ها هر ۱۵ دقیقه خلاصه می‌شوند. اگر خود اندروید TunnelProbe را خوابانده باشد، همان خط این را می‌گوید تا با قطعی شبکه اشتباه نشود. برای تست یک‌روزه با فاصلهٔ ۳۰ یا ۶۰ ثانیه روشنش کنید.

> نسخه‌های بعدی را روی همین نسخه نصب کنید (آپدیت). **TunnelLock را حذف نکنید**؛ حذف آن فضای امن و اپ‌های داخلش را هم پاک می‌کند.

## تست خودکار روی گوشی مجازی

هر بار که شاخهٔ `e2e` به‌روز شود، GitHub یک گوشی اندروید مجازی بالا می‌آورد (`.github/workflows/e2e.yml` و `e2e/`):
- کنار گوشی یک سرور واقعی V2Ray (Xray) و یک سرور واقعی OpenVPN با نام کاربری و رمز اجرا می‌شود.
- فضای امن با TunnelLock ساخته می‌شود و TunnelProbe داخل آن هر چند ثانیه اینترنت را امتحان می‌کند.
- سناریوها: اتصال با هر دو پروتکل، یخ زدن ۵ ثانیه‌ای سرور (حالت دقیق باید یک قطعی حدود ۵ ثانیه ثبت کند)، خاموش شدن سرور وسط کار، کشته شدن خود TunnelLock، قطع دستی، رمز اشتباه OpenVPN.
- در هر حالتی که VPN کار نمی‌کند، حتی یک درخواست هم نباید بیرون برود؛ وقتی وصل است، درخواست‌ها باید از سرور تست عبور کنند.

## ارسال مستقیم گزارش‌ها

TunnelLock (تنظیمات › گزارش‌ها › ارسال) و TunnelProbe (بعد از هر تست، خودکار) گزارش را به‌صورت کامنت در [issue #1](https://github.com/origamigraph-del/TunnelLock/issues/1) همین ریپو می‌فرستند.
هر دو گزارش یک روز کامل را نگه می‌دارند. اگر گزارش برای یک کامنت بزرگ باشد، اولین کامنت آخرین خطوط را خوانا نشان می‌دهد و کل گزارش در چند کامنت فشرده (gzip + base64) می‌آید: همهٔ بخش‌ها را به ترتیب کنار هم بگذارید و `base64 -d | gunzip` بزنید. برای فعال شدن، سکرت `LOG_TOKEN` لازم است: یک fine-grained token که فقط به همین ریپو و فقط مجوز **Issues: Read and write** دارد.

## ساختار کد

| مسیر | توضیح |
|---|---|
| `core/` | Kotlin خالص: پارسر لینک‌ها و سازندهٔ کانفیگ Xray (تست‌شده) |
| `app/.../vpn/` | `TunnelVpnService` (تونل + kill switch)، wrapper هستهٔ Xray، `OpenVpnEngine` |
| `native/` | ساخت OpenVPN برای اندروید از سورس‌های قفل‌شده (فقط در CI) |
| `app/.../strict/` | فضای امن: provisioning، lockdown، تعلیق و کپی اپ‌ها |
| `app/.../ui/` | رابط کاربری (Jetpack Compose، فارسی/انگلیسی) |
| `probe/` | TunnelProbe: اپ تست نشت |
| `docs/PLAN.md` | پلن کامل پروژه |

هستهٔ Xray از [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) گرفته می‌شود (همان هستهٔ v2rayNG). CI آن را با checksum ثابت دانلود می‌کند.
OpenVPN 2.6 (با OpenSSL، LZO و LZ4) را CI از سورس‌هایی با commit ثابت می‌سازد (`native/build-openvpn.sh`) و به‌صورت `libovpnexec.so` داخل APK می‌گذارد.

## Build

Builds run on GitHub Actions (`.github/workflows/build.yml`): every push to `main` runs the core tests, builds a signed release APK and publishes it as a pre-release.

Locally: put `libv2ray.aar` (see the workflow for version and SHA-256) in `app/libs/`, build OpenVPN with `native/build-openvpn.sh <android-ndk> app/src/main/jniLibs`, then `./gradlew :core:test :app:assembleRelease`.

**Signing:** `signing/release.jks` is the release key, encrypted with a password that lives only in the `SIGNING_PASSWORD` repository secret (Settings › Secrets and variables › Actions). Every build must use this key so it can update the installed app in place; a new key would mean uninstalling, which deletes the secure space. Without the secret, CI still builds (debug-signed) but does not publish a release.

## License note

Xray-core is MPL-2.0. OpenVPN is GPLv2 (with the OpenSSL exception); it runs as a separate program that the app talks to over its management socket. Distributing the APK publicly requires offering OpenVPN's source (the pinned upstream release) under the GPL.
