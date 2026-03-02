package com.jun.tun.safe.core.udp

import com.jun.tun.safe.core.handler.UdpToTcpHandler
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.util.AttributeKey
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP server - receives raw UDP packets
 *
 * As the entry point of udp2raw tunnel, listens on specified UDP port,
 * receives UDP packets from clients and forwards to TCP tunnel processor
 *
 * @author leolee
 * @create 2026/2/8
 */
class TunServer(
    private val bindHost: String = "0.0.0.0",
    private val bindPort: Int,
    private val tcpTargetHost: String,
    private val tcpTargetPort: Int,
    private val  initialPoolSize: Int=0,
    externalBossGroup: EventLoopGroup? = null,
    externalWorkerGroup: EventLoopGroup? = null
) {

    private val logger = LoggerFactory.getLogger(TunServer::class.java)

    private val bossGroup: EventLoopGroup = externalBossGroup ?: NioEventLoopGroup(1)
    private val workerGroup: EventLoopGroup = externalWorkerGroup ?: NioEventLoopGroup()

    private val ownsBossGroup: Boolean = externalBossGroup == null
    private val ownsWorkerGroup: Boolean = externalWorkerGroup == null

    @Volatile
    private var channel: Channel? = null

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val udpToTcpHandler: UdpToTcpHandler by lazy {
        UdpToTcpHandler(tcpTargetHost, tcpTargetPort, workerGroup, initialPoolSize)
    }

    /**
     * Start UDP server (non-blocking)
     */
    fun start(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        if (!started.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("Server already started"))
            return future
        }

        try {
            val bootstrap = Bootstrap()
                // UDP 无连接，通常只需要一个 group，但为兼容性保留 boss/worker 模式
                .group(if (ownsBossGroup) bossGroup else workerGroup)
                .channel(NioDatagramChannel::class.java)
                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024) // 1MB 接收缓冲区
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024) // 1MB 发送缓冲区
                .option(
                    ChannelOption.RCVBUF_ALLOCATOR,
                    FixedRecvByteBufAllocator(65535)
                ) // 固定缓冲区大小，避免碎片
                .handler(object : ChannelInitializer<NioDatagramChannel>() {
                    override fun initChannel(ch: NioDatagramChannel) {
                        val pipeline = ch.pipeline()

                        pipeline.addLast("datagramDecoder", DatagramPacketDecoder())

                        pipeline.addLast("udpToTcp", udpToTcpHandler)
                    }
                })

            bootstrap.bind(bindHost, bindPort).addListener { bindFuture ->
                if (bindFuture.isSuccess) {
                    channel = (bindFuture as ChannelFuture).channel()
                    logger.info("UDP server started successfully, listening on: {}:{}", bindHost, bindPort)
                    logger.info("Data will be forwarded to TCP server: {}:{}", tcpTargetHost, tcpTargetPort)

                    channel?.closeFuture()?.addListener {
                        if (!stopped.get()) {
                            logger.warn("UDP server channel closed unexpectedly")
                            cleanup()
                        }
                    }

                    future.complete(null)
                } else {
                    val cause = bindFuture.cause()
                    logger.error("Failed to bind UDP server to {}:{}", bindHost, bindPort, cause)
                    cleanup()
                    future.completeExceptionally(cause)
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to start UDP server", e)
            cleanup()
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * Stop UDP server (异步，不阻塞)
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

        logger.info("Stopping UDP server...")

        udpToTcpHandler.shutdown()

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
        val bossShutdown = if (ownsBossGroup) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS)
        } else {
            null
        }

        val workerShutdown = if (ownsWorkerGroup) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS)
        } else {
            null
        }

        val futures = listOfNotNull(bossShutdown, workerShutdown)
        if (futures.isEmpty()) {
            logger.info("UDP server stopped (using external groups)")
            future.complete(null)
            return
        }

        val combined = CompletableFuture.allOf(*futures.map {
            val cf = CompletableFuture<Void>()
            it.addListener { cf.complete(null) }
            cf
        }.toTypedArray())

        combined.whenComplete { _, _ ->
            logger.info("UDP server stopped")
            future.complete(null)
        }
    }

    private fun cleanup() {
        started.set(false)
        if (ownsBossGroup) bossGroup.shutdownGracefully()
        if (ownsWorkerGroup) workerGroup.shutdownGracefully()
    }

    fun isRunning(): Boolean = started.get() && !stopped.get() && channel?.isActive == true

    /**
     * UDP packet decoder
     * Converts DatagramPacket to byte array and retains sender info
     */
    class DatagramPacketDecoder : MessageToMessageDecoder<DatagramPacket>() {
        private val logger = LoggerFactory.getLogger(DatagramPacketDecoder::class.java)

        override fun decode(ctx: ChannelHandlerContext, msg: DatagramPacket, out: MutableList<Any>) {
            val sender = msg.sender()
            val content = msg.content()

            val readableBytes = content.readableBytes()
            if (readableBytes == 0) {
                return  // 空包直接返回，decoder会自动释放
            }

            try {
                val data = ByteArray(readableBytes)
                content.readBytes(data)

                ctx.channel().attr(SENDER_ADDRESS_KEY).set(sender)
                out.add(data)

                if (logger.isDebugEnabled) {
                    logger.debug("Decoded UDP packet from {}, size: {} bytes", sender, readableBytes)
                }
            } catch (e: Exception) {
                logger.error("Error decoding UDP packet from {}", sender, e)
                throw e
            }
        }

        @Deprecated("Deprecated in Java")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.error("DatagramPacketDecoder exception", cause)
        }

        companion object {
            val SENDER_ADDRESS_KEY: AttributeKey<InetSocketAddress> =
                AttributeKey.valueOf("TunServer.senderAddress")
        }
    }
}