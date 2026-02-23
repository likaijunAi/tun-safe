package com.jun.tun.safe.core.config

/**
 * Tunnel configuration class
 *
 * Defines configuration parameters for udp2raw tunnel
 *
 * @author leolee
 * @create 2026/2/8
 */
data class TunnelConfig(
    // General configuration
    val mode: TunnelMode = TunnelMode.SERVER,
    
    // Network configuration
    val bindHost: String = "0.0.0.0",
    val udpBindPort: Int = 8080,
    val tcpBindPort: Int = 9090,
    
    // Remote connection configuration
    val remoteTcpHost: String = "127.0.0.1",
    val remoteTcpPort: Int = 9090,
    
    // Target server configuration
    val targetUdpHost: String = "127.0.0.1",
    val targetUdpPort: Int = 8081,
    
    // Performance configuration
    val bufferSize: Int = 65536,
    val connectionTimeoutMs: Int = 5000,
    val idleTimeoutMs: Int = 30000,
    
    // Logging configuration
    val enableDebugLogging: Boolean = false
) {
    
    enum class TunnelMode {
        SERVER,  // Server mode
        CLIENT   // Client mode
    }
    
    companion object {
        /**
         * Create default server configuration
         */
        fun defaultServerConfig(
            udpPort: Int = 8080,
            tcpPort: Int = 9090,
            targetHost: String = "127.0.0.1",
            targetPort: Int = 8081
        ): TunnelConfig {
            return TunnelConfig(
                mode = TunnelMode.SERVER,
                udpBindPort = udpPort,
                tcpBindPort = tcpPort,
                targetUdpHost = targetHost,
                targetUdpPort = targetPort
            )
        }
        
        /**
         * Create default client configuration
         */
        fun defaultClientConfig(
            remoteHost: String = "127.0.0.1",
            remotePort: Int = 9090,
            targetHost: String = "127.0.0.1",
            targetPort: Int = 8081
        ): TunnelConfig {
            return TunnelConfig(
                mode = TunnelMode.CLIENT,
                remoteTcpHost = remoteHost,
                remoteTcpPort = remotePort,
                targetUdpHost = targetHost,
                targetUdpPort = targetPort
            )
        }
    }
}