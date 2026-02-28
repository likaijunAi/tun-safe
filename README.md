### 项目 `tun-safe` 说明文档

#### 1. **项目概述**
`tun-safe` 是一个基于 Kotlin 开发的网络隧道工具，支持通过 TCP 协议伪装 UDP 流量，实现安全的数据传输。项目分为两个核心模块：
- **`tun-safe-bootstrap`**：启动模块，提供命令行入口和参数解析。
- **`tun-safe-core`**：核心功能模块，实现 UDP over TCP 的数据转发逻辑。

#### 2. **功能特性**
- **支持两种运行模式**：
  - **服务端模式 (`server`)**：
    - 监听指定 TCP 端口，将接收到的数据转发到目标 UDP 地址。
  - **客户端模式 (`client`)**：
    - 绑定本地 UDP 端口，将数据通过 TCP 协议发送到远程服务端。
- **动态日志配置**：
  - 默认日志级别为 `INFO`，可通过环境变量 `LOG_LEVEL` 调整。
  - 日志输出到控制台，格式包含时间、线程、日志级别和消息。
- **灵活的依赖管理**：
  - 通过 Gradle 构建，模块间依赖清晰。

#### 3. **快速开始**
##### 3.1 构建项目
```bash
./gradlew build
```

##### 3.2 运行服务端
```bash
./gradlew :tun-safe-bootstrap:run --args="mode=server tcpBindPort=8080 targetUdpHost=127.0.0.1 targetUdpPort=9090"
```

##### 3.3 运行客户端
```bash
./gradlew :tun-safe-bootstrap:run --args="mode=client udpBindHost=127.0.0.1 udpBindPort=7070 remoteTcpHost=192.168.1.100 remoteTcpPort=8080"
```

#### 4. **Docker 支持**
##### 4.1 构建 Docker 镜像
```bash
docker build -t tun-safe .
```

##### 4.2 运行服务端
```bash
docker run -d \
  --name safe-server \
  --restart always \
  --network host \ 
  -e MODE=server \
  -e TCP_BIND_HOST=0.0.0.0 \
  -e TCP_BIND_PORT=9090 \
  -e TARGET_UDP_HOST=10.3.4.2 \
  -e TARGET_UDP_PORT=3478 \
  -e DEBUG=true \
  -e AUTH_TOKEN=replace-with-strong-token \
  registry.cn-hangzhou.aliyuncs.com/junhub/tun-safe:1.0.1
```

##### 4.3 运行客户端
```bash
docker run -d \
  --name safe-client \
  --restart always \
  --network host \ 
  -e MODE=client \
  -e UDP_BIND_HOST=0.0.0.0 \
  -e UDP_BIND_PORT=8080 \
  -e REMOTE_TCP_HOST=xx.xx.94.89 \
  -e REMOTE_TCP_PORT=9090 \
  -e DEBUG=true \
  -e AUTH_TOKEN=replace-with-strong-token \
  registry.cn-hangzhou.aliyuncs.com/junhub/tun-safe:1.0.1
```

#### 5. **参数说明**
| 参数名           | 描述                                                                 | 必填 |
|------------------|----------------------------------------------------------------------|------|
| `mode`           | 运行模式：`server` 或 `client`。                                     | 是   |
| `tcpBindPort`    | 服务端监听的 TCP 端口。                                              | 是   |
| `targetUdpHost`  | 服务端转发的目标 UDP 地址（服务端模式）。                            | 是   |
| `targetUdpPort`  | 服务端转发的目标 UDP 端口（服务端模式）。                            | 是   |
| `udpBindHost`    | 客户端绑定的本地 UDP 地址（客户端模式）。                            | 否   |
| `udpBindPort`    | 客户端绑定的本地 UDP 端口（客户端模式）。                            | 否   |
| `remoteTcpHost`  | 客户端连接的远程 TCP 地址（客户端模式）。                            | 是   |
| `remoteTcpPort`  | 客户端连接的远程 TCP 端口（客户端模式）。                            | 是   |
| `authToken`      | 隧道鉴权令牌（通过环境变量 `AUTH_TOKEN` 配置，客户端与服务端需一致）。 | 是   |

#### 6. **日志配置**
- 默认日志级别为 `INFO`，可通过环境变量 `LOG_LEVEL` 动态调整（如 `DEBUG`、`ERROR`）。
- 日志输出格式：
  ```
  HH:mm:ss.SSS [thread] LEVEL logger - message
  ```

#### 7. **模块依赖**
- **`tun-safe-bootstrap`** 依赖 **`tun-safe-core`**，通过 Gradle 的 `implementation(project(":tun-safe-core"))` 声明。

