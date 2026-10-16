# ChatShy 设计文档 — 人肉大模型网关

- 日期：2026-10-16
- 状态：已评审通过（brainstorming 阶段）
- 项目目录：`D:\Dev\self\ChatShy`

## 1. 概述

ChatShy 是一个"人肉大模型"网关：对外提供 OpenAI 与 Anthropic 兼容的 HTTP API，调用方把它当作一个大模型服务使用；实际上每个请求会被实时推送到操作员的网页操作台，由操作员（人类）撰写回复，回复再按协议要求的格式（含 SSE 流式事件序列）返回给调用方。操作台页面本身也是一个轻量聊天窗口，操作员无需经由 API 即可收发消息。

**目标场景**：部署在云服务器（VPS）上，以 Docker 运行；使用者为操作员本人及其熟人/小团队，调用方凭管理员签发的 API key 接入。

**非目标**：这不是一个转发到真实大模型的通用网关；v1 不实现工具调用、多模态、embeddings 等（见 §10）。

### 成功标准

1. 官方 OpenAI / Anthropic SDK 及常见客户端（Cherry Studio、ChatBox、Claude Code 等）可直接接入，流式与非流式均正常。
2. 调用方发出请求 → 操作台 ≤1 秒内出现该请求。
3. 操作员在超时前回复 → 调用方收到符合协议规范的完整响应。
4. 无人回复 → 在配置的超时后返回各协议的标准错误体，客户端能正常展示错误而不崩溃。

## 2. 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 协议 | OpenAI（`/v1/chat/completions`）与 Anthropic（`/v1/messages`）双兼容 |
| 部署 | 云服务器 Docker + Caddy（HTTPS）；熟人/小团队，key 签发制 |
| 等待策略 | 请求挂起等待回复；可配置超时（全局默认 180 秒，key 级可覆盖） |
| 技术栈 | Node.js 22 + TypeScript；Fastify；better-sqlite3；Vue 3 + Vite |
| 架构 | 单体一体式：一个进程 = 对外 API + 操作台页面 + 管理，SSE 实时推送 |

## 3. 整体架构

### 3.1 模块划分

| 模块 | 职责 | 边界 |
|---|---|---|
| `server/` | HTTP 层：路由、鉴权（API key / 管理会话）、静态资源托管、SSE 连接管理 | 只做 HTTP 语义；不解析协议消息结构 |
| `protocol/` | 协议适配层：OpenAI / Anthropic 请求解析为内部统一模型；把回复渲染为 JSON 响应或 SSE 事件序列 | 纯函数为主，无状态、无 IO；两种协议的格式差异全部收敛于此 |
| `core/` | 核心域：JobQueue（请求生命周期）、ThreadManager（会话归并）、KeyStore（key 管理）、OperatorHub（操作台实时通道）、Settings | 只见内部统一模型，完全不感知外部协议 |
| `db/` | SQLite 持久化：schema、迁移、访问封装 | 供 core/server 调用的数据访问函数 |
| `web/` | 操作台前端（Vue 3 + Vite），构建产物由 Fastify 托管 | 通过 `/admin/api/*` 与服务端通信，不直接访问数据库 |

### 3.2 目录结构

```
ChatShy/
├── package.json            # 服务端工程（npm run dev/build/start/test）
├── tsconfig.json
├── src/
│   ├── index.ts            # 启动入口
│   ├── server/
│   │   ├── app.ts          # Fastify 组装
│   │   ├── auth.ts         # API key / 管理 session 校验
│   │   └── routes/         # openai.ts / anthropic.ts / admin.ts / health.ts
│   ├── protocol/
│   │   ├── types.ts        # 内部统一模型
│   │   ├── openai.ts       # 解析 + 渲染（JSON / SSE）
│   │   └── anthropic.ts
│   ├── core/
│   │   ├── jobs.ts         # JobQueue 状态机
│   │   ├── threads.ts      # 会话归并
│   │   ├── keys.ts
│   │   ├── settings.ts
│   │   └── hub.ts          # OperatorHub
│   └── db/
│       ├── db.ts
│       └── migrate.ts
├── web/                    # Vue 3 + Vite 操作台前端（独立 package.json）
├── test/                   # 单元 + 集成测试
├── docker/                 # Dockerfile / docker-compose.yml / Caddyfile
└── docs/superpowers/specs/ # 本设计文档
```

