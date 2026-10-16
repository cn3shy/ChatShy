import { describe, expect, it } from 'vitest';
import { openDb } from '../../src/db/db.js';
import { SCHEMA_VERSION, migrate } from '../../src/db/migrate.js';

function tableNames(db: ReturnType<typeof openDb>): string[] {
  return (db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all() as { name: string }[]).map(
    (r) => r.name,
  );
}

describe('migrate', () => {
  it('创建全部表，且可重复执行', () => {
    const db = openDb(':memory:');
    migrate(db);
    migrate(db);

    expect(tableNames(db)).toEqual(
      expect.arrayContaining(['keys', 'threads', 'messages', 'jobs', 'settings', 'schema_version']),
    );
    const row = db.prepare('SELECT version FROM schema_version').get() as { version: number };
    expect(row.version).toBe(SCHEMA_VERSION);
  });

  it('把遗留的 pending job 标记为 dropped（重启语义）', () => {
    const db = openDb(':memory:');
    migrate(db);
    const now = Date.now();
    db.prepare("INSERT INTO keys (id, name, key_hash, key_prefix, created_at) VALUES ('k1', 't', 'h', 'p', ?)").run(
      now,
    );
    db.prepare(
      "INSERT INTO threads (id, key_id, protocol, created_at, updated_at) VALUES ('t1', 'k1', 'openai', ?, ?)",
    ).run(now, now);
    db.prepare(
      `INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
       VALUES ('j1', 't1', 'openai', 'stream', 'pending', '{}', ?, ?)`,
    ).run(now, now);

    migrate(db);

    const job = db.prepare("SELECT status FROM jobs WHERE id = 'j1'").get() as { status: string };
    expect(job.status).toBe('dropped');
  });
});
