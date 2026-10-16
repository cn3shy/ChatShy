# ChatShy 实现计划

- 日期：2026-10-16
- 依据：[设计文档](../specs/2026-10-16-chatshy-design.md)（已评审通过）
- 执行方式：按任务顺序实现，每个任务完成后跑验证命令；每完成一个里程碑提交一次 git。

## 0. 技术约定（所有任务共同遵守）

**依赖（服务端，`package.json`）**
- 运行时：`fastify@^5`、`@fastify/static`、`@fastify/cookie`、`@fastify/cors`、`better-sqlite3`
- 开发：`typescript`、`tsx`、`vitest`、`@types/node`、`@types/better-sqlite3`、`openai`、`@anthropic-ai/sdk`（后两个仅集成测试用）
- 不引入：ORM、zod、dotenv、argon2。密码哈希用 Node 内置 `crypto.scrypt`；`.env` 用自写 15 行的加载器（`src/config.ts`），避免依赖 Node 版本旗标。

**依赖（前端 `web/`）**：`vue@^3`、`markdown-it`、`highlight.js`；开发：`vite`、`@vitejs/plugin-vue`。

**npm scripts（根目录）**

```json
{
  "dev": "tsx watch src/index.ts",
  "build": "tsc -p tsconfig.json",
  "start": "node dist/index.js",
  "test": "vitest run",
  "web:dev": "npm --prefix web run dev",
  "web:build": "npm --prefix web run build",
  "build:all": "npm run web:build && npm run build"
}
```

**tsconfig**：`module/moduleResolution: NodeNext`、`target: ES2022`、`strict: true`、`outDir: dist`、`rootDir: src`。

**关键实现约定**（对应设计 §4.5，容易踩坑，逐条落实）：
1. 启动时 `server.requestTimeout = 0`（禁用 Node 默认 300 秒请求超时），`trustProxy = true`。
2. 所有 SSE 响应头：`Content-Type: text/event-stream`、`Cache-Control: no-cache`、`Connection: keep-alive`、`X-Accel-Buffering: no`；用 `reply.hijack()` + `reply.raw` 直接写。
3. 非流式挂起：handler 内 `await` 一个 Promise，超时/回复后 `reply.send()`；不在等待期间写任何响应头。
4. 调用方断开检测：`request.raw.on('close')` 时若 Job 仍为 `pending`（且响应未发出）→ 标记 `cancelled`。
5. 管理端会话：内存 Map + `@fastify/cookie`（`chatshy_session`，HttpOnly、SameSite=Lax、HTTPS 时 Secure）。管理员密码 `scrypt` 哈希存 settings 表。
6. 时间统一用 Unix 毫秒整数；SQLite 开 WAL。
7. SSE 事件只带最小负载（`jobId/threadId/deadlineAt` 等），前端收到后重新拉取列表/详情——减少双端状态同步 bug。

---

## M0 脚手架与配置

### 任务 1：初始化服务端工程
- 文件：`package.json`、`tsconfig.json`、`src/index.ts`、`src/config.ts`、`.env.example`
- 步骤：
  1. `npm init -y` 后改写成 §0 的 scripts 与依赖，`npm i`。
  2. `src/config.ts`：加载 `.env`（`KEY=VALUE`、`#` 注释；process.env 优先）；导出 `{ port=8787, dbPath, adminPassword?, defaultTimeoutMs=180000, modelName='chatshy' }`。
  3. `src/index.ts`：建 Fastify 实例（`bodyLimit: 10MB`、`trustProxy`），注册 `GET /healthz`，监听 `config.port`；未设 `ADMIN_PASSWORD` 时生成随机密码并 `console.log` 打印一次（存内存供任务 4 初始化）。
- 验证：`npm run dev` 后 `curl http://localhost:8787/healthz` 返回 `{"ok":true}`。

### 任务 2：数据库与迁移
- 文件：`src/db/db.ts`、`src/db/migrate.ts`、`test/unit/migrate.test.ts`
- 步骤：按设计 §7 建五张表（`schema_version` 表 + 手写迁移数组；`jobs.status` 用 TEXT，值为 pending/replied/timeout/cancelled/superseded/dropped）；WAL；导出 `openDb(path)` 返回 better-sqlite3 实例。启动时执行迁移；并把遗留 `pending` 的 job 批量标记 `dropped`（重启语义，设计 §3.5）。
- 验证：测试——迁移可重复执行不报错；重启标记逻辑正确（插入假 pending 后重跑迁移，状态变 dropped）。

