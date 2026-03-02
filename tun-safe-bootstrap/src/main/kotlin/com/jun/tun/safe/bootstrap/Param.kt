package com.jun.tun.safe.bootstrap

/**
 * @author leolee
 * https://github.com/likaijunAi
 * l@xsocket.cn
 * create 2026/2/22 23:35
 **/
class Param {
    var mode: String = env("MODE", "server")
    var tcpBindHost: String = env("TCP_BIND_HOST", "0.0.0.0")
    var tcpBindPort: Int = env("TCP_BIND_PORT", 9090)
    var targetUdpHost: String? = envNullable("TARGET_UDP_HOST")
    var targetUdpPort: Int = env("TARGET_UDP_PORT", 3478)

    var udpBindHost: String = env("UDP_BIND_HOST", "0.0.0.0")
    var udpBindPort: Int = env("UDP_BIND_PORT", 8080)
    var remoteTcpHost: String? = envNullable("REMOTE_TCP_HOST")
    var remoteTcpPort: Int = env("REMOTE_TCP_PORT", 9090)
    var authToken: String = env("AUTH_TOKEN", "")

    var initialClientPoolSize: Int = env("INITIAL_CLIENT_POOL_SIZE", 0)

    var debug: Boolean = env("DEBUG", "false").toBoolean()
        set(value) {
            field = value
            System.setProperty("LOG_LEVEL", if (value) "DEBUG" else "INFO")
        }

    init {
        System.setProperty("LOG_LEVEL", if (debug) "DEBUG" else "INFO")
    }

    companion object {
        private fun env(key: String, default: String): String {
            return System.getenv(key) ?: default
        }

        private fun env(key: String, default: Int): Int {
            return System.getenv(key)?.toIntOrNull() ?: default
        }

        private fun envNullable(key: String): String? {
            return System.getenv(key)?.takeIf { it.isNotBlank() }
        }
    }

    override fun toString(): String {
        return """
            Param(
              mode=$mode,
              tcpBindHost=$tcpBindHost, tcpBindPort=$tcpBindPort,
              targetUdpHost=$targetUdpHost, targetUdpPort=$targetUdpPort,
              udpBindHost=$udpBindHost, udpBindPort=$udpBindPort,
              remoteTcpHost=$remoteTcpHost, remoteTcpPort=$remoteTcpPort,
              debug=$debug, authToken=${authToken.take(2)}***
            )
        """.trimIndent()
    }
}