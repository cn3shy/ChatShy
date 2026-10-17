import { describe, expect, it } from 'vitest';
import { openDb, type Db } from '../../src/db/db.js';
import { MIGRATIONS, SCHEMA_VERSION, migrate } from '../../src/db/migrate.js';

function tableNames(db: Db): string[] {
  return (db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all() as { name: string }[]).map(
    (r) => r.name,
  );
}

function columnNames(db: Db, table: string): string[] {
  return (db.prepare(`PRAGMA table_info(${table})`).all() as { name: string }[]).map((r) => r.name);
}

/**
 * 铺出一个只跑到第 version 版的老库，用来测升级路径。
 * MIGRATIONS 下标 + 1 = 版本号，所以 version=1 就是第 0 项。
 */
function seedLegacyDb(db: Db, version: number): void {
  for (let i = 0; i < version; i++) db.exec(MIGRATIONS[i]!);
  db.exec('CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)');
  db.prepare('DELETE FROM schema_version').run();
  db.prepare('INSERT INTO schema_version (version) VALUES (?)').run(version);
}

describe('migrate', () => {
  it('创建全部表（keys 已移除），且可重复执行', () => {
    const db = openDb(':memory:');
    migrate(db);
    migrate(db);

    const names = tableNames(db);
    expect(names).toEqual(
      expect.arrayContaining(['threads', 'messages', 'jobs', 'settings', 'schema_version', 'providers']),
    );
    expect(names).not.toContain('keys');

    const row = db.prepare('SELECT version FROM schema_version').get() as { version: number };
    expect(row.version).toBe(SCHEMA_VERSION);
  });

  it('threads 带来源列，且不再有 key_id', () => {
    const db = openDb(':memory:');
    migrate(db);
    const cols = columnNames(db, 'threads');
    expect(cols).toEqual(
      expect.arrayContaining(['source_ip', 'source_ua', 'source_client', 'source_fingerprint']),
    );
    expect(cols).not.toContain('key_id');
  });

  it('把遗留的 pending job 标记为 dropped（重启语义）', () => {
    const db = openDb(':memory:');
    migrate(db);
    const now = Date.now();
    db.prepare(
      "INSERT INTO threads (id, protocol, created_at, updated_at) VALUES ('t1', 'openai', ?, ?)",
    ).run(now, now);
    db.prepare(
      `INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
       VALUES ('j1', 't1', 'openai', 'stream', 'pending', '{}', ?, ?)`,
    ).run(now, now);

    migrate(db);

    const job = db.prepare("SELECT status FROM jobs WHERE id = 'j1'").get() as { status: string };
    expect(job.status).toBe('dropped');
  });

  /**
   * 升级路径的回归测试：这是本次重构最危险的一段 SQL。
   * 迁移 2 会删掉 keys 表——若顺序写错（先 DROP TABLE keys 再解除引用），
   * SQLite 会顺着 ON DELETE CASCADE 静默清空 threads/messages/jobs。
   */
  it('从 v1 老库升级：数据全部保留，来源正确回填', () => {
    const db = openDb(':memory:');
    seedLegacyDb(db, 1);

    const now = Date.now();
    db.prepare(
      "INSERT INTO keys (id, name, key_hash, key_prefix, created_at) VALUES ('k1', 'alice', 'h', 'sk-ab…', ?)",
    ).run(now);
    // 老库里 key_id 为 NULL 的行真实存在：旧版「操作台测试会话」就是显式写 NULL 建的
    db.prepare("INSERT INTO threads (id, key_id, title, protocol, created_at, updated_at) VALUES ('t1', 'k1', '正常会话', 'openai', ?, ?)").run(now, now);
    db.prepare("INSERT INTO threads (id, key_id, title, protocol, created_at, updated_at) VALUES ('t2', NULL, '控制台测试会话', 'console', ?, ?)").run(now, now);
    db.prepare("INSERT INTO messages (id, thread_id, role, content, source, created_at) VALUES ('m1', 't1', 'user', '你好', 'api', ?)").run(now);
    db.prepare("INSERT INTO messages (id, thread_id, role, content, source, created_at) VALUES ('m2', 't2', 'user', '控制台消息', 'console', ?)").run(now);
    db.prepare(
      `INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
       VALUES ('j1', 't1', 'openai', 'stream', 'replied', '{}', ?, ?)`,
    ).run(now, now);

    migrate(db);

    // keys 表消失，threads 不再引用它
    expect(tableNames(db)).not.toContain('keys');
    expect(db.pragma('foreign_key_check')).toEqual([]);

    // 行还在，一个都没被级联删掉
    expect((db.prepare('SELECT COUNT(*) AS c FROM threads').get() as { c: number }).c).toBe(2);
    expect((db.prepare('SELECT COUNT(*) AS c FROM messages').get() as { c: number }).c).toBe(2);
    expect((db.prepare('SELECT COUNT(*) AS c FROM jobs').get() as { c: number }).c).toBe(1);

    // 来源回填：有 key 的取旧 key 名；key_id 为 NULL 的走 '历史会话' 分支
    // （不处理 NULL 的话 '旧 Key：' || NULL 会撞 NOT NULL 约束，整条迁移回滚）
    const rows = db
      .prepare('SELECT id, source_client, source_fingerprint FROM threads ORDER BY id')
      .all() as { id: string; source_client: string; source_fingerprint: string }[];
    expect(rows).toEqual([
      { id: 't1', source_client: '旧 Key：alice', source_fingerprint: 'legacy:t1' },
      { id: 't2', source_client: '历史会话', source_fingerprint: 'legacy:t2' },
    ]);
  });

  it('从 v2 升级到 v3：providers 表与 jobs 转发列就位，老数据保留', () => {
    const db = openDb(':memory:');
    seedLegacyDb(db, 2);

    const now = Date.now();
    db.prepare(
      "INSERT INTO threads (id, protocol, title, created_at, updated_at) VALUES ('t1', 'openai', '老会话', ?, ?)",
    ).run(now, now);
    db.prepare(
      "INSERT INTO messages (id, thread_id, role, content, source, created_at) VALUES ('m1', 't1', 'user', '你好', 'api', ?)",
    ).run(now);
    db.prepare(
      `INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
       VALUES ('j1', 't1', 'openai', 'stream', 'replied', '{}', ?, ?)`,
    ).run(now, now);

    migrate(db);

    expect(tableNames(db)).toContain('providers');
    expect(columnNames(db, 'jobs')).toEqual(
      expect.arrayContaining(['via', 'provider_id', 'forward_error', 'forward_error_at']),
    );
    expect((db.prepare('SELECT COUNT(*) AS c FROM threads').get() as { c: number }).c).toBe(1);
    expect((db.prepare('SELECT COUNT(*) AS c FROM messages').get() as { c: number }).c).toBe(1);
    const job = db.prepare("SELECT status, via, provider_id FROM jobs WHERE id = 'j1'").get() as {
      status: string;
      via: string | null;
      provider_id: string | null;
    };
    expect(job).toEqual({ status: 'replied', via: null, provider_id: null });
  });

  it('迁移只跑一次（版本号已是最新时是空操作）', () => {
    const db = openDb(':memory:');
    migrate(db);
    // 第二次不应因为 key_id 已不存在而报错
    expect(() => migrate(db)).not.toThrow();
  });
});