### 任务 3：KeyStore 与 Settings
- 文件：`src/core/keys.ts`、`src/core/settings.ts`、`test/unit/keys.test.ts`、`test/unit/settings.test.ts`
- 步骤：
  - keys：`generateKey(name, opts)` 返回 `{record, plaintext}`（`sk-chatshy-` + 32 字节随机，只存 SHA-256 与展示前缀）；`verifyKey(plaintext)` → key 记录或 null（存在+enabled）；`countTodayJobs(keyId)`（当日 0 点起）；增删改查。
  - settings：`getSetting/setSetting`，含默认值（`default_timeout_ms`, `model_name`）；`hashPassword/verifyPassword`（scrypt + 随机盐，`timingSafeEqual`）。
- 验证：单测覆盖生成/校验/禁用/哈希往返；`verifyKey` 对错误 key 恒定返回 null 不抛异常。

## M1 核心域

### 任务 4：ThreadManager（会话归并）
- 文件：`src/core/threads.ts`、`test/unit/threads.test.ts`
- 步骤：实现设计 §5.2 的归并顺序（前缀匹配 → fingerprint 兜底 → 新建）；**匹配只针对 `source='api'` 的历史消息**，避免控制台测试消息干扰；标题 = 第一条 user 消息前 50 字。暴露 `resolveThread(keyId, prompt) → {thread, appendedMessages}`，新消息入库。
- 验证：单测覆盖——连续追加历史归并到同一会话；客户端截断历史走 fingerprint；两个不同会话不误并；同一问候语不同会话指纹碰撞时的取舍（归并到最近活跃，仅影响展示）。

### 任务 5：JobQueue（请求生命周期）
- 文件：`src/core/jobs.ts`、`test/unit/jobs.test.ts`
- 步骤：`createJob({threadId, protocol, mode, requestJson, deadlineAt}) → PendingJob`；`PendingJob.wait(): Promise<JobOutcome>`；`replyJob(jobId, content)`、`ignoreJob(jobId)`、`cancelJob(jobId)`（断连）、超时 `setTimeout` 精确到 deadline；同 thread 新建 Job 时把更早的 `pending` 标记 `superseded` 并让其 `wait()` 以超时错误结束；`JobOutcome = {kind:'replied',content} | {kind:'timeout'|'cancelled'|'superseded'}`。状态变更同步写库。启动时内存为空（挂起不恢复）。
- 验证：单测用 vi.useFakeTimers 覆盖——正常回复、超时、忽略、断连、supersede（旧 Job 收到 outcome 且状态正确）、responder 只被调用一次。

### 任务 6：OperatorHub（操作台实时通道）
- 文件：`src/core/hub.ts`、`test/unit/hub.test.ts`
- 步骤：管理端 SSE 连接注册表（用注入的 `write` 回调抽象出可测试的 `addClient/removeClient`）；`broadcast({type, jobId, threadId, deadlineAt})`；每 20 秒 `ping`；提供 `onReply(cb)` / `onIgnore(cb)` 供 server 层接线。事件类型：`job.created / job.replied / job.timeout / job.superseded / job.cancelled / ping`。
- 验证：单测——广播到多个客户端、客户端移除后不再收到、ping 定时器用假时钟。

## M2 协议适配层

### 任务 7：OpenAI 适配（解析 + 渲染）
- 文件：`src/protocol/types.ts`、`src/protocol/openai.ts`、`test/unit/protocol-openai.test.ts`
- 步骤：
  - `types.ts`：`ChatMsg`、`Prompt`、`Reply`、`estimateTokens(text)`（CJK 1 字≈1 token，其余 4 字符≈1 token，向上取整）。
  - 解析：messages 的 content 支持字符串或部件数组（text 拼接，非 text → `[不支持的部件: <type>]`）；`stream`、`stream_options.include_usage`；错误体构造器（invalid_api_key / invalid_request_error / insufficient_quota / timeout / server_error）。
  - 渲染（非流式）：按设计 §4.2 的 JSON（`chatcmpl-` + randomBytes(12).hex、`model` 回显、usage 估算）。
  - 渲染（流式）：纯函数产出事件数组 `[roleDelta, contentDelta, finishChunk, (usageChunk), [DONE]]`，由 server 层负责心跳与写出；超时错误流式为 `data: {"error":...}`。
