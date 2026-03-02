package com.jun.tun.safe.core.protocol

import io.netty.buffer.ByteBuf
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * UDP to TCP tunnel packet protocol definition
 *
 * Protocol format (with auth):
 * [Magic Number(4 bytes)] + [Data Length(4 bytes)] + [AuthTag(16 bytes)] + [UDP Data(n bytes)]
 *
 * Protocol format (without auth):
 * [Magic Number(4 bytes)] + [Data Length(4 bytes)] + [UDP Data(n bytes)]
 *
 * Magic Number: 0x12345678 - used to identify protocol packets
 * Data Length: actual length of subsequent UDP data (big-endian, unsigned int)
 * AuthTag: HMAC-SHA256 截断前16字节，用于鉴权（可选）
 * UDP Data: original UDP packet content
 *
 * @author leolee
 * @create 2026/2/8
 */
object PacketProtocol {

    /** Protocol magic number, used to identify valid packets */
    private const val MAGIC_NUMBER: Int = 0x12345678

    /** Auth tag length (first 16 bytes of HMAC-SHA256) */
    private const val AUTH_TAG_LENGTH: Int = 16

    /** Base header length (Magic Number + Data Length) */
    private const val BASE_HEADER_LENGTH: Int = 8

    /** Auth header length (AuthTag) */
    private const val AUTH_HEADER_LENGTH: Int = AUTH_TAG_LENGTH

    /** Maximum packet size (64KB) */
    const val MAX_PACKET_SIZE: Int = 65536

    /** Magic number bytes (大端序缓存，避免重复计算) */
    private val MAGIC_BYTES = byteArrayOf(
        0x12.toByte(),
        0x34.toByte(),
        0x56.toByte(),
        0x78.toByte()
    )

    /** 鉴权密钥，null 表示不启用鉴权 */
    private val authSecret = AtomicReference<ByteArray?>(null)