#### 8. **后续计划** 
- 增强日志功能，记录端口绑定和模式切换的详细信息。
- 优化 `TunnelManager` 的性能和稳定性。

---

### `tun-safe` Project Documentation (English)

#### 1. **Overview**
`tun-safe` is a Kotlin-based network tunneling tool that encapsulates UDP traffic over TCP for safer and more reliable transmission. The project is split into two core modules:
- **`tun-safe-bootstrap`**: bootstrap module providing the CLI entry point and argument parsing.
- **`tun-safe-core`**: core module implementing UDP-over-TCP forwarding logic.

#### 2. **Features**
- **Two runtime modes**:
  - **Server mode (`server`)**:
    - Listens on a TCP port and forwards received traffic to a target UDP address.
  - **Client mode (`client`)**:
    - Binds to a local UDP port and forwards traffic to the remote server over TCP.
- **Dynamic logging configuration**:
  - Default log level is `INFO`, configurable through the `LOG_LEVEL` environment variable.
  - Logs are printed to stdout with timestamp, thread, level, and message.
- **Modular dependency management**:
  - Built with Gradle and clean module dependencies.

#### 3. **Quick Start**
##### 3.1 Build the project
```bash
./gradlew build
```

##### 3.2 Run in server mode
```bash
./gradlew :tun-safe-bootstrap:run --args="mode=server tcpBindPort=8080 targetUdpHost=127.0.0.1 targetUdpPort=9090"
```

##### 3.3 Run in client mode
```bash
./gradlew :tun-safe-bootstrap:run --args="mode=client udpBindHost=127.0.0.1 udpBindPort=7070 remoteTcpHost=192.168.1.100 remoteTcpPort=8080"
```

#### 4. **Docker**
##### 4.1 Build the image
```bash
docker build -t tun-safe .
```

##### 4.2 Run as server
```bash
docker run -d \
  --name safe-server \
  --restart always \
  --network host \ 
  -e MODE=server \
  -e TCP_BIND_HOST=0.0.0.0 \
  -e TCP_BIND_PORT=9090 \
  -e TARGET_UDP_HOST=10.3.4.2 \
  -e TARGET_UDP_PORT=3478 \
  -e DEBUG=true \
  -e AUTH_TOKEN=replace-with-strong-token \
  registry.cn-hangzhou.aliyuncs.com/junhub/tun-safe:1.0.1
```

##### 4.3 Run as client
```bash
docker run -d \
  --name safe-client \
  --restart always \
  --network host \ 
  -e MODE=client \
  -e UDP_BIND_HOST=0.0.0.0 \
  -e UDP_BIND_PORT=8080 \
  -e REMOTE_TCP_HOST=xx.xx.94.89 \
  -e REMOTE_TCP_PORT=9090 \
  -e DEBUG=true \
  -e AUTH_TOKEN=replace-with-strong-token \
  registry.cn-hangzhou.aliyuncs.com/junhub/tun-safe:1.0.1
```

#### 5. **Parameter Reference**
| Parameter        | Description                                                                | Required |
|------------------|----------------------------------------------------------------------------|----------|
| `mode`           | Runtime mode: `server` or `client`.                                       | Yes      |
| `tcpBindPort`    | TCP port listened to by the server.                                       | Yes      |
| `targetUdpHost`  | Target UDP host forwarded to by server mode.                              | Yes      |
| `targetUdpPort`  | Target UDP port forwarded to by server mode.                              | Yes      |
| `udpBindHost`    | Local UDP bind host in client mode.                                       | No       |
| `udpBindPort`    | Local UDP bind port in client mode.                                       | No       |
| `remoteTcpHost`  | Remote TCP host used by client mode.                                      | Yes      |
| `remoteTcpPort`  | Remote TCP port used by client mode.                                      | Yes      |
| `authToken`      | Tunnel auth token (`AUTH_TOKEN` env var, must match on both sides).       | Yes      |

#### 6. **Logging**
- Default log level is `INFO`; you can change it via `LOG_LEVEL` (e.g., `DEBUG`, `ERROR`).
- Log format:
  ```
  HH:mm:ss.SSS [thread] LEVEL logger - message
  ```

#### 7. **Module Dependency**
- **`tun-safe-bootstrap`** depends on **`tun-safe-core`** through Gradle:
  `implementation(project(":tun-safe-core"))`.

#### 8. **Roadmap** 
- Improve logging with richer runtime context.
- Optimize `TunnelManager` performance and stability.

#### 9. **License**
This project is licensed under the Apache License 2.0. See [LICENSE](./LICENSE) for details.
