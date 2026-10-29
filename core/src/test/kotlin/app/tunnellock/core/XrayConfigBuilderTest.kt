package app.tunnellock.core

import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.xray.AppRoute
import app.tunnellock.core.xray.CoreOptions
import app.tunnellock.core.xray.XrayConfigBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {

    private fun build(link: String, options: CoreOptions = CoreOptions()) =
        JSONObject(XrayConfigBuilder.build(LinkParser.parse(link), options))

    @Test
    fun tunInboundAndDnsRouting() {
        val c = build("vless://u@a.example:443?security=reality&sni=s.example&pbk=KEY&sid=ab&type=tcp#a")
        val inbound = c.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", inbound.getString("protocol"))
        assertEquals(1500, inbound.getJSONObject("settings").getInt("mtu"))

        val outbounds = c.getJSONArray("outbounds")
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("dns-out", outbounds.getJSONObject(1).getString("tag"))

        val rule = c.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("53", rule.getString("port"))
        assertEquals("dns-out", rule.getString("outboundTag"))

        // No "direct"/freedom outbound: nothing may leave outside the proxy.
        for (i in 0 until outbounds.length()) {
            assertFalse(outbounds.getJSONObject(i).getString("protocol") == "freedom")
        }
    }

    @Test
    fun vlessRealityOutbound() {
        val c = build("vless://uid@a.example:443?flow=xtls-rprx-vision&security=reality&sni=s.example&fp=Chrome&pbk=KEY&sid=ab&type=tcp#a")
        val proxy = c.getJSONArray("outbounds").getJSONObject(0)
        val settings = proxy.getJSONObject("settings")
        assertEquals("a.example", settings.getString("address"))
        assertEquals("uid", settings.getString("id"))
        assertEquals("none", settings.getString("encryption"))
        assertEquals("xtls-rprx-vision", settings.getString("flow"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("raw", stream.getString("network"))
        val reality = stream.getJSONObject("realitySettings")
        assertEquals("chrome", reality.getString("fingerprint"))
        assertEquals("KEY", reality.getString("publicKey"))
        assertEquals("ab", reality.getString("shortId"))
    }

    @Test
    fun tlsNeverUsesRemovedAllowInsecure() {
        val c = build("trojan://p@t.example:443?allowInsecure=1&type=ws&path=%2Fw&host=h.example#t")
        val stream = c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        val tls = stream.getJSONObject("tlsSettings")
        assertFalse(tls.has("allowInsecure"))
        assertEquals("h.example", tls.getString("serverName"))
        assertEquals("/w", stream.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun grpcAndXhttp() {
        val g = build("vless://u@a.example:443?type=grpc&serviceName=svc&mode=multi&security=tls#g")
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("svc", g.getString("serviceName"))
        assertTrue(g.getBoolean("multiMode"))

        val x = build("vless://u@a.example:443?type=xhttp&path=%2Fx&extra=%7B%22a%22%3A1%7D&security=tls#x")
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        assertEquals("/x", x.getString("path"))
        assertEquals("auto", x.getString("mode"))
        assertEquals(1, x.getJSONObject("extra").getInt("a"))
    }

    @Test
    fun shadowsocksHasNoStreamSettings() {
        val proxy = build("ss://aes-128-gcm:pw@1.2.3.4:8388#s").getJSONArray("outbounds").getJSONObject(0)
        assertFalse(proxy.has("streamSettings"))
        assertEquals("aes-128-gcm", proxy.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("method"))
    }

    @Test
    fun customConfigKeepsRoutingAndReplacesInbounds() {
        val custom = """{"remarks":"c","inbounds":[{"tag":"socks","port":10808,"protocol":"socks"}],
            "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"x","port":1,"users":[{"id":"u"}]}]}},
                         {"tag":"fragment","protocol":"freedom","settings":{"fragment":{"packets":"tlshello"}}}],
            "routing":{"rules":[{"inboundTag":["socks"],"outboundTag":"proxy"}]}}"""
        val c = JSONObject(XrayConfigBuilder.build(LinkParser.parse(custom)))
        val inbounds = c.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
        assertEquals("socks", inbounds.getJSONObject(0).getString("tag"))
        assertEquals(2, c.getJSONArray("outbounds").length())
        assertFalse(c.has("remarks"))
    }

    @Test
    fun hysteria2Outbound() {
        val proxy = build("hysteria2://secret@hy.example:443?sni=s.example&obfs=salamander&obfs-password=ob#h")
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria", proxy.getString("protocol"))
        assertEquals(2, proxy.getJSONObject("settings").getInt("version"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals("secret", stream.getJSONObject("hysteriaSettings").getString("auth"))
        assertEquals("s.example", stream.getJSONObject("tlsSettings").getString("serverName"))
        val mask = stream.getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0)
        assertEquals("salamander", mask.getString("type"))
        assertEquals("ob", mask.getJSONObject("settings").getString("password"))
    }

    @Test
    fun testConfigHasNoInbounds() {
        val c = build("vless://u@a.example:443#a", CoreOptions(useTun = false))
        assertEquals(0, c.getJSONArray("inbounds").length())
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun withoutAppRoutesNothingChanges() {
        val profile = LinkParser.parse("vless://u@a.example:443#a")
        assertEquals(XrayConfigBuilder.build(profile), XrayConfigBuilder.build(profile, appRoutes = emptyList()))
    }

    @Test
    fun appsGoOnlyThroughTheirOwnServer() {
        val main = LinkParser.parse("vless://u@main.example:443#main")
        val other = LinkParser.parse("vless://u@other.example:443#other")
        val c = JSONObject(
            XrayConfigBuilder.build(
                main,
                appRoutes = listOf(AppRoute(listOf(1010123, 1010124), other), AppRoute(listOf(1010200), null)),
            ),
        )
        val outbounds = c.getJSONArray("outbounds").objects()
        // The main server stays first: everything without a rule goes there.
        assertEquals("proxy", outbounds.first().getString("tag"))
        val own = outbounds.single { it.getString("tag") == "tl-app-1" }
        assertEquals("other.example", own.getJSONObject("settings").getString("address"))
        assertTrue(outbounds.any { it.getString("tag") == "tl-block" && it.getString("protocol") == "blackhole" })

        val rules = c.getJSONObject("routing").getJSONArray("rules").objects()
        // App rules come before the DNS rule, so an app's DNS goes through its own server too.
        assertEquals(listOf("1010123", "1010124"), rules[0].getJSONArray("process").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("tl-app-1", rules[0].getString("outboundTag"))
        // A server that is gone cuts its apps off instead of using the main server.
        assertEquals("1010200", rules[1].getJSONArray("process").getString(0))
        assertEquals("tl-block", rules[1].getString("outboundTag"))
        // Connections whose app is unknown are dropped (DNS aside).
        assertEquals("-1", rules[2].getJSONArray("process").getString(0))
        assertEquals("tl-block", rules[2].getString("outboundTag"))
        assertFalse(rules[2].getString("port").split(',').any { "53" in it.split('-') })
        assertEquals("dns-out", rules[3].getString("outboundTag"))
        assertEquals(4, rules.size)
        // Sniffed domains must not replace the address the app is found by.
        val sniffing = c.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
        assertTrue(sniffing.getBoolean("routeOnly"))
    }

    @Test
    fun sniffingOverridesAsBeforeWithoutAppRoutes() {
        val c = JSONObject(XrayConfigBuilder.build(LinkParser.parse("vless://u@a.example:443#a")))
        assertFalse(c.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing").optBoolean("routeOnly"))
    }

    @Test
    fun fakeDnsConfigsCannotCarryAppRoutes() {
        val fake = """{"dns":{"servers":["fakedns"]},"outbounds":[{"tag":"proxy","protocol":"vless","settings":{}}]}"""
        assertFalse(XrayConfigBuilder.canCarryAppRoutes(LinkParser.parse(fake)))
        assertTrue(XrayConfigBuilder.canCarryAppRoutes(LinkParser.parse("vless://u@a.example:443#a")))
    }

    @Test
    fun customMainConfigKeepsItsRulesAfterTheAppRules() {
        val custom = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"x","port":1,"users":[{"id":"u"}]}]}}],
            "routing":{"rules":[{"domain":["example.com"],"outboundTag":"proxy"}]}}"""
        val c = JSONObject(
            XrayConfigBuilder.build(
                LinkParser.parse(custom),
                appRoutes = listOf(AppRoute(listOf(1010123), LinkParser.parse("vless://u@other.example:443#o"))),
            ),
        )
        val rules = c.getJSONObject("routing").getJSONArray("rules").objects()
        assertEquals("tl-app-1", rules[0].getString("outboundTag"))
        assertEquals("tl-block", rules[1].getString("outboundTag"))
        assertEquals("example.com", rules[2].getJSONArray("domain").getString(0))
        assertTrue(c.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing").getBoolean("routeOnly"))
    }

    @Test
    fun aWholeJsonConfigCannotBeAnAppsServer() {
        val custom = LinkParser.parse("""{"outbounds":[{"tag":"proxy","protocol":"freedom"}]}""")
        assertFalse(XrayConfigBuilder.canRouteApps(custom))
        val c = JSONObject(
            XrayConfigBuilder.build(LinkParser.parse("vless://u@a.example:443#a"), appRoutes = listOf(AppRoute(listOf(1010123), custom))),
        )
        assertEquals("tl-block", c.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertFalse(c.getJSONArray("outbounds").objects().any { it.getString("tag").startsWith("tl-app-") })
    }
}