    /**
     * 设置鉴权令牌，传入 null 或空字符串表示禁用鉴权
     */
    fun setAuthToken(token: String?) {
        if (token.isNullOrBlank()) {
            authSecret.set(null)
        } else {
            authSecret.set(token.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * 获取当前鉴权密钥（用于调试，生产环境慎用）
     */
    fun getAuthSecret(): ByteArray? = authSecret.get()

    /**
     * 检查是否启用了鉴权
     */
    fun isAuthEnabled(): Boolean = authSecret.get() != null

    /**
     * 获取当前头部总长度（根据是否启用鉴权动态计算）
     */
    private fun getHeaderLength(): Int = if (isAuthEnabled()) BASE_HEADER_LENGTH + AUTH_HEADER_LENGTH else BASE_HEADER_LENGTH

    /**
     * 获取最小有效包大小
     */
    private fun getMinPacketSize(): Int = getHeaderLength()

    /**
     * Encapsulate UDP data into TCP transmission format
     *
     * @param udpData raw UDP data
     * @return encapsulated byte array, or null if invalid input
     */
    fun encodeUdpToTcp(udpData: ByteArray): ByteArray? {
        // 参数校验
        if (udpData.isEmpty()) {
            return null
        }

        val headerLength = getHeaderLength()
        if (udpData.size > MAX_PACKET_SIZE - headerLength) {
            return null // 超过最大限制
        }

        val packetSize = headerLength + udpData.size
        val result = ByteArray(packetSize)

        // 写入魔数 (使用缓存的字节数组，避免位运算)
        System.arraycopy(MAGIC_BYTES, 0, result, 0, 4)

        // 写入数据长度 (大端序，使用位运算)
        val dataLength = udpData.size
        result[4] = (dataLength ushr 24).toByte()
        result[5] = (dataLength ushr 16).toByte()
        result[6] = (dataLength ushr 8).toByte()
        result[7] = dataLength.toByte()

        // 如果启用鉴权，写入鉴权标签
        if (isAuthEnabled()) {
            val authTag = createAuthTag(udpData)
            System.arraycopy(authTag, 0, result, BASE_HEADER_LENGTH, AUTH_TAG_LENGTH)
            // 写入UDP数据
            System.arraycopy(udpData, 0, result, headerLength, udpData.size)
        } else {
            // 未启用鉴权，直接写入UDP数据
            System.arraycopy(udpData, 0, result, BASE_HEADER_LENGTH, udpData.size)
        }

        return result
    }

    /**
     * Parse UDP data from TCP packet
     *
     * @param tcpData TCP transmitted packet
     * @return parsed raw UDP data, returns null if not a valid protocol packet
     */
    fun decodeTcpToUdp(tcpData: ByteArray): ByteArray? {
        val headerLength = getHeaderLength()

        // 长度检查
        if (tcpData.size < headerLength) {
            return null // 数据不完整
        }

        // 魔数校验
        if (!checkMagicNumber(tcpData)) {
            return null // 非法协议包
        }

        // 读取数据长度 (大端序，无符号)
        val dataLength = readIntBigEndian(tcpData, 4)
        if (dataLength < 0 || dataLength > MAX_PACKET_SIZE - headerLength) {
            return null // 非法长度
        }

        // 验证数据完整性
        val expectedSize = headerLength + dataLength
        if (tcpData.size != expectedSize) {
            return null // 长度不匹配
        }

        // 提取UDP数据
        val udpData = tcpData.copyOfRange(headerLength, expectedSize)

        // 如果启用鉴权，进行鉴权校验
        if (isAuthEnabled()) {
            if (!verifyAuthTag(tcpData, udpData)) {
                return null
            }
        }

        return udpData
    }

    /**
     * 从 ByteBuf 解析 UDP 数据（零拷贝优化）
     * 用于 Netty 的 ByteBuf 直接处理，避免数组复制
     *
     * @param buffer Netty ByteBuf
     * @return 解析后的字节数组，或 null
     */
    fun decodeFromByteBuf(buffer: ByteBuf): ByteArray? {
        val headerLength = getHeaderLength()

        if (buffer.readableBytes() < headerLength) {
            return null
        }

        // 标记当前读位置
        buffer.markReaderIndex()

        // 读取魔数
        val magic = buffer.readInt()
        if (magic != MAGIC_NUMBER) {
            buffer.resetReaderIndex()
            return null
        }

        // 读取长度
        val dataLength = buffer.readInt()
        if (dataLength < 0 || dataLength > MAX_PACKET_SIZE - headerLength) {
            buffer.resetReaderIndex()
            return null
        }

        // 如果启用鉴权，读取并验证鉴权标签
        if (isAuthEnabled()) {
            val authTag = ByteArray(AUTH_TAG_LENGTH)
            buffer.readBytes(authTag)

            // 检查剩余数据
            if (buffer.readableBytes() < dataLength) {
                buffer.resetReaderIndex()
                return null // 数据不完整，等待更多数据
            }

            // 读取数据
            val result = ByteArray(dataLength)
            buffer.readBytes(result)

            // 鉴权校验
            if (!authTag.contentEquals(createAuthTag(result))) {
                return null
            }

            return result
        } else {
            // 未启用鉴权，直接读取数据
            if (buffer.readableBytes() < dataLength) {
                buffer.resetReaderIndex()
                return null // 数据不完整，等待更多数据
            }

            val result = ByteArray(dataLength)
            buffer.readBytes(result)
            return result
        }
    }

    /**
     * 将 UDP 数据编码到 ByteBuf（零拷贝）
     *
     * @param allocator ByteBuf 分配器
     * @param udpData 原始 UDP 数据
     * @return 编码后的 ByteBuf
     */
    fun encodeToByteBuf(allocator: io.netty.buffer.ByteBufAllocator, udpData: ByteArray): ByteBuf? {
        if (udpData.isEmpty()) {
            return null
        }

        val headerLength = getHeaderLength()
        if (udpData.size > MAX_PACKET_SIZE - headerLength) {
            return null
        }

        val packetSize = headerLength + udpData.size
        val buffer = allocator.buffer(packetSize, packetSize)

        buffer.writeInt(MAGIC_NUMBER)
        buffer.writeInt(udpData.size)

        if (isAuthEnabled()) {
            buffer.writeBytes(createAuthTag(udpData))
        }

        buffer.writeBytes(udpData)

        return buffer
    }

    /**
     * 验证包头是否有效（快速校验，不读取长度）
     *
     * @param headerData 包含头部的字节数组
     * @return 是否为有效头部
     */
    fun isValidHeader(headerData: ByteArray): Boolean {
        return headerData.size >= getMinPacketSize() && checkMagicNumber(headerData)
    }

    /**
     * 从包头读取数据长度（不验证魔数）
     *
     * @param headerData 包含头部的字节数组（至少8字节）
     * @return 数据长度，或 -1 如果无效
     */
    fun getDataLengthFromHeader(headerData: ByteArray): Int {
        val headerLength = getHeaderLength()
        if (headerData.size < headerLength) {
            return -1
        }
        val length = readIntBigEndian(headerData, 4)
        return if (length in 0..(MAX_PACKET_SIZE - headerLength)) length else -1
    }

    /**
     * 计算完整包所需的字节数
     *
     * @param headerData 已接收的头部数据
     * @return 还需要的字节数，如果头部无效返回 -1
     */
    fun getRemainingBytesNeeded(headerData: ByteArray): Int {
        if (!isValidHeader(headerData)) {
            return -1
        }
        val dataLength = getDataLengthFromHeader(headerData)
        if (dataLength < 0) return -1

        val totalNeeded = getHeaderLength() + dataLength
        return if (headerData.size >= totalNeeded) 0 else totalNeeded - headerData.size
    }

    // ==================== 私有工具方法 ====================

    /**
     * 校验魔数（快速比较，避免位运算）
     */
    private fun checkMagicNumber(data: ByteArray): Boolean {
        return data.size >= 4 &&
                data[0] == MAGIC_BYTES[0] &&
                data[1] == MAGIC_BYTES[1] &&
                data[2] == MAGIC_BYTES[2] &&
                data[3] == MAGIC_BYTES[3]
    }

    /**
     * 从指定偏移读取大端序 Int（无符号转换）
     */
    private fun readIntBigEndian(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun verifyAuthTag(packet: ByteArray, udpData: ByteArray): Boolean {
        val authTagStart = BASE_HEADER_LENGTH
        val authTagEnd = authTagStart + AUTH_TAG_LENGTH
        val actualTag = packet.copyOfRange(authTagStart, authTagEnd)
        return actualTag.contentEquals(createAuthTag(udpData))
    }

    private fun createAuthTag(udpData: ByteArray): ByteArray {
        val secret = authSecret.get() ?: return ByteArray(0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal(udpData)
        return digest.copyOfRange(0, AUTH_TAG_LENGTH)
    }
}