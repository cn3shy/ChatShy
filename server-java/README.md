# ChatShy · Spring Boot 后端

ChatShy 的 JDK 21 / Spring Boot 3.5 实现。与 Node 版（仓库根目录 `src/`）功能对齐，并额外把**豆奶**站点并入同一个进程。

## 端口与站点

| 端口 | 对外站点 | 内容 |
| --- | --- | --- |
| `127.0.0.1:8088` | `ai.3shy.cn` | 操作台 + OpenAI / Anthropic 协议网关 |
| `127.0.0.1:8099` | `dounai.3shy.cn` / `dounai.chat` | 豆奶聊天页 |

两个端口由同一个 Spring Boot 进程提供（额外挂载一个 Tomcat Connector），对 nginx 来说与原来的两个服务完全一致。

## 技术栈

- Java 21（虚拟线程）
- Spring Boot 3.5.16 / Spring MVC
- SQLite（`sqlite-jdbc` + HikariCP 单连接，WAL / foreign_keys）
- `java.net.http.HttpClient` 调用 OpenAI 兼容上游
- BouncyCastle SCrypt（兼容 Node 版 `scrypt$N$r$p$salt$hash` 管理员密码）
- JUnit 5 + Spring Boot Test（70 个用例）

## 构建与测试

```bash
cd server-java
mvn test                 # 70 个 JUnit 用例
mvn -DskipTests package  # 产物 target/chatshy-server.jar
```

## 运行

必须在仓库根目录 `/home/ChatShy` 下运行，前端构建产物与 `.env` 都相对于工作目录：

```bash
cd /home/ChatShy
java -Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC \
  -jar server-java/target/chatshy-server.jar
```

- 操作台：<http://localhost:8088>
- 豆奶：<http://localhost:8099>
- 健康检查：`GET /healthz`
- 豆奶模型信息：`GET /api/config`

## 配置

与 Node 版共用同一份 `.env`（Spring Boot 启动时通过 `spring.config.import` 加载），关键变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | `8088` | 网关/操作台端口 |
| `HOST` | `0.0.0.0` | 监听地址（线上 `.env` 为 `127.0.0.1`） |
| `DB_PATH` | `./data/chatshy.db` | SQLite 文件 |
| `ADMIN_PASSWORD` | 随机生成并打印 | 仅用于首次播种 |
| `CALLER_KEY` | 随机生成并打印 | 仅用于首次播种 |
| `DEFAULT_TIMEOUT_MS` | `180000` | 挂起超时 |
| `MODEL_NAME` | `chatshy` | `/v1/models` 展示名 |
| `DOUNAI_ENABLED` | `true` | 是否启动 8099 监听 |
| `DOUNAI_PORT` | `8099` | 豆奶端口 |
| `DOUNAI_HOST` | `127.0.0.1` | 豆奶监听地址 |
| `DOUNAI_CALLER` | `豆奶` | 操作台来源名 |
| `DOUNAI_SYSTEM_PROMPT` | 空 | 豆奶前置 system 提示词 |
| `DOUNAI_UPSTREAM_BASE_URL` | `http://127.0.0.1:${PORT}/v1` | 豆奶转发的网关地址 |

## 数据兼容

- 沿用 Node 版 SQLite schema（`schema_version=3`），启动时执行同一套迁移，只允许追加。
- 管理员密码哈希、调用方 Key、会话/消息/Job、厂商配置全部原样可用。
- 启动时把遗留 `pending` Job 标记为 `dropped`（与 Node 版重启语义一致）。

## 前端静态资源

- 操作台：`web/dist`（`npm --prefix web run build`），资源路径 `/assets/**`
- 豆奶：`dounai/dist`（`pnpm -C dounai build`），资源路径 `/dounai-assets/**`
- 豆奶页面带 CSP / nosniff / referrer 安全头；`/api/chat` 只允许同源调用，不开放 CORS。

## systemd

```ini
[Service]
WorkingDirectory=/home/ChatShy
ExecStart=/usr/bin/java -Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC \
  -jar /home/ChatShy/server-java/target/chatshy-server.jar
Restart=always
RestartSec=3
```

`dounai.service` 已并入 `chatshy.service`，不再单独运行。

## 回滚

Node 版源码、`dist/` 与数据库迁移前的备份均保留：

- systemd 单元：`/root/chatshy-java-rollback-2026-11-12/`
- 数据库：`/home/ChatShy/data/chatshy.db.before-java-2026-11-12`
