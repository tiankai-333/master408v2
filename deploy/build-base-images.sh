#!/usr/bin/env bash
# 自建基础镜像（specs/2026-09-23-public-deploy PD-16）
# 链路：mirrors.aliyun.com/alpine 的 rootfs 与 apk 包 → 本机/服务器各自构建
# 产出：m408base:alpine3.20 / m408base:jre21 / m408base:redis7
# 依据：阿里云加速器白名单不含 openjdk/redis（2026-09-24 实测 404），Docker Hub 直连不可用
set -euo pipefail

MIRROR="https://mirrors.aliyun.com/alpine"
BRANCH="v3.20"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> [1/3] 构建底座 m408base:alpine3.20（docker import rootfs）"
ROOTFS_NAME="$(curl -s "$MIRROR/$BRANCH/releases/x86_64/" | grep -o 'alpine-minirootfs-[0-9.]*-x86_64.tar.gz' | sort -V | tail -1)"
[ -n "$ROOTFS_NAME" ] || { echo "FAIL: 未找到 rootfs 文件名"; exit 1; }
echo "    rootfs: $ROOTFS_NAME"
curl -sL -o "$WORK/rootfs.tar.gz" "$MIRROR/$BRANCH/releases/x86_64/$ROOTFS_NAME"
docker import "$WORK/rootfs.tar.gz" m408base:alpine3.20

echo "==> [2/3] 构建 m408base:jre21（apk openjdk21-jre）"
docker rmi m408base:jre21 >/dev/null 2>&1 || true
docker build -t m408base:jre21 - <<-'EOF'
FROM m408base:alpine3.20
RUN sed -i 's|dl-cdn.alpinelinux.org|mirrors.aliyun.com|g' /etc/apk/repositories \
 && apk add --no-cache openjdk21-jre \
 && java -version
ENV JAVA_HOME=/usr/lib/jvm/default-jvm
EOF

echo "==> [3/3] 构建 m408base:redis7（apk redis）"
docker rmi m408base:redis7 >/dev/null 2>&1 || true
docker build -t m408base:redis7 - <<-'EOF'
FROM m408base:alpine3.20
RUN sed -i 's|dl-cdn.alpinelinux.org|mirrors.aliyun.com|g' /etc/apk/repositories \
 && apk add --no-cache redis \
 && redis-server --version
EOF

echo "完成："
docker images --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep m408base
