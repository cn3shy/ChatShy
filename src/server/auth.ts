import type { FastifyRequest } from 'fastify';
import type { Db } from '../db/db.js';
import type { JobQueue } from '../core/jobs.js';
import { countTodayJobs, verifyKey, type KeyRecord } from '../core/keys.js';

function bearerToken(req: FastifyRequest): string | null {
  const h = req.headers.authorization;
  if (typeof h !== 'string' || !h.startsWith('Bearer ')) return null;
  const token = h.slice('Bearer '.length).trim();
  return token === '' ? null : token;
}

function xApiKey(req: FastifyRequest): string | null {
  const h = req.headers['x-api-key'];
  const token = Array.isArray(h) ? h[0] : h;
  return typeof token === 'string' && token !== '' ? token : null;
}

/** OpenAI 端点：优先 Authorization: Bearer，其次 x-api-key（兼容 Claude Code 等只发 x-api-key 的探测） */
export function getOpenAIKey(db: Db, req: FastifyRequest): KeyRecord | null {
  const token = bearerToken(req) ?? xApiKey(req);
  return token === null ? null : verifyKey(db, token);
}

/** Anthropic 端点：优先 x-api-key，其次 Authorization: Bearer（兼容 ANTHROPIC_AUTH_TOKEN 的用法） */
export function getAnthropicKey(db: Db, req: FastifyRequest): KeyRecord | null {
  const token = xApiKey(req) ?? bearerToken(req);
  return token === null ? null : verifyKey(db, token);
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
