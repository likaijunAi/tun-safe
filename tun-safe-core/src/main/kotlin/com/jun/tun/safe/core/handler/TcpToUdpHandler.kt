package com.jun.tun.safe.core.handler

import com.jun.tun.safe.core.protocol.PacketProtocol
import com.jun.tun.safe.core.udp.TunClient
import io.netty.channel.*
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP to UDP data conversion handler (重构版)
 *
 * 每个TCP连接对应一个UDP Channel，实现双向数据转发：
 * TCP Client -> [Length][Data] -> SafeServer -> UDP Packet -> Target Server
 * TCP Client <- [Length][Data] <- SafeServer <- UDP Packet <- Target Server
 *
 * @author leolee
 * @create 2026/2/8
 */
class TcpToUdpHandler(
    private val targetUdpHost: String,
    private val targetUdpPort: Int,
    private val sharedWorkerGroup: EventLoopGroup? = null,
    private val heartbeatTimeout: Int = 60
) : ChannelDuplexHandler() {

    private val logger = LoggerFactory.getLogger(TcpToUdpHandler::class.java)

    // TCP Channel ID -> UDP Client 映射
    private val connections = ConcurrentHashMap<ChannelId, TunClient>()

    private val pendingFutures = ConcurrentHashMap<ChannelId, CompletableFuture<TunClient>>()

    private val connectTimeoutSeconds = 10L
    private val handlerClosed = AtomicBoolean(false)

    /**
     * 当TCP连接建立时，创建对应的UDP Channel
     */
    override fun channelActive(ctx: ChannelHandlerContext) {
        val tcpChannel = ctx.channel()
        logger.info(
            "TCP client connected: {}, preparing UDP channel to {}:{}",
            tcpChannel.remoteAddress(), targetUdpHost, targetUdpPort
        )

        getOrCreateClientAsync(tcpChannel).whenComplete { _, error ->
            if (error != null) {
                logger.error("Failed to create UDP client for {}, closing TCP", tcpChannel.id(), error)
                ctx.close()
            }
        }

        ctx.fireChannelActive()
    }

    /**
     * 异步获取或创建UDP客户端（非阻塞，线程安全）
     */
    private fun getOrCreateClientAsync(tcpChannel: Channel): CompletableFuture<TunClient> {
        val channelId = tcpChannel.id()

        connections[channelId]?.let { existing ->
            if (existing.isActive()) {
                return CompletableFuture.completedFuture(existing)
            }
            removeAndShutdown(channelId)
        }

        pendingFutures[channelId]?.let { pending ->
            logger.debug("Connection creation in progress for {}, reusing future", channelId)
            return pending
        }

        val future = CompletableFuture<TunClient>()
        val previous = pendingFutures.putIfAbsent(channelId, future)

        if (previous != null) {
            return previous
        }

        createTunClient(tcpChannel, future)

        future.orTimeout(connectTimeoutSeconds, TimeUnit.SECONDS).exceptionally { ex ->
            pendingFutures.remove(channelId)
            logger.error("UDP connection timeout for {}", channelId)
            throw ex
        }

        return future
    }

    private fun createTunClient(
        tcpChannel: Channel,
        future: CompletableFuture<TunClient>
    ) {
        val channelId = tcpChannel.id()

        try {
            val tunClient = TunClient(
                targetUdpHost,
                targetUdpPort,
                tcpChannel,
                sharedWorkerGroup
            )

            tunClient.onDisconnect = {
                logger.info("UDP client disconnected, removing {}", channelId)
                removeAndShutdown(channelId)
            }

            tunClient.start().whenComplete { success, error ->
                pendingFutures.remove(channelId)

                when {
                    error != null -> {
                        logger.error("Failed to start UDP client for {}", channelId, error)
                        future.completeExceptionally(error)
                    }
                    success -> {
                        connections[channelId] = tunClient
                        logger.info("UDP connection established for {} -> {}:{}",
                            channelId, targetUdpHost, targetUdpPort)
                        future.complete(tunClient)
                    }
                    else -> {
                        future.completeExceptionally(IllegalStateException("Failed to start UDP client"))
                    }
                }
            }

        } catch (e: Exception) {
            pendingFutures.remove(channelId)
            logger.error("Exception while creating UDP client for {}", channelId, e)
            future.completeExceptionally(e)
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteArray) {
            ctx.fireChannelRead(msg)
            return
        }

        val tcpChannel = ctx.channel()

        getOrCreateClientAsync(tcpChannel).whenComplete { client, error ->
            if (error != null || client == null || !client.isActive()) {
                logger.warn("UDP channel not available for TCP {}, dropping packet", tcpChannel.id())
                return@whenComplete
            }

            try {
                val udpData = PacketProtocol.decodeTcpToUdp(msg)
                if (udpData == null) {
                    logger.warn("Failed to decode TCP packet from {}", tcpChannel.id())
                    return@whenComplete
                }

                val sent = client.sendUdpData(udpData)
                if (!sent) {
                    logger.warn("Failed to send UDP data for {}", tcpChannel.id())
                }
            } catch (e: Exception) {
                logger.error("Error processing TCP packet from {}", tcpChannel.id(), e)
            }
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            when (evt.state()) {
                IdleState.READER_IDLE -> {
                    logger.warn(
                        "TCP client {} read idle for {}s, closing connection",
                        ctx.channel().remoteAddress(), heartbeatTimeout
                    )
                    ctx.close()
                }
                else -> {}
            }
        }
        ctx.fireUserEventTriggered(evt)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val channelId = ctx.channel().id()
        logger.info("TCP client disconnected: {}", ctx.channel().remoteAddress())
        removeAndShutdown(channelId)
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        val channelId = ctx.channel().id()
        logger.debug("Handler removed for TCP {}", channelId)
        removeAndShutdown(channelId)
        super.handlerRemoved(ctx)
    }

    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("Exception in TCP handler for {}", ctx.channel().id(), cause)
        ctx.close()
    }

    private fun removeAndShutdown(channelId: ChannelId) {
        pendingFutures.remove(channelId)?.cancel(true)

        connections.remove(channelId)?.shutdown()
    }

    /**
     * 清理所有资源（服务器关闭时调用）
     */
    fun shutdown() {
        if (!handlerClosed.compareAndSet(false, true)) return

        logger.info("Shutting down TcpToUdpHandler, cleaning {} connections", connections.size)

        pendingFutures.values.forEach { it.cancel(true) }
        pendingFutures.clear()

        connections.keys.toList().forEach { removeAndShutdown(it) }
    }
}