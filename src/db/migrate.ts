import type { Db } from './db.js';

/**
 * 手写迁移：数组下标 + 1 = schema 版本号；只允许追加，不允许修改历史项。
 * 导出是为了让测试能单独铺出"某一版老库"，从而覆盖升级路径（见 test/unit/migrate.test.ts）。
 */
export const MIGRATIONS: readonly string[] = [
  `
CREATE TABLE IF NOT EXISTS keys (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  key_hash     TEXT NOT NULL UNIQUE,
  key_prefix   TEXT NOT NULL,
  enabled      INTEGER NOT NULL DEFAULT 1,
  timeout_ms   INTEGER,
  daily_limit  INTEGER,
  max_pending  INTEGER NOT NULL DEFAULT 5,
  created_at   INTEGER NOT NULL,
  last_used_at INTEGER
);

CREATE TABLE IF NOT EXISTS threads (
  id          TEXT PRIMARY KEY,
  key_id      TEXT REFERENCES keys(id) ON DELETE CASCADE,
  title       TEXT NOT NULL DEFAULT '',
  protocol    TEXT NOT NULL,
  model       TEXT NOT NULL DEFAULT '',
  fingerprint TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
  id         TEXT PRIMARY KEY,
  thread_id  TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  role       TEXT NOT NULL,
  content    TEXT NOT NULL,
  source     TEXT NOT NULL,
  job_id     TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS jobs (
  id            TEXT PRIMARY KEY,
  thread_id     TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  protocol      TEXT NOT NULL,
  mode          TEXT NOT NULL,
  status        TEXT NOT NULL,
  request_json  TEXT NOT NULL,
  reply_content TEXT,
  deadline_at   INTEGER NOT NULL,
  created_at    INTEGER NOT NULL,
  replied_at    INTEGER
);

CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_keys_hash        ON keys(key_hash);
CREATE INDEX IF NOT EXISTS idx_threads_key      ON threads(key_id);
CREATE INDEX IF NOT EXISTS idx_messages_thread  ON messages(thread_id);
CREATE INDEX IF NOT EXISTS idx_jobs_thread      ON jobs(thread_id);
CREATE INDEX IF NOT EXISTS idx_jobs_status      ON jobs(status);
`,

  // 迁移 2：多 Key 体系 → 唯一全局 Key，身份标识改为请求来源（IP + UA）
  //
  // 四步顺序不可调换，两个坑都在真实 SQLite 上实测过：
  //  - DROP TABLE keys 在 foreign_keys=ON 时不报错，但会顺着 threads.key_id 的
  //    ON DELETE CASCADE 静默删光 threads → messages/jobs。所以必须先解除引用。
  //  - 「关 FK 再重建表」的常规写法在这里行不通：PRAGMA foreign_keys 在事务内是空操作，
  //    而 migrate() 恰好把迁移包在 db.transaction() 里。下面用 DROP COLUMN 绕开重建表。
  `
-- 1) 先加列。已有行必须给 DEFAULT，否则 ADD COLUMN NOT NULL 会失败
ALTER TABLE threads ADD COLUMN source_ip          TEXT NOT NULL DEFAULT '';
ALTER TABLE threads ADD COLUMN source_ua          TEXT NOT NULL DEFAULT '';
ALTER TABLE threads ADD COLUMN source_client      TEXT NOT NULL DEFAULT '';
ALTER TABLE threads ADD COLUMN source_fingerprint TEXT NOT NULL DEFAULT '';

-- 2) 回填来源。必须在加列之后（否则列不存在），又在 DROP COLUMN key_id 之前（要用它做子查询）。
--    key_id IS NULL 的老行真实存在——旧版「操作台测试会话」就是显式写 NULL 建的。
--    而 '旧 Key：' || NULL 的结果是 NULL，会撞 NOT NULL 约束让整条迁移回滚、服务起不来，
--    所以必须用 CASE 分流。
UPDATE threads SET source_client =
  CASE WHEN key_id IS NULL THEN '历史会话'
       ELSE '旧 Key：' || (SELECT name FROM keys WHERE keys.id = threads.key_id) END;

-- 3) 哨兵指纹：老会话自成一类，永远不会与真实 IP+UA 指纹碰撞，
--    于是新调用方不会接管到历史会话上（老会话升级后不再增长，属预期行为）。
UPDATE threads SET source_fingerprint = 'legacy:' || id;

-- 4) 必须先删索引，否则 DROP COLUMN 报 "error in index idx_threads_key after drop column"
DROP INDEX IF EXISTS idx_threads_key;
ALTER TABLE threads DROP COLUMN key_id;

-- 5) 引用解除后删 keys 才安全。顺序关键：直接删会顺着 ON DELETE CASCADE 静默清空 threads
DROP TABLE keys;

-- 4) 新索引：会话归并按来源指纹查候选集
CREATE INDEX IF NOT EXISTS idx_threads_source ON threads(source_fingerprint, updated_at);
`,

  // 迁移 3：多厂商 AI 转发
  //
  // providers 存管理员配置的上游（OpenAI 兼容接口）。api_key 明文存储是刻意的，
  // 与 caller_key 同属"数据库文件即信任边界"的取舍；管理端接口只回掩码。
  //
  // jobs 上的四列只做审计与失败可观测，不改变回复落库路径：
  //  - via             成功回复的厂商名快照（厂商改名/删除后历史仍可读）
  //  - provider_id     触发该回复的厂商 id（不加外键，避免厂商生命周期影响历史）
  //  - forward_error*  最近一次自动/手动转发失败原因与时间，供待回复条展示
  `
CREATE TABLE IF NOT EXISTS providers (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  base_url   TEXT NOT NULL,
  api_key    TEXT NOT NULL,
  model      TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

ALTER TABLE jobs ADD COLUMN via              TEXT;
ALTER TABLE jobs ADD COLUMN provider_id      TEXT;
ALTER TABLE jobs ADD COLUMN forward_error    TEXT;
ALTER TABLE jobs ADD COLUMN forward_error_at INTEGER;
`,
];

export const SCHEMA_VERSION = MIGRATIONS.length;

export function migrate(db: Db): void {
  db.exec('CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)');
  const row = db.prepare('SELECT version FROM schema_version LIMIT 1').get() as { version: number } | undefined;
  const current = row?.version ?? 0;

  if (current < MIGRATIONS.length) {
    db.transaction(() => {
      for (let i = current; i < MIGRATIONS.length; i++) {
        db.exec(MIGRATIONS[i]!);
      }
      db.prepare('DELETE FROM schema_version').run();
      db.prepare('INSERT INTO schema_version (version) VALUES (?)').run(MIGRATIONS.length);
    })();
  }

  // 重启语义：内存中的挂起请求随进程消失，遗留 pending 一律标记 dropped（设计 §3.5）
  db.prepare("UPDATE jobs SET status = 'dropped' WHERE status = 'pending'").run();
}
