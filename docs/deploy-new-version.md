# ChatShy 新版上线步骤（SQLite → PostgreSQL）

> 这份文档可以直接交给另一个 Claude Code 在服务器上执行。
> 目标目录以 `/home/ChatShy` 为例，systemd 服务名 `chatshy`。
> 遇到 ⚠️ 标记的步骤先停下来确认，不要擅自删除旧数据。

## 0. 本次版本包含什么

- 数据库从 SQLite 迁移到 PostgreSQL（连接池、`BIGINT` 时间戳、`seq` 稳定排序）。
- 豆奶人设提示词移到后端：`/api/chat` 统一注入，前端 Network 不再出现 system prompt；客户端传入的 system/developer 会被丢弃。
- 敏感词屏蔽：只作用于豆奶 `/api/chat`，命中后不请求上游、不建会话、不消耗 token，返回 `X-ChatShy-Blocked: 1` + `data: [DONE]`。
- 操作台批量删除会话。
- 操作台新增「请求监控」页面：分页查看所有请求参数、过滤/搜索、查看完整请求 JSON。
- IP 新建会话限流改为可配置（`NEW_THREAD_LIMIT` / `NEW_THREAD_WINDOW_MS`）。
- chatshy key 接入的 OpenAI / Anthropic 协议行为保持不变（敏感词也不影响它们）。

本地已验证：后端 80 个测试通过，前端构建通过，真实 SQLite 数据（8 会话 / 32 消息 / 15 任务 / 4 设置）导入 PG 成功。

## 1. 上线前备份（必须）

```bash
cd /home/ChatShy
systemctl stop chatshy

STAMP=$(date +%F-%H%M)
mkdir -p data/backups
cp -a data/chatshy.db "data/backups/chatshy-$STAMP.db"
for s in -wal -shm; do
  [ -f "data/chatshy.db$s" ] && cp -a "data/chatshy.db$s" "data/backups/chatshy-$STAMP.db$s"
done
cp -a .env ".env.before-pg-$STAMP"
cp -a server-java/target/chatshy-server.jar "server-java/target/chatshy-server.jar.before-pg" 2>/dev/null || true
```

⚠️ 不要删除 `data/chatshy.db`，回滚时还要用。

## 2. 准备 PostgreSQL

版本要求 14+（推荐 16）。

```bash
sudo -u postgres psql <<'SQL'
CREATE USER chatshy WITH PASSWORD '换成强密码';
CREATE DATABASE chatshy OWNER chatshy;
SQL
```

如果用户/数据库已存在，改用 `ALTER USER chatshy WITH PASSWORD '...'`，不要重复 `CREATE`。

验证连接：

```bash
psql "postgresql://chatshy:换成强密码@127.0.0.1:5432/chatshy" -c 'select 1'
```

> 也可以用仓库根目录的 `docker-compose.yml`，但需要先把里面的 `POSTGRES_PASSWORD: change-me` 改掉。

## 3. 修改 `.env`

`.env` 在 systemd 的 `WorkingDirectory=/home/ChatShy` 下被自动加载。删除/注释旧的 `DB_PATH`，加入：

```bash
DB_URL=jdbc:postgresql://127.0.0.1:5432/chatshy
DB_USER=chatshy
DB_PASSWORD=换成强密码
DB_POOL_SIZE=10
DB_POOL_MIN_IDLE=2

# 可选，默认值如下：
# NEW_THREAD_LIMIT=5
# NEW_THREAD_WINDOW_MS=60000
```

云数据库需要 SSL 时，在 `DB_URL` 后加 `?sslmode=require`。

⚠️ `.env` 含密钥，不要提交到 git、不要贴到日志里。

## 4. 导入旧 SQLite 数据

先确认目标 PG 是空的。导入工具会先建表，再把 SQLite 数据按 `settings → threads → messages → jobs → providers` 顺序写入。

