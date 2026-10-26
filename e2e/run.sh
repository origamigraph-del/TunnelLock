#!/usr/bin/env bash
# Emulator test of TunnelLock's promise: protected apps reach the internet only
# through the VPN, and get nothing at all whenever the VPN is not working.
#
# Runs on the CI machine with the emulator booted and e2e/servers.sh running.
# TunnelProbe runs inside the secure space and records every few seconds whether
# its requests got out; the checks below read its log around each event.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/e2e/out
APP=app.tunnellock
PROBE=app.tunnellock.probe
OVPN_PASSWORD=${OVPN_PASSWORD:-e2e-secret-7}
FAILED=0
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}

pass() { echo "::notice title=PASS $1::$2"; echo "- ✅ **$1**: $2" >>"$SUMMARY"; }
fail() { echo "::error title=FAIL $1::$2"; echo "- ❌ **$1**: $2" >>"$SUMMARY"; FAILED=1; }
oneline() { sed -e 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g'; }
sh_() { adb shell "$@" | tr -d '\r'; }

# ---- set up the secure space from the shell -------------------------------------

adb root >/dev/null
adb wait-for-device
sleep 3
adb install -r -g "$ROOT"/app/build/outputs/apk/release/*.apk >/dev/null
adb install -r -g "$ROOT"/probe/build/outputs/apk/release/*.apk >/dev/null

U=$(sh_ pm create-user --profileOf 0 --managed TunnelLockTest | grep -o '[0-9]*$')
if [ -z "$U" ]; then
    fail "Secure space" "could not create a work profile on the emulator"
    exit 1
fi
sh_ am start-user -w "$U" >/dev/null
sh_ pm install-existing --user "$U" $APP >/dev/null
sh_ pm install-existing --user "$U" $PROBE >/dev/null
owner_result=$(sh_ dpm set-profile-owner --user "$U" $APP/.strict.AdminReceiver)
case "$owner_result" in
    *Success*) pass "Secure space" "work profile $U created, TunnelLock is its owner" ;;
    *) fail "Secure space" "set-profile-owner: $owner_result"; exit 1 ;;
esac
sh_ appops set --user "$U" $APP ACTIVATE_VPN allow >/dev/null
sh_ pm grant --user "$U" $PROBE android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

DATA=/data/user/$U/$APP
PROBE_LOG=/data/user/$U/$PROBE/files/probe.log
APP_LOG=$DATA/files/app.log

applog() { sh_ cat "$APP_LOG" 2>/dev/null; }
probelog() { sh_ cat "$PROBE_LOG" 2>/dev/null; }
mark() { probelog | wc -l; }
applines() { applog | wc -l; }

# Writes the server list and the selection, then starts TunnelLock (it connects on its own).
start_with() { # <xray|ovpn> [password]
    sh_ am force-stop --user "$U" $APP
    python3 "$ROOT/e2e/prefs.py" "$OUT/xray.link" "$OUT/client.ovpn" "$1" "${2:-$OVPN_PASSWORD}" >"$OUT/tunnellock.xml"
    adb push "$OUT/tunnellock.xml" /data/local/tmp/tunnellock.xml >/dev/null
    local uid ctx
    uid=$(sh_ stat -c %u "$DATA")
    ctx=$(sh_ ls -Zd "$DATA" | awk '{print $1}')
    sh_ "mkdir -p $DATA/shared_prefs && cp /data/local/tmp/tunnellock.xml $DATA/shared_prefs/tunnellock.xml \
        && chown $uid:$uid $DATA/shared_prefs $DATA/shared_prefs/tunnellock.xml \
        && chmod 771 $DATA/shared_prefs && chmod 660 $DATA/shared_prefs/tunnellock.xml \
        && chcon $ctx $DATA/shared_prefs $DATA/shared_prefs/tunnellock.xml"
    sh_ am start --user "$U" -n $APP/.ui.MainActivity >/dev/null
}

# Waits until TunnelLock's log shows "-> PHASE" after line <from>.
wait_phase() { # <phase> <from line> <seconds>
    local end=$((SECONDS + $3))
    while [ $SECONDS -lt $end ]; do
        applog | tail -n +"$(($2 + 1))" | grep -q -- "-> $1" && return 0
        sleep 3
    done
    return 1
}

# Probe verdicts (OK, BLOCKED, LEAK!!, NEW-IP, DIRECT) after line <from>, skipping <skip> rounds.
verdicts() { # <from> <skip>
    probelog | tail -n +"$(($1 + 1))" | awk '$2 != "==" && $2 != "--" {print $2}' | tail -n +"$(($2 + 1))"
}

check_blocked() { # <name> <from>
    local v total bad
    v=$(verdicts "$2" 2)
    total=$(echo "$v" | grep -c . || true)
    bad=$(echo "$v" | grep -vc '^BLOCKED$' || true)
    if [ "$total" -ge 4 ] && [ "$bad" -eq 0 ]; then
        pass "$1" "all $total probe rounds were BLOCKED"
    else
        fail "$1" "$bad of $total probe rounds got out (expected all BLOCKED): $(echo "$v" | sort | uniq -c | tr '\n' ' ')"
    fi
}

# Like check_blocked, but only for the probe rounds before TunnelLock logged "-> CONNECTED"
# after line <app from> (Android may restart a killed always-on VPN by itself).
check_blocked_until_connected() { # <name> <probe from> <app from>
    local until v total bad
    until=$(applog | tail -n +"$(($3 + 1))" | grep -m1 -- "-> CONNECTED" | awk '{print $2}')
    v=$(probelog | tail -n +"$(($2 + 1))" | awk -v until="$until" '$2 != "==" && $2 != "--" && (until == "" || $1 < until) {print $2}' | tail -n +3)
    total=$(echo "$v" | grep -c . || true)
    bad=$(echo "$v" | grep -vc '^BLOCKED$' || true)
    if [ "$total" -ge 2 ] && [ "$bad" -eq 0 ]; then
        pass "$1" "all $total probe rounds were BLOCKED${until:+ until TunnelLock came back at $until}"
    else
        fail "$1" "$bad of $total probe rounds got out (expected all BLOCKED): $(echo "$v" | sort | uniq -c | tr '\n' ' ')"
    fi
}

check_online() { # <name> <from>
    local v total ok leaks
    v=$(verdicts "$2" 1)
    total=$(echo "$v" | grep -c . || true)
    ok=$(echo "$v" | grep -cE '^(OK|NEW-IP)$' || true)
    leaks=$(echo "$v" | grep -cE '^(LEAK!!|DIRECT)$' || true)
    if [ "$ok" -ge 3 ] && [ "$leaks" -eq 0 ]; then
        pass "$1" "$ok of $total probe rounds reached the internet through the tunnel"
    else
        fail "$1" "only $ok of $total rounds online, $leaks leaks: $(echo "$v" | sort | uniq -c | tr '\n' ' ')"
    fi
}

# ---- 1. V2Ray ---------------------------------------------------------------------

from=$(applines)
start_with xray
sh_ am start-foreground-service --user "$U" -n $PROBE/.ProbeService >/dev/null
if wait_phase CONNECTED "$from" 120; then pass "V2Ray connect" "TunnelLock connected through the test Xray server"; else fail "V2Ray connect" "no CONNECTED within 120 s"; fi
m=$(mark); sleep 25; check_online "V2Ray online" "$m"
if grep -q "accepted" "$OUT/xray-access.log" 2>/dev/null; then
    pass "Through the server" "the Xray server logged the probe's connections"
else
    fail "Through the server" "the Xray server saw no connections"
fi

# ---- 2. server dies mid-use ----------------------------------------------------------

m=$(mark); from=$(applines)
"$ROOT/e2e/servers.sh" stop xray
sleep 50
check_blocked "V2Ray server down" "$m"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> RECONNECTING"; then pass "Drop detected" "TunnelLock went to RECONNECTING"; else fail "Drop detected" "no RECONNECTING"; fi

# ---- 3. server comes back -------------------------------------------------------------

from=$(applines)
"$ROOT/e2e/servers.sh" start xray
if wait_phase CONNECTED "$from" 120; then pass "V2Ray reconnect" "connected again by itself"; else fail "V2Ray reconnect" "no CONNECTED within 120 s"; fi
m=$(mark); sleep 20; check_online "V2Ray online again" "$m"

# ---- 4. TunnelLock killed: Android's lockdown must block on its own ------------------

m=$(mark); from=$(applines)
sh_ am force-stop --user "$U" $APP
sleep 30
check_blocked_until_connected "TunnelLock killed" "$m" "$from"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> CONNECTED"; then
    pass "Restart" "Android restarted TunnelLock and it reconnected by itself"
else
    from=$(applines)
    sh_ am start --user "$U" -n $APP/.ui.MainActivity >/dev/null
    if wait_phase CONNECTED "$from" 120; then pass "Restart" "reconnected after being opened again"; else fail "Restart" "no CONNECTED within 120 s"; fi
fi

# ---- 5. manual disconnect -------------------------------------------------------------

m=$(mark); from=$(applines)
sh_ am start-foreground-service --user "$U" -n $APP/.vpn.TunnelVpnService -a app.tunnellock.action.DISCONNECT >/dev/null
sleep 30
check_blocked "Disconnected" "$m"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> GUARDING"; then pass "Disconnect" "TunnelLock is GUARDING"; else fail "Disconnect" "no GUARDING"; fi

# ---- 6. OpenVPN -----------------------------------------------------------------------

from=$(applines)
start_with ovpn
if wait_phase CONNECTED "$from" 150; then pass "OpenVPN connect" "connected with username and password"; else fail "OpenVPN connect" "no CONNECTED within 150 s"; fi
m=$(mark); sleep 25; check_online "OpenVPN online" "$m"
if sudo grep -q "e2e" "$OUT/openvpn-status.log" 2>/dev/null; then pass "OpenVPN server" "the server lists the emulator as a client"; else fail "OpenVPN server" "the client is not in the server's status"; fi

# ---- 7. OpenVPN server dies mid-use and comes back ------------------------------------

m=$(mark)
"$ROOT/e2e/servers.sh" stop openvpn
sleep 50
check_blocked "OpenVPN server down" "$m"
from=$(applines)
"$ROOT/e2e/servers.sh" start openvpn
if wait_phase CONNECTED "$from" 180; then pass "OpenVPN reconnect" "connected again by itself"; else fail "OpenVPN reconnect" "no CONNECTED within 180 s"; fi
m=$(mark); sleep 20; check_online "OpenVPN online again" "$m"

# ---- 8. wrong OpenVPN password: a clear error, no endless retries ----------------------

m=$(mark); from=$(applines)
start_with ovpn wrong-password
if wait_phase GUARDING "$from" 120 && applog | tail -n +"$((from + 1))" | grep -q "rejected the login"; then
    pass "Wrong password" "stopped with the login error"
else
    fail "Wrong password" "no login error within 120 s"
fi
sleep 15
check_blocked "Wrong password blocks" "$m"

# ---- report ---------------------------------------------------------------------------

if adb logcat -d -b crash | grep -q "$APP"; then
    fail "No crashes" "$(adb logcat -d -b crash | grep -A 12 "$APP" | head -30 | oneline)"
else
    pass "No crashes" "no crash of TunnelLock or TunnelProbe"
fi
echo "::notice title=TunnelLock log (end)::$(applog | tail -70 | cut -c1-200 | oneline)"
echo "::notice title=TunnelProbe log (end)::$(probelog | tail -40 | cut -c1-160 | oneline)"
mkdir -p "$OUT/logs"
applog >"$OUT/logs/tunnellock.log"
probelog >"$OUT/logs/probe.log"
adb logcat -d >"$OUT/logs/logcat.txt"
exit $FAILED
