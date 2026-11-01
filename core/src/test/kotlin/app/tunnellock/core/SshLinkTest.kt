package app.tunnellock.core

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParseException
import app.tunnellock.core.parser.SshLink
import app.tunnellock.core.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SshLinkTest {
    @Test
    fun plainLinkWithDefaultPort() {
        val p = LinkParser.parse("ssh://ali:p%40ss:word@ssh.example.com#Home")
        assertEquals(Protocol.SSH, p.protocol)
        assertEquals("ssh.example.com", p.address)
        assertEquals(22, p.port)
        assertEquals("ali", p.username)
        assertEquals("p@ss:word", p.secret)
        assertEquals("tcp", p.stream.network)
        assertEquals("Home", p.name)
    }

    @Test
    fun transportsAndHostKey() {
        val p = LinkParser.parse("ssh://u@1.2.3.4:443?transport=wss&sni=cdn.example&host=ws.example&path=%2Fssh&hostkey=SHA256%3Aabc#ws")
        assertEquals(443, p.port)
        assertEquals("wss", p.stream.network)
        assertEquals("tls", p.stream.security)
        assertEquals("cdn.example", p.stream.sni)
        assertEquals("ws.example", p.stream.host)
        assertEquals("/ssh", p.stream.path)
        assertEquals("SHA256:abc", p.hostKey)
        assertEquals("", p.secret)
    }

    @Test
    fun ipv6WithoutPort() {
        val p = LinkParser.parse("ssh://u@[2001:db8::1]?transport=tls#v6")
        assertEquals("2001:db8::1", p.address)
        assertEquals(22, p.port)
    }

    @Test
    fun unknownTransportIsRefused() {
        assertThrows(ParseException::class.java) { LinkParser.parse("ssh://u@h:22?transport=quic") }
    }

    @Test
    fun builtLinksParseBackWithoutThePassword() {
        val link = SshLink.build("My server", "h.example", 2222, "user name", "ws", wsHost = "front.example", path = "/a b")
        val p = LinkParser.parse(link)
        assertEquals("My server", p.name)
        assertEquals(2222, p.port)
        assertEquals("user name", p.username)
        assertEquals("front.example", p.stream.host)
        assertEquals("/a b", p.stream.path)
        assertEquals("", p.secret)
        assertNull(p.hostKey)
    }

    @Test
    fun sshIsNotAnXrayOutboundButReachesItAsSocks() {
        val ssh = LinkParser.parse("ssh://u:p@h:22")
        assertFalse(ssh.protocol.isXray)
        assertFalse(XrayConfigBuilder.canRouteApps(ssh))
        val socks = ServerProfile("bridge", Protocol.SOCKS, "127.0.0.1", 40000, secret = "pw", username = "tl")
        val c = JSONObject(XrayConfigBuilder.build(socks))
        val proxy = c.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", proxy.getString("protocol"))
        val settings = proxy.getJSONObject("settings")
        assertEquals("127.0.0.1", settings.getString("address"))
        assertEquals(40000, settings.getInt("port"))
        assertEquals("tl", settings.getString("user"))
        assertEquals("pw", settings.getString("pass"))
        assertFalse(proxy.has("streamSettings"))
    }

    @Test
    fun accountsBehindOneCdnAddressKeepTheirOwnHostKeys() {
        val a = LinkParser.parse("ssh://u@cdn.example:443?transport=wss&host=a.example&path=%2Fa")
        val b = LinkParser.parse("ssh://u@cdn.example:443?transport=wss&host=b.example&path=%2Fb")
        assert(SshLink.hostKeyId(a) != SshLink.hostKeyId(b))
        assertEquals(SshLink.hostKeyId(a), SshLink.hostKeyId(LinkParser.parse("ssh://other:pw@cdn.example:443?transport=wss&host=a.example&path=%2Fa#x")))
    }
}
