package com.jun.tun.safe.core.tcp

import com.jun.tun.safe.core.protocol.PacketProtocol
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SafeClient(
    private val remoteHost: String,
    private val remotePort: Int,
    private val udpClientHost: String,
    private val udpClientPort: Int,
    private val udpChannel: Channel,
    sharedWorkerGroup: EventLoopGroup? = null
) : ChannelDuplexHandler() {

    private val logger = LoggerFactory.getLogger(SafeClient::class.java)

    private val workerGroup: EventLoopGroup = sharedWorkerGroup
        ?: NioEventLoopGroup(1)

    private val ownsWorkerGroup: Boolean = sharedWorkerGroup == null

    @Volatile
    private var channel: Channel? = null

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    var onDisconnect: ((SafeClient) -> Unit)? = null

    private val targetAddress = InetSocketAddress(udpClientHost, udpClientPort)

    /**
     * 启动 TCP 客户端，返回是否成功
     */
    fun start(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        if (!started.compareAndSet(false, true)) {
            future.completeExceptionally(IllegalStateException("Client already started"))
            return future
        }

        try {
            val bootstrap = Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 20000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_RCVBUF, 256 * 1024)
                .option(ChannelOption.SO_SNDBUF, 256 * 1024)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        setupPipeline(ch.pipeline())
                    }
                })

            bootstrap.connect(remoteHost, remotePort).addListener { connFuture ->
                if (connFuture.isSuccess) {
                    val newChannel = (connFuture as ChannelFuture).channel()
                    channel = newChannel
                    active.set(true)

                    logger.info(
                        "TCP connected: {}:{} <-> UDP {}:{}",
                        remoteHost, remotePort, udpClientHost, udpClientPort
                    )

                    newChannel.closeFuture().addListener {
                        handleDisconnect()
                    }

                    future.complete(true)
                } else {
                    logger.error("TCP connection failed: {}:{}", remoteHost, remotePort, connFuture.cause())
                    cleanup()
                    future.complete(false)
                }
            }

        } catch (e: Exception) {
            logger.error("Unexpected error starting client", e)
            cleanup()
            future.completeExceptionally(e)
        }

        return future
    }

    private fun setupPipeline(pipeline: ChannelPipeline) {
        pipeline.addLast("frameEncoder", LengthFieldPrepender(4))
        pipeline.addLast("bytesEncoder", ByteArrayEncoder())

        pipeline.addLast("frameDecoder", LengthFieldBasedFrameDecoder(
            PacketProtocol.MAX_PACKET_SIZE,
            0, 4, 0, 4
        ))
        pipeline.addLast("bytesDecoder", ByteArrayDecoder())
        pipeline.addLast("tcpToUdp", this)
    }

    /**
     * Send UDP packet（线程安全，非阻塞）
     */
    private fun sendUdpData(data: DatagramPacket): Boolean {
        val ch = udpChannel
        if (!ch.isActive) {
            logger.warn("UDP channel not active")
            return false
        }

        if (ch.eventLoop().inEventLoop()) {
            ch.writeAndFlush(data).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
        } else {
            ch.eventLoop().execute {
                ch.writeAndFlush(data).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
            }
        }
        return true
    }

    /**
     * 发送 TCP 数据（线程安全，非阻塞）
     */
    fun sendTcpData(data: ByteArray): Boolean {
        val ch = channel
        if (ch == null || !ch.isActive) {
            logger.warn("TCP channel not active, drop {} bytes", data.size)
            return false
        }

        if (ch.eventLoop().inEventLoop()) {
            writeToChannel(ch, data)
        } else {
            ch.eventLoop().execute { writeToChannel(ch, data) }
        }
        return true
    }

    private fun writeToChannel(ch: Channel, data: ByteArray) {
        if (!ch.isWritable) {
            logger.warn("TCP channel not writable, drop {} bytes", data.size)
            return
        }
        ch.writeAndFlush(data).addListener { f ->
            if (!f.isSuccess) {
                logger.error("Failed to send TCP data", f.cause())
            }
        }
    }

    fun isConnected(): Boolean = active.get() && channel?.isActive == true

    /**
     * 异步关闭，不阻塞调用线程
     */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return

        logger.info("Shutting down SafeClient {}:{}", remoteHost, remotePort)
        active.set(false)

        val ch = channel
        if (ch != null && ch.isActive) {
            ch.close().addListener {
                shutdownWorkerGroup()
            }
        } else {
            shutdownWorkerGroup()
        }
    }

    private fun shutdownWorkerGroup() {
        if (ownsWorkerGroup) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS)
                .addListener { logger.info("SafeClient shutdown complete") }
        }
    }

    private fun handleDisconnect() {
        logger.info("Connection lost: {}:{}", remoteHost, remotePort)
        active.set(false)

        onDisconnect?.invoke(this)

        if (!closed.get()) {
            shutdown()
        }
    }

    private fun cleanup() {
        started.set(false)
        if (!closed.get() && ownsWorkerGroup) {
            workerGroup.shutdownGracefully()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteArray) {
            ctx.fireChannelRead(msg)
            return
        }

        try {
            val udpPacket = PacketProtocol.decodeTcpToUdp(msg) ?: return
            val buffer = Unpooled.copiedBuffer(udpPacket)
            val datagramPacket = DatagramPacket(buffer, targetAddress)

            if (!sendUdpData(datagramPacket)) {
                buffer.release()
            }
        } catch (e: Exception) {
            logger.error("Error processing TCP to UDP packet", e)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        handleDisconnect()
        ctx.fireChannelInactive()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("SafeClient exception {}:{}", remoteHost, remotePort, cause)
        ctx.close()
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (!ctx.channel().isWritable) {
            logger.warn("TCP channel became not writable {}:{}", remoteHost, remotePort)
        }
        ctx.fireChannelWritabilityChanged()
    }
}