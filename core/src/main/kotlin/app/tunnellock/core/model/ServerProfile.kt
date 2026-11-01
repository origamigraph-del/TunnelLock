package app.tunnellock.core.model

enum class Protocol(val label: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    HYSTERIA2("Hysteria2"),
    CUSTOM("Xray JSON"),
    OPENVPN("OpenVPN"),

    /** An SSH account: TunnelLock keeps the SSH connection and Xray sends traffic into it. */
    SSH("SSH"),

    /** A SOCKS5 proxy. Used inside TunnelLock to hand Xray's traffic to the SSH connection. */
    SOCKS("SOCKS"),
    ;

    /** An outbound of the Xray core (OpenVPN and SSH run their own connection). */
    val isXray: Boolean get() = this != OPENVPN && this != SSH
}

/** Transport and security settings shared by the V2Ray-family protocols. */
data class StreamConfig(
    /** tcp, ws, grpc, httpupgrade, xhttp, kcp, hysteria. SSH: tcp, tls, ws, wss. */
    val network: String = "tcp",
    /** none, tls, reality */
    val security: String = "none",
    val sni: String? = null,
    val fingerprint: String? = null,
    val alpn: List<String> = emptyList(),
    /** TLS: pinned certificate SHA-256 (replaces the removed allowInsecure). */
    val pinnedPeerCertSha256: String? = null,
    /** TLS: verify the certificate against this name instead of the SNI. */
    val verifyPeerCertByName: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realitySpiderX: String? = null,
    val realityMldsa65Verify: String? = null,
    val host: String? = null,
    val path: String? = null,
    val serviceName: String? = null,
    val authority: String? = null,
    /** grpc: gun / multi. xhttp: auto / packet-up / stream-up / stream-one. */
    val mode: String? = null,
    /** tcp: none / http. kcp: header type. */
    val headerType: String? = null,
    val seed: String? = null,
    /** xhttp "extra" object as raw JSON. */
    val xhttpExtra: String? = null,
    /** Hysteria2 "salamander" obfuscation password. */
    val obfsPassword: String? = null,
)

data class ServerProfile(
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    /** UUID for VLESS/VMess, password for Trojan/Shadowsocks/SSH/SOCKS, auth for Hysteria2. Unused for OpenVPN. */
    val secret: String = "",
    /** SSH and SOCKS login name. */
    val username: String? = null,
    /** SSH: the server's host key fingerprint ("SHA256:..."), when the link pins it. */
    val hostKey: String? = null,
    /** Shadowsocks cipher, VMess security, or VLESS encryption. */
    val method: String? = null,
    val flow: String? = null,
    val stream: StreamConfig = StreamConfig(),
    /** Full Xray JSON for [Protocol.CUSTOM]; the .ovpn file for [Protocol.OPENVPN]. */
    val rawConfig: String? = null,
) {
    val displayAddress: String
        get() = if (address.contains(':')) "[$address]:$port" else "$address:$port"
}
