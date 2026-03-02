package com.jun.tun.safe.bootstrap

import com.jun.tun.safe.core.manager.TunnelManager
import com.jun.tun.safe.core.protocol.PacketProtocol


/**
 * @author leolee
 * https://github.com/likaijunAi
 * l@xsocket.cn
 * create 2026/2/9 13:44
 **/
class Bootstrap

fun main(args: Array<String>) {
    val param = LoadConf.loadParam()
    PacketProtocol.setAuthToken(param.authToken)

    when (param.mode) {
        "server" -> {
            startServer(param)
        }

        "client" -> {
            startClient(param)
        }

        else -> {
            throw IllegalArgumentException("Invalid mode: ${param.mode}")
        }
    }
}

fun startServer(param: Param) {
    param.targetUdpHost ?: throw IllegalArgumentException("targetUdpHost is required")
    val manager = TunnelManager()
    manager.startServerMode(
        tcpBindHost = param.tcpBindHost,
        tcpBindPort = param.tcpBindPort,
        targetUdpHost = param.targetUdpHost!!,
        targetUdpPort = param.targetUdpPort
    )
}

fun startClient(param: Param) {
    param.remoteTcpHost ?: throw IllegalArgumentException("remoteTcpHost is required")
    val manager = TunnelManager()
    manager.startClientMode(
        udpBindHost = param.udpBindHost,
        udpBindPort = param.udpBindPort,
        remoteTcpHost = param.remoteTcpHost!!,
        remoteTcpPort = param.remoteTcpPort,
        initialPoolSize = param.initialClientPoolSize
    )
}