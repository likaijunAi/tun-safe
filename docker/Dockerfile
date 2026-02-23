# graalvm app
# VERSION eclipse-temurin:21-jre-alpine
# Author: likaijun(l@xsocket.cn)

FROM eclipse-temurin:21-jre-alpine

MAINTAINER likaijun <l@xsocket.cn>

ENV TZ=Asia/Shanghai

# Alpine 使用 apk 包管理器
RUN apk add --no-cache tzdata && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone

ENV LANG=C.UTF-8

VOLUME ["/apps"]

ADD ./bootstrap.jar /apps/bootstrap.jar

WORKDIR /apps

# 直接执行 java -jar 启动
CMD ["java", "-Xms512m", "-Xmx1024m", "-jar", "/apps/bootstrap.jar"]