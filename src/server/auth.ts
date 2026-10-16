import type { FastifyRequest } from 'fastify';
import type { Db } from '../db/db.js';
import type { JobQueue } from '../core/jobs.js';
import { countTodayJobs, verifyKey, type KeyRecord } from '../core/keys.js';

/** OpenAI 协议：Authorization: Bearer <key> */
export function getOpenAIKey(db: Db, req: FastifyRequest): KeyRecord | null {
  const h = req.headers.authorization;
  if (typeof h !== 'string' || !h.startsWith('Bearer ')) return null;
  const token = h.slice('Bearer '.length).trim();
  return token === '' ? null : verifyKey(db, token);
}

/** Anthropic 协议：x-api-key: <key>（anthropic-version 忽略） */
export function getAnthropicKey(db: Db, req: FastifyRequest): KeyRecord | null {
  const h = req.headers['x-api-key'];
  const token = Array.isArray(h) ? h[0] : h;
  return typeof token === 'string' && token !== '' ? verifyKey(db, token) : null;
}

/** 当天 0 点（服务器本地时区）的毫秒时间戳，用于日限额统计 */
export function startOfTodayMs(now = Date.now()): number {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export type LimitViolation = 'quota' | 'pending' | null;

/** 校验顺序：日限额 → 挂起并发上限（设计 §5.3） */
export function checkKeyLimits(db: Db, queue: JobQueue, key: KeyRecord): LimitViolation {
  if (key.dailyLimit !== null && countTodayJobs(db, key.id, startOfTodayMs()) >= key.dailyLimit) {
    return 'quota';
  }
  if (queue.countPendingForKey(key.id) >= key.maxPending) {
    return 'pending';
  }
  return null;
}
