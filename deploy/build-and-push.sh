#!/usr/bin/env bash
# 本机构建并推送镜像（A-03 定稿流程；在仓库根或任意目录执行均可）
# 用法：bash deploy/build-and-push.sh [TAG]
#   TAG 缺省 = 当前 git 短 SHA；生产禁用 latest（PD-08）
# 前置：本机已装 docker、mvn、node；已 docker login 公网端点（用户名 nick4576420725）
set -euo pipefail

REGISTRY="crpi-z118pytqkuhi1rn1.cn-wulanchabu.personal.cr.aliyuncs.com"
NAMESPACE="wutiankai"
REPO="master408"   # 单仓库（A-03）：业务镜像共用，tag 前缀区分
TAG="${1:-$(git rev-parse --short HEAD)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/deploy/build"

cd "$ROOT"
mkdir -p "$BUILD"

echo "==> [1/4] 构建后端 jar（skip tests）"
(cd apps/backend && mvn -q -pl backend-app -am -DskipTests package)
JAR="$(ls apps/backend/backend-app/target/backend-app-*.jar | grep -v original | head -1)"
cp -f "$JAR" "$BUILD/backend.jar"
echo "    jar: $(basename "$JAR")"

echo "==> [2/4] 构建前端 dist（student / admin）"
(cd apps/frontend/student && npm run build)
(cd apps/frontend/admin && npm run build)
rm -rf "$BUILD/student-dist" "$BUILD/admin-dist"
cp -r apps/frontend/student/dist "$BUILD/student-dist"
cp -r apps/frontend/admin/dist "$BUILD/admin-dist"

echo "==> [3/4] 构建镜像（:$TAG）"
docker build -f deploy/Dockerfile.backend -t "$REGISTRY/$NAMESPACE/$REPO:backend-$TAG" "$ROOT/deploy"
docker build -f deploy/Dockerfile.web      -t "$REGISTRY/$NAMESPACE/$REPO:web-$TAG"      "$ROOT/deploy"

echo "==> [4/4] 推送 :$TAG"
docker push "$REGISTRY/$NAMESPACE/$REPO:backend-$TAG"
docker push "$REGISTRY/$NAMESPACE/$REPO:web-$TAG"

echo "完成：$REGISTRY/$NAMESPACE/$REPO:{backend,web}-$TAG"
echo "服务器侧拉取请用 VPC 端点（见 deploy/README.md）"
