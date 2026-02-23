package com.jun.tun.safe.core.util

import java.net.*
import java.util.*

/**
 * Network utilities
 *
 * Provides network-related helper methods
 *
 * @author leolee
 * @create 2026/2/8
 */
object NetworkUtils {
    
    /**
     * Get all local IP addresses
     */
    fun getLocalIpAddresses(): List<String> {
        val ipList = mutableListOf<String>()
        
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                
                // Skip loopback and disabled interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {  // 只获取IPv4地址
                        ipList.add(address.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return ipList
    }
    
    /**
     * Get the first non-loopback IPv4 address of local machine
     */
    fun getLocalIpAddress(): String? {
        return getLocalIpAddresses().firstOrNull()
    }
    
    /**
     * Check if port is available
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Find available port
     */
    fun findAvailablePort(startPort: Int = 10000, endPort: Int = 65535): Int? {
        for (port in startPort..endPort) {
            if (isPortAvailable(port)) {
                return port
            }
        }
        return null
    }
    
    /**
     * Resolve host address (supports domain name resolution)
     */
    fun resolveHost(host: String): InetAddress? {
        return try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Test UDP connectivity
     */
    fun testUdpConnectivity(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            
            val testData = "PING".toByteArray()
            val packet = DatagramPacket(testData, testData.size, InetAddress.getByName(host), port)
            
            socket.send(packet)
            socket.receive(DatagramPacket(ByteArray(1024), 1024))
            
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Test TCP connectivity
     */
    fun testTcpConnectivity(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}