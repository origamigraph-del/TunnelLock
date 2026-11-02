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
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}
PASSED=()
FAILED=()

# GitHub shows at most 10 annotations of a kind per step: results are collected
# and reported together at the end.
pass() { echo "PASS $1: $2"; PASSED+=("$1: $2"); echo "- ✅ **$1**: $2" >>"$SUMMARY"; }
fail() { echo "FAIL $1: $2"; FAILED+=("$1: $2"); echo "- ❌ **$1**: $2" >>"$SUMMARY"; }
oneline() { sed -e 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g'; }
sh_() { adb shell "$@" | tr -d '\r'; }

report() {
    if [ ${#PASSED[@]} -gt 0 ]; then
        echo "::notice title=Passed (${#PASSED[@]})::$(printf '%s\n' "${PASSED[@]}" | oneline)"
    fi
    if [ ${#FAILED[@]} -gt 0 ]; then
        echo "::error title=Failed (${#FAILED[@]})::$(printf '%s\n' "${FAILED[@]}" | oneline)"
    fi
    echo "::notice title=TunnelLock log (end)::$(applog | tail -80 | cut -c1-200 | oneline)"
    echo "::notice title=TunnelProbe log (end)::$(probelog | tail -40 | cut -c1-160 | oneline)"
    mkdir -p "$OUT/logs"
    applog >"$OUT/logs/tunnellock.log"
    probelog >"$OUT/logs/probe.log"
    adb logcat -d >"$OUT/logs/logcat.txt"
    [ ${#FAILED[@]} -eq 0 ]
    exit $?
}

# ---- set up the secure space from the shell -------------------------------------

adb root >/dev/null
adb wait-for-device
sleep 3
adb install -r -g "$ROOT"/app/build/outputs/apk/release/*.apk >/dev/null
adb install -r -g "$ROOT"/probe/build/outputs/apk/release/*.apk >/dev/null

U=$(sh_ pm create-user --profileOf 0 --managed TunnelLockTest | grep -o '[0-9]*$')
DATA=/data/user/${U:-0}/$APP
PROBE_LOG=/data/user/${U:-0}/$PROBE/files/probe.log
APP_LOG=$DATA/files/app.log

applog() { sh_ cat "$APP_LOG" 2>/dev/null; }
probelog() { sh_ cat "$PROBE_LOG" 2>/dev/null; }
mark() { probelog | wc -l; }
applines() { applog | wc -l; }

if [ -z "$U" ]; then
    fail "Secure space" "could not create a work profile on the emulator"
    report
fi
sh_ am start-user -w "$U" >/dev/null
sh_ pm install-existing --user "$U" $APP >/dev/null
sh_ pm install-existing --user "$U" $PROBE >/dev/null
owner_result=$(sh_ dpm set-profile-owner --user "$U" $APP/.strict.AdminReceiver)
case "$owner_result" in
    *Success*) pass "Secure space" "work profile $U created, TunnelLock is its owner" ;;
    *) fail "Secure space" "set-profile-owner: $owner_result"; report ;;
esac
sh_ appops set --user "$U" $APP ACTIVATE_VPN allow >/dev/null
sh_ pm grant --user "$U" $PROBE android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
# Picking the mock-location app in Developer Options grants this appop; do it directly.
sh_ appops set --user "$U" $APP android:mock_location allow >/dev/null 2>&1
sh_ pm grant --user "$U" $APP android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1

# TunnelLock's process in the secure space (not the personal copy).
app_pid() { sh_ ps -A -o PID,USER,NAME | awk -v user="u${U}_" 'index($2, user) == 1 && $3 == "app.tunnellock" {print $1}'; }

# Kills TunnelLock like a crash would (a profile owner cannot be force-stopped).
kill_app() {
    local pid
    pid=$(app_pid)
    [ -n "$pid" ] && sh_ kill -9 "$pid"
    sleep 1
}

# Writes the server list and the selection, then (re)starts TunnelLock; it connects on its own.
start_with() { # <xray|ovpn> [password] [package that gets server B as its own]
    MOCK_LOCATION="${MOCK_LOCATION:-}" python3 "$ROOT/e2e/prefs.py" "$OUT/xray.link" "$OUT/client.ovpn" "$1" "${2:-$OVPN_PASSWORD}" "$OUT/xray-b.link" ${3:+"$3"} >"$OUT/tunnellock.xml"
    adb push "$OUT/tunnellock.xml" /data/local/tmp/tunnellock.xml >/dev/null
    local uid ctx
    uid=$(sh_ stat -c %u "$DATA")
    ctx=$(sh_ ls -Zd "$DATA" | awk '{print $1}')
    sh_ "mkdir -p $DATA/shared_prefs && cp /data/local/tmp/tunnellock.xml $DATA/shared_prefs/tunnellock.xml \
        && chown $uid:$uid $DATA/shared_prefs $DATA/shared_prefs/tunnellock.xml \
        && chmod 771 $DATA/shared_prefs && chmod 660 $DATA/shared_prefs/tunnellock.xml \
        && chcon $ctx $DATA/shared_prefs $DATA/shared_prefs/tunnellock.xml"
    # A running TunnelLock still has the old list in memory.
    kill_app
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
    probelog | tail -n +"$(($1 + 1))" | awk '$2 ~ /^(OK|BLOCKED|LEAK!!|NEW-IP|DIRECT)$/ {print $2}' | tail -n +"$(($2 + 1))"
}

# A blocked round takes up to ~20 s (DNS gives up slowly), so a window sees only
# a few: the round in flight at the event is skipped, and none of the rest may get out.
check_blocked() { # <name> <from>
    local v total bad
    v=$(verdicts "$2" 1)
    total=$(echo "$v" | grep -c . || true)
    bad=$(echo "$v" | grep . | grep -vc '^BLOCKED$' || true)
    if [ "$total" -ge 1 ] && [ "$bad" -eq 0 ]; then
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
    v=$(probelog | tail -n +"$(($2 + 1))" | awk -v until="$until" '$2 ~ /^(OK|BLOCKED|LEAK!!|NEW-IP|DIRECT)$/ && (until == "" || $1 < until) {print $2}' | tail -n +2)
    total=$(echo "$v" | grep -c . || true)
    bad=$(echo "$v" | grep . | grep -vc '^BLOCKED$' || true)
    if [ "$total" -ge 1 ] && [ "$bad" -eq 0 ]; then
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

# Precise mode (a ping every second): the breaks it logged after line <from>.
breaks_after() { probelog | tail -n +"$(($1 + 1))" | grep "~~ BREAK"; }
break_seconds() { grep -oE 'no answer for [0-9]+' | head -1 | grep -oE '[0-9]+$'; }

# ---- 1. V2Ray ---------------------------------------------------------------------

from=$(applines)
start_with xray
if wait_phase CONNECTED "$from" 120; then pass "V2Ray connect" "TunnelLock connected through the test Xray server"; else fail "V2Ray connect" "no CONNECTED within 120 s"; fi

# TunnelProbe, opened the way a person would, then its test started.
sh_ am start --user "$U" -n $PROBE/.MainActivity >/dev/null
sleep 3
sh_ am start-foreground-service --user "$U" -n $PROBE/.ProbeService --ez precise true >/dev/null
sleep 15
if [ "$(verdicts 0 0 | grep -c .)" -lt 2 ]; then
    fail "TunnelProbe runs" "no probe rounds: $(probelog | tail -5 | tr '\n' ' ') | $(adb logcat -d | grep -iE 'tunnelprobe|app.tunnellock.probe|AndroidRuntime' | tail -15 | cut -c1-200 | oneline)"
    report
fi
pass "TunnelProbe runs" "probing every 3 s inside the secure space, precise mode on"
if applog | grep -q "I/Battery: battery at start"; then
    pass "Battery log" "$(applog | grep -m1 'I/Battery:' | cut -d' ' -f4- | cut -c1-160)"
else
    fail "Battery log" "no battery sample in TunnelLock's log"
fi

m=$(mark); sleep 25; check_online "V2Ray online" "$m"
if grep -q "ipify" "$OUT/xray-access.log" 2>/dev/null; then
    pass "Through the server" "the Xray server carried the probe's requests"
else
    fail "Through the server" "the probe's requests did not reach the Xray server"
fi
if first=$(probelog | grep -m1 "~~ pings answered"); then
    pass "Precise mode" "${first#* ~~ }"
else
    fail "Precise mode" "no ping answered: $(probelog | grep '~~' | tail -3 | tr '\n' ' ')"
fi
# The test machine's own network hiccups now and then (a gap of 2-3 s); only a real break fails.
long_breaks=$(breaks_after "$m" | while read -r line; do
    secs=$(echo "$line" | break_seconds)
    [ -n "$secs" ] && [ "$secs" -ge 4 ] && echo "$line"
done)
if [ -z "$long_breaks" ]; then
    pass "Precise: steady" "no break of 4 s or more while connected ($(breaks_after "$m" | grep -c . || true) shorter gaps)"
else
    fail "Precise: steady" "breaks while connected: $(echo "$long_breaks" | head -3 | cut -d' ' -f3- | tr '\n' ' ')"
fi

# The interface has TunnelLock's own address, not sing-box's default (Hiddify, NekoBox).
if sh_ ip -o addr show | grep -q "10.233.97.1/30"; then
    pass "Own tunnel address" "10.233.97.1/30, no clash with VPN apps in the personal profile"
else
    fail "Own tunnel address" "$(sh_ ip -o addr show | grep -E 'tun' | cut -c1-120 | tr '\n' ' ')"
fi
if applog | grep -q "W/TunnelVpn: network: \|I/TunnelVpn: network: "; then
    pass "Network logged" "$(applog | grep -m1 'TunnelVpn: network: ' | cut -d' ' -f3- | cut -c1-100)"
else
    fail "Network logged" "no network line in TunnelLock's log"
fi

# Battery saving: with the screen off the connection is checked every minute, not every 15 s.
sh_ input keyevent KEYCODE_SLEEP
sleep 2
from=$(applines)
sleep 62
sh_ input keyevent KEYCODE_WAKEUP
sh_ wm dismiss-keyguard >/dev/null 2>&1
sleep 4
checks=$(applog | tail -n +"$((from + 1))" | grep "I/Battery: screen on" | tail -1 | grep -oE '[0-9]+ checks' | grep -oE '^[0-9]+')
if [ -n "$checks" ] && [ "$checks" -le 2 ]; then
    pass "Battery saving" "$checks connection checks in a minute with the screen off (every 15 s it would be 4)"
else
    fail "Battery saving" "checks with the screen off: ${checks:-no sample} $(applog | tail -n +"$((from + 1))" | grep 'Battery' | tail -2 | cut -c1-120 | tr '\n' ' ')"
fi

# ---- 1b. the server stalls for 5 s: too short for TunnelLock's 15 s check ---------------

m=$(mark)
xray_pid=$(cat "$OUT/xray.pid")
kill -STOP "$xray_pid"
sleep 5
kill -CONT "$xray_pid"
sleep 10
stall=$(breaks_after "$m" | head -1)
secs=$(echo "$stall" | break_seconds)
if [ -n "$secs" ] && [ "$secs" -ge 3 ] && [ "$secs" -le 10 ]; then
    pass "Precise: 5 s stall" "${stall#* ~~ }"
else
    fail "Precise: 5 s stall" "expected one break of about 5 s, got: ${stall:-none}"
fi

# ---- 2. server dies mid-use ----------------------------------------------------------

m=$(mark); from=$(applines); m_down=$m
"$ROOT/e2e/servers.sh" stop xray
sleep 50
check_blocked "V2Ray server down" "$m"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> RECONNECTING"; then pass "Drop detected" "TunnelLock went to RECONNECTING"; else fail "Drop detected" "no RECONNECTING"; fi

# ---- 3. server comes back -------------------------------------------------------------

from=$(applines)
"$ROOT/e2e/servers.sh" start xray
if wait_phase CONNECTED "$from" 120; then pass "V2Ray reconnect" "connected again by itself"; else fail "V2Ray reconnect" "no CONNECTED within 120 s"; fi
m=$(mark); sleep 20; check_online "V2Ray online again" "$m"
outage=$(breaks_after "$m_down" | head -1)
secs=$(echo "$outage" | break_seconds)
if [ -n "$secs" ] && [ "$secs" -ge 40 ]; then
    pass "Precise: server down" "${outage#* ~~ }"
else
    fail "Precise: server down" "expected a break of the whole outage (50 s or more), got: ${outage:-none}"
fi

# ---- 4. TunnelLock killed: Android's lockdown must block on its own ------------------

m=$(mark); from=$(applines)
old_pid=$(app_pid)
kill_app
if [ -n "$old_pid" ] && [ "$(app_pid)" = "$old_pid" ]; then
    fail "Kill" "TunnelLock's process survived kill -9"
fi
sleep 30
check_blocked_until_connected "TunnelLock killed" "$m" "$from"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> CONNECTED"; then
    pass "Restart" "Android restarted TunnelLock and it reconnected by itself"
else
    from=$(applines)
    sh_ am start --user "$U" -n $APP/.ui.MainActivity >/dev/null
    if wait_phase CONNECTED "$from" 120; then pass "Restart" "reconnected when opened again"; else fail "Restart" "no CONNECTED within 120 s"; fi
fi

# ---- 5. manual disconnect -------------------------------------------------------------

m=$(mark); from=$(applines)
sh_ am start-foreground-service --user "$U" -n $APP/.vpn.TunnelVpnService -a app.tunnellock.action.DISCONNECT >/dev/null
sleep 50
check_blocked "Disconnected" "$m"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> GUARDING"; then pass "Disconnect" "TunnelLock is GUARDING"; else fail "Disconnect" "no GUARDING"; fi
# Disconnected under lockdown: no VPN interface (no key in the status bar), Android blocks.
# VpnService interfaces are tun0, tun1...; the emulator's kernel also has tunl0 (IP-in-IP).
tuns=$(sh_ ls /sys/class/net | grep -E '^tun[0-9]+$' | tr '\n' ' ')
if [ -z "$tuns" ]; then
    pass "VPN icon off" "no VPN interface while disconnected"
else
    pid=$(app_pid)
    fail "VPN icon off" "VPN interface still up: $tuns| TunnelLock holds $(sh_ ls -l /proc/"$pid"/fd 2>/dev/null | grep -c /dev/tun) TUN fds | VPN networks: $(sh_ dumpsys connectivity | grep -c 'type: VPN')"
fi
if applog | tail -n +"$((from + 1))" | grep -q -- "-> CONNECTING"; then
    fail "Stays disconnected" "TunnelLock connected again by itself after a manual disconnect"
else
    pass "Stays disconnected" "no reconnect after the manual disconnect"
fi

# ---- 5b. TunnelProbe on its own server B; the main server A must never carry it --------

# The probe's own traffic in a server's access log: STUN (UDP 19302, nothing else uses it)
# and ipify by name or address (with own servers, sniffing only routes: servers get addresses).
ipify_ips=$( { dig +short @1.1.1.1 api64.ipify.org A; dig +short @1.1.1.1 api64.ipify.org AAAA; } 2>/dev/null \
    | grep -E '^[0-9a-fA-F:.]+$' | sed 's/\./\\./g' | paste -sd'|' -)
PROBE_MARKS=":19302|ipify${ipify_ips:+|$ipify_ips}"
probe_hits() { tail -n +"$(($2 + 1))" "$OUT/$1-access.log" 2>/dev/null | grep -cE "$PROBE_MARKS" || true; }
lines_of() { wc -l <"$OUT/$1-access.log" 2>/dev/null || echo 0; }

from=$(applines)
start_with xray "$OVPN_PASSWORD" "$PROBE"
if wait_phase CONNECTED "$from" 120; then
    pass "Own server connect" "$(applog | tail -n +"$((from + 1))" | grep -m1 "own servers:" | cut -d' ' -f4-)"
else
    fail "Own server connect" "no CONNECTED within 120 s"
fi
a0=$(lines_of xray); b0=$(lines_of xray-b)
m=$(mark); sleep 25; check_online "Own server online" "$m"
a=$(probe_hits xray "$a0"); b=$(probe_hits xray-b "$b0")
if [ "$b" -gt 0 ] && [ "$a" -eq 0 ]; then
    pass "Own server only" "server B carried $b of the probe's connections (HTTPS and UDP), the main server none"
else
    fail "Own server only" "server B carried $b of the probe's connections, the main server $a: $(tail -n +"$((a0 + 1))" "$OUT/xray-access.log" | grep -E "$PROBE_MARKS" | head -3 | cut -c1-160 | tr '\n' ' ')"
fi

# B goes down: the probe gets nothing (never the main server), the main connection stays.
m=$(mark); from=$(applines); a0=$(lines_of xray)
"$ROOT/e2e/servers.sh" stop xray-b
sleep 40
check_blocked "Own server down" "$m"
a=$(probe_hits xray "$a0")
if [ "$a" -eq 0 ]; then pass "No fallback" "nothing of the probe went to the main server while B was down"; else fail "No fallback" "$a probe connections went to the main server"; fi
if applog | tail -n +"$((from + 1))" | grep -q -- "-> RECONNECTING"; then
    fail "Main server unaffected" "TunnelLock dropped the main connection"
else
    pass "Main server unaffected" "TunnelLock stayed connected through the main server"
fi
"$ROOT/e2e/servers.sh" start xray-b
m=$(mark); sleep 25; check_online "Own server back" "$m"

# ---- 5c. SSH: in TLS, over WebSocket and plain; down, wrong password, changed host key ---

ssh_lines() { sudo wc -l "$OUT/sshd.log" 2>/dev/null | awk '{print $1}' | grep . || echo 0; }
ssh_forwards() { sudo tail -n +"$(($1 + 1))" "$OUT/sshd.log" 2>/dev/null | grep -c "direct_tcpip.*ipify" || true; }
ssh_why() { applog | tail -n +"$(($1 + 1))" | grep -E "SSH|TunnelVpn" | tail -3 | cut -c1-160 | tr '\n' ' '; }

for transport in tls ws tcp; do
    from=$(applines); s0=$(ssh_lines)
    start_with "ssh-$transport"
    if wait_phase CONNECTED "$from" 120; then
        # SSH (especially wrapped in TLS) is slower per round, so allow a longer window for enough samples.
        m=$(mark); sleep 45; check_online "SSH $transport online" "$m"
        n=$(ssh_forwards "$s0")
        if [ "$n" -gt 0 ]; then
            pass "SSH $transport through the server" "the SSH server opened $n connections to ipify for the probe"
        else
            fail "SSH $transport through the server" "sshd forwarded nothing: $(sudo tail -n +"$((s0 + 1))" "$OUT/sshd.log" | grep -iE 'direct|Accepted|fail|error' | tail -3 | cut -c1-140 | tr '\n' ' ')"
        fi
    else
        fail "SSH $transport connect" "no CONNECTED within 120 s: $(ssh_why "$from")"
    fi
done

# Still on plain SSH: the server goes down (sessions too) and comes back.
m=$(mark); from=$(applines)
"$ROOT/e2e/servers.sh" stop ssh
sleep 50
check_blocked "SSH server down" "$m"
if applog | tail -n +"$((from + 1))" | grep -q -- "-> RECONNECTING"; then pass "SSH drop detected" "TunnelLock went to RECONNECTING"; else fail "SSH drop detected" "no RECONNECTING: $(ssh_why "$from")"; fi
from=$(applines)
"$ROOT/e2e/servers.sh" start ssh
if wait_phase CONNECTED "$from" 120; then pass "SSH reconnect" "connected again by itself"; else fail "SSH reconnect" "no CONNECTED within 120 s: $(ssh_why "$from")"; fi

m=$(mark); from=$(applines)
start_with ssh-wrong
if wait_phase GUARDING "$from" 90 && applog | tail -n +"$((from + 1))" | grep -q "rejected the username"; then
    pass "SSH wrong password" "stopped with the login error"
else
    fail "SSH wrong password" "no login error within 90 s: $(ssh_why "$from")"
fi
# The login error comes at once now: wait for a few blocked rounds (each takes up to ~20 s).
sleep 45
check_blocked "SSH wrong password blocks" "$m"

# A different server answers on the same address: refused, apps stay blocked.
from=$(applines)
start_with ssh-tcp
wait_phase CONNECTED "$from" 120 || fail "SSH before key change" "no CONNECTED within 120 s: $(ssh_why "$from")"
m=$(mark); from=$(applines)
"$ROOT/e2e/servers.sh" new-hostkeys ssh
sleep 45
if applog | tail -n +"$((from + 1))" | grep -q "host key of .* changed"; then
    pass "SSH host key change" "the new key was refused"
else
    fail "SSH host key change" "no refusal: $(ssh_why "$from")"
fi
check_blocked "SSH host key change blocks" "$m"

# ---- 6. OpenVPN -----------------------------------------------------------------------

from=$(applines)
start_with ovpn
if wait_phase CONNECTED "$from" 150; then pass "OpenVPN connect" "connected with username and password"; else fail "OpenVPN connect" "no CONNECTED within 150 s"; fi
m=$(mark); sleep 45; check_online "OpenVPN online" "$m"
if sudo grep -q "e2e" "$OUT/openvpn-status.log" 2>/dev/null; then pass "OpenVPN server" "the server lists the emulator as a client"; else fail "OpenVPN server" "the client is not in the server's status"; fi

# ---- 6b. an app with its own V2Ray server while the main server is OpenVPN: cut off ------

from=$(applines)
start_with ovpn "$OVPN_PASSWORD" "$PROBE"
if wait_phase CONNECTED "$from" 150; then
    m=$(mark); sleep 30; check_blocked "Own server under OpenVPN" "$m"
else
    fail "Own server under OpenVPN" "OpenVPN did not connect within 150 s"
fi
from=$(applines)
start_with ovpn
wait_phase CONNECTED "$from" 150 || fail "OpenVPN again" "no CONNECTED within 150 s"

# ---- 7. OpenVPN server dies mid-use and comes back ------------------------------------

m=$(mark)
"$ROOT/e2e/servers.sh" stop openvpn
sleep 60
check_blocked "OpenVPN server down" "$m"
from=$(applines)
"$ROOT/e2e/servers.sh" start openvpn
if wait_phase CONNECTED "$from" 180; then pass "OpenVPN reconnect" "connected again by itself"; else fail "OpenVPN reconnect" "no CONNECTED within 180 s"; fi
m=$(mark); sleep 20; check_online "OpenVPN online again" "$m"

# ---- 7b. OpenVPN filtered mid-handshake, the way DPI does it: a clear message ----------

m=$(mark); from=$(applines)
# The server's first small packet gets through, its certificate (large packets) does not.
FILTER=(OUTPUT -p udp --sport 1194 -m length --length 400:65535 -j DROP)
sudo iptables -I "${FILTER[@]}"
start_with ovpn
sleep 40
if applog | tail -n +"$((from + 1))" | grep -q "the server answered, then the handshake got stuck"; then
    pass "OpenVPN filtered" "TunnelLock says the handshake got stuck after the server answered"
else
    fail "OpenVPN filtered" "no stuck-handshake message: $(applog | tail -n +"$((from + 1))" | grep OpenVPN | tail -4 | cut -c1-150 | tr '\n' ' ')"
fi
check_blocked "OpenVPN filtered blocks" "$m"
sudo iptables -D "${FILTER[@]}"

# ---- 8. wrong OpenVPN password: a clear error, no endless retries ----------------------

m=$(mark); from=$(applines)
start_with ovpn wrong-password
if wait_phase GUARDING "$from" 120 && applog | tail -n +"$((from + 1))" | grep -q "rejected the login"; then
    pass "Wrong password" "stopped with the login error"
else
    fail "Wrong password" "no login error within 120 s"
fi
sleep 45
check_blocked "Wrong password blocks" "$m"

# ---- 9. Fake location for the secure space (run from inside the secure space) --------
# A mock location only reaches apps in the SAME Android user, and the secure space is a
# separate user (10). Developer Options can make TunnelLock the mock app for the personal
# user only, not the work profile -- so the appop is granted to the secure-space copy
# directly here (the one-time adb step a user would run). With it, the secure-space
# TunnelLock feeds the fake and the secure-space apps read it. Real location is Munich
# (device-wide via the emulator); the fake is Tehran; the probe must read Tehran.
adb emu geo fix 11.5820 48.1351 >/dev/null 2>&1 || true
sh_ appops set --user "$U" $APP android:mock_location allow >/dev/null 2>&1
sh_ pm grant --user "$U" $APP android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
sh_ pm grant --user "$U" $PROBE android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
# Location services must be on for each user or no provider delivers anything to anyone.
for who in 0 "$U"; do
    sh_ settings put secure --user "$who" location_mode 3 >/dev/null 2>&1
    sh_ cmd location set-location-enabled true --user "$who" >/dev/null 2>&1
done

# Secure-space (user $U) prefs with the fake coordinate, then start its mock service there.
printf '%s' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"mockLocation\">35.6892, 51.389</string></map>" >"$OUT/mock.xml"
adb push "$OUT/mock.xml" /data/local/tmp/mock.xml >/dev/null
uuid=$(sh_ stat -c %u "$DATA"); uctx=$(sh_ ls -Zd "$DATA" | awk '{print $1}')
sh_ "mkdir -p $DATA/shared_prefs && cp /data/local/tmp/mock.xml $DATA/shared_prefs/tunnellock.xml \
    && chown $uuid:$uuid $DATA/shared_prefs $DATA/shared_prefs/tunnellock.xml \
    && chmod 771 $DATA/shared_prefs && chmod 660 $DATA/shared_prefs/tunnellock.xml \
    && chcon $uctx $DATA/shared_prefs $DATA/shared_prefs/tunnellock.xml"
sh_ am force-stop --user 0 $APP >/dev/null 2>&1
sh_ am force-stop --user "$U" $APP >/dev/null 2>&1
sh_ am start-foreground-service --user "$U" -n $APP/.vpn.MockLocationService >/dev/null 2>&1

# Wait for the secure-space app to accept the mock (its log is in user $U).
mock_ok=""
for _ in $(seq 1 15); do
    if applog | grep -q "fake location on: 35.6892"; then mock_ok=yes; break; fi
    sleep 2
done
if [ -n "$mock_ok" ]; then
    pass "Fake location set" "the secure-space TunnelLock feeds 35.6892, 51.389"
else
    fail "Fake location set" "not set: $(applog | grep -i location | tail -3 | cut -c1-140 | tr '\n' ' ')"
fi

# Ground truth from the system, so "none yet" in the secure space can be told apart from the
# fake simply not crossing the profile boundary: is location on per user, and does the gps
# provider device-wide actually hold the mocked 35.689 point?
sleep 5
ldiag="location_mode u0=$(sh_ settings get secure --user 0 location_mode) u$U=$(sh_ settings get secure --user "$U" location_mode)"
ldiag="$ldiag | dumpsys: $(sh_ dumpsys location 2>/dev/null | grep -iE 'last location|mock|35\.68|48\.13|enabled' | head -12 | cut -c1-120 | tr '\n' '/')"
echo "::notice title=Location diag::$(printf '%s' "$ldiag" | oneline)"

# The probe inside the secure space reports the location it reads: must be the fake, not the real.
# It needs a couple of 30 s location rounds after it starts listening before a fix arrives.
m=$(mark); sleep 75
loc=$(probelog | tail -n +"$((m + 1))" | grep -- "-- location:" | tail -1)
if echo "$loc" | grep -q "35.689"; then
    pass "Fake reaches the secure space" "${loc#*-- }"
else
    fail "Fake reaches the secure space" "the probe did not read the fake: ${loc:-no location line} | $(probelog | grep -- '-- location:' | tail -2 | tr '\n' ' ')"
fi
# The real position must not appear on any provider: Munich if the geo fix took, else the
# emulator's default (~37.4, Mountain View).
if echo "$loc" | grep -qE "48\.13|37\.4"; then
    fail "No real-location leak" "the real position leaked to the secure space: $loc"
else
    pass "No real-location leak" "the real position did not leak to the secure space"
fi

# Turned off: the mock service stops and removes the test providers.
sh_ am force-stop --user "$U" $APP >/dev/null 2>&1
sh_ "rm -f $DATA/shared_prefs/tunnellock.xml" >/dev/null 2>&1
m=$(mark); sleep 40
if probelog | tail -n +"$((m + 1))" | grep -- "-- location:" | tail -1 | grep -q "35.689"; then
    fail "Fake location off" "the fake is still delivered after the service stopped"
else
    pass "Fake location off" "no fake location once the service is stopped"
fi

# ---- 10. Open a link in the secure space --------------------------------------------

# The secure-space handler, invoked directly (the personal->secure hop uses Android's own
# cross-profile forwarder, the same one the app-copy and tile hand-overs use).
m=$(applines)
sh_ am start --user "$U" -a app.tunnellock.action.OPEN_LINK --es url "https://activate.e2e.test/go?t=secrettoken" -n $APP/.strict.OpenLinkActivity >/dev/null 2>&1
opened=""
for _ in $(seq 1 8); do
    if applog | tail -n +"$((m + 1))" | grep -qE "OpenLink: opened a link in the secure space|OpenLink: no app in the secure space opened"; then opened=yes; break; fi
    sleep 2
done
if [ -n "$opened" ]; then
    pass "Open link in secure space" "the secure-space handler opened the link"
else
    fail "Open link in secure space" "the handler did not run: $(applog | tail -n +"$((m + 1))" | grep -i openlink | tail -3 | cut -c1-140 | tr '\n' ' ')"
fi
# A one-time token in the link must never be written to the log.
if applog | grep -q "secrettoken"; then fail "Link token kept out of the log" "the token was logged"; else pass "Link token kept out of the log" "the link's token is not in the log"; fi

# ---- report ---------------------------------------------------------------------------

if adb logcat -d -b crash | grep -q "$APP"; then
    fail "No crashes" "$(adb logcat -d -b crash | grep -A 12 "$APP" | head -30 | cut -c1-200 | oneline)"
else
    pass "No crashes" "no crash of TunnelLock or TunnelProbe"
fi
report
