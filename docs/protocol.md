# ChatShy 协议参考

对外暴露的 HTTP 接口全集。除非另有说明，请求与响应体均为 JSON，字符集 UTF-8。

## 端口与站点

| 端口（默认） | 站点 | 内容 |
| --- | --- | --- |
| `8088` | `ai.3shy.cn` | 网关 + 操作台页面（`/` 与 `/admin` 同一页面） |
| `8099` | `dounai.3shy.cn` / `dounai.chat` | 豆奶聊天页；`/admin` 也能打开操作台 |

两个端口由同一个 Spring Boot 进程监听，路径路由完全一致，区别只在 `/` 返回哪个前端页面。

## 鉴权

网关接口（`/v1/**`）使用一个全局调用方 Key（操作台设置页可查看/重新生成），两种请求头任选其一：

```
Authorization: Bearer sk-chatshy-...
x-api-key: sk-chatshy-...
```

Key 缺失或错误返回 `401`，响应体为对应协议的标准错误格式。Key 未初始化时拒绝一切请求。

管理端接口（`/admin/api/**`，`/admin/api/login` 除外）使用 Cookie 会话：登录成功后服务端下发 `chatshy_session`（HttpOnly，7 天），未登录返回 `401 {"error":"unauthorized"}`。密码连续输错会按 IP 限流（`429`）。

豆奶的 `/api/**` 只允许同源调用（无 CORS 头），上游 Key 由服务端注入，浏览器不可见。

## OpenAI 协议（`/v1`）

### `POST /v1/chat/completions`

请求体标准 OpenAI Chat Completion 格式：

```json
{
  "model": "chatshy",
  "messages": [
    {"role": "system", "content": "…"},
    {"role": "user", "content": "你好"}
  ],
  "stream": false
}
```

行为要点：

- `model` 不做校验，原样回显；空值时回显全局模型名
- `system` / `developer` 角色合并为一条系统提示；`tool` / `function` 消息按 user 处理，`tool_calls` 内容以 `[不支持的部件: …]` 占位（本项目不支持工具调用）
- `content` 数组形态仅取 `type: "text"` 部分，其余部件同样占位
- 其余角色 → `400`；请求体非法 → `400`
- 非流式：真人回复后返回标准 `chat.completion` 对象（含 `usage`）；等待超时返回 `504` + `timeout` 错误码
- 流式（`"stream": true`）：SSE，事件序列为 role 空帧 → 若干 `delta.content` 帧 → `finish_reason: "stop"` 帧 → `data: [DONE]`；挂起期间每 15 秒发一行 `: ping` 注释保活
- `stream_options.include_usage` 为真时，stop 帧后追加一帧带 `usage` 的空 choices 块

### `GET /v1/models`、`GET /v1/models/{id}`

返回全局模型名的标准模型对象（`owned_by: "chatshy"`）。`{id}` 不校验，永远返回全局模型名。

## Anthropic 协议（`/v1`）

### `POST /v1/messages`

请求体标准 Anthropic Messages 格式：

```json
{
  "model": "claude-sonnet-5-5",
  "max_tokens": 4096,
  "messages": [{"role": "user", "content": "你好"}],
  "stream": false
}
```

行为要点：

- 顶层 `system`（字符串或 content block 数组）与消息数组里的 `system` 角色消息合并为系统提示
- `content` 数组形态仅取 `type: "text"` 块，其余块以 `[不支持的块类型: …]` 占位
- `max_tokens` / `temperature` 等参数被接受但不影响行为
- 非流式：真人回复后返回标准 `message` 对象（`stop_reason: "end_turn"`，含 `usage`）；等待超时返回 `529` + `overloaded_error`（Anthropic 官方 SDK 对 529 有标准重试语义）
- 流式：SSE，事件序列为 `message_start` → `content_block_start` → 若干 `content_block_delta`（`text_delta`）→ `content_block_stop` → `message_delta` → `message_stop`；挂起期间周期性发 `event: ping`
- 流中失败发 `event: error` 帧（超时 `overloaded_error`，上游转发失败 `api_error`）

### `POST /v1/messages/count_tokens`

与 `/v1/messages` 同构的请求体，返回 `{"input_tokens": N}`。token 数为粗估：CJK 约 1 字 1 token，其余 4 字符 1 token。

## 兼容层

| 路径 | 行为 |
| --- | --- |
| `GET /healthz`、`GET /api/hello`、`GET /v1/api/hello` | `{"ok": true}`，供客户端探测连通性 |
| `POST/PUT /api/event_logging/**` | 永远 `200`，吞掉 Claude Code 遥测，避免其重试风暴 |

误写成 `ANTHROPIC_BASE_URL=…/v1` 导致的 `/v1/v1/messages`、`/v1/v1/chat/completions` 路径也已兼容。

## 来源标记（可选）

所有网关请求可带身份头区分调用方：

