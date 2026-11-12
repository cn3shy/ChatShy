# ChatShy · 人肉大模型网关

对外是一个 **OpenAI / Anthropic 双协议兼容** 的大模型 API；实际上每个请求都会实时推送到网页操作台，由你（人类）撰写回复，再按协议格式（含流式 SSE）原样返回给调用方。操作台本身也是个聊天窗口，不经过 API 就能直接收发消息。

同一个 Java 进程还托管着**豆奶**——一个独立的访客聊天页，服务端已固定接入本网关，访客无需填写 API Key。

- 接口参考（全部 HTTP 端点、SSE 事件、错误格式）：`docs/protocol.md`

## 运行形态

| 端口 | 站点 | 内容 |
| --- | --- | --- |
| `127.0.0.1:8088` | `ai.3shy.cn` | 操作台 + OpenAI / Anthropic 网关 |
| `127.0.0.1:8099` | `dounai.3shy.cn` / `dounai.chat` | 豆奶聊天页 |

- 后端：Spring Boot 3.5 / JDK 21，代码在 `server-java/`
- 前端：`dounai/` 是唯一的 Vite + Vue 3 + TypeScript 工程，一次构建产出**豆奶页**（`/`）与**操作台**（`/admin`）两个页面，依赖用 pnpm 管理
- 操作台入口：`ai.3shy.cn`、`ai.3shy.cn/admin`、`dounai.chat/admin` 均可打开
- PostgreSQL 存储（连接信息见 `.env.example` 的 `DB_URL` / `DB_USER` / `DB_PASSWORD`），启动时自动建表；旧 SQLite 数据可用一次性工具导入

```
server-java/   Spring Boot 后端
dounai/        前端工程（豆奶页 src/ + 操作台 admin/）
docs/          设计文档与协议参考
```

## 功能

- **双协议兼容**：`POST /v1/chat/completions`（OpenAI）与 `POST /v1/messages`（Anthropic），流式/非流式均可；Cherry Studio、ChatBox、LobeChat、官方 SDK、Claude Code 等直接接入
- **操作台**：SSE 实时推送新请求（≤1 秒）、桌面通知 + 提示音、倒计时、重复请求提示、Markdown 输入与代码高亮渲染、手机浏览器可用
- **唯一全局 Key + 来源标记**：所有调用方共用一个 Key，用请求来源区分"谁在说话"；来源优先取调用方自报的身份头 `X-ChatShy-Caller`，没带则回落到 IP + User-Agent 识别出的客户端名
- **请求监控**：操作台新增独立页面，分页查看所有请求的参数记录（完整请求 JSON、协议/模式、状态、来源、模型、转发厂商与回复内容），支持按状态/协议/关键词过滤，5 秒自动刷新
- **删除会话**：悬停任意会话即可单条删除；进入「批量管理」可多选批量删除，消息与请求记录一并清除
- **敏感词屏蔽**：豆奶公开入口的最新一条用户消息命中词库时直接不回复——不请求上游、不建会话、不消耗 token；词库和开关可在操作台设置页维护
- **流式打字机**：流式模式下人工回复按字素切片、逐帧下发，观感与真实大模型一致，总时长封顶约 9 秒；AI 转发的回复不走打字机，直接实时透传上游增量
- **请求挂起 + 可配置超时**：默认 180 秒（全局设置项）；超时返回各协议标准错误体
- **第三方 AI 转发**：可配置多个 OpenAI 兼容厂商（DeepSeek、通义千问、GLM、任意自定义端点）。对每个待回复请求可手动选择厂商「AI 回复」；侧边栏的「AI 自动转发」开关开启后服务端自动回复所有新请求——操作台关掉也继续生效。流式调用方收到上游实时增量；自动失败时请求留在待回复列表并标注原因
- **会话归并**：多轮请求自动归并成一条会话线，按来源隔离；超时后补的回复只存档并标注"未送达"
- **豆奶页面**：独立访客聊天页，Markdown 渲染、流式输出、历史会话存本地、思考过程折叠

## 本地开发

后端测试使用 embedded PostgreSQL（Zonky），无需本机安装 PostgreSQL 或 Docker；首次运行会下载 PostgreSQL 二进制。

```bash
# 后端（需要 JDK 21）
cd server-java
mvn test                  # 单元 + 集成测试（自动启动 embedded PostgreSQL）
mvn -DskipTests package   # 产物 target/chatshy-server.jar

# 前端（需要 pnpm）
pnpm -C dounai install
pnpm -C dounai build      # 产物 dounai/dist/，由 Spring Boot 托管
pnpm -C dounai dev        # http://localhost:5174，豆奶与操作台接口自动代理到后端
```

运行（必须在仓库根目录，才能找到 `dounai/dist` 与 `.env`）：

```bash
java -jar server-java/target/chatshy-server.jar
# 操作台 http://localhost:8088 ；豆奶 http://localhost:8099 ；操作台也可走 /admin
```

## 使用

### 操作台

打开 `http://localhost:8088`（或 `/admin`），用管理员密码登录：

- 左侧：会话列表。未回复的置顶、红点、剩余秒数倒计时；每条显示请求来源（名字/客户端 + IP），悬停行首可删除
- 右侧：对话视图 + 底部输入框（Enter 发送，Shift+Enter 换行，支持 Markdown）
- ⚙ 设置页：查看/复制/重新生成全局调用方 Key、一键复制接入配置、管理 AI 厂商、改默认超时、模型名、管理员密码、通知开关
- 侧边栏：「AI 自动转发」开关 + 厂商下拉
- 待回复条：选择厂商后点「AI 回复」，由该厂商直接回答调用方；AI 生成的回复在消息上标注 `AI · 厂商名`

> 删除会话时，若那条会话还有挂起中的请求，调用方会立刻收到超时错误。

