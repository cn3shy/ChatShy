import type { FastifyRequest } from 'fastify';
import { callerKeyMatches, getCallerKey } from '../core/settings.js';
import type { Db } from '../db/db.js';

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

/**
 * 校验调用方 Key。全项目只有一个全局 Key，所有调用方共用。
 *
 * 两种头都接受：OpenAI 系客户端习惯 `Authorization: Bearer`（ANTHROPIC_AUTH_TOKEN 走这里），
 * Anthropic 系与 Claude Code 习惯 `x-api-key`。单一 Key 的前提下不必再分优先级——
 * 挨个试，匹配上任一即可，配错头也不会莫名 401。
 */
export function requireCallerKey(db: Db, req: FastifyRequest): boolean {
  const expected = getCallerKey(db);
  // 尚未初始化时拒绝一切，避免"开门"状态
  if (expected === null) return false;
  for (const token of [bearerToken(req), xApiKey(req)]) {
    if (token !== null && callerKeyMatches(expected, token)) return true;
  }
  return false;
}
