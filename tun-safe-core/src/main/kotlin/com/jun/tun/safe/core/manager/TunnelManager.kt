package com.jun.tun.safe.core.manager

import com.jun.tun.safe.core.tcp.SafeServer
import com.jun.tun.safe.core.udp.TunServer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tunnel manager - coordinates the operation of the entire udp2raw tunnel system
 *
 * Manages the lifecycle of UDP and TCP components, provides unified start/stop interface
 * Supports resource sharing between client and server modes
 *
 * @author leolee
 * @create 2026/2/8
 */
class TunnelManager(
    // 可选配置参数
    private val shutdownTimeoutSeconds: Long = 10
) {

    private val logger = LoggerFactory.getLogger(TunnelManager::class.java)

    // 状态管理
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val mode = AtomicReference<Mode>(Mode.NONE)

    // 组件实例
    @Volatile
    private var tunServer: TunServer? = null
    @Volatile
    private var safeServer: SafeServer? = null

    // 共享的 EventLoopGroup（用于资源优化）
    private var sharedBossGroup: EventLoopGroup? = null
    private var sharedWorkerGroup: EventLoopGroup? = null

    enum class Mode {
        NONE, SERVER, CLIENT, DUAL
    }

    /**
     * 启动服务器模式（接收 TCP 数据，转发到 UDP）
     * 适用于：服务端，接收来自客户端的 TCP 隧道数据
     *
     * @param tcpBindHost TCP 监听地址
     * @param tcpBindPort TCP 监听端口
     * @param targetUdpHost 目标 UDP 服务器地址
     * @param targetUdpPort 目标 UDP 服务器端口
     */
    fun startServerMode(
        tcpBindHost: String = "0.0.0.0",
        tcpBindPort: Int,
        targetUdpHost: String,
        targetUdpPort: Int
    ): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!started.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("Manager already started"))
            return future
        }

        mode.set(Mode.SERVER)

        logger.info("Starting server mode...")
        logger.info("TCP bind: {}:{}", tcpBindHost, tcpBindPort)
        logger.info("Target UDP: {}:{}", targetUdpHost, targetUdpPort)

        try {
            ensureSharedGroups()

            safeServer = SafeServer(
                bindHost = tcpBindHost,
                bindPort = tcpBindPort,
                udpTargetHost = targetUdpHost,
                udpTargetPort = targetUdpPort,
                externalBossGroup = sharedBossGroup,
                externalWorkerGroup = sharedWorkerGroup
            )

            safeServer?.start()?.whenComplete { _, throwable ->
                if (throwable != null) {
                    logger.error("Failed to start TCP server", throwable)
                    cleanup()
                    future.completeExceptionally(throwable)
                } else {
                    logger.info("Server mode started successfully")
                    future.complete(null)
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to start server mode", e)
            cleanup()
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 启动客户端模式（接收 UDP 数据，通过 TCP 发送）
     * 适用于：客户端，将本地 UDP 数据封装到 TCP 隧道
     *
     * @param udpBindHost UDP 监听地址
     * @param udpBindPort UDP 监听端口
     * @param remoteTcpHost 远程 TCP 服务器地址
     * @param remoteTcpPort 远程 TCP 服务器端口
     */
    fun startClientMode(
        udpBindHost: String = "0.0.0.0",
        udpBindPort: Int,
        remoteTcpHost: String,
        remoteTcpPort: Int
    ): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!started.compareAndSet(false, true)) {
            if (mode.get() == Mode.SERVER && tunServer == null) {
                logger.info("Upgrading to dual mode, adding client component...")
                mode.set(Mode.DUAL)
            } else {
                future.completeExceptionally(IllegalStateException(
                    "Manager already started in mode: ${mode.get()}"
                ))
                return future
            }
        } else {
            mode.set(Mode.CLIENT)
        }

        logger.info("Starting client mode...")
        logger.info("UDP bind: {}:{}", udpBindHost, udpBindPort)
        logger.info("Remote TCP: {}:{}", remoteTcpHost, remoteTcpPort)

        try {
            ensureSharedGroups()

            tunServer = TunServer(
                bindHost = udpBindHost,
                bindPort = udpBindPort,
                tcpTargetHost = remoteTcpHost,
                tcpTargetPort = remoteTcpPort,
                externalWorkerGroup = sharedWorkerGroup
            )

            tunServer?.start()?.whenComplete { _, throwable ->
                if (throwable != null) {
                    logger.error("Failed to start UDP server", throwable)
                    // 如果只是客户端模式，需要清理；如果是双模式，保留服务器
                    if (mode.get() == Mode.CLIENT) {
                        cleanup()
                    } else {
                        tunServer = null
                    }
                    future.completeExceptionally(throwable)
                } else {
                    logger.info("Client mode started successfully")
                    future.complete(null)
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to start client mode", e)
            if (mode.get() == Mode.CLIENT) {
                cleanup()
            }
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 启动双模式（同时作为客户端和服务器）
     * 适用于：中继节点，同时接收 UDP 客户端和 TCP 客户端
     */
    fun startDualMode(
        // 服务器模式参数
        tcpBindHost: String = "0.0.0.0",
        tcpBindPort: Int,
        targetUdpHost: String,
        targetUdpPort: Int,
        // 客户端模式参数
        udpBindHost: String = "0.0.0.0",
        udpBindPort: Int,
        remoteTcpHost: String,
        remoteTcpPort: Int
    ): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!started.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("Manager already started"))
            return future
        }

        mode.set(Mode.DUAL)
        logger.info("Starting dual mode...")

        ensureSharedGroups()

        // 先启动服务器模式
        val serverFuture = CompletableFuture<Void>()
        val clientFuture = CompletableFuture<Void>()

        try {
            safeServer = SafeServer(
                bindHost = tcpBindHost,
                bindPort = tcpBindPort,
                udpTargetHost = targetUdpHost,
                udpTargetPort = targetUdpPort,
                externalBossGroup = sharedBossGroup,
                externalWorkerGroup = sharedWorkerGroup
            )

            safeServer?.start()?.whenComplete { _, ex ->
                if (ex != null) {
                    serverFuture.completeExceptionally(ex)
                } else {
                    serverFuture.complete(null)
                }
            }

        } catch (e: Exception) {
            serverFuture.completeExceptionally(e)
        }

        // 服务器启动成功后启动客户端
        serverFuture.whenComplete { _, serverEx ->
            if (serverEx != null) {
                cleanup()
                future.completeExceptionally(serverEx)
                return@whenComplete
            }

            try {
                tunServer = TunServer(
                    bindHost = udpBindHost,
                    bindPort = udpBindPort,
                    tcpTargetHost = remoteTcpHost,
                    tcpTargetPort = remoteTcpPort,
                    externalWorkerGroup = sharedWorkerGroup
                )

                tunServer?.start()?.whenComplete { _, clientEx ->
                    if (clientEx != null) {
                        // 客户端失败，但服务器已启动，进入降级模式
                        logger.warn("Client mode failed, running in server-only mode", clientEx)
                        mode.set(Mode.SERVER)
                        tunServer = null
                        future.complete(null) // 仍视为成功，只是降级
                    } else {
                        logger.info("Dual mode started successfully")
                        future.complete(null)
                    }
                }

            } catch (e: Exception) {
                logger.warn("Client mode failed, running in server-only mode", e)
                mode.set(Mode.SERVER)
                tunServer = null
                future.complete(null)
            }
        }

        return future
    }

    /**
     * 优雅停止所有组件（异步）
     */
    fun stop(): CompletableFuture<Void> {
        return stopAsync()
    }

    /**
     * 同步停止（阻塞直到完成）
     */
    fun stopSync(timeoutSeconds: Long = shutdownTimeoutSeconds): Boolean {
        return try {
            stopAsync().get(timeoutSeconds, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            logger.error("Stop timeout or interrupted", e)
            false
        }
    }

    private fun stopAsync(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!stopped.compareAndSet(false, true)) {
            logger.warn("Manager already stopped")
            future.complete(null)
            return future
        }

        if (!started.get()) {
            logger.warn("Manager not started")
            future.complete(null)
            return future
        }

        logger.info("Stopping tunnel system (mode: {})...", mode.get())

        val futures = mutableListOf<CompletableFuture<Void>>()

        // 停止客户端组件（先停客户端，避免新连接）
        tunServer?.let { server ->
            val stopFuture = CompletableFuture<Void>()
            futures.add(stopFuture)

            try {
                server.stop().whenComplete { _, _ ->
                    logger.info("TunServer stopped")
                    stopFuture.complete(null)
                }
            } catch (e: Exception) {
                logger.error("Error stopping TunServer", e)
                stopFuture.complete(null) // 忽略错误，继续停止其他
            }
        }

        // 停止服务器组件
        safeServer?.let { server ->
            val stopFuture = CompletableFuture<Void>()
            futures.add(stopFuture)

            try {
                server.stop().whenComplete { _, _ ->
                    logger.info("SafeServer stopped")
                    stopFuture.complete(null)
                }
            } catch (e: Exception) {
                logger.error("Error stopping SafeServer", e)
                stopFuture.complete(null)
            }
        }

        // 等待所有组件停止后关闭共享资源
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            shutdownSharedGroups()
            started.set(false)
            mode.set(Mode.NONE)
            logger.info("Tunnel system completely stopped")
            future.complete(null)
        }

        return future
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): Status {
        return Status(
            mode = mode.get(),
            isRunning = started.get() && !stopped.get(),
            isServerRunning = safeServer?.isRunning() ?: false,
            isClientRunning = tunServer?.isRunning() ?: false,
            sharedGroupsActive = sharedBossGroup != null
        )
    }

    data class Status(
        val mode: Mode,
        val isRunning: Boolean,
        val isServerRunning: Boolean,
        val isClientRunning: Boolean,
        val sharedGroupsActive: Boolean
    )

    // ==================== 私有方法 ====================

    private fun ensureSharedGroups() {
        if (sharedBossGroup == null) {
            sharedBossGroup = NioEventLoopGroup(1)
            logger.debug("Created shared boss group")
        }
        if (sharedWorkerGroup == null) {
            sharedWorkerGroup = NioEventLoopGroup()
            logger.debug("Created shared worker group")
        }
    }

    private fun shutdownSharedGroups() {
        val boss = sharedBossGroup
        val worker = sharedWorkerGroup

        if (boss != null) {
            boss.shutdownGracefully(0, shutdownTimeoutSeconds, TimeUnit.SECONDS)
            sharedBossGroup = null
        }
        if (worker != null) {
            worker.shutdownGracefully(0, shutdownTimeoutSeconds, TimeUnit.SECONDS)
            sharedWorkerGroup = null
        }
    }

    private fun cleanup() {
        started.set(false)
        mode.set(Mode.NONE)
        shutdownSharedGroups()
    }
}