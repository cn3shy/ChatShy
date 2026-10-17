# ChatShy · 人肉大模型网关

对外是一个 **OpenAI / Anthropic 双协议兼容** 的大模型 API；实际上每个请求都会实时推送到你的网页操作台，由你（人类）撰写回复，再按协议格式（含流式 SSE）原样返回给调用方。操作台本身也是个聊天窗口，不经过 API 就能直接收发消息。

- 设计文档：`docs/superpowers/specs/2026-10-16-chatshy-design.md`
- 实现计划：`docs/superpowers/plans/2026-10-16-chatshy-implementation.md`

## 功能

- **双协议兼容**：`POST /v1/chat/completions`（OpenAI）与 `POST /v1/messages`（Anthropic），流式/非流式均可；Cherry Studio、ChatBox、LobeChat、官方 SDK、Claude Code 等直接接入
- **操作台**：SSE 实时推送新请求（≤1 秒）、桌面通知 + 提示音、倒计时、重复请求提示、Markdown 输入与代码高亮渲染、手机浏览器可用
- **唯一全局 Key + 来源标记**：所有调用方共用一个 Key（设置页可查看/重新生成），用**请求来源**区分"谁在说话"，操作台在每条会话上标注来源。来源优先取调用方自报的身份头 `X-ChatShy-Caller`（见下），没带则回落到 IP + User-Agent 识别出的客户端名
- **删除会话**：操作台上悬停任意会话即可删除，消息与请求记录一并清除
- **流式打字机**：流式模式下操作员的人工回复按字素切片、逐帧下发，观感与真实大模型一致。**总时长封顶约 9 秒**——操作员其实是一次写完整段的，分片只为观感，不该让调用方干等；超长回复自动加大每帧粒度。AI 转发的回复不走打字机，直接实时透传上游增量
- **请求挂起 + 可配置超时**：默认 180 秒（全局设置项）；超时返回各协议标准错误体
- **第三方 AI 转发**：可配置多个 OpenAI 兼容厂商（DeepSeek、通义千问、GLM、任意自定义端点）。操作台对每个待回复请求可手动选择厂商「AI 回复」并直接回给调用方；侧边栏的「AI 自动转发」开关开启后，服务端会自动回复所有新请求——**操作台关掉/人不在线也继续生效**。流式调用方会收到上游的**实时增量**（首字延迟与直连厂商一致），不再等整段生成完再打字机补发。自动失败时请求留在待回复列表并标注原因
- **会话归并**：无状态协议的多轮请求自动归并成一条会话线，**按来源隔离**（不同调用方绝不会串线）；超时后补的回复只存档并标注"未送达"
- **单进程 + SQLite**：一个 Docker 镜像部署，数据一个文件备份

## 本地开发

```bash
npm install          # 服务端依赖
npm --prefix web install   # 前端依赖（首次）
cp .env.example .env # 可选：设置 ADMIN_PASSWORD；不设则首次启动打印随机密码
npm run dev          # 服务端 + 操作台 → http://localhost:8088
```

前端热更新开发：终端 1 `npm run dev`，终端 2 `npm run web:dev` → http://localhost:5173（已代理管理端接口）。

```bash
npm run build:all    # 前端 + 服务端生产构建
npm start            # 运行生产产物
npm test             # 单元 + 集成测试（98 个）
```

## 使用

### 操作台

打开 `http://localhost:8088`（或你的域名），用管理员密码登录：

- 左侧：会话列表。**未回复的置顶、红点、剩余秒数倒计时**；每条显示请求来源（名字/客户端 + IP）、最后消息，悬停行首出现 ✕ 可删除
- 右侧：对话视图 + 底部输入框（Enter 发送，Shift+Enter 换行，支持 Markdown）
- ⚙ 设置页：查看/复制/重新生成**全局调用方 Key**、**一键复制接入配置**（Base URL + Key + 模型名三行，直接发给调用方）、管理 **AI 厂商**、改默认超时、模型名、管理员密码、通知开关
- 侧边栏：「AI 自动转发」开关 + 厂商下拉（见下）
- 待回复条：选择厂商后点「AI 回复」，由该厂商直接回答调用方；AI 生成的回复在消息上标注 `AI · 厂商名`

