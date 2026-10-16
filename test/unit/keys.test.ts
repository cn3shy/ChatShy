import { describe, expect, it } from 'vitest';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';
import {
  API_KEY_PREFIX,
  countTodayJobs,
  deleteKey,
  generateKey,
  hashKey,
  listKeys,
  updateKey,
  verifyKey,
} from '../../src/core/keys.js';

function freshDb(): Db {
  const db = openDb(':memory:');
  migrate(db);
  return db;
}

describe('KeyStore', () => {
  it('生成：明文带前缀、库里只存哈希、展示前缀打码', () => {
    const db = freshDb();
    const { record, plaintext } = generateKey(db, '小伙伴');

    expect(plaintext.startsWith(API_KEY_PREFIX)).toBe(true);
    expect(record.keyHash).toBe(hashKey(plaintext));
    expect(record.keyPrefix.startsWith(API_KEY_PREFIX)).toBe(true);
    expect(record.keyPrefix.endsWith('…')).toBe(true);
    expect(record.enabled).toBe(true);
    expect(record.maxPending).toBe(5);

    const raw = db.prepare('SELECT * FROM keys WHERE id = ?').get(record.id) as Record<string, unknown>;
    expect(Object.values(raw)).not.toContain(plaintext);
  });

  it('校验：正确通过、错误为 null、禁用后为 null', () => {
    const db = freshDb();
    const { record, plaintext } = generateKey(db, 'a');

    expect(verifyKey(db, plaintext)?.id).toBe(record.id);
    expect(verifyKey(db, 'sk-chatshy-not-exist')).toBeNull();

    updateKey(db, record.id, { enabled: false });
    expect(verifyKey(db, plaintext)).toBeNull();
  });

  it('校验成功会更新 last_used_at', () => {
    const db = freshDb();
    const { record, plaintext } = generateKey(db, 'a');
    expect(record.lastUsedAt).toBeNull();

    verifyKey(db, plaintext);

    const after = listKeys(db).find((k) => k.id === record.id)!;
    expect(after.lastUsedAt).not.toBeNull();
  });

  it('countTodayJobs 只统计 dayStart 之后的请求', () => {
    const db = freshDb();
    const { record } = generateKey(db, 'a');
    const now = Date.now();
    const dayStart = now - 60_000;

    db.prepare(
      "INSERT INTO threads (id, key_id, protocol, created_at, updated_at) VALUES ('t1', ?, 'openai', ?, ?)",
    ).run(record.id, now, now);
    const insertJob = db.prepare(
      `INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
       VALUES (?, 't1', 'openai', 'non_stream', 'replied', '{}', ?, ?)`,
    );
    insertJob.run('j1', now, now); // 今天
    insertJob.run('j2', now, now); // 今天
    insertJob.run('j3', dayStart - 1, dayStart - 1); // 昨天

    expect(countTodayJobs(db, record.id, dayStart)).toBe(2);
  });

  it('删除后查不到了', () => {
    const db = freshDb();
    const { record } = generateKey(db, 'a');
    expect(deleteKey(db, record.id)).toBe(true);
    expect(deleteKey(db, record.id)).toBe(false);
    expect(listKeys(db)).toHaveLength(0);
  });
});
