package com.jun.tun.safe.core.handler

import com.jun.tun.safe.core.protocol.PacketProtocol
import com.jun.tun.safe.core.tcp.SafeClientFactory
import com.jun.tun.safe.core.udp.TunServer.DatagramPacketDecoder.Companion.SENDER_ADDRESS_KEY
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP 到 TCP 的转发处理器
 * 使用 SafeClientFactory 管理连接池，支持 UDP 客户端心跳检测
 */
class UdpToTcpHandler(
    targetTcpHost: String,
    targetTcpPort: Int,
    sharedWorkerGroup: io.netty.channel.EventLoopGroup? = null,
    initialPoolSize: Int = 0,  // 默认预初始化连接数为 0
    // 心跳检测配置
    private val heartbeatTimeoutMs: Long = 60000L,      // 默认 60 秒无活动视为离线
    private val heartbeatCheckIntervalMs: Long = 30000L // 默认 30 秒检查一次
) : SimpleChannelInboundHandler<ByteArray>() {

    private val logger = LoggerFactory.getLogger(UdpToTcpHandler::class.java)

    // 使用工厂替代直接管理连接
    private val clientFactory = SafeClientFactory(
        targetTcpHost = targetTcpHost,
        targetTcpPort = targetTcpPort,
        sharedWorkerGroup = sharedWorkerGroup,
        initialPoolSize = initialPoolSize
    )

    // UDP 客户端活跃时间记录：addressKey -> 最后活跃时间戳
    private val lastActivityTime = ConcurrentHashMap<String, AtomicLong>()

    // UDP 客户端统计信息：addressKey -> 统计对象
    private val clientStats = ConcurrentHashMap<String, ClientStats>()

    // 心跳检测定时任务
    private var heartbeatChecker: ScheduledFuture<*>? = null

    // 处理器状态
    private val active = AtomicBoolean(false)

    /**
     * 客户端统计信息
     */
    data class ClientStats(
        val addressKey: String,
        val connectTime: Long = System.currentTimeMillis(),
        var packetsReceived: Long = 0,
        var packetsSent: Long = 0,
        var bytesReceived: Long = 0,
        var bytesSent: Long = 0,
        var lastPacketTime: Long = System.currentTimeMillis()
    )

    /**
     * 启动心跳检测
     */
    private fun startHeartbeatChecker(eventLoop: io.netty.channel.EventLoop) {
        if (active.compareAndSet(false, true)) {
            heartbeatChecker = eventLoop.scheduleAtFixedRate(
                { checkAndCleanupInactiveClients() },
                heartbeatCheckIntervalMs,
                heartbeatCheckIntervalMs,
                TimeUnit.MILLISECONDS
            )
            logger.info("Heartbeat checker started, interval={}ms, timeout={}ms",
                heartbeatCheckIntervalMs, heartbeatTimeoutMs)
        }
    }

    /**
     * 停止心跳检测
     */
    private fun stopHeartbeatChecker() {
        active.set(false)
        heartbeatChecker?.cancel(false)
        heartbeatChecker = null
        logger.info("Heartbeat checker stopped")
    }

    override fun channelRead0(ctx: ChannelHandlerContext, udpData: ByteArray) {
        val senderAddress = ctx.channel().attr(SENDER_ADDRESS_KEY).get()
            ?: run {
                logger.warn("No sender address found in channel attributes")
                return
            }

        val addressKey = "${senderAddress.hostString}:${senderAddress.port}"
        val now = System.currentTimeMillis()

        lastActivityTime.computeIfAbsent(addressKey) { AtomicLong(now) }.set(now)

        val stats = clientStats.computeIfAbsent(addressKey) {
            ClientStats(addressKey, connectTime = now)
        }
        stats.apply {
            packetsReceived++
            bytesReceived += udpData.size
            lastPacketTime = now
        }

        logger.debug("Received UDP packet from {}, size: {} bytes, total packets: {}",
            addressKey, udpData.size, stats.packetsReceived)

        clientFactory.getOrCreateClientAsync(senderAddress, addressKey, ctx.channel()) { client ->
            if (client != null) {
                try {
                    val tcpPacket = PacketProtocol.encodeUdpToTcp(udpData) ?: return@getOrCreateClientAsync
                    if (client.sendTcpData(tcpPacket)) {
                        stats.packetsSent++
                        stats.bytesSent += tcpPacket.size
                    } else {
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

    /**
     * 检查并清理不活跃的客户端
     */
    private fun checkAndCleanupInactiveClients() {
        if (!active.get()) return

        val now = System.currentTimeMillis()
        val timeoutKeys = mutableListOf<String>()

        lastActivityTime.forEach { (key, lastTime) ->
            val inactiveDuration = now - lastTime.get()
            if (inactiveDuration > heartbeatTimeoutMs) {
                timeoutKeys.add(key)
                logger.info("Client {} inactive for {}ms (timeout {}ms), marking for cleanup",
                    key, inactiveDuration, heartbeatTimeoutMs)
            }
        }

        timeoutKeys.forEach { key ->
            cleanupClient(key)
        }

        if (timeoutKeys.isNotEmpty()) {
            logger.info("Cleaned up {} inactive clients, remaining active clients: {}",
                timeoutKeys.size, lastActivityTime.size)
            logStats()
        }
    }

    /**
     * 清理指定客户端的资源
     */
    private fun cleanupClient(addressKey: String) {
        lastActivityTime.remove(addressKey)

        clientStats.remove(addressKey)?.let { stats ->
            val duration = System.currentTimeMillis() - stats.connectTime
            logger.info("Client {} disconnected, session duration: {}ms, " +
                    "packets: rx={}, tx={}, bytes: rx={}, tx={}",
                addressKey, duration,
                stats.packetsReceived, stats.packetsSent,
                stats.bytesReceived, stats.bytesSent)
        }

        clientFactory.releaseConnection(addressKey)
    }

    /**
     * 手动标记客户端活跃（可用于外部心跳包）
     */
    fun markClientActive(addressKey: String) {
        val now = System.currentTimeMillis()
        lastActivityTime.computeIfAbsent(addressKey) { AtomicLong(now) }.set(now)
        logger.debug("Manually marked client {} as active", addressKey)
    }

    /**
     * 获取客户端最后活跃时间
     */
    fun getLastActivityTime(addressKey: String): Long? {
        return lastActivityTime[addressKey]?.get()
    }

    /**
     * 检查客户端是否活跃
     */
    fun isClientActive(addressKey: String): Boolean {
        val lastTime = lastActivityTime[addressKey]?.get() ?: return false
        return System.currentTimeMillis() - lastTime <= heartbeatTimeoutMs
    }

    /**
     * 获取所有活跃客户端列表
     */
    fun getActiveClients(): List<String> {
        val now = System.currentTimeMillis()
        return lastActivityTime.entries
            .filter { now - it.value.get() <= heartbeatTimeoutMs }
            .map { it.key }
    }

    /**
     * 获取客户端统计信息
     */
    fun getClientStats(addressKey: String): ClientStats? = clientStats[addressKey]

    /**
     * 获取所有客户端统计信息
     */
    fun getAllClientStats(): Map<String, ClientStats> = clientStats.toMap()

    /**
     * 打印统计信息
     */
    fun logStats() {
        val now = System.currentTimeMillis()
        val activeCount = lastActivityTime.count { now - it.value.get() <= heartbeatTimeoutMs }
        val totalStats = clientStats.values.fold(ClientStats("total")) { acc, stats ->
            acc.apply {
                packetsReceived += stats.packetsReceived
                packetsSent += stats.packetsSent
                bytesReceived += stats.bytesReceived
                bytesSent += stats.bytesSent
            }
        }

        logger.info("=== Handler Stats ===")
        logger.info("Active clients: {}/{}", activeCount, lastActivityTime.size)
        logger.info("Total packets: rx={}, tx={}", totalStats.packetsReceived, totalStats.packetsSent)
        logger.info("Total bytes: rx={}, tx={}", totalStats.bytesReceived, totalStats.bytesSent)
        logger.info("Connection pool: {}", clientFactory.getStats())
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val senderAddress = ctx.channel().attr(SENDER_ADDRESS_KEY).get()
        val addressKey = senderAddress?.let { "${it.hostString}:${it.port}" }
            ?: ctx.channel().remoteAddress()?.toString()

        logger.info("Channel inactive: {}", addressKey)

        addressKey?.let { key ->
            cleanupClient(key)
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
        startHeartbeatChecker(ctx.channel().eventLoop())
    }

    /**
     * 获取连接池统计信息
     */
    fun getConnectionStats(): Map<String, Int> = clientFactory.getStats()

    /**
     * 关闭处理器，释放所有资源
     */
    fun shutdown() {
        logger.info("Shutting down UdpToTcpHandler...")

        stopHeartbeatChecker()

        val allClients = lastActivityTime.keys.toList()
        allClients.forEach { cleanupClient(it) }

        logStats()

        clientFactory.shutdown()

        logger.info("UdpToTcpHandler shutdown complete")
    }
}