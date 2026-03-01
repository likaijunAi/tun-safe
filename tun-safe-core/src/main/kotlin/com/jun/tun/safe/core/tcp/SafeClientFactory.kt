package com.jun.tun.safe.core.tcp

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * @author leolee
 * https://github.com/likaijunAi
 * l@xsocket.cn
 * create 2026/3/1 14:13
 **/
class SafeClientFactory {
    private val logger = LoggerFactory.getLogger(SafeClientFactory::class.java)

    private val connections = ConcurrentHashMap<String, SafeClient>()

    private val connecting = ConcurrentHashMap.newKeySet<String>()

    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<(SafeClient?) -> Unit>>()


}