- 验证：golden 单测——解析各种输入形态；两种模式的完整输出快照与设计 §4.2 逐字段比对。

### 任务 8：Anthropic 适配（解析 + 渲染）
- 文件：`src/protocol/anthropic.ts`、`test/unit/protocol-anthropic.test.ts`
- 步骤：同任务 7，按设计 §4.3——`system` 字符串/数组、messages content 字符串/块数组（text 拼接 + `[不支持的块类型: <type>]` 占位）、错误体 `{"type":"error","error":{...}}`、非流式 message 结构、流式六段事件序列（`message_start` 骨架带 usage.input_tokens 估算）、超时走 `event: error`。
- 验证：同任务 7 的 golden 方式，逐事件比对设计 §4.3 序列。

## M3 HTTP 服务

### 任务 9：Fastify 组装 + 鉴权 + OpenAI 非流式全链路
- 文件：`src/server/app.ts`（改 `index.ts` 调用）、`src/server/auth.ts`、`src/server/routes/openai.ts`、`src/server/routes/health.ts`、`test/integration/openai-nonstream.test.ts`
- 步骤：装配 CORS（`*`，允许 Authorization/x-api-key/anthropic-version 头）；`auth.ts` 提取并校验 key（OpenAI：Bearer；Anthropic：x-api-key），失败按协议返回 401；`POST /v1/chat/completions` 非流式实现完整数据流（设计 §3.4 步骤 1-7 的非流式子集）；`GET /v1/models`、`/v1/models/:id`。
- 验证：集成测试——临时目录建库、`listen({port:0})`；用官方 `openai` SDK 发请求，测试内调 `replyJob` 后断言 SDK 收到的响应结构与内容；无效 key → 401。

### 任务 10：流式 SSE（双协议）
- 文件：`src/server/sse.ts`（心跳/写出/关闭工具）、`routes/openai.ts`、`routes/anthropic.ts`（本任务新增）、`test/integration/streaming.test.ts`
- 步骤：SSE 写出工具（hijack、头、15 秒 ping——OpenAI 注释行 / Anthropic `event: ping`、结束清理定时器）；两个协议的流式路径；断连检测（任务 5 的 cancel）。
- 验证：集成测试用原始 HTTP 读 SSE 文本——事件序列与设计逐条匹配、`[DONE]`/`message_stop` 结尾；`@anthropic-ai/sdk` 流式迭代器能完整消费。

### 任务 11：管理端 API + 登录
- 文件：`src/server/routes/admin.ts`、`src/server/session.ts`、`test/integration/admin.test.ts`
- 步骤：按设计 §6.2 全部端点——login/logout/me（scrypt 校验、连续失败 5 次锁 1 分钟、会话 7 天）、`GET /admin/api/events`（SSE，接线 OperatorHub）、threads 列表（排序规则按设计 §6.1，含 pendingJobId/deadlineAt/摘要）、threads/:id 详情（消息带 `delivered` 标志——由其关联 job 的最终状态推导：job 非 replied 即为"未送达"）、`POST jobs/:id/reply`、`jobs/:id/ignore`、新建测试会话与发消息、keys CRUD（生成时明文返回一次）、settings GET/PATCH（改密码需带 `current_password`）。
- 验证：集成测试——未登录全部 401；登录后走完整"API 请求挂起 → 管理端 reply → 客户端拿到回复"链路；ignore → 客户端收到超时错误；keys 生成后旧明文不可再取。

### 任务 12：限额、错误与边界
- 文件：`src/server/routes/*` 收尾、`test/integration/limits.test.ts`
- 步骤：启用 key 的日限额与 `max_pending`（429）；请求体不合法 → 400；`bodyLimit` 超限 → 413；CORS 预检通过；`server.requestTimeout=0` 下长挂起（把 key 超时设 5 秒，等 2 秒再回复，仍成功）。
- 验证：对应集成测试全覆盖；`npm test` 全绿。

