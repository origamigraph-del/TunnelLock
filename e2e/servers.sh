#!/usr/bin/env bash
# Test servers for the emulator test, running on the CI machine. The emulator
# reaches this machine at 10.0.2.2.
#
#   e2e/servers.sh setup                 install and configure both servers, start them
#   e2e/servers.sh start|stop xray       VLESS (Xray) on port 10086, no TLS
#   e2e/servers.sh start|stop xray-b     a second one on port 10087 (an app's own server)
#   e2e/servers.sh start|stop openvpn    OpenVPN on UDP 1194, login e2e / $OVPN_PASSWORD
#   e2e/servers.sh start|stop ssh        SSH (user e2essh / $SSH_PASSWORD) on 2222, in TLS on 2443,
#                                        over WebSocket (GET /ssh) on 2280
#   e2e/servers.sh new-hostkeys ssh      restart SSH with new host keys (a different server)
#
# Client configs end up in e2e/out: xray.link, xray-b.link and client.ovpn.
set -euo pipefail

OUT=$(cd "$(dirname "$0")" && pwd)/out
E2E=$(cd "$(dirname "$0")" && pwd)
OVPN_PASSWORD=${OVPN_PASSWORD:-e2e-secret-7}
SSH_PASSWORD=${SSH_PASSWORD:-e2e-ssh-pass-9}
mkdir -p "$OUT"

# Runs a command quietly; on failure shows its output as an annotation and stops.
quiet() { # <title> <command...>
    local title=$1 log
    shift
    log=$(mktemp)
    if ! "$@" >"$log" 2>&1; then
        echo "::error title=$title failed::$(tail -15 "$log" | cut -c1-200 | sed ':a;N;$!ba;s/\n/%0A/g')"
        exit 1
    fi
}

start_xray() { # [name: xray | xray-b]
    local name=${1:-xray}
    nohup "$OUT/xray" run -c "$OUT/$name-server.json" >>"$OUT/$name.log" 2>&1 &
    echo $! >"$OUT/$name.pid"
    sleep 1
    kill -0 "$(cat "$OUT/$name.pid")" || { echo "::error title=Xray server $name::did not start: $(tail -5 "$OUT/$name.log")"; exit 1; }
}

stop_xray() { # [name]
    local name=${1:-xray}
    [ -f "$OUT/$name.pid" ] && kill "$(cat "$OUT/$name.pid")" 2>/dev/null || true
    rm -f "$OUT/$name.pid"
}

# A VLESS server without TLS: <name> <port>. Writes <name>-server.json and <name>.link.
xray_server() {
    local name=$1 port=$2 uuid
    uuid=$(cat /proc/sys/kernel/random/uuid)
    cat >"$OUT/$name-server.json" <<EOF
{
  "log": { "loglevel": "warning", "access": "$OUT/$name-access.log" },
  "inbounds": [{
    "listen": "0.0.0.0", "port": $port, "protocol": "vless",
    "settings": { "clients": [{ "id": "$uuid" }], "decryption": "none" },
    "streamSettings": { "network": "raw" }
  }],
  "outbounds": [{ "protocol": "freedom" }]
}
EOF
    echo "vless://$uuid@10.0.2.2:$port?encryption=none&type=tcp&security=none#e2e-$name" >"$OUT/$name.link"
}

start_openvpn() {
    quiet "OpenVPN server start" sudo openvpn --config "$OUT/server.conf" --daemon --writepid "$OUT/openvpn.pid" --log-append "$OUT/openvpn.log"
    sleep 2
    sudo kill -0 "$(sudo cat "$OUT/openvpn.pid")" || { echo "::error title=OpenVPN server::did not start: $(sudo tail -5 "$OUT/openvpn.log")"; exit 1; }
}

stop_openvpn() {
    [ -f "$OUT/openvpn.pid" ] && sudo kill "$(sudo cat "$OUT/openvpn.pid")" 2>/dev/null || true
    sudo rm -f "$OUT/openvpn.pid"
}

ssh_host_keys() {
    rm -rf "$OUT/ssh-keys"
    mkdir -p "$OUT/ssh-keys"
    for type in ecdsa rsa ed25519; do ssh-keygen -q -t "$type" -N "" -f "$OUT/ssh-keys/ssh_host_${type}_key"; done
}

start_ssh() {
    sudo mkdir -p /run/sshd
    quiet "SSH server start" sudo /usr/sbin/sshd -f "$OUT/sshd_config" -E "$OUT/sshd.log"
    # In TLS (stunnel-style) and behind an HTTP upgrade (SSH over WebSocket).
    nohup socat "OPENSSL-LISTEN:2443,cert=$OUT/ssh-tls.pem,verify=0,reuseaddr,fork" TCP:127.0.0.1:2222 >>"$OUT/socat.log" 2>&1 &
    echo $! >"$OUT/socat.pid"
    nohup python3 "$E2E/ws-ssh.py" 2280 2222 >>"$OUT/ws-ssh.log" 2>&1 &
    echo $! >"$OUT/ws-ssh.pid"
    sleep 1
}

stop_ssh() {
    [ -f "$OUT/sshd.pid" ] && sudo kill "$(sudo cat "$OUT/sshd.pid")" 2>/dev/null || true
    # Open sessions run in their own processes: end them too, as a server going down would.
    # Anchored, so the patterns do not match sudo's own command line.
    sudo pkill -f "^sshd: e2essh" 2>/dev/null || true
    sudo pkill -f "^sshd-session: e2essh" 2>/dev/null || true
    sudo pkill -u e2essh 2>/dev/null || true
    for name in socat ws-ssh; do
        [ -f "$OUT/$name.pid" ] && kill "$(cat "$OUT/$name.pid")" 2>/dev/null || true
        rm -f "$OUT/$name.pid"
    done
    pkill -f "socat OPENSSL-LISTEN:2443" 2>/dev/null || true
    sleep 1
}

