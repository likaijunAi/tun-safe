package com.jun.tun.safe.bootstrap

import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * @author leolee
 * https://github.com/likaijunAi
 * l@xsocket.cn
 * create 2026/2/28 10:39
 **/
object LoadConf {
    private const val TEMP_ROOT = "safe.temp.dir"
    private const val CONF_FILE_NAME = "conf.properties"
    private val defaultConfDir = File(System.getProperty("user.dir"), "conf")

    private fun confFile(): File {
        val path = System.getProperty(TEMP_ROOT) ?: defaultConfDir.absolutePath
        return File(path, CONF_FILE_NAME)
    }

    fun loadParam(): Param {
        val file = confFile()
        return if (file.exists()) {
            loadFromFile(file)
        } else {
            Param()
        }
    }

    private fun loadFromFile(file: File): Param {
        val props = Properties().apply {
            FileInputStream(file).use { load(it) }
        }

        return Param().apply {
            props.getProperty("mode")?.let { mode = it }
            props.getProperty("tcp.bind.host")?.let { tcpBindHost = it }
            props.getProperty("tcp.bind.port")?.toIntOrNull()?.let { tcpBindPort = it }
            props.getProperty("target.udp.host")?.let { targetUdpHost = it }
            props.getProperty("target.udp.port")?.toIntOrNull()?.let { targetUdpPort = it }
            props.getProperty("udp.bind.host")?.let { udpBindHost = it }
            props.getProperty("udp.bind.port")?.toIntOrNull()?.let { udpBindPort = it }
            props.getProperty("remote.tcp.host")?.let { remoteTcpHost = it }
            props.getProperty("remote.tcp.port")?.toIntOrNull()?.let { remoteTcpPort = it }
            props.getProperty("initial.client.pool.size")?.toIntOrNull()?.let { initialClientPoolSize = it }
            props.getProperty("auth.token")?.let { authToken = it }
            props.getProperty("debug")?.toBooleanStrictOrNull()?.let { debug = it }
        }
    }
}