> 删除会话时，若那条会话还有挂起中的请求，调用方会立刻收到超时错误（而不是永远挂着等）。

> 不同调用方的会话按**来源指纹**隔离。默认指纹是 IP + 客户端名：同一个人换了网络
> （手机 4G 切 Wi-Fi、开关 VPN）会被当成新来源，原会话不再增长——这是为了不让两个不同的人
> 串到一条会话里，是刻意的取舍。**想彻底摆脱这个取舍，就给每个调用方配一个身份头**（见下）。

### 调用方接入

Key 从哪来：登录操作台 → ⚙ 设置 → 「调用方 Key」→ 复制。**所有调用方共用这一个**，改 Key 后所有人都要更新配置。

OpenAI 协议（Python SDK 示例）：

```python
from openai import OpenAI
client = OpenAI(base_url="https://你的域名/v1", api_key="sk-chatshy-...")
resp = client.chat.completions.create(
    model="chatshy",  # 任意名称都会原样回显
    messages=[{"role": "user", "content": "在吗"}],
)
```

Cherry Studio / ChatBox 等客户端：API 地址填 `https://你的域名/v1`，API Key 填那个全局 Key。

> 操作台会按 User-Agent 认出客户端（Cherry Studio / ChatBox / Claude Code / curl …）并标在会话上，
> 所以同一台机器上换不同客户端会被当成不同来源。识别不出来时会显示原始 UA，不会丢信息。

Anthropic 协议（Claude Code 等）：

```bash
export ANTHROPIC_BASE_URL="https://你的域名"   # 注意：不要以 /v1 结尾！
export ANTHROPIC_API_KEY="sk-chatshy-..."      # 或 ANTHROPIC_AUTH_TOKEN，两种都支持
```

> - `ANTHROPIC_BASE_URL` **不要**以 `/v1` 结尾（SDK 会自己拼 `/v1/messages`，写成 `…/v1` 会请求成 `/v1/v1/messages`）。ChatShy 已对误带 `/v1` 的写法做了兼容，但正确配法是不带。
> - 鉴权同时接受 `x-api-key`（ANTHROPIC_API_KEY）与 `Authorization: Bearer`（ANTHROPIC_AUTH_TOKEN）。
> - Claude Code 会用到的 `POST /v1/messages/count_tokens`、`/api/hello` 探测、遥测路径均已实现（遥测一律 200，避免客户端重试风暴）。
> - 等待真人回复不会断流：网关建流后每 15 秒发 ping，新版 Claude Code 会把 ping 计入"仍在传输"，默认 5 分钟空闲看门狗不会触发。若仍出现 5 分钟断开，可尝试设置 `API_FORCE_IDLE_TIMEOUT=0` 并升级到最新版。
> - 可选加固：`export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` 关闭遥测——旧版 Claude Code 的遥测可能把网关凭据发往官方服务器。

curl 流式：