setup_ssh() {
    quiet "SSH tools install" sudo apt-get install -y -qq openssh-server socat
    id e2essh >/dev/null 2>&1 || quiet "SSH user" sudo useradd -m -s /usr/sbin/nologin e2essh
    echo "e2essh:$SSH_PASSWORD" | sudo chpasswd || { echo "::error title=SSH user::chpasswd failed"; exit 1; }
    ssh_host_keys
    cat >"$OUT/sshd_config" <<EOF
Port 2222
ListenAddress 0.0.0.0
HostKey $OUT/ssh-keys/ssh_host_ecdsa_key
HostKey $OUT/ssh-keys/ssh_host_rsa_key
HostKey $OUT/ssh-keys/ssh_host_ed25519_key
PidFile $OUT/sshd.pid
PasswordAuthentication yes
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM yes
AllowTcpForwarding yes
X11Forwarding no
PermitTTY no
LogLevel DEBUG1
EOF
    quiet "SSH TLS certificate" openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=ssh.e2e.test" \
        -keyout "$OUT/ssh-tls.key" -out "$OUT/ssh-tls.crt"
    cat "$OUT/ssh-tls.key" "$OUT/ssh-tls.crt" >"$OUT/ssh-tls.pem"
    start_ssh
}

setup() {
    # ---- Xray: the same core version as the app, built from source (Go verifies the module checksums).
    GOBIN="$OUT/gobin" quiet "Xray build" go install "$XRAY_SERVER_MODULE"
    cp "$OUT/gobin/main" "$OUT/xray"
    xray_server xray 10086
    xray_server xray-b 10087
    start_xray xray
    start_xray xray-b

    # ---- OpenVPN: a throw-away CA, login checked by a script, traffic NATed to the internet.
    quiet "apt-get update" sudo apt-get update -qq
    quiet "OpenVPN install" sudo apt-get install -y -qq openvpn easy-rsa
    export EASYRSA_BATCH=1 EASYRSA_PKI="$OUT/pki"
    # easy-rsa looks for its openssl config and x509-types next to where it runs.
    local easyrsa_dir=/usr/share/easy-rsa
    quiet "easy-rsa init-pki" bash -c "cd $easyrsa_dir && ./easyrsa init-pki"
    # The CA's name only: easy-rsa 3.1 refuses a preset name for the server certificate.
    quiet "easy-rsa build-ca" bash -c "cd $easyrsa_dir && EASYRSA_REQ_CN=tunnellock-e2e ./easyrsa build-ca nopass"
    quiet "easy-rsa server cert" bash -c "cd $easyrsa_dir && ./easyrsa build-server-full server nopass"
    cat >"$OUT/check-login.sh" <<EOF
#!/bin/sh
# OpenVPN passes a file with the username and the password on two lines.
[ "\$(sed -n 1p "\$1")" = "e2e" ] && [ "\$(sed -n 2p "\$1")" = "$OVPN_PASSWORD" ]
EOF
    chmod 755 "$OUT/check-login.sh"
    cat >"$OUT/server.conf" <<EOF
port 1194
proto udp4
dev tun
ca $OUT/pki/ca.crt
cert $OUT/pki/issued/server.crt
key $OUT/pki/private/server.key
dh none
server 10.8.0.0 255.255.255.0
topology subnet
push "redirect-gateway def1"
push "dhcp-option DNS 1.1.1.1"
keepalive 5 20
verify-client-cert none
username-as-common-name
script-security 2
auth-user-pass-verify $OUT/check-login.sh via-file
status $OUT/openvpn-status.log 5
verb 3
EOF
    quiet "IP forwarding" sudo sysctl -qw net.ipv4.ip_forward=1
    local iface
    iface=$(ip route show default | awk '{print $5; exit}')
    quiet "NAT" sudo iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "$iface" -j MASQUERADE
    # Docker on the runner sets the FORWARD policy to DROP.
    quiet "Forward out" sudo iptables -I FORWARD 1 -s 10.8.0.0/24 -j ACCEPT
    quiet "Forward in" sudo iptables -I FORWARD 1 -d 10.8.0.0/24 -j ACCEPT
    {
        echo "client"
        echo "dev tun"
        echo "proto udp"
        echo "remote 10.0.2.2 1194"
        echo "nobind"
        echo "remote-cert-tls server"
        echo "auth-user-pass"
        echo "<ca>"
        cat "$OUT/pki/ca.crt"
        echo "</ca>"
    } >"$OUT/client.ovpn"
    start_openvpn
    setup_ssh
    echo "::notice title=Test servers::Xray on 10086/tcp and 10087/tcp, OpenVPN on 1194/udp, SSH on 2222/2443/2280 are running"
}

case "${1:-} ${2:-}" in
    "setup ") setup ;;
    "start xray") start_xray xray ;;
    "stop xray") stop_xray xray ;;
    "start xray-b") start_xray xray-b ;;
    "stop xray-b") stop_xray xray-b ;;
    "start openvpn") start_openvpn ;;
    "stop openvpn") stop_openvpn ;;
    "start ssh") start_ssh ;;
    "stop ssh") stop_ssh ;;
    "new-hostkeys ssh") stop_ssh; ssh_host_keys; start_ssh ;;
    *) echo "usage: $0 setup | start|stop xray|xray-b|openvpn|ssh | new-hostkeys ssh" >&2; exit 2 ;;
esac
