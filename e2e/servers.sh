#!/usr/bin/env bash
# Test servers for the emulator test, running on the CI machine. The emulator
# reaches this machine at 10.0.2.2.
#
#   e2e/servers.sh setup                 install and configure both servers, start them
#   e2e/servers.sh start|stop xray       VLESS (Xray) on port 10086, no TLS
#   e2e/servers.sh start|stop openvpn    OpenVPN on UDP 1194, login e2e / $OVPN_PASSWORD
#
# Client configs end up in e2e/out: xray.link and client.ovpn.
set -euo pipefail

OUT=$(cd "$(dirname "$0")" && pwd)/out
OVPN_PASSWORD=${OVPN_PASSWORD:-e2e-secret-7}
mkdir -p "$OUT"

start_xray() {
    nohup "$OUT/xray" run -c "$OUT/xray-server.json" >>"$OUT/xray.log" 2>&1 &
    echo $! >"$OUT/xray.pid"
    sleep 1
    kill -0 "$(cat "$OUT/xray.pid")" || { echo "::error title=Xray server::did not start: $(tail -5 "$OUT/xray.log")"; exit 1; }
}

stop_xray() {
    [ -f "$OUT/xray.pid" ] && kill "$(cat "$OUT/xray.pid")" 2>/dev/null || true
    rm -f "$OUT/xray.pid"
}

start_openvpn() {
    sudo openvpn --config "$OUT/server.conf" --daemon --writepid "$OUT/openvpn.pid" --log-append "$OUT/openvpn.log"
    sleep 2
    sudo kill -0 "$(sudo cat "$OUT/openvpn.pid")" || { echo "::error title=OpenVPN server::did not start: $(sudo tail -5 "$OUT/openvpn.log")"; exit 1; }
}

stop_openvpn() {
    [ -f "$OUT/openvpn.pid" ] && sudo kill "$(sudo cat "$OUT/openvpn.pid")" 2>/dev/null || true
    sudo rm -f "$OUT/openvpn.pid"
}

setup() {
    # ---- Xray: the same core version as the app, built from source (Go verifies the module checksums).
    GOBIN="$OUT/gobin" go install "$XRAY_SERVER_MODULE"
    cp "$OUT/gobin/main" "$OUT/xray"
    local uuid
    uuid=$(cat /proc/sys/kernel/random/uuid)
    cat >"$OUT/xray-server.json" <<EOF
{
  "log": { "loglevel": "warning", "access": "$OUT/xray-access.log" },
  "inbounds": [{
    "listen": "0.0.0.0", "port": 10086, "protocol": "vless",
    "settings": { "clients": [{ "id": "$uuid" }], "decryption": "none" },
    "streamSettings": { "network": "raw" }
  }],
  "outbounds": [{ "protocol": "freedom" }]
}
EOF
    echo "vless://$uuid@10.0.2.2:10086?encryption=none&type=tcp&security=none#e2e-xray" >"$OUT/xray.link"
    start_xray

    # ---- OpenVPN: a throw-away CA, login checked by a script, traffic NATed to the internet.
    sudo apt-get install -y -qq openvpn easy-rsa >/dev/null
    export EASYRSA_BATCH=1 EASYRSA_PKI="$OUT/pki" EASYRSA_REQ_CN=tunnellock-e2e
    /usr/share/easy-rsa/easyrsa init-pki >/dev/null
    /usr/share/easy-rsa/easyrsa build-ca nopass >/dev/null 2>&1
    /usr/share/easy-rsa/easyrsa build-server-full server nopass >/dev/null 2>&1
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
    sudo sysctl -qw net.ipv4.ip_forward=1
    local iface
    iface=$(ip route show default | awk '{print $5; exit}')
    sudo iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "$iface" -j MASQUERADE
    # Docker on the runner sets the FORWARD policy to DROP.
    sudo iptables -I FORWARD 1 -s 10.8.0.0/24 -j ACCEPT
    sudo iptables -I FORWARD 1 -d 10.8.0.0/24 -j ACCEPT
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
    echo "::notice title=Test servers::Xray on 10086/tcp and OpenVPN on 1194/udp are running"
}

case "${1:-} ${2:-}" in
    "setup ") setup ;;
    "start xray") start_xray ;;
    "stop xray") stop_xray ;;
    "start openvpn") start_openvpn ;;
    "stop openvpn") stop_openvpn ;;
    *) echo "usage: $0 setup | start|stop xray|openvpn" >&2; exit 2 ;;
esac