### 3.3 内部统一模型

```ts
interface ChatMsg { role: 'user' | 'assistant'; content: string }
interface Prompt  { model: string; system?: string; messages: ChatMsg[] }
type Reply = { content: string }
```

协议适配层负责"协议特有形状 ↔ 该模型"的全部翻译；核心域只处理 `Prompt` / `Reply`。

### 3.4 一次调用的完整数据流

1. 调用方携带 API key 发起请求（`stream` 为 true 或 false）。
2. `server/` 鉴权（key 存在、启用、日限额、挂起并发上限）；失败按协议返回标准错误。`protocol/` 解析请求为 `Prompt`；不合法返回协议标准 400。
3. `ThreadManager` 归并会话（见 §5.2），新消息写入 `messages`；创建 Job（`jobs` 表落盘 + 内存挂起对象），期限 `deadline = now + 有效超时`（key 覆盖值或全局默认）。
4. 挂起开始：
   - 非流式：不写响应头，HTTP 响应保持挂起；
   - 流式：立即发送"连接已建立"事件（OpenAI：首个 role delta；Anthropic：`message_start`），此后每 15 秒发一次心跳（OpenAI：`: ping` 注释行；Anthropic：官方 `ping` 事件）；
   - 同时监听调用方断开（连接 close → Job 标记 `cancelled`）。
5. `OperatorHub` 向所有在线操作台广播 `job.created` → 页面置顶该会话、红点、倒计时、通知/声音。
6. 操作员输入回复 → `POST /admin/api/jobs/:id/reply` → 内容入库（`source=console`）→ Job 完成 → 适配层渲染：非流式整包 JSON；流式发完整事件序列后关闭连接。
7. 到 `deadline` 无人回复 → Job 标记 `timeout` → 返回协议标准错误；页面标记"已超时"。操作员此后补的回复只入库并在页面标注"未送达"（客户端早已断开）。

### 3.5 生命周期与边界情况

- **同一会话存在多个挂起 Job**（客户端超时重试造成）：回复发给最新一个；更早的标记 `superseded`，按各自协议的超时错误响应终止其连接（对调用方而言等价于那次请求失败）。
- **调用方中途断开**：Job 标记 `cancelled`，操作台实时移除挂起状态但保留记录，之后对该 Job 的回复不再尝试发送。
- **服务重启**：内存挂起丢失，调用方收到连接错误后自行重试；启动时把所有 `pending` Job 批量标记为 `dropped`，历史记录完整保留。
- **超时后操作员补回复**：只入库，不发送。

## 4. 对外 API 规格

### 4.1 鉴权

- OpenAI 协议：`Authorization: Bearer sk-chatshy-...`
- Anthropic 协议：`x-api-key: sk-chatshy-...`
- key 无效或已禁用：HTTP 401，返回各自协议的 error 体（见 §4.4）。`anthropic-version` 请求头忽略，不做强制校验。

### 4.2 OpenAI 兼容端点

**`POST /v1/chat/completions`**

- 请求字段：
  - `model`：任意值，响应中一律回显请求值（兼容写死模型名的客户端）；
  - `messages`：`system` / `user` / `assistant`；`content` 为字符串或 text 部件数组（取 text 拼接，非 text 部件以 `[不支持的部件: <type>]` 占位）；
  - `stream`：默认 false；
  - `stream_options.include_usage: true`：支持，在结尾以 `choices: []` 的空块附带 usage；
  - 其余字段（temperature、top_p、n、stop、tools 等）一律忽略；带 `tools` 的请求也正常以纯文本回复。
- 非流式响应（HTTP 200）：

```json
{
  "id": "chatcmpl-<随机>",
  "object": "chat.completion",
  "created": 1730000000,
  "model": "<回显请求值>",
  "choices": [{
    "index": 0,
    "message": { "role": "assistant", "content": "<操作员回复>" },
    "finish_reason": "stop"
  }],
  "usage": { "prompt_tokens": 12, "completion_tokens": 34, "total_tokens": 46 }
}
```

