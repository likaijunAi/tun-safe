package com.jun.tun.safe.core.handler

import com.jun.tun.safe.core.protocol.PacketProtocol
import com.jun.tun.safe.core.tcp.SafeClient
import com.jun.tun.safe.core.udp.TunServer.DatagramPacketDecoder.Companion.SENDER_ADDRESS_KEY
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UdpToTcpHandler(
    private val targetTcpHost: String,
    private val targetTcpPort: Int,
    private val sharedWorkerGroup: io.netty.channel.EventLoopGroup? = null
) : SimpleChannelInboundHandler<ByteArray>() {

    private val logger = LoggerFactory.getLogger(UdpToTcpHandler::class.java)

    private val connections = ConcurrentHashMap<String, SafeClient>()

    private val connecting = ConcurrentHashMap.newKeySet<String>()

    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<(SafeClient?) -> Unit>>()

    override fun channelRead0(ctx: ChannelHandlerContext, udpData: ByteArray) {
        val senderAddress = ctx.channel().attr(SENDER_ADDRESS_KEY).get()
            ?: run {
                logger.warn("No sender address found in channel attributes")
                return
            }

        val addressKey = "${senderAddress.hostString}:${senderAddress.port}"
        logger.debug("Received UDP packet from {}, size: {} bytes", addressKey, udpData.size)

        getOrCreateClientAsync(senderAddress, addressKey, ctx.channel()) { client ->
            if (client != null) {
                try {
                    val tcpPacket = PacketProtocol.encodeUdpToTcp(udpData) ?: return@getOrCreateClientAsync
                    if (!client.sendTcpData(tcpPacket)) {
                        logger.warn("Failed to send TCP data for {}", addressKey)
                    }
                } catch (e: Exception) {
                    logger.error("Error processing UDP packet from {}", addressKey, e)
                }
            } else {
                logger.warn("Failed to get TCP client for {}", addressKey)
            }
        }
    }

    private fun getOrCreateClientAsync(
        socketAddress: InetSocketAddress,
        addressKey: String,
        channel: Channel,
        callback: (SafeClient?) -> Unit
    ) {
        val existing = connections[addressKey]
        if (existing != null && existing.isConnected()) {
            callback(existing)
            return
        }

        if (!connecting.add(addressKey)) {
            pendingCallbacks.computeIfAbsent(addressKey) { mutableListOf() }.add(callback)
            return
        }

        createClientWithRetry(socketAddress, addressKey, channel, AtomicInteger(0), callback)
    }

    private fun createClientWithRetry(
        socketAddress: InetSocketAddress,
        addressKey: String,
        channel: Channel,
        attemptCounter: AtomicInteger,
        originalCallback: (SafeClient?) -> Unit
    ) {
        val attempt = attemptCounter.incrementAndGet()

        if (attempt > 3) {
            logger.error("Failed to connect TCP server for {} after 3 attempts", addressKey)
            completeConnection(addressKey, null, originalCallback)
            return
        }

        val safeClient = SafeClient(
            targetTcpHost,
            targetTcpPort,
            sharedWorkerGroup
        ).apply {
            setUdpClient(
                socketAddress.hostString,
                socketAddress.port,
                channel,
            )
        }

        safeClient.onDisconnect = {
            logger.info("TCP client disconnected, removing {}", addressKey)
            connections.remove(addressKey, it) // 使用 remove(key, value) 避免误删
        }

        safeClient.start().whenComplete { success, error ->
            when {
                error != null -> {
                    logger.error("Exception while creating TCP client for {} (attempt {})", addressKey, attempt, error)
                    scheduleRetry(socketAddress, addressKey, channel, attemptCounter, originalCallback)
                }

                success -> {
                    val prev = connections.putIfAbsent(addressKey, safeClient)
                    if (prev != null && prev.isConnected()) {
                        safeClient.shutdown()
                        completeConnection(addressKey, prev, originalCallback)
                    } else {
                        logger.info(
                            "TCP connection established for {} -> {}:{}",
                            addressKey,
                            targetTcpHost,
                            targetTcpPort
                        )
                        completeConnection(addressKey, safeClient, originalCallback)
                    }
                }

                else -> {
                    logger.error("Failed to connect TCP server for {} (attempt {})", addressKey, attempt)
                    scheduleRetry(socketAddress, addressKey, channel, attemptCounter, originalCallback)
                }
            }
        }
    }

    private fun scheduleRetry(
        socketAddress: InetSocketAddress,
        addressKey: String,
        channel: Channel,
        attemptCounter: AtomicInteger,
        callback: (SafeClient?) -> Unit
    ) {
        channel.eventLoop().schedule({
            createClientWithRetry(socketAddress, addressKey, channel, attemptCounter, callback)
        }, 3, TimeUnit.SECONDS)
    }

    private fun completeConnection(addressKey: String, client: SafeClient?, originalCallback: (SafeClient?) -> Unit) {
        originalCallback(client)

        val callbacks = pendingCallbacks.remove(addressKey)
        callbacks?.forEach { it(client) }

        connecting.remove(addressKey)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val senderAddress = ctx.channel().attr(SENDER_ADDRESS_KEY).get()
        val addressKey = senderAddress?.let { "${it.hostString}:${it.port}" }
            ?: ctx.channel().remoteAddress()?.toString()

        logger.info("Channel inactive: {}", addressKey)

        addressKey?.let { key ->
            connections.remove(key)?.shutdown()
        }

        ctx.fireChannelInactive()
    }

    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("UDP handler exception from {}", ctx.channel().remoteAddress(), cause)
        ctx.close()
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        logger.info("Channel active: {}", ctx.channel().remoteAddress())
    }

    fun shutdown() {
        connections.values.forEach { it.shutdown() }
        connections.clear()
        connecting.clear()
        pendingCallbacks.clear()
    }
}