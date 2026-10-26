package app.tunnellock.core

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.openvpn.OpenVpnConfig
import app.tunnellock.core.openvpn.OvpnRemote
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenVpnConfigTest {

    private val config = """
        # Exported by the panel
        client
        dev tun
        proto udp
        remote vpn.example.com 1194
        remote 203.0.113.5 443 tcp-client
        resolv-retry infinite
        nobind
        persist-key
        auth-user-pass /etc/openvpn/login.txt
        cipher AES-256-GCM
        script-security 2
        up /etc/openvpn/update-resolv-conf
        block-outside-dns
        verb 4
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIBszCCAVmgAwIBAgIU
        -----END CERTIFICATE-----
        </ca>
        <tls-crypt>
        -----BEGIN OpenVPN Static key V1-----
        0123456789abcdef
        -----END OpenVPN Static key V1-----
        </tls-crypt>
    """.trimIndent()

    @Test
    fun detectsOvpnButNotLinksOrJson() {
        assertTrue(OpenVpnConfig.looksLikeOvpn(config))
        assertFalse(OpenVpnConfig.looksLikeOvpn("vless://id@example.com:443?security=tls#a\ntrojan://pw@example.com:443#b"))
        assertFalse(OpenVpnConfig.looksLikeOvpn("""{"outbounds":[]}"""))
        assertFalse(OpenVpnConfig.looksLikeOvpn("remote control notes"))
    }

    @Test
    fun parsesThroughLinkParser() {
        val p = LinkParser.parse(config)
        assertEquals(Protocol.OPENVPN, p.protocol)
        assertEquals("vpn.example.com", p.address)
        assertEquals(1194, p.port)
        assertEquals("udp", p.method)
        assertEquals("vpn.example.com", p.name)
        assertTrue(p.rawConfig!!.startsWith("# Exported"))

        val report = LinkParser.parseReport(config)
        assertEquals(1, report.servers.size)
        assertEquals(config, report.servers.single().source)
    }

    @Test
    fun readsAllRemotes() {
        assertEquals(
            listOf(OvpnRemote("vpn.example.com", 1194, "udp"), OvpnRemote("203.0.113.5", 443, "tcp")),
            OpenVpnConfig.remotes(config),
        )
        val connections = """
            client
            port 1300
            <connection>
            remote a.example.com
            proto tcp
            </connection>
            <ca>
            x
            </ca>
        """.trimIndent()
        // The proto inside the <connection> block applies to its remote.
        assertEquals(listOf(OvpnRemote("a.example.com", 1300, "tcp")), OpenVpnConfig.remotes(connections))
    }

    @Test
    fun knowsWhatItNeeds() {
        val needs = OpenVpnConfig.needs(config)
        assertTrue(needs.credentials)
        assertFalse(needs.keyPassword)

        val inline = config.replace("auth-user-pass /etc/openvpn/login.txt", "<auth-user-pass>\nuser\npass\n</auth-user-pass>")
        assertFalse(OpenVpnConfig.needs(inline).credentials)

        val encrypted = config + "\n<cert>\nc\n</cert>\n<key>\n-----BEGIN ENCRYPTED PRIVATE KEY-----\nk\n</key>"
        assertTrue(OpenVpnConfig.needs(encrypted).keyPassword)
    }

    @Test
    fun runtimeConfigIsSafeAndManaged() {
        val run = OpenVpnConfig.runtimeConfig(config, "/data/x/mgmt", "TunnelLock 0.1.20")
        val lines = run.lines()
        assertTrue("management /data/x/mgmt unix" in lines)
        assertTrue("management-client" in lines)
        assertTrue("management-query-passwords" in lines)
        assertTrue("management-hold" in lines)
        assertTrue("setenv IV_GUI_VER \"TunnelLock 0.1.20\"" in lines)
        // The login is asked over management, never read from a file.
        assertTrue("auth-user-pass" in lines)
        assertFalse(run.contains("login.txt"))
        // Scripts and Windows-only options are gone.
        assertFalse(run.contains("script-security"))
        assertFalse(run.contains("update-resolv-conf"))
        assertFalse(run.contains("block-outside-dns"))
        // Logging stays at a level that keeps details out of the app's log.
        assertFalse("verb 4" in lines)
        assertEquals(1, lines.count { it == "verb 3" })
        // Defaults are added only where missing.
        assertTrue("persist-tun" in lines)
        assertTrue("connect-retry 2 30" in lines)
        assertEquals(1, lines.count { it.startsWith("dev ") })
        assertTrue(run.contains("<ca>\n-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\n-----END CERTIFICATE-----\n</ca>"))
        assertTrue(run.contains("<tls-crypt>"))
    }

    @Test
    fun inlineLoginStaysInline() {
        val inline = config.replace("auth-user-pass /etc/openvpn/login.txt", "<auth-user-pass>\nuser\npass\n</auth-user-pass>")
        val run = OpenVpnConfig.runtimeConfig(inline, "/m", "t")
        assertFalse("auth-user-pass" in run.lines())
        assertTrue(run.contains("<auth-user-pass>\nuser\npass\n</auth-user-pass>"))
    }

    @Test
    fun tlsAuthFileNextToInlineBlockKeepsDirection() {
        val withTlsAuth = config.replace("<tls-crypt>", "tls-auth ta.key 1\n<tls-auth>").replace("</tls-crypt>", "</tls-auth>")
        val run = OpenVpnConfig.runtimeConfig(withTlsAuth, "/m", "t")
        assertFalse(run.contains("ta.key"))
        assertTrue("key-direction 1" in run.lines())
        assertTrue(run.contains("<tls-auth>"))
    }

    @Test
    fun rejectsWhatAndroidCannotRun() {
        expectError("inline") { OpenVpnConfig.parse(config.replace("<ca>", "ca ca.crt\n<cert>").replace("</ca>", "</cert>")) }
        expectError("dev tun") { OpenVpnConfig.parse(config.replace("dev tun", "dev tap")) }
        expectError("server config") { OpenVpnConfig.parse(config.replace("client", "server 10.8.0.0 255.255.255.0")) }
        expectError("never closed") { OpenVpnConfig.parse(config.replace("</tls-crypt>", "")) }
        expectError("no remote") { OpenVpnConfig.parse("client\ndev tun\n<ca>\nx\n</ca>") }
    }

    @Test
    fun quotedArgumentsAndTrailingComments() {
        val text = "client\ndev tun\nremote \"vpn example.com\" 1195 # backup\n<ca>\nx\n</ca>"
        assertEquals(listOf(OvpnRemote("vpn example.com", 1195, "udp")), OpenVpnConfig.remotes(text))
    }

    private fun expectError(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected an error containing \"$fragment\"")
        } catch (e: ParseException) {
            assertTrue("\"${e.message}\" should contain \"$fragment\"", e.message.orEmpty().contains(fragment))
        }
    }
}
