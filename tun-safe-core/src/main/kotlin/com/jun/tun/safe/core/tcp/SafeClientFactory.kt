package com.jun.tun.safe.core.tcp

import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * SafeClient 连接池工厂
 * 管理 TCP 连接的复用和生命周期
 *
 * @author leolee
 * https://github.com/likaijunAi
 * l@xsocket.cn
 * create 2026/3/1 14:13
 */
class SafeClientFactory(
    private val targetTcpHost: String,
    private val targetTcpPort: Int,
    private val sharedWorkerGroup: EventLoopGroup? = null,
    private val initialPoolSize: Int = 0,  // 默认预初始化连接数为 0
    private val maxPoolSize: Int = 100,     // 最大连接池大小
    private val maxRetries: Int = 3,        // 最大重试次数
    private val retryDelaySeconds: Long = 3 // 重试延迟（秒）
) {
    private val logger = LoggerFactory.getLogger(SafeClientFactory::class.java)

    // 活跃连接：addressKey -> SafeClient
    private val activeConnections = ConcurrentHashMap<String, SafeClient>()

    // 空闲连接池（预初始化的连接，未绑定具体 UDP 客户端）
    private val idleConnections = ConcurrentHashMap.newKeySet<SafeClient>()

    // 连接中标记，防止重复创建
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    // 等待回调队列
    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<(SafeClient?) -> Unit>>()

    // 连接统计
    private val connectionCounter = AtomicInteger(0)

    // 用于调度延时任务的线程池
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // 存储当前正在运行的延时任务
    private var scheduledTask: ScheduledFuture<*>? = null

    init {
        if (initialPoolSize > 0) {
            initializePool()
        }
    }

    /**
     * 预初始化连接池
     */
    private fun initializePool() {
        logger.info(
            "Initializing connection pool with {} connections to {}:{}",
            initialPoolSize, targetTcpHost, targetTcpPort
        )
        val size = idleConnections.size + 1

        val futures = (size..initialPoolSize).map { index ->
            createIdleClient().whenComplete { client, error ->
                if (error != null || client == null) {
                    logger.error("Failed to initialize pool connection {}/{}", index, initialPoolSize, error)
                } else {
                    idleConnections.add(client)
                    logger.debug(
                        "Pool connection {}/{} initialized [clientId={}]",
                        index, initialPoolSize, client.clientId
                    )
                }
            }
        }

        // 等待所有预初始化完成（可选，根据需求决定是否阻塞）
        CompletableFuture.allOf(*futures.toTypedArray()).orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { _, error ->
                if (error != null) {
                    logger.warn("Pool initialization timeout or error: {}", error.message)
                } else {
                    logger.info("Connection pool initialized: {} idle connections", idleConnections.size)
                }
            }
    }

    /**
     * 创建空闲连接（不绑定 UDP 客户端）
     */
    private fun createIdleClient(): CompletableFuture<SafeClient> {
        val future = CompletableFuture<SafeClient>()
        val client = SafeClient(targetTcpHost, targetTcpPort, sharedWorkerGroup)

        client.onDisconnect = {
            logger.info("Idle client disconnected [clientId={}], removing from pool", it.clientId)
            idleConnections.remove(it)
            activeConnections.values.remove(it)

            //启动一个延时任务 延时5秒  initializePool()
            scheduleInitializePool()
        }

        client.start().whenComplete { success, error ->
            when {
                error != null -> future.completeExceptionally(error)
                success -> {
                    connectionCounter.incrementAndGet()
                    future.complete(client)
                }

                else -> future.completeExceptionally(RuntimeException("Failed to start client"))
            }
        }

        return future
    }

    /**
     * 调度 initializePool() 方法，确保任务唯一性
     */
    private fun scheduleInitializePool() {
        scheduledTask?.cancel(false)

        scheduledTask = scheduler.schedule({
            initializePool()
        }, 5, TimeUnit.SECONDS)
    }

    /**
     * 获取或创建客户端（异步）
     * 优先使用空闲连接池中的连接
     */
    fun getOrCreateClientAsync(
        socketAddress: InetSocketAddress,
        addressKey: String,
        udpChannel: Channel,
        callback: (SafeClient?) -> Unit
    ) {
        val existing = activeConnections[addressKey]
        if (existing != null && existing.isConnected()) {
            callback(existing)
            return
        }

        val idleClient = idleConnections.firstOrNull { it.isConnected() }
        if (idleClient != null && idleConnections.remove(idleClient)) {
            idleClient.setUdpClient(socketAddress.hostString, socketAddress.port, udpChannel)
            idleClient.onDisconnect = {
                logger.info("Active client disconnected [clientId={}], removing {}", it.clientId, addressKey)
                activeConnections.remove(addressKey, it)

                if (it.isLocalClient()) {
                    it.setUdpClient("", 0, null)
                    it.shutdown()

                    scheduleInitializePool()
                } else if (!it.isConnected()) {
                    scheduleReconnect(socketAddress, addressKey, udpChannel)
                }
            }

            val prev = activeConnections.putIfAbsent(addressKey, idleClient)
            if (prev != null && prev.isConnected() && !prev.isLocalClient()) {
                idleClient.setUdpClient("", 0, null)
                idleConnections.add(idleClient)
                callback(prev)
            } else {
                logger.info("Reused idle connection for {} [clientId={}]", addressKey, idleClient.clientId)
                callback(idleClient)
            }
            return
        }

        if (!connecting.add(addressKey)) {
            pendingCallbacks.computeIfAbsent(addressKey) { mutableListOf() }.add(callback)
            return
        }

        createClientWithRetry(socketAddress, addressKey, udpChannel, AtomicInteger(0), callback)
    }

    /**
     * 带重试的连接创建
     */
    private fun createClientWithRetry(
        socketAddress: InetSocketAddress,
        addressKey: String,
        udpChannel: Channel,
        attemptCounter: AtomicInteger,
        originalCallback: (SafeClient?) -> Unit
    ) {
        val attempt = attemptCounter.incrementAndGet()

        if (attempt > maxRetries) {
            logger.error("Failed to connect TCP server for {} after {} attempts", addressKey, maxRetries)
            completeConnection(addressKey, null, originalCallback)
            return
        }

        // 检查池大小限制
        if (activeConnections.size + idleConnections.size >= maxPoolSize && attempt == 1) {
            logger.warn("Connection pool reached max size: {}, waiting for available connection", maxPoolSize)
            // 可以在这里实现等待逻辑，或继续尝试
        }

        val safeClient = SafeClient(targetTcpHost, targetTcpPort, sharedWorkerGroup).apply {
            setUdpClient(socketAddress.hostString, socketAddress.port, udpChannel)
        }

        safeClient.onDisconnect = {
            logger.info("TCP client disconnected, removing {} [clientId={}]", addressKey, it.clientId)
            activeConnections.remove(addressKey, it)
        }

        safeClient.start().whenComplete { success, error ->
            when {
                error != null -> {
                    logger.error(
                        "Exception while creating TCP client for {} (attempt {}/{})",
                        addressKey, attempt, maxRetries, error
                    )
                    scheduleRetry(socketAddress, addressKey, udpChannel, attemptCounter, originalCallback)
                }

                success -> {
                    connectionCounter.incrementAndGet()
                    val prev = activeConnections.putIfAbsent(addressKey, safeClient)
                    if (prev != null && prev.isConnected()) {
                        safeClient.shutdown()
                        completeConnection(addressKey, prev, originalCallback)
                    } else {
                        logger.info(
                            "TCP connection established for {} -> {}:{} [clientId={}]",
                            addressKey, targetTcpHost, targetTcpPort, safeClient.clientId
                        )
                        completeConnection(addressKey, safeClient, originalCallback)
                    }
                }

                else -> {
                    logger.error(
                        "Failed to connect TCP server for {} (attempt {}/{})",
                        addressKey, attempt, maxRetries
                    )
                    scheduleRetry(socketAddress, addressKey, udpChannel, attemptCounter, originalCallback)
                }
            }
        }
    }

    /**
     * 调度重试
     */
    private fun scheduleRetry(
        socketAddress: InetSocketAddress,
        addressKey: String,
        udpChannel: Channel,
        attemptCounter: AtomicInteger,
        callback: (SafeClient?) -> Unit
    ) {
        udpChannel.eventLoop().schedule({
            createClientWithRetry(socketAddress, addressKey, udpChannel, attemptCounter, callback)
        }, retryDelaySeconds, TimeUnit.SECONDS)
    }

    /**
     * 调度重连（当活跃连接断开时）
     */
    private fun scheduleReconnect(
        socketAddress: InetSocketAddress,
        addressKey: String,
        udpChannel: Channel
    ) {
        if (!connecting.add(addressKey)) return // 已在重连中

        udpChannel.eventLoop().schedule({
            createClientWithRetry(socketAddress, addressKey, udpChannel, AtomicInteger(0)) { client ->
                if (client == null) {
                    logger.error("Reconnect failed for {}", addressKey)
                }
            }
        }, retryDelaySeconds, TimeUnit.SECONDS)
    }

    /**
     * 完成连接，触发所有等待回调
     */
    private fun completeConnection(addressKey: String, client: SafeClient?, originalCallback: (SafeClient?) -> Unit) {
        originalCallback(client)

        val callbacks = pendingCallbacks.remove(addressKey)
        callbacks?.forEach { it(client) }

        connecting.remove(addressKey)
    }

    /**
     * 释放连接（当 UDP 客户端断开时）
     */
    fun releaseConnection(addressKey: String) {
        val client = activeConnections.remove(addressKey)
        if (client != null) {
            logger.info("Releasing connection for {} [clientId={}]", addressKey, client.clientId)
            client.setUdpClient("", 0, null)
            if (client.isConnected() && idleConnections.size < initialPoolSize) {
                client.onDisconnect = {
                    idleConnections.remove(it)
                    activeConnections.values.remove(it)
                }
            } else {
                client.shutdown()
            }
        }
    }

    /**
     * 获取连接统计信息
     */
    fun getStats(): Map<String, Int> = mapOf(
        "active" to activeConnections.size,
        "idle" to idleConnections.size,
        "connecting" to connecting.size,
        "pendingCallbacks" to pendingCallbacks.size,
        "totalCreated" to connectionCounter.get()
    )

    /**
     * 关闭工厂，释放所有资源
     */
    fun shutdown() {
        logger.info("Shutting down SafeClientFactory, stats before shutdown: {}", getStats())

        activeConnections.values.forEach { it.shutdown() }
        idleConnections.forEach { it.shutdown() }

        activeConnections.clear()
        idleConnections.clear()
        connecting.clear()
        pendingCallbacks.clear()
        scheduler.shutdown()

        logger.info("SafeClientFactory shutdown complete")
    }
}