import crypto from 'node:crypto';
import type { Db } from '../db/db.js';

export const SETTING_DEFAULT_TIMEOUT_MS = 'default_timeout_ms';
export const SETTING_MODEL_NAME = 'model_name';
export const SETTING_ADMIN_PASSWORD_HASH = 'admin_password_hash';
export const SETTING_CALLER_KEY = 'caller_key';

/** 调用方 Key 前缀，与 OpenAI 生态的习惯保持一致 */
export const CALLER_KEY_PREFIX = 'sk-chatshy-';

export function getSetting(db: Db, key: string): string | null {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(key) as { value: string } | undefined;
  return row === undefined ? null : row.value;
}

export function setSetting(db: Db, key: string, value: string): void {
  db.prepare(
    'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value',
  ).run(key, value);
}

/** 首次启动用环境变量播种默认值；已有值不覆盖 */
export function initSettings(db: Db, seed: { defaultTimeoutMs: number; modelName: string }): void {
  db.prepare('INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)').run(
    SETTING_DEFAULT_TIMEOUT_MS,
    String(seed.defaultTimeoutMs),
  );
  db.prepare('INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)').run(SETTING_MODEL_NAME, seed.modelName);
}

export function getDefaultTimeoutMs(db: Db, fallback = 180_000): number {
  const raw = getSetting(db, SETTING_DEFAULT_TIMEOUT_MS);
  const n = raw === null ? Number.NaN : Number.parseInt(raw, 10);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export function getModelName(db: Db, fallback = 'chatshy'): string {
  return getSetting(db, SETTING_MODEL_NAME) ?? fallback;
}

// ---- 调用方 Key（全局唯一，所有调用方共用）----
//
// 明文存储是刻意的：设置页需要随时查看/复制发给调用方。若存哈希，就只能像旧的多 Key
// 体系那样"生成时显示一次"，一旦分发出去的配置丢了，就得重新生成并让所有人改配置。
// 数据库文件本身就是本项目的信任边界（管理员密码哈希也在同一处）。

export function getCallerKey(db: Db): string | null {
  return getSetting(db, SETTING_CALLER_KEY);
}

/** 重新生成全局 Key 并返回。旧 Key 立即失效。 */
export function regenerateCallerKey(db: Db): string {
  const key = CALLER_KEY_PREFIX + crypto.randomBytes(32).toString('base64url');
  setSetting(db, SETTING_CALLER_KEY, key);
  return key;
}

export interface CallerKeyBootstrap {
  source: 'env' | 'generated' | 'existing';
  /** 仅 source === 'generated' 时非空，供启动日志打印一次 */
  key: string | null;
}

/** 确保存在全局 Key：已有则不动；否则用环境变量播种或随机生成 */
export function ensureCallerKey(db: Db, fromEnv: string | null): CallerKeyBootstrap {
  if (getCallerKey(db) !== null) return { source: 'existing', key: null };
  if (fromEnv !== null && fromEnv.length > 0) {
    setSetting(db, SETTING_CALLER_KEY, fromEnv);
    return { source: 'env', key: null };
  }
  return { source: 'generated', key: regenerateCallerKey(db) };
}

/**
 * 恒定时间比较。先各自哈希再比，这样两边的长度恒等（32 字节），
 * 不会因为提前返回而泄漏 key 的长度信息。
 */
export function callerKeyMatches(expected: string, provided: string): boolean {
  const digest = (s: string): Buffer => crypto.createHash('sha256').update(s, 'utf8').digest();
  return crypto.timingSafeEqual(digest(expected), digest(provided));
}

// ---- 密码哈希：Node 内置 scrypt，无原生依赖 ----

const SCRYPT_N = 16_384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const KEY_LEN = 64;

export function hashPassword(password: string): string {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, KEY_LEN, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P });
  return `scrypt$${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt.toString('hex')}$${hash.toString('hex')}`;
}

export function verifyPassword(password: string, stored: string): boolean {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const n = Number.parseInt(parts[1]!, 10);
  const r = Number.parseInt(parts[2]!, 10);
  const p = Number.parseInt(parts[3]!, 10);
  if (!Number.isFinite(n) || !Number.isFinite(r) || !Number.isFinite(p)) return false;
  let expected: Buffer;
  try {
    expected = Buffer.from(parts[5]!, 'hex');
  } catch {
    return false;
  }
  if (expected.length === 0) return false;
  let actual: Buffer;
  try {
    actual = crypto.scryptSync(password, Buffer.from(parts[4]!, 'hex'), expected.length, { N: n, r, p });
  } catch {
    return false;
  }
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

export function setAdminPassword(db: Db, password: string): void {
  setSetting(db, SETTING_ADMIN_PASSWORD_HASH, hashPassword(password));
}

export function verifyAdminPassword(db: Db, password: string): boolean {
  const stored = getSetting(db, SETTING_ADMIN_PASSWORD_HASH);
  return stored !== null && verifyPassword(password, stored);
}

export interface AdminBootstrap {
  source: 'env' | 'generated' | null;
  /** 仅 source === 'generated' 时非空，供启动日志打印一次 */
  password: string | null;
}

/** 确保存在管理员密码：已有哈希则不动；否则用环境变量播种或随机生成 */
export function ensureAdminPassword(db: Db, fromEnv: string | null): AdminBootstrap {
  if (getSetting(db, SETTING_ADMIN_PASSWORD_HASH) !== null) return { source: null, password: null };
  if (fromEnv !== null && fromEnv.length > 0) {
    setAdminPassword(db, fromEnv);
    return { source: 'env', password: null };
  }
  const password = crypto.randomBytes(12).toString('base64url');
  setAdminPassword(db, password);
  return { source: 'generated', password };
}
