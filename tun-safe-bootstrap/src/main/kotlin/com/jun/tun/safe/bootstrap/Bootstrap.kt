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
    val parm = Parm()
    PacketProtocol.setAuthToken(parm.authToken)
    when (parm.mode) {
        "server" -> {
            startServer(parm)
        }
        "client" -> {
            startClient(parm)
        }
        else -> {
            throw IllegalArgumentException("Invalid mode: ${parm.mode}")
        }
    }
}

fun startServer(parm: Parm) {
    parm.targetUdpHost ?: throw IllegalArgumentException("targetUdpHost is required")
    val manager = TunnelManager()
    manager.startServerMode(
        tcpBindPort = parm.tcpBindPort,
        targetUdpHost = parm.targetUdpHost!!,
        targetUdpPort = parm.targetUdpPort
    )
}

fun startClient(parm: Parm) {
    parm.remoteTcpHost ?: throw IllegalArgumentException("remoteTcpHost is required")
    val manager = TunnelManager()
    manager.startClientMode(
        udpBindHost = parm.udpBindHost,
        udpBindPort = parm.udpBindPort,
        remoteTcpHost = parm.remoteTcpHost!!,
        remoteTcpPort = parm.remoteTcpPort
    )
}