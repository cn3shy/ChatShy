# ChatShy 后端镜像（单端口 8088，只跑 API）
# 前端由同 Podman 网络里的 nginx 容器直接托管，见 podman-compose.yml。
#
# 构建：podman compose build
# 多阶段：构建层编译 jar；运行层只带 JRE 21 + curl（健康检查）。

# ---------- 阶段 1：后端 ----------
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS backend-builder
WORKDIR /build
COPY server-java/pom.xml ./pom.xml
RUN mvn -B -e -DskipTests dependency:go-offline
COPY server-java/src ./src
RUN mvn -B -e -DskipTests package
# 产物 /build/target/chatshy-server.jar

# ---------- 阶段 2：前端 ----------
FROM docker.io/library/node:22-bookworm AS frontend-builder
RUN npm install -g pnpm@12
WORKDIR /build
COPY dounai/package.json dounai/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY dounai/ ./
RUN pnpm build
# 产物 /build/dist（index.html 豆奶 + admin.html 控制台）

# ---------- 阶段 3：后端运行时 ----------
FROM docker.io/library/eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# 后端 jar：保持 /app/server-java/target/ 路径，工作目录 /app 下可由相对路径访问，
# 同时匹配 WebConfig 从工作目录读 dounai/dist 的相对路径约定（仅作兜底，前端实际由 nginx 托管）。
COPY --from=backend-builder /build/target/chatshy-server.jar /app/server-java/target/chatshy-server.jar

WORKDIR /app

# 容器内 8088（由 nginx 反代对外，不直接 publish）
EXPOSE 8088

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8088/healthz || exit 1

CMD ["bash", "-c", "exec java $JAVA_OPTS -jar server-java/target/chatshy-server.jar"]
