package com.jun.tun.safe.core.protocol

import io.netty.buffer.ByteBuf

/**
 * UDP to TCP tunnel packet protocol definition
 *
 * Protocol format:
 * [Magic Number(4 bytes)] + [Data Length(4 bytes)] + [UDP Data(n bytes)]
 *
 * Magic Number: 0x12345678 - used to identify protocol packets
 * Data Length: actual length of subsequent UDP data (big-endian, unsigned int)
 * UDP Data: original UDP packet content
 *
 * @author leolee
 * @create 2026/2/8
 */
object PacketProtocol {

    /** Protocol magic number, used to identify valid packets */
    const val MAGIC_NUMBER: Int = 0x12345678

    /** Header total length (Magic Number + Data Length) */
    const val HEADER_LENGTH: Int = 8

    /** Maximum packet size (64KB) */
    const val MAX_PACKET_SIZE: Int = 65536

    /** Minimum valid packet size */
    const val MIN_PACKET_SIZE: Int = HEADER_LENGTH

    /** Magic number bytes (大端序缓存，避免重复计算) */
    private val MAGIC_BYTES = byteArrayOf(
        0x12.toByte(),
        0x34.toByte(),
        0x56.toByte(),
        0x78.toByte()
    )

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

        if (udpData.size > MAX_PACKET_SIZE - HEADER_LENGTH) {
            return null // 超过最大限制
        }

        val packetSize = HEADER_LENGTH + udpData.size
        val result = ByteArray(packetSize)

        // 写入魔数 (使用缓存的字节数组，避免位运算)
        System.arraycopy(MAGIC_BYTES, 0, result, 0, 4)

        // 写入数据长度 (大端序，使用位运算)
        val dataLength = udpData.size
        result[4] = (dataLength ushr 24).toByte()
        result[5] = (dataLength ushr 16).toByte()
        result[6] = (dataLength ushr 8).toByte()
        result[7] = dataLength.toByte()

        // 写入UDP数据
        System.arraycopy(udpData, 0, result, HEADER_LENGTH, udpData.size)

        return result
    }

    /**
     * Parse UDP data from TCP packet
     *
     * @param tcpData TCP transmitted packet
     * @return parsed raw UDP data, returns null if not a valid protocol packet
     */
    fun decodeTcpToUdp(tcpData: ByteArray): ByteArray? {
        // 长度检查
        if (tcpData.size < MIN_PACKET_SIZE) {
            return null // 数据不完整
        }

        // 魔数校验
        if (!checkMagicNumber(tcpData)) {
            return null // 非法协议包
        }

        // 读取数据长度 (大端序，无符号)
        val dataLength = readIntBigEndian(tcpData, 4)
        if (dataLength < 0 || dataLength > MAX_PACKET_SIZE - HEADER_LENGTH) {
            return null // 非法长度
        }

        // 验证数据完整性
        val expectedSize = HEADER_LENGTH + dataLength
        if (tcpData.size != expectedSize) {
            return null // 长度不匹配
        }

        // 提取UDP数据
        return tcpData.copyOfRange(HEADER_LENGTH, expectedSize)
    }

    /**
     * 从 ByteBuf 解析 UDP 数据（零拷贝优化）
     * 用于 Netty 的 ByteBuf 直接处理，避免数组复制
     *
     * @param buffer Netty ByteBuf
     * @return 解析后的字节数组，或 null
     */
    fun decodeFromByteBuf(buffer: ByteBuf): ByteArray? {
        if (buffer.readableBytes() < MIN_PACKET_SIZE) {
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
        if (dataLength < 0 || dataLength > MAX_PACKET_SIZE - HEADER_LENGTH) {
            buffer.resetReaderIndex()
            return null
        }

        // 检查剩余数据
        if (buffer.readableBytes() < dataLength) {
            buffer.resetReaderIndex()
            return null // 数据不完整，等待更多数据
        }

        // 读取数据
        val result = ByteArray(dataLength)
        buffer.readBytes(result)
        return result
    }

    /**
     * 将 UDP 数据编码到 ByteBuf（零拷贝）
     *
     * @param allocator ByteBuf 分配器
     * @param udpData 原始 UDP 数据
     * @return 编码后的 ByteBuf
     */
    fun encodeToByteBuf(allocator: io.netty.buffer.ByteBufAllocator, udpData: ByteArray): ByteBuf? {
        if (udpData.isEmpty() || udpData.size > MAX_PACKET_SIZE - HEADER_LENGTH) {
            return null
        }

        val packetSize = HEADER_LENGTH + udpData.size
        val buffer = allocator.buffer(packetSize, packetSize)

        buffer.writeInt(MAGIC_NUMBER)
        buffer.writeInt(udpData.size)
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
        return headerData.size >= HEADER_LENGTH && checkMagicNumber(headerData)
    }

    /**
     * 从包头读取数据长度（不验证魔数）
     *
     * @param headerData 包含头部的字节数组（至少8字节）
     * @return 数据长度，或 -1 如果无效
     */
    fun getDataLengthFromHeader(headerData: ByteArray): Int {
        if (headerData.size < HEADER_LENGTH) {
            return -1
        }
        val length = readIntBigEndian(headerData, 4)
        return if (length in 0..(MAX_PACKET_SIZE - HEADER_LENGTH)) length else -1
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

        val totalNeeded = HEADER_LENGTH + dataLength
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
}