## M4 操作台前端

### 任务 13：web 脚手架 + 静态托管
- 文件：`web/package.json`、`web/vite.config.ts`（dev proxy `/admin/api` → `http://localhost:8787`；`build.outDir: 'dist'`）、`web/index.html`、`web/src/main.ts`、`web/src/App.vue`、`src/server/app.ts`（注册 `@fastify/static` 指向 `web/dist` + SPA fallback）
- 步骤：Vue 3 + TS 起页面骨架；`npm run web:build` 后由服务端根路径 `/` 打开。
- 验证：起服务后浏览器访问 `/` 能看到 App；`/admin/api` 代理在 `web:dev` 下可用。

### 任务 14：登录 + API 客户端
- 文件：`web/src/api.ts`、`web/src/components/Login.vue`、`web/src/composables/useSse.ts`
- 步骤：fetch 封装（401 → 回登录页）；登录页；SSE 客户端（EventSource + 自动重连 + 事件分发回调）。
- 验证：错误密码提示、登录后进入主界面、杀掉服务端后 EventSource 自动重连。

### 任务 15：主界面（会话列表 + 对话视图）
- 文件：`web/src/components/ThreadList.vue`、`ChatView.vue`、`web/src/markdown.ts`（markdown-it + highlight.js）
- 步骤：列表按设计 §6.1 排序与状态标签、pending 倒计时（本地 1 秒 tick）；对话视图标记消息来源（`API·key名` / `控制台`）、挂起倒计时条、"未送达"标注；收到 SSE 事件后重新拉取。
- 验证：双开浏览器窗口，一边用 curl 发请求，另一边观察到列表置顶+倒计时；回复后两边状态同步。

### 任务 16：输入框、管理区、提醒
- 文件：`web/src/components/Composer.vue`、`KeyManager.vue`、`Settings.vue`、`web/src/composables/useNotify.ts`
- 步骤：Composer（Enter/Shift+Enter、Markdown）；忽略按钮；测试会话新建；key 管理（生成后明文只显示一次）；设置页；Notification + 提示音 + 标题角标 `(N)`，开关存 localStorage。
- 验证：全流程手测——发送回复可由 curl 侧收到；新请求触发桌面通知与角标。

## M5 部署与收尾

### 任务 17：Docker 与反向代理
- 文件：`Dockerfile`（多阶段：web build → server build → 运行镜像，基础镜像用 `node:22-slim` 以获得 better-sqlite3 的 glibc 预编译包）、`docker-compose.yml`（chatshy + caddy，`./data:/data`、`DB_PATH=/data/chatshy.db`）、`docker/Caddyfile`、`docker/nginx.conf.example`
- 验证：`docker compose up --build` 后 `curl /healthz`；浏览器走 HTTPS 访问操作台，桌面通知可授权（HTTPS 上下文）。

### 任务 18：README、备份与手测清单
- 文件：`README.md`、`.env.example`（补齐注释）
- 内容：部署步骤、环境变量表、备份命令（`sqlite3 .backup` 与拷贝 data 卷 + 定时任务示例）、手测清单（Cherry Studio / ChatBox / Claude Code 设 `ANTHROPIC_BASE_URL`）、隐私说明（所有调用内容会呈现给操作员——设计 §11）。
- 验证：照 README 从零在干净目录跑一遍部署流程。

### 任务 19：最终验收
- 跑 `npm test`、`npm run build:all` 全绿；
- 对照设计 §1 成功标准逐条过：SDK 接入（openai / anthropic 各一）、操作台 ≤1s 出现请求、超时错误体、手测清单三个客户端。
- 汇总偏差（如有）更新设计文档并提交。

---

## 依赖关系与并行度

- 任务 1→2→3 串行（地基）；任务 4、5、6 依赖 2/3，可两两并行；任务 7、8 仅依赖 1，随时可做；任务 9 依赖 4/5/7；任务 10 依赖 9/7/8；任务 11 依赖 6；任务 12 依赖 9-11；前端 13-16 依赖 11 的 API 冻结；17-19 收尾。
- 每个任务提交一次 git，消息格式：`feat: <任务名>` / `test: ...` / `chore: ...`（附 Co-Authored-By 行）。
