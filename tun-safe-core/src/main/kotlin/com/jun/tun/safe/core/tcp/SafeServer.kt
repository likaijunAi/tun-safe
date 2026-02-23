package com.jun.tun.safe.core.tcp

import com.jun.tun.safe.core.handler.TcpToUdpHandler
import com.jun.tun.safe.core.protocol.PacketProtocol
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP server - receives disguised TCP connections
 *
 * As the relay point of udp2raw tunnel, receives TCP connections from clients,
 * parses UDP packets and forwards to target UDP server
 *
 * @author leolee
 * @create 2026/2/8
 */
class SafeServer(
    private val bindHost: String = "0.0.0.0",
    private val bindPort: Int,
    private val udpTargetHost: String,
    private val udpTargetPort: Int,
    externalBossGroup: EventLoopGroup? = null,
    externalWorkerGroup: EventLoopGroup? = null,
    private val heartbeatTimeout: Int = 60
) {

    private val logger = LoggerFactory.getLogger(SafeServer::class.java)

    private val bossGroup: EventLoopGroup = externalBossGroup ?: NioEventLoopGroup(1)
    private val workerGroup: EventLoopGroup = externalWorkerGroup ?: NioEventLoopGroup()
    private val ownsBossGroup: Boolean = externalBossGroup == null
    private val ownsWorkerGroup: Boolean = externalWorkerGroup == null

    @Volatile
    private var channel: Channel? = null
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /**
     * 启动TCP服务器（非阻塞）
     */
    fun start(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!started.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("Server already started"))
            return future
        }

        try {
            val handlerFactory = {
                TcpToUdpHandler(udpTargetHost, udpTargetPort, workerGroup, heartbeatTimeout)
            }

            val bootstrap = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_RCVBUF, 256 * 1024)
                .childOption(ChannelOption.SO_SNDBUF, 256 * 1024)
                .handler(LoggingHandler(LogLevel.INFO))
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val pipeline = ch.pipeline()

                        pipeline.addLast("idleHandler", IdleStateHandler(
                            heartbeatTimeout, 0, 0
                        ))

                        pipeline.addLast("frameEncoder", LengthFieldPrepender(4))
                        pipeline.addLast("bytesEncoder", ByteArrayEncoder())

                        pipeline.addLast("frameDecoder", LengthFieldBasedFrameDecoder(
                            PacketProtocol.MAX_PACKET_SIZE,
                            0, 4, 0, 4
                        ))
                        pipeline.addLast("bytesDecoder", ByteArrayDecoder())

                        // 业务处理器（每个连接独立实例）
                        pipeline.addLast("tcpToUdp", handlerFactory())
                    }
                })

            bootstrap.bind(bindHost, bindPort).addListener { bindFuture ->
                if (bindFuture.isSuccess) {
                    channel = (bindFuture as ChannelFuture).channel()
                    logger.info("TCP server started successfully, listening on: {}:{}", bindHost, bindPort)
                    logger.info("Will forward data to UDP server: {}:{}", udpTargetHost, udpTargetPort)

                    channel?.closeFuture()?.addListener {
                        if (!stopped.get()) {
                            logger.warn("TCP server channel closed unexpectedly")
                        }
                    }
                    future.complete(null)
                } else {
                    val cause = bindFuture.cause()
                    logger.error("Failed to bind TCP server to {}:{}", bindHost, bindPort, cause)
                    cleanup()
                    future.completeExceptionally(cause)
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to start TCP server", e)
            cleanup()
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 停止TCP服务器（异步，不阻塞）
     */
    fun stop(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!stopped.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("Server already stopped"))
            return future
        }

        if (!started.get()) {
            future.completeExceptionally(IllegalStateException("Server not started"))
            return future
        }

        logger.info("Stopping TCP server...")

        val ch = channel
        if (ch != null && ch.isActive) {
            ch.close().addListener {
                shutdownGroups(future)
            }
        } else {
            shutdownGroups(future)
        }

        return future
    }

    private fun shutdownGroups(future: CompletableFuture<Void>) {
        val futures = mutableListOf<CompletableFuture<Void>>()

        if (ownsBossGroup) {
            val bossFuture = CompletableFuture<Void>()
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                .addListener { bossFuture.complete(null) }
            futures.add(bossFuture)
        }

        if (ownsWorkerGroup) {
            val workerFuture = CompletableFuture<Void>()
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                .addListener { workerFuture.complete(null) }
            futures.add(workerFuture)
        }

        if (futures.isEmpty()) {
            logger.info("TCP server stopped (using external groups)")
            future.complete(null)
            return
        }

        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            logger.info("TCP server stopped")
            future.complete(null)
        }
    }

    private fun cleanup() {
        started.set(false)
        if (ownsBossGroup) bossGroup.shutdownGracefully()
        if (ownsWorkerGroup) workerGroup.shutdownGracefully()
    }

    fun isRunning(): Boolean = started.get() && !stopped.get() && channel?.isActive == true
}