```bash
curl -N https://你的域名/v1/chat/completions \
  -H "Authorization: Bearer sk-chatshy-..." -H "Content-Type: application/json" \
  -d '{"model":"chatshy","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

### AI 厂商转发（可选）

在 ⚙ 设置 →「AI 厂商转发」里添加配置，每个配置包含名称、Base URL、模型名、API Key。只支持 **OpenAI 兼容** 的 `/chat/completions` 接口：

| 厂商 | Base URL | 模型名示例 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| 其他 | 任意 OpenAI 兼容端点（如本地 Ollama / vLLM） | 按厂商文档 |

- **手动**：待回复条右侧选择厂商 → 点「AI 回复」，服务端立即调用上游并把结果直接回给调用方。
- **自动**：侧边栏打开「AI 自动转发」并选好厂商。开关存在服务端，之后所有新请求自动由该厂商回答——浏览器关掉、人不在线也继续工作。
- 上游使用配置里填的模型名，调用方请求里的 `model` 只用于回显；不自动重试、不自动切换厂商。非流式调用总超时 60 秒；流式调用按**空闲超时**计——连续 60 秒没有新数据才算失败，长回复不会因为总时长被掐断。
- 调用方要流式时，转发也走上游流式接口并实时透传增量；已经吐过字后上游再失败，会给调用方发协议错误帧并关闭连接（半截正文无法撤回）。这段期间操作台的人工回复会被拒绝，避免拼接出坏响应。
- 自动转发失败（Key 错、限流、超时等）时请求**不会被丢掉**：它留在待回复列表，侧边栏/待回复条显示失败原因并弹通知，可随时人工接管；已经超时的请求不会再补发。
- 上游 Key 明文存在本地 SQLite（与调用方 Key 同一信任边界），管理端接口只回显末四位；编辑时留空表示沿用旧 Key。
- ⚠️ 开启转发后，调用方的对话内容（含 system 提示词）会被发送到所配置的第三方厂商——请确认这是你要的隐私边界。
- Base URL 填到 `/chat/completions` 之前的那一段，误粘贴完整 endpoint 会自动纠正。不要把 Base URL 指回 ChatShy 自己（会造成递归转发）。

### 来源标记：让操作台认得出「谁在说话」（可选）

默认来源是「IP + 客户端名」。挂在 Nginx 后面时，如果反代没转发真实 IP，所有调用方的 IP 都是
`127.0.0.1`——两个人用同一个客户端就会被并成同一条会话。给每个调用方加一条自定义请求头即可解决：

```
X-ChatShy-Caller: alice
```

- 带了它，来源**只按这个名字算**，与 IP 完全无关：同一个人换网络、客户端升级版本，会话都不会被切段
- 没带它，行为与从前一致（IP + 客户端指纹）
- 中文名要写成百分号编码，因为 HTTP 头值只能是 latin-1 字节：`X-ChatShy-Caller: %E5%BC%A0%E4%B8%89`。
  服务端两种写法都认，也认直连发 UTF-8 字节的客户端（curl / Python / Go）

各家加在哪：Cherry Studio 的「自定义请求头」、ChatBox 的请求头设置、Claude Code 用
`export ANTHROPIC_CUSTOM_HEADERS="X-ChatShy-Caller: alice"`、官方 SDK 用 `default_headers`。

> 这是调用方**自报**的身份，不是鉴权——它用来区分信任的同事，挡不住有人故意填别人的名字。

## 部署（VPS + Docker + Caddy 自动 HTTPS）

1. 服务器装好 Docker 与 Compose；DNS 把你的域名解析到服务器
2. 拷贝项目到服务器，`cp .env.example .env` 并设置 `ADMIN_PASSWORD`
3. 编辑 `docker/Caddyfile`，把 `chatshy.example.com` 换成你的域名
4. `docker compose up -d --build`
5. 浏览器打开 `https://你的域名` 登录操作台

- 只用内网/HTTP：可以删掉 compose 里的 `caddy` 服务，直接暴露 8088 端口
- 用 Nginx：参考 `docker/nginx.conf.example`，**必须 `proxy_buffering off`**（SSE 流式），服务端已带 `X-Accel-Buffering: no` 头兜底
- **必须把真实 IP 转发进来**（`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 或 `X-Real-IP`，两者服务端都认）。漏了的话操作台上所有来源的 IP 都是 `127.0.0.1`，不同调用方会被当成同一个人
- 桌面通知需要 HTTPS 上下文（Caddy 自动证书正好满足）

## 环境变量（仅作首次启动引导；运行时改请走操作台设置页）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8088` | 监听端口 |
| `DB_PATH` | `./data/chatshy.db` | SQLite 文件路径（compose 里设为 `/data/chatshy.db`） |
| `ADMIN_PASSWORD` | 随机生成并打印 | 管理员初始密码（库里已有密码哈希时忽略） |
| `CALLER_KEY` | 随机生成并打印 | 全局调用方 Key（库里已有则忽略；之后可在设置页改） |
| `DEFAULT_TIMEOUT_MS` | `180000` | 全局默认挂起超时（毫秒） |
| `MODEL_NAME` | `chatshy` | `GET /v1/models` 展示的模型名 |

