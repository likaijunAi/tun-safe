docker run -d \
  --name safe-client \
  --restart always \
  --network host \
  -v "$(pwd)":/apps \
  -e MODE=client \
  -e UDP_BIND_HOST=0.0.0.0 \
  -e UDP_BIND_PORT=8080 \
  -e REMOTE_TCP_HOST=xx.xx.94.89 \
  -e REMOTE_TCP_PORT=9090 \
  -e DEBUG=true \
  registry.cn-hangzhou.aliyuncs.com/junhub/openjdk-17-alpine:1.0
