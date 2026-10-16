import crypto from 'node:crypto';
import type { FastifyRequest } from 'fastify';

/**
 * 请求来源（设计：多 Key 体系移除后，来源接替 key 成为调用方身份标识）。
 *
 * 来源同时承担两个职责：
 * 1. 展示——操作台在会话上标注"谁在说话"；
 * 2. 会话归并作用域——resolveThread 按 fingerprint 限定候选集，避免不同调用方的
 *    对话被并成一条（Claude Code 的 system 提示词对所有用户几乎一致，内容指纹
 *    兜底碰撞概率很高，必须隔离）。
 */
export interface RequestSource {
  ip: string;
  ua: string;
  /** UA 识别出的可读客户端名 */
  client: string;
  /** 会话归并的身份标识，见 fingerprintOf */
  fingerprint: string;
}

/**
 * UA → 可读客户端名。顺序敏感：特征越明确的越靠前。
 *
 * 各家客户端的真实 UA 字符串会随版本变化，这里是启发式匹配；匹配不上时回落到
 * 展示原始 UA（见末尾），而不是丢掉信息。接入新客户端后按实际抓到的 UA 补一条即可。
 */
export function detectClient(ua: string): string {
  const s = ua.trim();
  if (s === '') return '未知来源';

  // 顺序敏感：先匹配特征最明确的
  if (/claude[-_ ]?(cli|code)/i.test(s)) return 'Claude Code';
  if (/cherry\s*studio/i.test(s)) return 'Cherry Studio';
  if (/chatbox/i.test(s)) return 'ChatBox';
  if (/lobe/i.test(s)) return 'LobeChat';
  if (/openai\/python|openai-python/i.test(s)) return 'OpenAI Python SDK';
  if (/openai\/node/i.test(s)) return 'OpenAI Node SDK';
  if (/anthropic\/python|anthropic-sdk-python/i.test(s)) return 'Anthropic Python SDK';
  if (/anthropic\/node/i.test(s)) return 'Anthropic Node SDK';
  if (/^curl\//i.test(s)) return 'curl';
  if (/python-requests|python-httpx|httpx|aiohttp|python-urllib/i.test(s)) return 'Python 脚本';
  if (/axios|node-fetch|undici|got\s*\(|okhttp/i.test(s)) return '脚本客户端';
  if (/postman/i.test(s)) return 'Postman';
  if (/insomnia/i.test(s)) return 'Insomnia';
  if (/mozilla\/5\.0|applewebkit|gecko/i.test(s)) return '浏览器';

  return `其他（${truncate(s, 40)}）`;
}

/**
 * 这些客户端名足以标识一类调用方，且它们的 UA 里带版本号（如 claude-cli/2.0.24）——
 * 客户端一升级 UA 就变。若指纹直接用原始 UA，这位调用方每次升级后会话都会被切段，
 * 而 Claude Code 这类工具是自动更新的，属于会周期性发生的事故。
 */
const STABLE_IDENTITY = new Set([
  'Claude Code',
  'Cherry Studio',
  'ChatBox',
  'LobeChat',
  'OpenAI Python SDK',
  'OpenAI Node SDK',
  'Anthropic Python SDK',
  'Anthropic Node SDK',
]);

/**
 * 来源指纹：会话归并的身份标识。
 *
 * 身份串优先用归一化的客户端名（跨版本稳定），只在识别不出具体产品时才回落到原始 UA ——
 * 那种情况下 UA 反而是唯一的区分依据（浏览器、curl、各种脚本，它们的版本变化慢得多）。
 *
 * ⚠️ 这个公式是落库常量：老行的指纹已经写死在库里。改动它等于让所有会话 fork 一次，
 * 必须配一次数据迁移。
 */
export function fingerprintOf(ip: string, ua: string, client: string): string {
  const identity = STABLE_IDENTITY.has(client) ? client : ua;
  return crypto.createHash('sha256').update(`${ip} ${identity}`, 'utf8').digest('hex').slice(0, 32);
}

/**
 * 取 IPv6 映射的 IPv4 还原成点分写法（Fastify 在双栈监听下可能给出 ::ffff:127.0.0.1）。
 */
export function normalizeIp(ip: string): string {
  const m = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(ip.trim());
  return m !== null ? m[1]! : ip.trim();
}

/** 从请求中提取来源。req.ip 已由 app.ts 的 trustProxy 处理过反向代理的真实 IP。 */
export function requestSource(req: FastifyRequest): RequestSource {
  const ip = normalizeIp(req.ip);
  const rawUa = req.headers['user-agent'];
  const ua = (Array.isArray(rawUa) ? rawUa[0] : rawUa) ?? '';
  const client = detectClient(ua);
  return { ip, ua, client, fingerprint: fingerprintOf(ip, ua, client) };
}

function truncate(s: string, max: number): string {
  return s.length <= max ? s : `${s.slice(0, max - 1)}…`;
}