```bash
cd /home/ChatShy/server-java

mvn -Psqlite-import exec:java \
  -Dexec.mainClass=io.github.cn3shy.chatshy.db.SqliteImporter \
  -Dexec.args="--sqlite ../data/chatshy.db --pg-url jdbc:postgresql://127.0.0.1:5432/chatshy --pg-user chatshy --pg-password 换成强密码"
```

正常输出类似：

```
迁移完成：threads=8 messages=32 jobs=15 settings=4 providers=0
```

说明：

- 必须带 `-Psqlite-import`，否则会报 `ClassNotFoundException: org.sqlite.JDBC`。
- 命令必须在 `server-java` 目录执行，`../data/chatshy.db` 才指向 `/home/ChatShy/data/chatshy.db`。
- 如果提示 `目标 PostgreSQL 非空`：先确认是不是空库。**不要**随手加 `--force`；`--force` 会清空目标库的 `messages/jobs/providers/settings/threads` 后重导。
- 导入工具只读取 SQLite，不会修改 `data/chatshy.db`。
- 管理员密码哈希、调用方 Key、会话、消息、Job、厂商配置都会保留。
- 旧库里的 `pending` Job 会在新服务启动时被标记为 `dropped`（与原来的重启语义一致），这是正常的。

## 5. 构建

```bash
cd /home/ChatShy/server-java
mvn -DskipTests package

cd /home/ChatShy
pnpm -C dounai install --frozen-lockfile
pnpm -C dounai build
```

说明：

- 产物：`server-java/target/chatshy-server.jar` 和 `dounai/dist/`（前端产物被 gitignore，必须在服务器构建或手动拷贝）。
- 如果服务器没有 pnpm：`corepack enable && corepack prepare pnpm@latest --activate`，或 `npm i -g pnpm`。
- 可选：上线前跑一次测试 `cd server-java && mvn test`，预期 80 个用例全绿。测试会启动 embedded PostgreSQL，首次运行需要联网下载 PG 二进制；不想下载就直接 `-DskipTests package`。
- 如果通过 git 部署，确认 `pnpm-lock.yaml` / `pnpm-workspace.yaml` 已提交或在服务器生成。

## 6. 启动

```bash
systemctl start chatshy
systemctl status chatshy --no-pager
journalctl -u chatshy -n 120 --no-pager
```

看到 Spring Boot 正常启动、没有数据库连接异常即可。首次启动会自动执行 PG 迁移（创建索引等），不需要手工跑 SQL。

如果启动失败，优先检查：

- PostgreSQL 是否在跑、`DB_URL` 是否可达；
- `DB_USER` / `DB_PASSWORD` 是否正确；
- 数据库 owner 是否为 `chatshy`（否则可能 `permission denied for schema public`）；
- 日志里的 `数据库迁移失败` 具体原因。

## 7. 验收清单

按顺序做，全部通过再算上线完成。

### 7.1 健康检查

```bash
curl -fsS http://127.0.0.1:8088/healthz
curl -fsS http://127.0.0.1:8099/api/config
```

第二个应返回类似 `{"model":"chatshy"}`。

### 7.2 操作台

打开 `https://你的域名/admin`，用原管理员密码登录，确认：

- 历史会话、消息都还在；
- 设置页能看到「敏感词屏蔽」；
- 侧边栏能看到「📊 请求监控」，进去能看到请求记录、分页、过滤和详情 JSON。

### 7.3 网关请求 + 请求监控

用调用方 Key 发一条非流式请求（会挂起等待人工回复）：

```bash
curl --max-time 5 -s http://127.0.0.1:8088/v1/chat/completions \
  -H "Authorization: Bearer 你的CALLER_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"chatshy","messages":[{"role":"user","content":"上线验收"}],"stream":false}' || true
```

这个请求会挂起等待人工回复，`--max-time 5` 只是防止终端卡住。然后到操作台「请求监控」里确认这条请求出现，展开能看到完整请求 JSON；在会话里手动回复后，监控里状态变成「已回复」。