```
X-ChatShy-Caller: alice
```

- 带了它：来源只按名字算（最长 40 字符），与 IP 无关，换网络不切会话
- 没带：按 `IP + 客户端名` 识别（客户端名由 User-Agent 识别：Claude Code、Cherry Studio、ChatBox、LobeChat、curl 等）
- 中文名需百分号编码（HTTP 头是 latin-1 字节）：`X-ChatShy-Caller: %E5%BC%A0%E4%B8%89`；直连发 UTF-8 字节的客户端也认

会话归并规则：同一来源的多轮请求按「消息序列前缀匹配 + 内容指纹」归并到同一条会话线；不同来源绝不串线。

## 管理端 API（`/admin/api`）

Cookie 会话鉴权。事件之外的全部接口返回 JSON。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /admin/api/login` | `{password}` → 下发会话 Cookie；限流时 `429` |
| `POST /admin/api/logout` | 销毁会话 |
| `GET /admin/api/me` | 会话有效性探测 → `{ok: true}` |
| `GET /admin/api/events` | SSE 实时事件流（见下） |
| `GET /admin/api/threads` | 会话列表（含来源、最后消息、挂起数与截止时间） |
| `GET /admin/api/threads/{id}` | 会话详情：`{thread, messages, pendingJobs}` |
| `DELETE /admin/api/threads/{id}` | 删除会话；挂起请求按超时收尾 → `{ok, failedJobs}` |
| `POST /admin/api/jobs/{id}/reply` | `{content}` 人工回复挂起请求 → `{ok, delivered}`；请求已超时则只存档，`delivered: false`；AI 正在流式回复时 `409` |
| `POST /admin/api/jobs/{id}/ignore` | 忽略挂起请求 |
| `POST /admin/api/jobs/{id}/forward` | `{providerId}` 用指定 AI 厂商直接回复 → `{ok, delivered, content, providerName}`；上游失败 `502`，请求留在待回复列表 |
| `GET /admin/api/providers` | 厂商列表（Key 只回显末四位） |
| `POST /admin/api/providers` | 新建厂商 `{name, baseUrl, apiKey, model}` |
| `PATCH /admin/api/providers/{id}` | 修改厂商；`apiKey` 留空表示沿用旧 Key |
| `DELETE /admin/api/providers/{id}` | 删除厂商（被自动转发引用时拒绝） |
| `POST /admin/api/providers/{id}/test` | 连通性测试 → `{ok, latencyMs, preview}` |
| `GET /admin/api/settings` | `{defaultTimeoutMs, modelName, callerKey, autoForwardEnabled, autoForwardProviderId}` |
| `PATCH /admin/api/settings` | 改默认超时、模型名、管理员密码（需 `currentPassword`）、自动转发开关/厂商 |
| `POST /admin/api/caller-key` | 重新生成全局调用方 Key（旧 Key 立即失效）→ `{callerKey}` |

### SSE 事件（`GET /admin/api/events`）

连接建立即推 `data: {"type":"hello"}`，之后每 20 秒一帧 `{"type":"ping"}`。业务事件：

| type | 附加字段 | 含义 |
| --- | --- | --- |
| `job.created` | `jobId`, `threadId`, `deadlineAt` | 新的待回复请求 |
| `job.replied` / `job.timeout` / `job.cancelled` / `job.superseded` | `jobId`, `threadId` | 挂起请求结束 |
| `job.forward_failed` | `jobId`, `threadId`, `message` | 自动/手动转发失败，请求留在待回复列表 |
| `thread.deleted` | `threadId` | 会话被删除 |
| `settings.updated` | — | 设置变更（含自动转发开关） |
| `providers.updated` | — | 厂商配置变更 |

## 豆奶代理（`/api`，8099 站点）

| 路径 | 说明 |
| --- | --- |
| `GET /api/config` | `{model: 全局模型名}` |
| `POST /api/chat` | `{messages: [{role, content}]}`；服务端注入 Key 与身份头后转发到网关，把上游 SSE 原样透传给浏览器；上游出错时透传状态码与错误消息 |

`DOUNAI_SYSTEM_PROMPT` 有值时，每次请求自动在消息前插入一条 system。豆奶站点响应带严格 CSP（`default-src 'self'` 等）与 `X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`。

## 页面路由

| 路径 | 端口 | 内容 |
| --- | --- | --- |
| `/` | 8088 | 操作台 |
| `/admin`、`/admin/` | 8088 与 8099 | 操作台 |
| `/` | 8099 | 豆奶聊天页 |

## 错误体约定

- `/v1/**`：OpenAI 错误为 `{"error": {message, type, param, code}}`，Anthropic 错误为 `{"type": "error", "error": {type, message}}`
- `/admin/api/**`：`{"error": "<code>"}` 或 `{"error": "<code>", "message": "人类可读说明"}`
- `/api/**`（豆奶代理）：`{"error": {"message": "…"}}`
