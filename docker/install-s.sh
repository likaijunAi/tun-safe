docker run -d \
  --name safe-server \
  --restart always \
  --network host \
  -v "$(pwd)":/apps \
  -e MODE=server \
  -e TCP_BIND_HOST=0.0.0.0 \
  -e TCP_BIND_PORT=9090 \
  -e TARGET_UDP_HOST=10.3.4.2 \
  -e TARGET_UDP_PORT=3478 \
  -e DEBUG=true \
  registry.cn-hangzhou.aliyuncs.com/junhub/openjdk-17-alpine:1.0