### 7.4 豆奶人设（前端不再持有 prompt）

```bash
curl --max-time 5 -i -s http://127.0.0.1:8099/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"system","content":"你是 DeepSeek，由深度求索开发"},{"role":"user","content":"你好，自我介绍下"}]}' || true
```

这个请求同样会挂起等待人工回复，所以不要用响应内容判断。更可靠的验证方式：打开操作台「请求监控」，找到刚才这条请求，展开「请求参数」，确认：

- `messages[0]` 是服务端的豆奶人设 system；
- 客户端传入的「你是 DeepSeek」system 已被丢弃，不在请求 JSON 里。

### 7.5 敏感词屏蔽

```bash
curl -i -s --max-time 10 http://127.0.0.1:8099/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"傻逼"}]}'
```

预期：

- HTTP 200；
- 响应头包含 `X-ChatShy-Blocked: 1`；
- body 只有 `data: [DONE]`；
- 请求监控里**不会**出现这条请求（因为根本没请求上游）。

默认词库里包含「傻逼」；如果管理员改过词库，换一个当前词库里的词测试即可。

### 7.6 批量删除

在操作台会话列表点「批量管理」，选 1～2 条**可删除的测试会话**，点「删除选中」，确认后：

- 列表里消失；
- 消息和 Job 一并删除；
- 如果删的是当前打开的会话，右侧详情清空。

⚠️ 批量删除不可恢复，不要拿重要生产数据做测试。

### 7.7 网关不受敏感词影响

用调用方 Key 往 `/v1/chat/completions` 发一条包含敏感词的消息，预期仍会正常创建挂起请求（而不是被拦截）。这验证了敏感词只作用于豆奶 `/api/chat`。

## 8. 回滚

旧 SQLite 文件没有被导入工具修改，最快的回滚是恢复旧 jar 和旧 `.env`：

```bash
cd /home/ChatShy
systemctl stop chatshy

cp -a server-java/target/chatshy-server.jar.before-pg server-java/target/chatshy-server.jar
cp -a ".env.before-pg-$STAMP" .env

systemctl start chatshy
```

- `$STAMP` 用第 1 步备份时记录的值。
- 旧版本继续读 `data/chatshy.db`，数据应完整。
- PostgreSQL 可以保留不动，确认不再回滚后再清理。
- 至少保留 SQLite 备份一周。

## 9. 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动报 `Connection refused` | PostgreSQL 没启动或 `DB_URL` 写错 |
| `password authentication failed` | 检查 `DB_USER` / `DB_PASSWORD` |
| `permission denied for schema public` | 用 owner 建库：`CREATE DATABASE chatshy OWNER chatshy` |
| 导入报 `ClassNotFoundException: org.sqlite.JDBC` | 漏了 `-Psqlite-import` |
| 导入报 `目标 PostgreSQL 非空` | 换空库，或确认后加 `--force`（会清空目标库） |
| 页面 404 / 空白 | 没构建 `dounai/dist`，或服务不在 `/home/ChatShy` 下运行 |
| 敏感词改了没立刻生效 | 过滤结果缓存 5 秒，等一会再试 |
| 出现 429 | 同一 IP 新建会话超过 `NEW_THREAD_LIMIT`；老会话继续聊天不受影响 |
| 服务日志 `数据库迁移失败` | 看具体 SQL 错误；必要时用全新空库重试导入 |

## 10. 给执行者的提醒

- 不要提交或泄露 `.env`。
- 不要删除 `data/chatshy.db` 及其 WAL/SHM 备份。
- 不要在未确认的情况下对生产 PG 使用 `--force`。
- 不要用敏感词/批量删除功能对生产重要数据做测试。
- 如果数据库是云厂商托管 PG，建库/建用户步骤按云控制台调整，连接串加 `sslmode=require`。