> 不同调用方的会话按来源指纹隔离。默认指纹是 IP + 客户端名：同一个人换网络会被当成新来源——这是为了不让两个人串到一条会话里。给每个调用方配 `X-ChatShy-Caller` 身份头可彻底解决，见 `docs/protocol.md`。

### 调用方接入

Key 从操作台 ⚙ 设置 →「调用方 Key」复制，所有调用方共用。

OpenAI 协议（Python SDK 示例）：

```python
from openai import OpenAI
client = OpenAI(base_url="https://你的域名/v1", api_key="sk-chatshy-...")
resp = client.chat.completions.create(
    model="chatshy",  # 任意名称都会原样回显
    messages=[{"role": "user", "content": "在吗"}],
)
```

Cherry Studio / ChatBox 等客户端：API 地址填 `https://你的域名/v1`，API Key 填全局 Key。

Anthropic 协议（Claude Code 等）：

```bash
export ANTHROPIC_BASE_URL="https://你的域名"   # 不要以 /v1 结尾
export ANTHROPIC_API_KEY="sk-chatshy-..."      # 或 ANTHROPIC_AUTH_TOKEN
```

- 鉴权同时接受 `x-api-key` 与 `Authorization: Bearer`
- `POST /v1/messages/count_tokens`、`/api/hello` 探测、遥测路径均已实现（遥测一律 200，避免客户端重试风暴）
- 等待真人回复不会断流：网关建流后每 15 秒发 ping，Claude Code 默认 5 分钟空闲看门狗不会触发

完整端点列表、SSE 事件序列与错误格式见 `docs/protocol.md`。

### AI 厂商转发（可选）

在 ⚙ 设置 →「AI 厂商转发」里添加配置，每个配置包含名称、Base URL、模型名、API Key。只支持 OpenAI 兼容的 `/chat/completions` 接口：

| 厂商 | Base URL | 模型名示例 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| 其他 | 任意 OpenAI 兼容端点（如本地 Ollama / vLLM） | 按厂商文档 |

- **手动**：待回复条选择厂商 → 点「AI 回复」
- **自动**：侧边栏打开「AI 自动转发」并选好厂商，之后所有新请求自动由该厂商回答
- 上游使用配置里填的模型名，调用方请求里的 `model` 只用于回显；不自动重试、不自动切换厂商
- 调用方要流式时，转发也走上游流式接口并实时透传增量
- 自动转发失败（Key 错、限流、超时等）时请求留在待回复列表，显示失败原因，可随时人工接管
- 上游 Key 明文存在 PostgreSQL，管理端接口只回显末四位；编辑时留空表示沿用旧 Key
- ⚠️ 开启转发后，调用方的对话内容（含 system 提示词）会被发送到所配置的第三方厂商
- Base URL 填到 `/chat/completions` 之前的那一段，误粘贴完整 endpoint 会自动纠正。不要把 Base URL 指回 ChatShy 自己（会造成递归转发）

## 部署（本机 systemd）

先准备 PostgreSQL（14+；仓库根目录提供可选的 `docker-compose.yml`）：

```sql
CREATE USER chatshy WITH PASSWORD '换成强密码';
CREATE DATABASE chatshy OWNER chatshy;
```

在 `.env` 中填写：

```bash
DB_URL=jdbc:postgresql://127.0.0.1:5432/chatshy
DB_USER=chatshy
DB_PASSWORD=换成强密码
```

- systemd 单元：`/etc/systemd/system/chatshy.service`（`WorkingDirectory=/home/ChatShy`）
- 构建：推送到 `main` 后由 GitHub Actions 自动构建，产物发布到 GitHub Release（`.github/workflows/build-release.yml`）；服务器只需 JRE 21，不需要 Maven/Node
- 更新：首次把 `scripts/update.sh` 放到服务器（如 `/home/ChatShy/update.sh`）并 `chmod +x`；之后每次执行 `/home/ChatShy/update.sh` 拉取最新产物、备份并重启，`--rollback` 回滚到上一版。仅前端有变化时脚本不会重启服务
- 启停：`systemctl restart chatshy`；日志：`journalctl -u chatshy -f`
- `.env` 由 Spring Boot 启动时自动加载，完整变量见 `.env.example`
- nginx 反代需保持 `proxy_buffering off`（SSE 流式），并转发真实 IP（`X-Forwarded-For` / `X-Real-IP`）

### 从旧 SQLite 迁移数据

停服务后执行一次性导入（目标库非空会拒绝，确认覆盖加 `--force`）：

```bash
cd /home/ChatShy/server-java
mvn -Psqlite-import exec:java \
  -Dexec.mainClass=io.github.cn3shy.chatshy.db.SqliteImporter \
  -Dexec.args="--sqlite ../data/chatshy.db --pg-url jdbc:postgresql://127.0.0.1:5432/chatshy --pg-user chatshy --pg-password 换成强密码"
```

导入会保留会话、消息、Job、设置（含管理员密码哈希 / 调用方 Key）与厂商配置。

## 备份

```bash
# 备份
pg_dump -U chatshy -h 127.0.0.1 -Fc chatshy > chatshy-$(date +%F).dump

# 恢复（会覆盖现有数据）
pg_restore -U chatshy -h 127.0.0.1 -d chatshy --clean --if-exists chatshy-2026-11-12.dump
```

旧 SQLite 文件建议在迁移完成后继续保留一段时间，作为回滚兜底。

## 隐私说明

所有调用方内容（含 system 提示词）都会**完整呈现给操作台管理员**——这正是本项目的设计。把它提供给别人使用时，请确保对方知晓这一点。

如果启用了 AI 厂商转发，这些内容还会被发送到你配置的第三方厂商；是否使用、发往哪家，由管理员决定。
