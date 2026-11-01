"""Writes TunnelLock's settings file (SharedPreferences XML) with the test servers.

The emulator test puts it into the app's data directory instead of typing the
servers in through the screens.

usage: prefs.py <xray link file> <client.ovpn> <selected: xray|ovpn> <openvpn password>
                [<second xray link file> [<package on the second server>]] > tunnellock.xml
"""
import json
import os
import sys
from xml.sax.saxutils import escape

xray_link = open(sys.argv[1]).read().strip()
ovpn = open(sys.argv[2]).read()
selected = sys.argv[3]
password = sys.argv[4]
xray_b_link = open(sys.argv[5]).read().strip() if len(sys.argv) > 5 else None
own_server_package = sys.argv[6] if len(sys.argv) > 6 else None

servers = [
    {"id": "xray", "name": "e2e V2Ray", "link": xray_link, "protocol": "VLESS", "address": "10.0.2.2:10086"},
    {
        "id": "ovpn",
        "name": "e2e OpenVPN",
        "link": ovpn,
        "protocol": "OpenVPN",
        "address": "10.0.2.2:1194",
        "username": "e2e",
        "password": password,
    },
]


# SSH accounts: plain, in TLS, over WebSocket, and one with a wrong password.
ssh_password = os.environ.get("SSH_PASSWORD", "e2e-ssh-pass-9")
for server_id, link, address, login_password in (
    ("ssh-tcp", "ssh://e2essh@10.0.2.2:2222?transport=tcp#e2e%20SSH", "10.0.2.2:2222", ssh_password),
    ("ssh-tls", "ssh://e2essh@10.0.2.2:2443?transport=tls&sni=ssh.e2e.test#e2e%20SSH%20TLS", "10.0.2.2:2443", ssh_password),
    ("ssh-ws", "ssh://e2essh@10.0.2.2:2280?transport=ws&host=ws.e2e.test&path=%2Fssh#e2e%20SSH%20WS", "10.0.2.2:2280", ssh_password),
    ("ssh-wrong", "ssh://e2essh@10.0.2.2:2222?transport=tcp#e2e%20SSH%20wrong", "10.0.2.2:2222", "not-the-password"),
):
    servers.append(
        {"id": server_id, "name": server_id, "link": link, "protocol": "SSH", "address": address, "username": "e2essh", "password": login_password}
    )

app_servers = {}
if xray_b_link:
    servers.append({"id": "xray-b", "name": "e2e V2Ray B", "link": xray_b_link, "protocol": "VLESS", "address": "10.0.2.2:10087"})
    if own_server_package:
        app_servers[own_server_package] = {"link": xray_b_link, "name": "e2e V2Ray B"}


def string(name, value):
    return '    <string name="%s">%s</string>\n' % (name, escape(value, {'"': "&quot;"}))


def boolean(name, value):
    return '    <boolean name="%s" value="%s" />\n' % (name, "true" if value else "false")


sys.stdout.write(
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
    + string("servers", json.dumps(servers))
    + string("selected", selected)
    + string("appServers", json.dumps(app_servers))
    + boolean("autoConnect", True)
    + boolean("pauseWhenDisconnected", True)
    + "</map>\n"
)
