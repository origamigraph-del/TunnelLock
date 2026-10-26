package app.tunnellock.core

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkParserTest {

    @Test
    fun vlessReality() {
        val p = LinkParser.parse(
            "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?encryption=none&flow=xtls-rprx-vision" +
                "&security=reality&sni=www.microsoft.com&fp=chrome&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw" +
                "&sid=6ba85179e30d4fc2&spx=%2F&type=tcp&headerType=none#%D8%B3%D8%B1%D9%88%D8%B1%201",
        )
        assertEquals(Protocol.VLESS, p.protocol)
        assertEquals("example.com", p.address)
        assertEquals(443, p.port)
        assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", p.secret)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("سرور 1", p.name)
        assertEquals("reality", p.stream.security)
        assertEquals("www.microsoft.com", p.stream.sni)
        assertEquals("Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw", p.stream.realityPublicKey)
        assertEquals("6ba85179e30d4fc2", p.stream.realityShortId)
        assertEquals("/", p.stream.realitySpiderX)
        assertEquals("tcp", p.stream.network)
        assertNull(p.stream.headerType)
    }

    @Test
    fun vlessWsTlsIpv6() {
        val p = LinkParser.parse(
            "vless://uuid-1@[2001:db8::1]:8443?type=ws&security=tls&host=cdn.example.com&path=%2Fws%3Fed%3D2048&sni=cdn.example.com&alpn=h2,http/1.1#ws",
        )
        assertEquals("2001:db8::1", p.address)
        assertEquals(8443, p.port)
        assertEquals("ws", p.stream.network)
        assertEquals("/ws?ed=2048", p.stream.path)
        assertEquals("cdn.example.com", p.stream.host)
        assertEquals(listOf("h2", "http/1.1"), p.stream.alpn)
        assertEquals("[2001:db8::1]:8443", p.displayAddress)
    }

    @Test
    fun vlessXhttpWithExtra() {
        val p = LinkParser.parse(
            "vless://u@h.example:443?type=xhttp&security=tls&path=%2Fx&mode=packet-up&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D#x",
        )
        assertEquals("xhttp", p.stream.network)
        assertEquals("packet-up", p.stream.mode)
        assertEquals("{\"xPaddingBytes\":\"100-1000\"}", p.stream.xhttpExtra)
    }

    @Test
    fun vmessBase64Json() {
        val json = """{"v":"2","ps":"my vmess","add":"1.2.3.4","port":"443","id":"abc-uuid","aid":"0","scy":"auto",
            "net":"grpc","type":"multi","host":"","path":"mygrpc","tls":"tls","sni":"sni.example","fp":"firefox"}"""
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val p = LinkParser.parse(link)
        assertEquals(Protocol.VMESS, p.protocol)
        assertEquals("my vmess", p.name)
        assertEquals("1.2.3.4", p.address)
        assertEquals(443, p.port)
        assertEquals("grpc", p.stream.network)
        assertEquals("mygrpc", p.stream.serviceName)
        assertEquals("multi", p.stream.mode)
        assertEquals("tls", p.stream.security)
        assertEquals("firefox", p.stream.fingerprint)
    }

    @Test
    fun vmessUnpaddedUrlSafeBase64() {
        val json = """{"add":"a.example","port":8080,"id":"id","net":"ws","path":"/p","ps":"x"}"""
        val link = "vmess://" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val p = LinkParser.parse(link)
        assertEquals(8080, p.port)
        assertEquals("/p", p.stream.path)
    }

    @Test
    fun trojanDefaultsToTls() {
        val p = LinkParser.parse("trojan://p%40ss@t.example:443?sni=t.example#trojan")
        assertEquals(Protocol.TROJAN, p.protocol)
        assertEquals("p@ss", p.secret)
        assertEquals("tls", p.stream.security)
    }

    @Test
    fun shadowsocksSip002() {
        val userInfo = Base64.getUrlEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:secret".toByteArray())
        val p = LinkParser.parse("ss://$userInfo@ss.example:8388/?plugin=#ss1")
        assertEquals(Protocol.SHADOWSOCKS, p.protocol)
        assertEquals("chacha20-ietf-poly1305", p.method)
        assertEquals("secret", p.secret)
        assertEquals(8388, p.port)
        assertEquals("ss1", p.name)
    }

    @Test
    fun shadowsocks2022Plain() {
        val p = LinkParser.parse("ss://2022-blake3-aes-128-gcm:YctPZ6U7xPPcU%2Bgp3u%2BzuA%3D%3D@1.2.3.4:443#ss2022")
        assertEquals("2022-blake3-aes-128-gcm", p.method)
        assertEquals("YctPZ6U7xPPcU+gp3u+zuA==", p.secret)
    }

    @Test
    fun shadowsocksLegacy() {
        val body = Base64.getEncoder().encodeToString("aes-256-gcm:pw@5.6.7.8:1234".toByteArray())
        val p = LinkParser.parse("ss://$body#legacy")
        assertEquals("5.6.7.8", p.address)
        assertEquals(1234, p.port)
        assertEquals("pw", p.secret)
    }

    @Test
    fun customJson() {
        val p = LinkParser.parse(
            """{"remarks":"fragment","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"x.example","port":443,"users":[{"id":"u"}]}]}}]}""",
        )
        assertEquals(Protocol.CUSTOM, p.protocol)
        assertEquals("fragment", p.name)
        assertEquals("x.example", p.address)
        assertEquals(443, p.port)
    }

    @Test
    fun subscriptionBase64() {
        val body = listOf(
            "vless://u@a.example:443?security=tls#a",
            "garbage line",
            "trojan://p@b.example:443#b",
        ).joinToString("\n")
        val list = LinkParser.parseMany(Base64.getEncoder().encodeToString(body.toByteArray()))
        assertEquals(listOf("a", "b"), list.map { it.name })
    }

    @Test
    fun plainMultiLine() {
        val list = LinkParser.parseMany("vless://u@a.example:443#a\r\n\r\ntrojan://p@b.example:443#b\n")
        assertEquals(2, list.size)
    }

    @Test(expected = ParseException::class)
    fun removedTransportRejected() {
        LinkParser.parse("vless://u@a.example:443?type=h2#a")
    }

    @Test(expected = ParseException::class)
    fun badPortRejected() {
        LinkParser.parse("vless://u@a.example:99999#a")
    }

    @Test
    fun unknownLinesAreSkipped() {
        assertTrue(LinkParser.parseMany("hello\nworld").isEmpty())
    }
}
