# ChatShy · 人肉大模型网关

对外是一个 **OpenAI / Anthropic 双协议兼容** 的大模型 API；实际上每个请求都会实时推送到你的网页操作台，由你（人类）撰写回复，再按协议格式（含流式 SSE）原样返回给调用方。操作台本身也是个聊天窗口，不经过 API 就能直接收发消息。

- 设计文档：`docs/superpowers/specs/2026-10-16-chatshy-design.md`
- 实现计划：`docs/superpowers/plans/2026-10-16-chatshy-implementation.md`

## 功能

- **双协议兼容**：`POST /v1/chat/completions`（OpenAI）与 `POST /v1/messages`（Anthropic），流式/非流式均可；Cherry Studio、ChatBox、LobeChat、官方 SDK、Claude Code 等直接接入
- **操作台**：SSE 实时推送新请求（≤1 秒）、桌面通知 + 提示音、倒计时、重复请求提示、Markdown 输入与代码高亮渲染、手机浏览器可用
- **唯一全局 Key + 来源标记**：所有调用方共用一个 Key（设置页可查看/重新生成），用**请求来源**（IP + User-Agent 识别出的客户端名）区分"谁在说话"，操作台在每条会话上标注来源
- **调用方页面（`/chat`）**：豆包风格的浅色对话界面，填上全局 Key 即可聊天——支持 OpenAI / Anthropic 格式切换、流式显示、中途取消；Key 与对话记录只存浏览器本地
- **请求挂起 + 可配置超时**：默认 180 秒（全局设置项）；超时返回各协议标准错误体
- **会话归并**：无状态协议的多轮请求自动归并成一条会话线，**按来源隔离**（不同调用方绝不会串线）；超时后补的回复只存档并标注"未送达"
- **单进程 + SQLite**：一个 Docker 镜像部署，数据一个文件备份

## 本地开发

```bash
npm install          # 服务端依赖
npm --prefix web install   # 前端依赖（首次）
cp .env.example .env # 可选：设置 ADMIN_PASSWORD；不设则首次启动打印随机密码
npm run dev          # 服务端 + 操作台 → http://localhost:8787
```

前端热更新开发：终端 1 `npm run dev`，终端 2 `npm run web:dev` → http://localhost:5173（已代理管理端接口）。

```bash
npm run build:all    # 前端 + 服务端生产构建
npm start            # 运行生产产物
npm test             # 单元 + 集成测试（98 个）
```

## 使用

### 操作台

打开 `http://localhost:8787`（或你的域名），用管理员密码登录：

- 左侧：会话列表。**未回复的置顶、红点、剩余秒数倒计时**；每条显示请求来源（客户端名 + IP）、最后消息
- 右侧：对话视图 + 底部输入框（Enter 发送，Shift+Enter 换行，支持 Markdown）
- ⚙ 设置页：查看/复制/重新生成**全局调用方 Key**、改默认超时、模型名、管理员密码、通知开关

> 不同调用方的会话按**来源指纹**（IP + 客户端）隔离。同一个人换了网络（手机 4G 切 Wi-Fi、
> 开关 VPN）会被当成新来源，原会话不再增长——这是为了不让两个不同的人串到一条会话里，
> 是刻意的取舍。

### 调用方页面（/chat）

把 `https://你的域名/chat` 发给要用的人：点右上角 ⚙ 填上全局 Key 即可聊天，可选 OpenAI / Anthropic 格式、开/关流式，请求期间可随时点"停止"。界面是豆包风格的浅色对话页；API Key 与对话记录都只存在调用方的浏览器里。

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

## 部署（VPS + Docker + Caddy 自动 HTTPS）

1. 服务器装好 Docker 与 Compose；DNS 把你的域名解析到服务器
2. 拷贝项目到服务器，`cp .env.example .env` 并设置 `ADMIN_PASSWORD`
3. 编辑 `docker/Caddyfile`，把 `chatshy.example.com` 换成你的域名
4. `docker compose up -d --build`
5. 浏览器打开 `https://你的域名` 登录操作台

- 只用内网/HTTP：可以删掉 compose 里的 `caddy` 服务，直接暴露 8787 端口
- 用 Nginx：参考 `docker/nginx.conf.example`，**必须 `proxy_buffering off`**（SSE 流式），服务端已带 `X-Accel-Buffering: no` 头兜底
- 桌面通知需要 HTTPS 上下文（Caddy 自动证书正好满足）

## 环境变量（仅作首次启动引导；运行时改请走操作台设置页）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8787` | 监听端口 |
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
- [ ] `/chat` 页面：填全局 Key 后 OpenAI / Anthropic 两种格式各发一条（流式），中途点"停止"验证取消
- [ ] **来源隔离**：用两个不同客户端（如 Cherry Studio 和 curl）发完全相同的第一条消息 → 操作台应出现两条独立会话，不串线
- [ ] 设置页能查看并复制全局 Key；重新生成后旧 Key 立即 401

## 隐私说明

所有调用方内容（含 system 提示词）都会**完整呈现给操作台管理员**——这正是本项目的设计。把它提供给别人使用时，请确保对方知晓这一点。

## v1 范围之外

工具调用（tools / tool_use）、多模态、embeddings、多操作员协作、对话全文搜索、短信/邮件通知、流式逐字打字机效果（整段一次送达，避免代码块渲染闪烁）。

## 测试

```bash
npm test
```

- 单元测试：协议解析/渲染的 golden 断言、会话归并边界（含**来源隔离**）、来源识别与指纹稳定性、Job 状态机（假时钟）、密码与全局 Key、数据库迁移（含从旧版多 Key 库升级的数据保留）
- 集成测试：进程内起真实 HTTP 服务，用官方 `openai` / `@anthropic-ai/sdk` 打全链路，脚本扮演操作员回复；覆盖流式、超时、断连、限额、管理端全流程