## 备份

数据只有一个 SQLite 文件（`data/chatshy.db`）。推荐在线备份：

```bash
# 在项目目录（宿主机装了 sqlite3 CLI）
sqlite3 data/chatshy.db ".backup 'backup-$(date +%F).db'"
```

或者停服务直接拷目录：

```bash
docker compose stop chatshy
tar czf chatshy-backup-$(date +%F).tgz data/
docker compose start chatshy
```

定时任务示例（每天 03:17）：

```
17 3 * * * cd /opt/chatshy && sqlite3 data/chatshy.db ".backup 'backups/chatshy-$(date +\%F).db'"
```

## 手测清单（接入真实客户端）

- [ ] Cherry Studio 接入（OpenAI 格式）：非流式与流式正常，回复内容/ Markdown 渲染正常
- [ ] ChatBox 接入（OpenAI 格式）
- [ ] Claude Code：`ANTHROPIC_BASE_URL` 指向本服务，能收到流式回复
- [ ] 超时：发起请求后不回复，等到超时 → 客户端展示错误而非崩溃
- [ ] 同一会话快速重发 → 操作台显示"重复挂起请求"，回复发给最新一个
- [ ] 客户端中途取消 → 操作台挂起状态解除
- [ ] 手机浏览器打开操作台回复一次
- [ ] 桌面通知与提示音（首次需授权）
- [ ] **来源隔离**：用两个不同客户端（如 Cherry Studio 和 curl）发完全相同的第一条消息 → 操作台应出现两条独立会话，不串线
- [ ] **身份头**：同一台机器上用两个不同的 `X-ChatShy-Caller` 各发一条相同的消息 → 两条独立会话，标签显示各自的名字；同一个人换 IP 再发 → 仍归到原来那条会话
- [ ] **反代真实 IP**：经 Nginx 访问，操作台上的来源 IP 是客户端真实 IP，不是 `127.0.0.1`
- [ ] **删除会话**：删一条没有挂起请求的 → 列表里消失；删一条有挂起请求的 → 调用方立刻收到超时错误，消息一并清空
- [ ] 设置页能查看并复制全局 Key、能一键复制接入配置；重新生成后旧 Key 立即 401

## 隐私说明

所有调用方内容（含 system 提示词）都会**完整呈现给操作台管理员**——这正是本项目的设计。把它提供给别人使用时，请确保对方知晓这一点。

如果启用了 AI 厂商转发，这些内容还会被发送到你配置的第三方厂商；是否使用、发往哪家，由管理员决定。

## v1 范围之外

工具调用（tools / tool_use）、多模态、embeddings、多操作员协作、对话全文搜索、短信/邮件通知。

> 附带一句：流式回复现在是逐帧下发的，因此客户端会把 Markdown 边收边渲染——代码块在开头的 ``` 到达之前会短暂显示成普通文本。这与真实大模型 API 的观感一致，主流客户端都按这个前提写过；如果你更在意代码块一次成型，把 `src/server/serve.ts` 里的 `TYPEWRITER_TICK_MS` 调成 `0` 即可让正文仍分帧但不等待间隔。

## 测试

```bash
npm test
```

- 单元测试：协议解析/渲染的 golden 断言、会话归并边界（含**来源隔离**）、来源识别与指纹稳定性、Job 状态机（假时钟）、密码与全局 Key、数据库迁移（含从旧版多 Key 库升级的数据保留）
- 集成测试：进程内起真实 HTTP 服务，用官方 `openai` / `@anthropic-ai/sdk` 打全链路，脚本扮演操作员回复；覆盖流式、超时、断连、限额、管理端全流程