- 流式 SSE 事件顺序（`Content-Type: text/event-stream`）：

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
data: {...,"choices":[{"index":0,"delta":{"content":"<回复全文，整段一次>"},"finish_reason":null}]}
data: {...,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
（若 include_usage：data: {...,"choices":[],"usage":{...}}）
data: [DONE]
```

  内容整段一次发送，不模拟逐字效果——避免 Markdown 代码块在客户端分块渲染时闪烁。

**`GET /v1/models`**：返回单个模型对象列表（id = 配置的模型名，默认 `chatshy`，`owned_by: "chatshy"`）。**`GET /v1/models/:id`**：返回该模型对象。两者供客户端启动探测使用。

- `usage` 估算：中文按 1 字 ≈ 1 token，其余按 4 字符 ≈ 1 token，向上取整；仅用于客户端展示。

### 4.3 Anthropic 兼容端点

**`POST /v1/messages`**

- 请求字段：
  - `model`：任意值，响应回显；
  - `max_tokens`：忽略；
  - `system`：顶层字段，字符串或 block 数组皆可；
  - `messages`：`user` / `assistant`，`content` 为字符串或 block 数组（取 text 块拼接，非 text 块以 `[不支持的块类型: <type>]` 占位）；
  - `stream`、其余字段同 OpenAI 策略：忽略不影响的字段。
- 非流式响应（HTTP 200）：

```json
{
  "id": "msg_<随机>",
  "type": "message",
  "role": "assistant",
  "model": "<回显请求值>",
  "content": [{ "type": "text", "text": "<操作员回复>" }],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": { "input_tokens": 12, "output_tokens": 34 }
}
```

- 流式 SSE 事件顺序（每事件 `event: <name>` + `data: <json>` 两行）：

```
event: message_start
data: {"type":"message_start","message":{...message 骨架，content 为空数组...}}
event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"<回复全文>"}}
event: content_block_stop
data: {"type":"content_block_stop","index":0}
event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":...}}
event: message_stop
data: {"type":"message_stop"}
```

### 4.4 错误与超时

| 情形 | HTTP | 响应体 |
|---|---|---|
| key 无效/禁用 | 401 | OpenAI：`{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}`；Anthropic：`{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}` |
| 请求体不合法 | 400 | 各协议 `invalid_request_error` |
| 超过 key 日限额 | 429 | OpenAI：`insufficient_quota`；Anthropic：`rate_limit_error` |
| 超过 key 挂起并发上限 | 429 | 同上 |
| 挂起超时 | 504 | OpenAI：`{"error":{"message":"The model timed out while generating a response.","type":"server_error","code":"timeout"}}`；Anthropic：HTTP 529 + `{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}` |
| 流式已建立连接后超时 | 200（已发头） | OpenAI：`data: {"error":{...}}` 后关闭；Anthropic：`event: error` 事件后关闭 |

Anthropic 所有错误体统一为 `{"type":"error","error":{"type":"<类型>","message":"<文本>"}}` 形状。操作台上的"忽略"动作等价于立即超时。

### 4.5 兼容性细节

- 全部 SSE 响应带：`Content-Type: text/event-stream`、`Cache-Control: no-cache`、`Connection: keep-alive`、`X-Accel-Buffering: no`（防 Nginx 缓冲卡死流式）。
- CORS 全开放（`Access-Control-Allow-Origin: *`，允许 `Authorization` / `x-api-key` 头），支持 `OPTIONS` 预检——服务浏览器端网页客户端。
- **必须禁用 Node 的 `server.requestTimeout`（默认 300 秒）**，否则长挂起请求会被运行时掐断；`headersTimeout` 保持默认。
- 请求体上限 `bodyLimit` 设为 10 MB。

## 5. 核心域行为

### 5.1 JobQueue（请求生命周期）

- Job 状态机：`pending → replied | timeout | cancelled | superseded | dropped`。
- 每个 Job 对应一个"回复承诺"（responder）：操作员回复或超时触发时，responder 被调用一次且仅一次（已完成 Job 的后续操作全部忽略）。
- 超时用 `setTimeout` 实现（deadline 精确到毫秒），回复或取消时清除定时器。
- 内存中是挂起 Job 的唯一真相；`jobs` 表用于审计与重启后标记 `dropped`。

### 5.2 ThreadManager（会话归并启发式）

两种协议都是无状态传输（每次请求携带全量历史），操作台需要把连续请求归并成一条会话线。归并顺序：

1. **前缀匹配**（首选）：同 key 下，某已有会话的消息序列是本次请求消息序列的前缀 → 归并到该会话，追加新消息；多个命中取最长前缀。
2. **指纹兜底**：同 key 下，`fingerprint = sha256(system + 第一条 user 消息)` 相同 → 归并到最近活跃的那个会话。
3. 否则新建会话。会话标题取第一条 user 消息前 50 字。

归并是**展示分组用的启发式**，出错只影响操作台列表的归组，不影响协议正确性——回复永远发给当前请求的 Job。不做手动合并/拆分会话（YAGNI）。

### 5.3 KeyStore

- 生成：`sk-chatshy-` + 32 字节随机；数据库只存 SHA-256 哈希与展示前缀（如 `sk-chatshy-ab12…`）；明文仅在创建时返回一次。
- 校验顺序：存在 → 启用 → 日限额（按 `jobs` 表当日计数，`daily_limit` 为空则不限）→ 挂起并发上限（`max_pending`，默认 5）。
- 每次成功鉴权更新 `last_used_at`。
- 管理操作（生成/吊销/改配置）仅经管理端 API。

### 5.4 OperatorHub（操作台实时通道）

- 管理端 SSE 长连接 `/admin/api/events`（需登录会话）；广播事件：
  - `job.created`（含会话、消息、协议、模型、deadline）
  - `job.replied` / `job.timeout` / `job.superseded` / `job.cancelled`
  - `ping`（每 20 秒）
- 回复派发：管理端 `POST /admin/api/jobs/:id/reply` 是唯一"完成 Job"的入口（"忽略"走 `/ignore`，等价超时）。
- 多操作台（多标签页/多设备）同时在线：都收到事件；回复以先到为准，其余端收到 `job.replied` 后自动同步。

## 6. 操作台（web/）

### 6.1 页面结构

响应式布局（桌面与手机浏览器均可用，不做 PWA）：

- **登录页**：管理员密码登录。
- **左列 · 会话列表**：每条显示 key 名、模型、协议、最后消息摘要、时间；`pending` 会话置顶（按 deadline 升序）并显示红点与剩余秒数倒计时；其余按最近更新降序。状态标签：等待中 / 已回复 / 已超时 / 已断开 / 已被取代。
- **右列 · 对话视图**：完整历史（含调用方发来的 system/user 消息，标注来源 `API·<key名>` 或 `控制台`）；Markdown 渲染 + 代码高亮；挂起时顶部显示"正在等待你的回复…"倒计时条；同会话有多个挂起时显示"有 N 个重复请求"。
- **底部 · 输入框**：多行，Enter 发送、Shift+Enter 换行，支持 Markdown；发送即回复当前 Job。
- **管理区**：key 管理（生成——明文仅显示一次、吊销、改超时覆盖/日限额/挂起上限/名称）、全局设置（默认超时、模型名、改管理员密码）。
- **测试会话**：可新建会话并直接在其中以 `user` 或 `assistant` 身份发消息，等同于不经过 API 的本地测试窗口。

### 6.2 管理端 API

| 端点 | 说明 |
|---|---|
| `POST /admin/api/login` / `logout`、`GET /admin/api/me` | 会话登录 |
| `GET /admin/api/events` | SSE 事件流 |
| `GET /admin/api/threads`、`GET /admin/api/threads/:id` | 会话列表 / 完整历史 |
| `POST /admin/api/jobs/:id/reply`、`POST /admin/api/jobs/:id/ignore` | 回复 / 忽略 |
| `POST /admin/api/threads`、`POST /admin/api/threads/:id/messages` | 新建测试会话 / 以指定角色发消息 |
| `GET/POST /admin/api/keys`、`PATCH/DELETE /admin/api/keys/:id` | key 管理 |
| `GET/PATCH /admin/api/settings` | 全局设置 |

### 6.3 提醒

- 新请求触发：浏览器桌面通知（Notification API，需 HTTPS 上下文）+ 提示音 + 页面标题角标 `(N)`；开关存 localStorage。
- 页面关闭/手机浏览器后台时通知能力有限，属已知限制（文档说明）。

### 6.4 登录与会话

- 管理员密码以 argon2（或 bcrypt）哈希存 settings 表；初始值来自环境变量，未设置则首次启动生成随机密码并打印到服务日志。
- 登录成功签发 HttpOnly + SameSite=Lax cookie 会话（内存存储、有效期 7 天、重启需重新登录）；连续登录失败 5 次锁定 1 分钟。

## 7. 存储（SQLite）

better-sqlite3，WAL 模式；启动时按 `schema_version` 做简单手写迁移。

| 表 | 字段 |
|---|---|
| `keys` | id (uuid PK)、name、key_hash (UNIQUE)、key_prefix、enabled、timeout_ms (NULL=全局)、daily_limit (NULL=不限)、max_pending (默认 5)、created_at、last_used_at |
| `threads` | id、key_id(FK)、title、protocol、model、fingerprint、created_at、updated_at |
| `messages` | id、thread_id(FK)、role(user/assistant/system)、content、source(api/console)、job_id (NULL)、created_at |
| `jobs` | id、thread_id(FK)、protocol、mode(stream/non_stream)、status、request_json（原始请求，审计）、reply_content (NULL)、deadline_at、created_at、replied_at (NULL) |
| `settings` | key (PK)、value（admin_password_hash、default_timeout_ms、model_name） |

索引：`keys(key_hash)`、`threads(key_id)`、`messages(thread_id)`、`jobs(thread_id)`、`jobs(status)`。

## 8. 部署与配置

### 8.1 Docker

- 多阶段 Dockerfile：前端构建（node:22-alpine，`web/` 出 `dist/`）→ 服务端构建（`tsc`）→ 运行镜像（node:22-alpine，含 better-sqlite3 预编译原生模块）。
- `docker-compose.yml`：单个 `chatshy` 服务 + 数据卷挂载 `/data`（存 SQLite 文件）；`restart: unless-stopped`。
- 健康检查：`GET /healthz` 返回 `{"ok":true}`。

### 8.2 反向代理

- 首选 Caddy：自动 HTTPS，SSE 无需特殊配置，示例 Caddyfile 放 `docker/`。
- Nginx 备选：需 `proxy_buffering off`（响应头 `X-Accel-Buffering: no` 已兜底）；示例一并提供。

### 8.3 环境变量（仅初始引导；运行时项改存 settings 表）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 8787 | 监听端口 |
| `DB_PATH` | `./data/chatshy.db` | SQLite 路径 |
| `ADMIN_PASSWORD` | 随机生成并打印 | 管理员初始密码 |
| `DEFAULT_TIMEOUT_MS` | 180000 | 全局默认挂起超时 |
| `MODEL_NAME` | `chatshy` | `/v1/models` 展示的模型名 |

### 8.4 备份

SQLite 单文件，文档给出 `sqlite3 .backup` 与直接拷贝 data 卷两种方式及定时任务示例。

## 9. 测试策略

- **单元测试**（vitest）：
  - `protocol/` 适配层：请求解析（含 content 数组、system 数组、占位符）+ 响应与 SSE 事件序列正确性，两种协议各一套 golden 断言；
  - `ThreadManager` 前缀匹配/指纹兜底的各边界；
  - JobQueue 超时与状态机（假时钟）。
- **集成测试**：拉起真实进程，用官方 SDK（`openai`、`@anthropic-ai/sdk`）打通全链路，测试脚本扮演操作员回复。覆盖：非流式、流式、超时、调用方断连、同会话重复挂起、无效 key、日限额。
- **手测清单**：Cherry Studio / ChatBox 真实接入；Claude Code 设 `ANTHROPIC_BASE_URL` 验证 Anthropic 流式。

## 10. v1 范围之外（YAGNI）

工具调用（tools / tool_use）、多模态、embeddings、多操作员协作与转交、对话全文搜索、主题皮肤、短信/邮件通知、流式逐字打字机效果、手动合并/拆分会话。

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| 调用方客户端自身超时短于操作员回复耗时 → 回复白费 | 默认超时 3 分钟且可调；操作台显式倒计时便于取舍；文档说明流式客户端等待体验最好 |
| 所有调用内容会完整呈现给人工操作员 | 设计使然；供他人使用时由部署者自行确保符合其数据预期（文档写明） |
| 服务重启丢挂起请求 | 调用方收到连接错误后重试；`pending` 批量标记 `dropped`，页面留痕 |
| Nginx 缓冲杀 SSE | `X-Accel-Buffering: no` + 文档优先给 Caddy 配置 |
| 归并启发式误判 | 仅影响操作台展示分组；回复与协议正确性不受影响（§5.2） |
