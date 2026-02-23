package com.jun.tun.safe.core.udp

import com.jun.tun.safe.core.protocol.PacketProtocol
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.MessageToMessageEncoder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP client - sends packets to target UDP server
 *
 * As the exit point of udp2raw tunnel, receives data from TCP tunnel,
 * and sends it to target UDP server
 *
 * @author leolee
 * @create 2026/2/8
 */
class TunClient(
    private val targetHost: String,
    private val targetPort: Int,
    private val tcpChannel: Channel,
    sharedWorkerGroup: EventLoopGroup? = null
) : SimpleChannelInboundHandler<DatagramPacket>() {

    private val logger = LoggerFactory.getLogger(TunClient::class.java)

    private val workerGroup: EventLoopGroup = sharedWorkerGroup ?: NioEventLoopGroup(1)
    private val ownsWorkerGroup: Boolean = sharedWorkerGroup == null

    @Volatile
    private var channel: Channel? = null
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    var onDisconnect: ((TunClient) -> Unit)? = null
    private val targetAddress = InetSocketAddress(targetHost, targetPort)

    /**
     * Start UDP client（异步，非阻塞）
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
                .channel(NioDatagramChannel::class.java)
                .option(ChannelOption.SO_BROADCAST, false)
                .option(ChannelOption.SO_RCVBUF, 256 * 1024)
                .option(ChannelOption.SO_SNDBUF, 256 * 1024)
                .handler(object : ChannelInitializer<NioDatagramChannel>() {
                    override fun initChannel(ch: NioDatagramChannel) {
                        val pipeline = ch.pipeline()
                        // 出站：编码为 DatagramPacket
                        pipeline.addLast("encoder", DatagramPacketEncoder(targetAddress))
                        // 入站：处理收到的 UDP 数据
                        pipeline.addLast("handler", this@TunClient)
                    }
                })

            // 异步绑定
            bootstrap.bind("0.0.0.0", 0).addListener { bindFuture ->
                if (bindFuture.isSuccess) {
                    channel = (bindFuture as ChannelFuture).channel()
                    logger.info("UDP client started: {}:{} <-> TCP {}",
                        targetHost, targetPort, tcpChannel.id())

                    // 监听关闭
                    channel?.closeFuture()?.addListener {
                        handleDisconnect()
                    }
                    future.complete(true)
                } else {
                    logger.error("UDP bind failed", bindFuture.cause())
                    cleanup()
                    future.complete(false)
                }
            }

        } catch (e: Exception) {
            logger.error("Unexpected error starting UDP client", e)
            cleanup()
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * Send UDP packet（线程安全，非阻塞）
     */
    fun sendUdpData(data: ByteArray): Boolean {
        val ch = channel
        if (ch == null || !ch.isActive) {
            logger.warn("UDP channel not active, drop {} bytes", data.size)
            return false
        }

        // 确保在正确的 EventLoop 上执行
        if (ch.eventLoop().inEventLoop()) {
            ch.writeAndFlush(data).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
        } else {
            ch.eventLoop().execute {
                ch.writeAndFlush(data).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
            }
        }
        return true
    }

    fun isActive(): Boolean = channel?.isActive == true

    /**
     * 异步关闭，不阻塞调用线程
     */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return

        logger.info("Shutting down TunClient {}:{}", targetHost, targetPort)

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
                .addListener { logger.info("TunClient shutdown complete") }
        }
    }

    private fun handleDisconnect() {
        logger.info("UDP connection lost: {}:{}", targetHost, targetPort)
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

    /**
     * 收到 UDP 响应，转发给 TCP
     */
    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
        try {
            val data = ByteArray(msg.content().readableBytes())
            msg.content().readBytes(data)

            val tcpPacket = PacketProtocol.encodeUdpToTcp(data) ?: return

            // 转发到 TCP
            sendTcpData(tcpPacket)
        } catch (e: Exception) {
            logger.error("Error processing UDP response", e)
        }
    }

    /**
     * Send TCP data（线程安全）
     */
    private fun sendTcpData(data: ByteArray): Boolean {
        if (!tcpChannel.isActive) {
            logger.warn("TCP channel not active, cannot forward UDP response")
            return false
        }

        if (tcpChannel.eventLoop().inEventLoop()) {
            tcpChannel.writeAndFlush(data)
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
        } else {
            tcpChannel.eventLoop().execute {
                tcpChannel.writeAndFlush(data)
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("UDP channel exception for TCP {}", tcpChannel.id(), cause)
        if (!closed.get()) {
            shutdown()
        }
    }

    /**
     * UDP packet encoder
     * Wraps byte array into DatagramPacket
     */
    private class DatagramPacketEncoder(
        private val targetAddress: InetSocketAddress
    ) : MessageToMessageEncoder<ByteArray>() {

        override fun encode(ctx: ChannelHandlerContext, msg: ByteArray, out: MutableList<Any>) {
            val buffer = Unpooled.wrappedBuffer(msg) // 零拷贝
            val datagramPacket = DatagramPacket(buffer, targetAddress)
            out.add(datagramPacket)
        }

        @Deprecated("Deprecated in Java")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.fireExceptionCaught(cause)
        }
    }
}