import crypto from 'node:crypto';
import type { FastifyRequest } from 'fastify';

/**
 * 调用方自报身份的头名。转发给要用的人时，让他在客户端的「自定义请求头」里加上
 * `X-ChatShy-Caller: alice`（中文名要写成百分号编码，见 parseCaller），
 * 操作台就会按这个名字标记来源。
 *
 * 为什么需要它：本服务通常挂在 Nginx 后面，若反代没转发真实 IP，所有调用方的
 * req.ip 都是 127.0.0.1，来源就退化成同一个值——两个都用 Cherry Studio 的人会被
 * 并进同一条会话。这个头把身份从"网络位置"换成"调用方自己声明的名字"，与 IP、
 * 与网络切换都无关。没带这个头时，行为与从前完全一致（IP + UA 指纹）。
 */
export const CALLER_HEADER = 'x-chatshy-caller';

/** 名字长度上限：防止有人拿超长字符串把会话列表撑爆 */
const CALLER_MAX_LEN = 40;

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
  /** X-ChatShy-Caller 自报的名字；没带则空串 */
  caller: string;
  /** 展示名：带了身份头就是那个名字，否则是 UA 识别出的可读客户端名 */
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
 * 带身份头的调用方：指纹只由这个名字决定，**与 IP 无关**——这正是这个头存在的意义，
 * 同一个人从 Wi-Fi 切到 4G、开开关关代理，会话都不该被切成两段。
 *
 * 没带身份头时沿用原公式：身份串优先用归一化的客户端名（跨版本稳定），只在识别不出
 * 具体产品时才回落到原始 UA —— 那种情况下 UA 反而是唯一的区分依据（浏览器、curl、
 * 各种脚本，它们的版本变化慢得多）。
 *
 * ⚠️ 后半段公式是落库常量：老行的指纹已经写死在库里。改动它等于让所有会话 fork 一次，
 * 必须配一次数据迁移。caller 的默认值保证了三参数调用与旧行为逐字节一致。
 */
export function fingerprintOf(ip: string, ua: string, client: string, caller = ''): string {
  // 带身份头的分支产出 caller:<hex>，**不含空格**；而下面那条老公式恒为 "ip 身份串"，
  // 中间必有一个空格（UA 缺失时 ip 后也留一个）。两者结构互斥，永远算不出同一个串，
  // 所以不需要在分隔符上做特殊处理。
  const identity =
    caller !== ''
      ? `caller:${crypto.createHash('sha256').update(caller, 'utf8').digest('hex')}`
      : `${ip} ${STABLE_IDENTITY.has(client) ? client : ua}`;
  return crypto.createHash('sha256').update(identity, 'utf8').digest('hex').slice(0, 32);
}

/**
 * 取 IPv6 映射的 IPv4 还原成点分写法（Fastify 在双栈监听下可能给出 ::ffff:127.0.0.1）。
 */
export function normalizeIp(ip: string): string {
  const m = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(ip.trim());
  return m !== null ? m[1]! : ip.trim();
}

/**
 * 连接对端是否来自本机/内网（即反向代理所在的位置），用于决定能否采信 X-Real-IP。
 * 只认得 RFC1918、回环、链路本地与 IPv6 ULA 这几类；其余一律当公网看待。
 */
function isPrivateOrLoopback(ip: string): boolean {
  if (ip === '::1' || ip === '127.0.0.1') return true;
  if (/^(fc|fd|fe80)/i.test(ip)) return true; // IPv6 ULA 与链路本地
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip);
  if (m === null) return false;
  const a = Number(m[1]);
  const b = Number(m[2]);
  return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
}

function firstHeader(req: FastifyRequest, name: string): string | null {
  const raw = req.headers[name];
  const value = Array.isArray(raw) ? raw[0] : raw;
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

/**
 * 客户端 IP。
 *
 * Fastify 的 trustProxy 只认 X-Forwarded-For，**不认 X-Real-IP**——而 Nginx 配置里只写
 * `proxy_set_header X-Real-IP $remote_addr;` 是极常见的写法，那样子 req.ip 会一直停在
 * 反代自己的地址（127.0.0.1）。所以这里补一条回退。
 *
 * 回退只在连接对端确实是本机/内网时生效：公网客户端直接打过来的请求，对端是公网地址，
 * 它再怎么伪造 X-Real-IP 也不会被采信。
 */
export function clientIpOf(req: FastifyRequest): string {
  const peer = normalizeIp(req.ip);
  if (isPrivateOrLoopback(peer)) {
    const real = firstHeader(req, 'x-real-ip');
    if (real !== null) return normalizeIp(real);
  }
  return peer;
}

/**
 * 还原头值里的中文。
 *
 * HTTP 头值按规范只允许 latin-1 字节，所以「张三」这个名字会以两种形态到达，
 * 取决于调用方用的客户端能不能自己编码：
 *  - 直接发 UTF-8 字节（curl、Python requests、Go 这类）：Node 按 latin-1 解出来是
 *    乱码「å¼ ä¸‰」，这里按 latin-1 收回字节再按 UTF-8 解一次；
 *  - 只能发 ASCII（Node/undici、浏览器 fetch 会直接拒绝非 latin-1 字符）：发的是
 *    百分号编码 %E5%BC%A0%E4%B8%89，这里解回来。
 *
 * 两条路都归一到同一个名字，正是为了让两种客户端配置到同一条会话上。
 */
function decodeHeaderText(raw: string): string {
  if (/%[0-9a-fA-F]{2}/.test(raw)) {
    try {
      return decodeURIComponent(raw);
    } catch {
      /* 不是合法的百分号编码（比如名字里真有个 %），按原样用 */
    }
  }
  if (allCodePointsBelow(raw, 0x7f)) return raw; // 纯 ASCII，没有可还原的东西
  // 出现 >U+00FF 的字符，说明拿到的已经是解好的 Unicode 而不是头的原始字节——
  // 此时再按 latin-1 收字节只会把它截断（「张」U+5F20 会被压成 0x20 空格）
  if (!allCodePointsBelow(raw, 0xff)) return raw;
  return Buffer.from(raw, 'latin1').toString('utf8');
}

function allCodePointsBelow(s: string, max: number): boolean {
  for (const ch of s) {
    if ((ch.codePointAt(0) ?? 0) > max) return false;
  }
  return true;
}

/** 清洗调用方自报的名字：控制字符（含换行）会让展示位排版错乱，超长的直接截断 */
export function parseCaller(raw: string | null): string {
  if (raw === null) return '';
  const cleaned = decodeHeaderText(raw)
    .replace(/[\p{Cc}\p{Cf}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
  // 按码点截断，别把 emoji 的代理对劈成半个字符
  return [...cleaned].slice(0, CALLER_MAX_LEN).join('');
}

/** 从请求中提取来源。req.ip 已由 app.ts 的 trustProxy 处理过反向代理的真实 IP。 */
export function requestSource(req: FastifyRequest): RequestSource {
  const ip = clientIpOf(req);
  const ua = firstHeader(req, 'user-agent') ?? '';
  const caller = parseCaller(firstHeader(req, CALLER_HEADER));
  const client = caller !== '' ? caller : detectClient(ua);
  return { ip, ua, caller, client, fingerprint: fingerprintOf(ip, ua, client, caller) };
}

function truncate(s: string, max: number): string {
  return s.length <= max ? s : `${s.slice(0, max - 1)}…`;
}
