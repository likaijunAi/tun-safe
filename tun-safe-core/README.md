# udp2raw-kotlin

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-blue.svg)](https://kotlinlang.org/)
[![Netty](https://img.shields.io/badge/Netty-4.1+-green.svg)](https://netty.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个基于 Kotlin + Netty 的高性能 UDP over TCP 隧道工具，将 UDP 流量伪装为 TCP 流量传输，用于穿透防火墙/NAT。

## 特性

- 🚀 **高性能**：基于 Netty 的异步非阻塞 I/O，支持数万并发连接
- 🔒 **协议伪装**：UDP 数据包封装为 TCP 流，绕过 UDP 封锁
- 🔄 **双向转发**：支持客户端/服务器/中继三种模式
- 💾 **资源优化**：共享 EventLoopGroup，减少线程开销
- ⏱️ **连接管理**：自动心跳检测、空闲超时、优雅重连
- 🛡️ **零拷贝**：支持 Netty ByteBuf 直接操作，减少内存复制

## 架构
┌─────────────┐      TCP Tunnel        ┌─────────────┐
│  UDP Client │  ═══════════════════►  │  UDP Server │
│  (TunServer)│  ◄═══════════════════  │ (SafeServer)│
└─────────────┘    [Length][Data]      └─────────────┘
      │                                      │
      │ UDP                                  │ UDP
      ▼                                      ▼
┌─────────────┐                         ┌─────────────┐
│  Target App │                         │  Target App │
└─────────────┘                         └─────────────┘


## 快速开始

### 依赖

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.netty:netty-all:4.1.100.Final")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")
}

客户端模式（UDP → TCP）
将本地 UDP 端口的数据通过 TCP 隧道发送到远程服务器：

import com.jun.tun.safe.core.manager.TunnelManager

fun main() {
    val manager = TunnelManager()
    
    manager.startClientMode(
        udpBindHost = "0.0.0.0",
        udpBindPort = 5353,           // 本地 UDP 监听端口
        remoteTcpHost = "server.ip",   // 远程 TCP 服务器
        remoteTcpPort = 8080           // 远程 TCP 端口
    ).thenRun {
        println("Client started: udp://0.0.0.0:5353 -> tcp://server.ip:8080")
    }.exceptionally { ex ->
        println("Failed to start: ${ex.message}")
        null
    }
    
    // 保持运行
    Thread.currentThread().join()
}

服务器模式（TCP → UDP）
接收 TCP 隧道数据，解压并转发到目标 UDP 服务器：

fun main() {
    val manager = TunnelManager()
    
    manager.startServerMode(
        tcpBindHost = "0.0.0.0",
        tcpBindPort = 8080,            // TCP 监听端口
        targetUdpHost = "8.8.8.8",     // 目标 UDP 服务器（如 DNS）
        targetUdpPort = 53             // 目标 UDP 端口
    ).thenRun {
        println("Server started: tcp://0.0.0.0:8080 -> udp://8.8.8.8:53")
    }
    
    Thread.currentThread().join()
}

双模式（中继）
同时作为客户端和服务器，实现 UDP 流量中继：
fun main() {
    val manager = TunnelManager()
    
    manager.startDualMode(
        // 服务器端（接收 TCP）
        tcpBindPort = 8080,
        targetUdpHost = "127.0.0.1",
        targetUdpPort = 53,
        // 客户端端（发送 TCP）
        udpBindPort = 5353,
        remoteTcpHost = "upstream.server",
        remoteTcpPort = 8080
    ).thenRun {
        println("Relay node started")
    }
    
    Thread.currentThread().join()
}

协议格式
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Magic Number                        |
|                         (0x12345678)                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Data Length                          |
|                     (Big-Endian, 4 bytes)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                          UDP Payload                          +
|                     (Variable Length)                         |
+                                                               +

Magic Number: 0x12345678（4 bytes，大端序）
Data Length: UDP 数据长度（4 bytes，大端序，无符号）
Max Packet Size: 64KB（含头部）

配置参数
TunServer（UDP 入口）

| 参数              | 说明         | 默认值       |
| --------------- | ---------- | --------- |
| `bindHost`      | UDP 监听地址   | `0.0.0.0` |
| `bindPort`      | UDP 监听端口   | 必填        |
| `tcpTargetHost` | 目标 TCP 服务器 | 必填        |
| `tcpTargetPort` | 目标 TCP 端口  | 必填        |

SafeServer（TCP 入口）

| 参数                 | 说明         | 默认值       |
| ------------------ | ---------- | --------- |
| `bindHost`         | TCP 监听地址   | `0.0.0.0` |
| `bindPort`         | TCP 监听端口   | 必填        |
| `udpTargetHost`    | 目标 UDP 服务器 | 必填        |
| `udpTargetPort`    | 目标 UDP 端口  | 必填        |
| `heartbeatTimeout` | 读空闲超时（秒）   | 60        |

TunnelManager
| 参数                       | 说明     | 默认值 |
| ------------------------ | ------ | --- |
| `shutdownTimeoutSeconds` | 优雅停止超时 | 10  |

高级用法
共享 EventLoopGroup
在高并发场景下，共享线程组减少资源占用：
val sharedBoss = NioEventLoopGroup(2)
val sharedWorker = NioEventLoopGroup(8)

val server = SafeServer(
    bindPort = 8080,
    udpTargetHost = "127.0.0.1",
    udpTargetPort = 53,
    externalBossGroup = sharedBoss,
    externalWorkerGroup = sharedWorker
)

自定义协议处理
// 使用 ByteBuf 零拷贝 API
val buffer: ByteBuf = PacketProtocol.encodeToByteBuf(ctx.alloc(), udpData)
val decoded: ByteArray? = PacketProtocol.decodeFromByteBuf(byteBuf)

状态监控
val status = manager.getStatus()
println("""
    Mode: ${status.mode}
    Running: ${status.isRunning}
    Server Active: ${status.isServerRunning}
    Client Active: ${status.isClientRunning}
""".trimIndent())

性能优化建议
调整缓冲区大小
// 根据网络环境调整
.option(ChannelOption.SO_RCVBUF, 1024 * 1024)  // 1MB
.option(ChannelOption.SO_SNDBUF, 1024 * 1024)
 
启用 Epoll（Linux）
// 替换 NioEventLoopGroup 为 EpollEventLoopGroup
val group = EpollEventLoopGroup()

连接池调优
默认每个 UDP 源地址对应一个 TCP 连接
可通过 UdpToTcpHandler 的共享 group 参数优化

日志配置
<!-- logback.xml -->
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.jun.tun.safe" level="INFO"/>
    <logger name="io.netty" level="WARN"/>
    
    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>

项目结构
com.jun.tun.safe.core
├── handler          # 数据处理器
│   ├── TcpToUdpHandler.kt    # TCP → UDP 转换
│   └── UdpToTcpHandler.kt    # UDP → TCP 转换
├── manager          # 生命周期管理
│   └── TunnelManager.kt      # 统一入口
├── protocol         # 协议定义
│   └── PacketProtocol.kt     # 编解码
├── tcp              # TCP 组件
│   └── SafeServer.kt         # TCP 服务器
└── udp              # UDP 组件
    ├── TunClient.kt          # UDP 客户端
    └── TunServer.kt          # UDP 服务器

注意事项
⚠️ MTU 限制：UDP 包超过 65467 字节会被丢弃（TCP 头部开销）
⚠️ 连接状态：TCP 连接断开后需重建，不保证 UDP 包顺序
⚠️ 防火墙：确保服务器端 TCP 端口开放

致谢
Netty - 异步事件驱动的网络应用框架
udp2raw - 原始项目灵感来源