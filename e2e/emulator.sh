#!/usr/bin/env bash
# Starts an Android emulator for the end-to-end test and waits until it has
# booted. If it does not boot, the end of the emulator's own output is shown as
# an annotation (CI logs are not always at hand).
set -uo pipefail

API=${API:-30}
IMAGE="system-images;android-$API;google_apis;x86_64"
SDK=$ANDROID_HOME
OUT=$(cd "$(dirname "$0")" && pwd)/out
mkdir -p "$OUT"
# avdmanager and the emulator must agree on where the virtual device lives.
export ANDROID_AVD_HOME=$HOME/.android/avd
mkdir -p "$ANDROID_AVD_HOME"

annotate() { sed -e 's/%/%25/g' | cut -c1-220 | sed ':a;N;$!ba;s/\n/%0A/g'; }

# "yes" is cut off when sdkmanager is done, so the licence step's status says nothing;
# whether the image is there afterwards is what counts.
{ yes || true; } | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install "$IMAGE" emulator platform-tools >"$OUT/sdk.log" 2>&1
if [ ! -d "$SDK/system-images/android-$API/google_apis/x86_64" ]; then
    echo "::error title=Emulator image::$(tr '\r' '\n' <"$OUT/sdk.log" | grep -v '^\s*$' | grep -v 'Loading\|\[=*' | tail -8 | annotate)"
    exit 1
fi
echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n e2e -k "$IMAGE" -d pixel_5 --force >"$OUT/avd.log" 2>&1
if [ ! -f "$ANDROID_AVD_HOME/e2e.avd/config.ini" ]; then
    echo "::error title=AVD::not created in $ANDROID_AVD_HOME: $(tail -5 "$OUT/avd.log" | annotate) | found: $(find "$HOME" -maxdepth 4 -name 'e2e.ini' 2>/dev/null | head -3)"
    exit 1
fi
# A bigger data partition: two users and two apps with native libraries.
printf 'disk.dataPartition.size=4G\nhw.ramSize=3072\nhw.cpu.ncore=2\n' >>"$ANDROID_AVD_HOME/e2e.avd/config.ini"

nohup "$SDK/emulator/emulator" -avd e2e -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -camera-back none -camera-front none -accel on \
    >"$OUT/emulator.log" 2>&1 &
echo $! >"$OUT/emulator.pid"

"$SDK/platform-tools/adb" start-server >/dev/null 2>&1
start=$SECONDS
while [ $((SECONDS - start)) -lt 900 ]; do
    if ! kill -0 "$(cat "$OUT/emulator.pid")" 2>/dev/null; then
        echo "::error title=Emulator exited::$(tail -30 "$OUT/emulator.log" | annotate)"
        exit 1
    fi
    if [ "$("$SDK/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        echo "::notice title=Emulator::Android $API booted in $((SECONDS - start)) s"
        "$SDK/platform-tools/adb" shell settings put global window_animation_scale 0
        "$SDK/platform-tools/adb" shell settings put global transition_animation_scale 0
        "$SDK/platform-tools/adb" shell settings put global animator_duration_scale 0
        exit 0
    fi
    sleep 5
done
echo "::error title=Emulator did not boot in 15 min::$(tail -30 "$OUT/emulator.log" | annotate)"
exit 1
