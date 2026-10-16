import crypto from 'node:crypto';
import type { Db } from '../db/db.js';

export const API_KEY_PREFIX = 'sk-chatshy-';

export interface KeyRecord {
  id: string;
  name: string;
  keyHash: string;
  keyPrefix: string;
  enabled: boolean;
  timeoutMs: number | null;
  dailyLimit: number | null;
  maxPending: number;
  createdAt: number;
  lastUsedAt: number | null;
}

interface KeyRow {
  id: string;
  name: string;
  key_hash: string;
  key_prefix: string;
  enabled: number;
  timeout_ms: number | null;
  daily_limit: number | null;
  max_pending: number;
  created_at: number;
  last_used_at: number | null;
}

function rowToRecord(row: KeyRow): KeyRecord {
  return {
    id: row.id,
    name: row.name,
    keyHash: row.key_hash,
    keyPrefix: row.key_prefix,
    enabled: row.enabled === 1,
    timeoutMs: row.timeout_ms,
    dailyLimit: row.daily_limit,
    maxPending: row.max_pending,
    createdAt: row.created_at,
    lastUsedAt: row.last_used_at,
  };
}

export function hashKey(plaintext: string): string {
  return crypto.createHash('sha256').update(plaintext, 'utf8').digest('hex');
}

export interface GenerateKeyOptions {
  timeoutMs?: number | null;
  dailyLimit?: number | null;
  maxPending?: number;
}

/** 生成新 key：明文只在此处返回一次，库里只存哈希与展示前缀 */
export function generateKey(
  db: Db,
  name: string,
  opts: GenerateKeyOptions = {},
): { record: KeyRecord; plaintext: string } {
  const plaintext = API_KEY_PREFIX + crypto.randomBytes(32).toString('base64url');
  const id = crypto.randomUUID();
  const now = Date.now();
  db.prepare(
    `INSERT INTO keys (id, name, key_hash, key_prefix, enabled, timeout_ms, daily_limit, max_pending, created_at, last_used_at)
     VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, NULL)`,
  ).run(
    id,
    name,
    hashKey(plaintext),
    `${plaintext.slice(0, API_KEY_PREFIX.length + 4)}…`,
    opts.timeoutMs ?? null,
    opts.dailyLimit ?? null,
    opts.maxPending ?? 5,
    now,
  );
  const record = getKey(db, id);
  if (record === null) throw new Error('key 插入失败');
  return { record, plaintext };
}

export function getKey(db: Db, id: string): KeyRecord | null {
  const row = db.prepare('SELECT * FROM keys WHERE id = ?').get(id) as KeyRow | undefined;
  return row === undefined ? null : rowToRecord(row);
}

export function listKeys(db: Db): KeyRecord[] {
  return (db.prepare('SELECT * FROM keys ORDER BY created_at DESC').all() as KeyRow[]).map(rowToRecord);
}

/** 校验明文 key；无效或已禁用返回 null；成功时更新 last_used_at */
export function verifyKey(db: Db, plaintext: string): KeyRecord | null {
  const row = db.prepare('SELECT * FROM keys WHERE key_hash = ?').get(hashKey(plaintext)) as KeyRow | undefined;
  if (row === undefined || row.enabled !== 1) return null;
  const now = Date.now();
  db.prepare('UPDATE keys SET last_used_at = ? WHERE id = ?').run(now, row.id);
  return rowToRecord({ ...row, last_used_at: now });
}

export interface KeyPatch {
  name?: string;
  enabled?: boolean;
  timeoutMs?: number | null;
  dailyLimit?: number | null;
  maxPending?: number;
}

export function updateKey(db: Db, id: string, patch: KeyPatch): KeyRecord | null {
  const existing = getKey(db, id);
  if (existing === null) return null;
  db.prepare(
    'UPDATE keys SET name = ?, enabled = ?, timeout_ms = ?, daily_limit = ?, max_pending = ? WHERE id = ?',
  ).run(
    patch.name ?? existing.name,
    (patch.enabled ?? existing.enabled) ? 1 : 0,
    patch.timeoutMs !== undefined ? patch.timeoutMs : existing.timeoutMs,
    patch.dailyLimit !== undefined ? patch.dailyLimit : existing.dailyLimit,
    patch.maxPending ?? existing.maxPending,
    id,
  );
  return getKey(db, id);
}

export function deleteKey(db: Db, id: string): boolean {
  return db.prepare('DELETE FROM keys WHERE id = ?').run(id).changes > 0;
}

/** 该 key 从 dayStartMs（含）起创建的请求数，用于日限额 */
export function countTodayJobs(db: Db, keyId: string, dayStartMs: number): number {
  const row = db
    .prepare(
      'SELECT COUNT(*) AS c FROM jobs j JOIN threads t ON j.thread_id = t.id WHERE t.key_id = ? AND j.created_at >= ?',
    )
    .get(keyId, dayStartMs) as { c: number };
  return row.c;
}
