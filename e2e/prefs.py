"""Writes TunnelLock's settings file (SharedPreferences XML) with the two test servers.

The emulator test puts it into the app's data directory instead of typing the
servers in through the screens.

usage: prefs.py <xray link file> <client.ovpn> <selected: xray|ovpn> <openvpn password> > tunnellock.xml
"""
import json
import sys
from xml.sax.saxutils import escape

xray_link = open(sys.argv[1]).read().strip()
ovpn = open(sys.argv[2]).read()
selected = sys.argv[3]
password = sys.argv[4]

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


def string(name, value):
    return '    <string name="%s">%s</string>\n' % (name, escape(value, {'"': "&quot;"}))


def boolean(name, value):
    return '    <boolean name="%s" value="%s" />\n' % (name, "true" if value else "false")


sys.stdout.write(
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
    + string("servers", json.dumps(servers))
    + string("selected", selected)
    + boolean("autoConnect", True)
    + boolean("pauseWhenDisconnected", True)
    + "</map>\n"
)
