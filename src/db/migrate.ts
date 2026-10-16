import type { Db } from './db.js';

/** 手写迁移：数组下标 + 1 = schema 版本号；只允许追加，不允许修改历史项 */
const MIGRATIONS: readonly string[] = [
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
  key_id      TEXT NOT NULL REFERENCES keys(id) ON DELETE CASCADE,
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
