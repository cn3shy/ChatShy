import crypto from 'node:crypto';
import type { Db } from '../db/db.js';
import type { Prompt } from '../protocol/types.js';

/**
 * 第三方 AI 厂商配置（OpenAI 兼容 /chat/completions）。
 *
 * 只支持一种上游协议是刻意的：DeepSeek、通义千问（DashScope 兼容模式）、GLM 都提供
 * OpenAI 兼容端点，一套实现即可覆盖；自定义厂商只要兼容该接口就能接入。
 *
 * API Key 明文存库与 caller_key 同属"数据库文件即信任边界"的取舍。管理端接口
 * 只回掩码（见 toPublicProvider），编辑时留空表示沿用旧 Key。
 */

export interface Provider {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  createdAt: number;
  updatedAt: number;
}

/** 管理端可见形状：绝不包含完整 Key */
export interface PublicProvider {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  hasApiKey: boolean;
  apiKeyMasked: string;
  createdAt: number;
  updatedAt: number;
}

interface ProviderRow {
  id: string;
  name: string;
  base_url: string;
  api_key: string;
  model: string;
  created_at: number;
  updated_at: number;
}

function toProvider(row: ProviderRow): Provider {
  return {
    id: row.id,
    name: row.name,
    baseUrl: row.base_url,
    apiKey: row.api_key,
    model: row.model,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export type ProviderValidationCode =
  | 'invalid_name'
  | 'invalid_base_url'
  | 'invalid_model'
  | 'invalid_api_key'
  | 'provider_exists';

export class ProviderValidationError extends Error {
  constructor(
    readonly code: ProviderValidationCode,
    message: string,
  ) {
    super(message);
    this.name = 'ProviderValidationError';
  }
}

export interface ProviderInput {
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
}

/** 编辑时 apiKey 可省略或传空串，表示沿用原 Key */
export interface ProviderPatch {
  name?: string;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
}

const NAME_MAX = 64;
const URL_MAX = 2048;
const MODEL_MAX = 200;
const KEY_MAX = 4096;

function normalizeName(raw: string): string {
  const name = raw.replace(/\s+/g, ' ').trim();
  if (name === '') throw new ProviderValidationError('invalid_name', '厂商名称不能为空');
  if ([...name].length > NAME_MAX) {
    throw new ProviderValidationError('invalid_name', `厂商名称不能超过 ${NAME_MAX} 个字符`);
  }
  return name;
}

/**
 * Base URL 规范化。用户填的是"在 /chat/completions 之前的那一段"，例如：
 *   https://api.deepseek.com/v1
 *   https://dashscope.aliyuncs.com/compatible-mode/v1
 *   https://open.bigmodel.cn/api/paas/v4
 *
 * 容错：尾部斜杠去掉；误把完整 endpoint 粘进来时自动剥掉 /chat/completions，
 * 而不是拼成 /chat/completions/chat/completions。
 */
export function normalizeBaseUrl(raw: string): string {
  const s = raw.trim();
  let url: URL;
  try {
    url = new URL(s);
  } catch {
    throw new ProviderValidationError('invalid_base_url', '接口地址不是合法的 URL');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ProviderValidationError('invalid_base_url', '接口地址只支持 http/https');
  }
  if (url.username !== '' || url.password !== '') {
    throw new ProviderValidationError('invalid_base_url', '接口地址里不能带用户名/密码');
  }
  if (url.search !== '' || url.hash !== '') {
    throw new ProviderValidationError('invalid_base_url', '接口地址里不能带查询参数或锚点');
  }

  let path = url.pathname.replace(/\/+$/, '');
  if (path.toLowerCase().endsWith('/chat/completions')) {
    path = path.slice(0, -'/chat/completions'.length).replace(/\/+$/, '');
  }
  const normalized = `${url.origin}${path}`;
  if (normalized.length > URL_MAX) {
    throw new ProviderValidationError('invalid_base_url', `接口地址不能超过 ${URL_MAX} 个字符`);
  }
  return normalized;
}

function normalizeModel(raw: string): string {
  const model = raw.trim();
  if (model === '') throw new ProviderValidationError('invalid_model', '模型名不能为空');
  if (model.length > MODEL_MAX) {
    throw new ProviderValidationError('invalid_model', `模型名不能超过 ${MODEL_MAX} 个字符`);
  }
  return model;
}

function normalizeApiKey(raw: string): string {
  const key = raw.trim();
  if (key === '') throw new ProviderValidationError('invalid_api_key', 'API Key 不能为空');
  if (key.length > KEY_MAX) {
    throw new ProviderValidationError('invalid_api_key', `API Key 不能超过 ${KEY_MAX} 个字符`);
  }
  return key;
}

function validateInput(input: ProviderInput): ProviderInput {
  return {
    name: normalizeName(input.name),
    baseUrl: normalizeBaseUrl(input.baseUrl),
    apiKey: normalizeApiKey(input.apiKey),
    model: normalizeModel(input.model),
  };
}

function assertNameFree(db: Db, name: string, exceptId?: string): void {
  const row = db.prepare('SELECT id FROM providers WHERE name = ?').get(name) as { id: string } | undefined;
  if (row !== undefined && row.id !== exceptId) {
    throw new ProviderValidationError('provider_exists', `已存在名为「${name}」的厂商配置`);
  }
}

export function listProviders(db: Db): Provider[] {
  return (db.prepare('SELECT * FROM providers ORDER BY created_at ASC, rowid ASC').all() as ProviderRow[]).map(
    toProvider,
  );
}

export function getProvider(db: Db, id: string): Provider | null {
  const row = db.prepare('SELECT * FROM providers WHERE id = ?').get(id) as ProviderRow | undefined;
  return row === undefined ? null : toProvider(row);
}

export function createProvider(db: Db, input: ProviderInput): Provider {
  const v = validateInput(input);
  assertNameFree(db, v.name);
  const now = Date.now();
  const id = crypto.randomUUID();
  db.prepare(
    `INSERT INTO providers (id, name, base_url, api_key, model, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  ).run(id, v.name, v.baseUrl, v.apiKey, v.model, now, now);
  return getProvider(db, id)!;
}

/** 返回 null 表示厂商不存在；字段缺省表示保持不变 */
export function updateProvider(db: Db, id: string, patch: ProviderPatch): Provider | null {
  const current = getProvider(db, id);
  if (current === null) return null;

  const name = patch.name === undefined ? current.name : normalizeName(patch.name);
  const baseUrl = patch.baseUrl === undefined ? current.baseUrl : normalizeBaseUrl(patch.baseUrl);
  const model = patch.model === undefined ? current.model : normalizeModel(patch.model);
  // 空串 = 沿用旧 Key，方便编辑表单不回显完整 Key
  const apiKey = patch.apiKey === undefined || patch.apiKey === '' ? current.apiKey : normalizeApiKey(patch.apiKey);

  assertNameFree(db, name, id);
  db.prepare(
    'UPDATE providers SET name = ?, base_url = ?, api_key = ?, model = ?, updated_at = ? WHERE id = ?',
  ).run(name, baseUrl, apiKey, model, Date.now(), id);
  return getProvider(db, id);
}

export function deleteProvider(db: Db, id: string): boolean {
  return db.prepare('DELETE FROM providers WHERE id = ?').run(id).changes > 0;
}

/** 只暴露末 4 位；短 Key 全部遮住 */
export function maskApiKey(key: string): string {
  if (key === '') return '';
  if (key.length <= 8) return '••••••••';
  return `••••••••${key.slice(-4)}`;
}

export function toPublicProvider(provider: Provider): PublicProvider {
  return {
    id: provider.id,
    name: provider.name,
    baseUrl: provider.baseUrl,
    model: provider.model,
    hasApiKey: provider.apiKey !== '',
    apiKeyMasked: maskApiKey(provider.apiKey),
    createdAt: provider.createdAt,
    updatedAt: provider.updatedAt,
  };
}

// ---- 上游调用 ----

/**
 * 非流式调用的总预算 / 流式调用的空闲预算（连续无数据多久算失败）。
 * 取值低于常见反代的 60 秒读超时，避免"管理端报错、后端随后成功"的错觉。
 */
export const PROVIDER_TIMEOUT_MS = 60_000;

export type ProviderErrorCode =
  | 'upstream_timeout'
  | 'upstream_aborted'
  | 'upstream_unreachable'
  | 'upstream_http_error'
  | 'upstream_bad_response'
  | 'upstream_empty';

export class ProviderError extends Error {
  constructor(
    readonly code: ProviderErrorCode,
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'ProviderError';
  }
}

export interface CallProviderOptions {
  /** 与超时合并；Job 结束（超时/被取代/断连/人工抢先）时用它中止上游 */
  signal?: AbortSignal;
  timeoutMs?: number;
}

function sanitizeErrorText(text: string, max: number): string {
  const cleaned = text
    .replace(/[\p{Cc}\p{Cf}]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned.length <= max ? cleaned : `${cleaned.slice(0, max)}…`;
}

/** 从上游错误体里提炼可读信息，绝不包含请求头/Key */
function upstreamErrorMessage(status: number, body: string): string {
  const text = sanitizeErrorText(body, 4000);
  if (text !== '') {
    try {
      const data = JSON.parse(text) as { error?: unknown; message?: unknown };
      const raw =
        data.error !== null && typeof data.error === 'object'
          ? (data.error as { message?: unknown }).message
          : typeof data.error === 'string'
            ? data.error
            : data.message;
      if (typeof raw === 'string' && raw.trim() !== '') return sanitizeErrorText(raw, 300);
    } catch {
      /* 不是 JSON，直接把截断后的响应体当错误信息 */
    }
  }
  return text === '' ? `HTTP ${status}` : `HTTP ${status}：${sanitizeErrorText(text, 300)}`;
}

/** OpenAI 兼容响应里的 content 可能是字符串，也可能是个 text parts 数组 */
function contentToString(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    const parts: string[] = [];
    for (const part of content) {
      if (part !== null && typeof part === 'object') {
        const p = part as { type?: unknown; text?: unknown };
        if (p.type === 'text' && typeof p.text === 'string') parts.push(p.text);
      }
    }
    return parts.join('');
  }
  return '';
}

export function providerMessages(prompt: Prompt): { role: string; content: string }[] {
  const messages: { role: string; content: string }[] = [];
  if (prompt.system !== undefined && prompt.system !== '') {
    messages.push({ role: 'system', content: prompt.system });
  }
  for (const m of prompt.messages) messages.push({ role: m.role, content: m.content });
  return messages;
}

/** 从非流式响应体里取正文；格式不对/内容为空时抛 ProviderError */
function completionFromJson(text: string): string {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    throw new ProviderError('upstream_bad_response', '上游返回的不是 JSON（检查接口地址是否指向 OpenAI 兼容服务）');
  }

  const choices = (data as { choices?: unknown } | null)?.choices;
  const first = Array.isArray(choices) ? (choices[0] as { message?: { content?: unknown } } | undefined) : undefined;
  const content = contentToString(first?.message?.content);
  if (content.trim() === '') {
    throw new ProviderError('upstream_empty', '上游返回了空内容');
  }
  return content;
}

/** 流式数据块里夹带的错误对象（有的兼容端点用 200 + error 事件报错） */
function upstreamStreamError(data: unknown): string | null {
  if (data === null || typeof data !== 'object') return null;
  const raw = (data as { error?: unknown }).error;
  if (raw === undefined || raw === null) return null;
  const message =
    typeof raw === 'string'
      ? raw
      : typeof (raw as { message?: unknown }).message === 'string'
        ? (raw as { message: string }).message
        : '上游在流式响应中返回错误';
  return sanitizeErrorText(message, 300);
}

/**
 * 调用上游并返回正文。不做自动重试：重试会重复计费，而且自动转发模式下
 * "失败转人工"比"悄悄换一条路"更可预测。
 */
export async function callProvider(
  provider: Provider,
  prompt: Prompt,
  opts: CallProviderOptions = {},
): Promise<string> {
  const timeoutMs = opts.timeoutMs ?? PROVIDER_TIMEOUT_MS;
  const timeoutSignal = AbortSignal.timeout(timeoutMs);
  const signal = opts.signal === undefined ? timeoutSignal : AbortSignal.any([opts.signal, timeoutSignal]);

  let res: Response;
  try {
    res = await fetch(`${provider.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        accept: 'application/json',
        authorization: `Bearer ${provider.apiKey}`,
      },
      body: JSON.stringify({
        model: provider.model,
        messages: providerMessages(prompt),
        stream: false,
      }),
      // 不让带 Authorization 的请求跟随跳转，也避免被重定向到意外主机
      redirect: 'error',
      signal,
    });
  } catch (err) {
    if (signal.aborted) {
      if (timeoutSignal.aborted) {
        throw new ProviderError('upstream_timeout', `上游 ${Math.round(timeoutMs / 1000)} 秒内没有响应`);
      }
      throw new ProviderError('upstream_aborted', '上游调用已中止');
    }
    const detail = err instanceof Error ? sanitizeErrorText(err.message, 200) : '未知错误';
    throw new ProviderError('upstream_unreachable', `无法连接上游：${detail}`);
  }

  if (!res.ok) {
    let body = '';
    try {
      body = await res.text();
    } catch {
      /* 读不出错误体就只报状态码 */
    }
    throw new ProviderError('upstream_http_error', upstreamErrorMessage(res.status, body), res.status);
  }

  let text: string;
  try {
    text = await res.text();
  } catch (err) {
    if (signal.aborted) throw new ProviderError('upstream_aborted', '上游调用已中止');
    const detail = err instanceof Error ? sanitizeErrorText(err.message, 200) : '未知错误';
    throw new ProviderError('upstream_unreachable', `读取上游响应失败：${detail}`);
  }

  return completionFromJson(text);
}

export interface StreamProviderOptions extends CallProviderOptions {
  /** 每收到一段正文就回调一次；调用方负责决定要不要转发给终端用户 */
  onDelta(text: string): void;
}

/**
 * 流式调用上游（OpenAI 兼容 SSE），边读边回调，结束时返回完整正文。
 *
 * 与 callProvider 的两点差异：
 *  - 超时是**空闲超时**：每读到一块数据就重新计时。整段生成的耗时可能远超 60 秒，
 *    但连续 60 秒没有任何数据基本可以断定上游已经卡死；
 *  - 同样不做自动重试（会重复计费）。
 */
export async function callProviderStream(
  provider: Provider,
  prompt: Prompt,
  opts: StreamProviderOptions,
): Promise<string> {
  const timeoutMs = opts.timeoutMs ?? PROVIDER_TIMEOUT_MS;
  const idleController = new AbortController();
  const signal =
    opts.signal === undefined ? idleController.signal : AbortSignal.any([opts.signal, idleController.signal]);

  let idleTimedOut = false;
  let idleTimer: NodeJS.Timeout | null = null;
  const armIdleTimer = (): void => {
    if (idleTimer !== null) clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      idleTimedOut = true;
      idleController.abort();
    }, timeoutMs);
    idleTimer.unref?.();
  };
  const abortError = (): ProviderError =>
    idleTimedOut
      ? new ProviderError('upstream_timeout', `上游 ${Math.round(timeoutMs / 1000)} 秒内没有新数据`)
      : new ProviderError('upstream_aborted', '上游调用已中止');

  armIdleTimer();
  try {
    let res: Response;
    try {
      res = await fetch(`${provider.baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          accept: 'text/event-stream',
          authorization: `Bearer ${provider.apiKey}`,
        },
        body: JSON.stringify({
          model: provider.model,
          messages: providerMessages(prompt),
          stream: true,
        }),
        // 不让带 Authorization 的请求跟随跳转，也避免被重定向到意外主机
        redirect: 'error',
        signal,
      });
    } catch (err) {
      if (signal.aborted) throw abortError();
      const detail = err instanceof Error ? sanitizeErrorText(err.message, 200) : '未知错误';
      throw new ProviderError('upstream_unreachable', `无法连接上游：${detail}`);
    }

    if (!res.ok) {
      let body = '';
      try {
        body = await res.text();
      } catch {
        /* 读不出错误体就只报状态码 */
      }
      throw new ProviderError('upstream_http_error', upstreamErrorMessage(res.status, body), res.status);
    }

    // 有的"兼容"端点会忽略 stream=true 直接回 JSON：按非流式响应解析，再一次性下发
    const contentType = (res.headers.get('content-type') ?? '').toLowerCase();
    if (contentType.includes('application/json')) {
      let text: string;
      try {
        text = await res.text();
      } catch (err) {
        if (signal.aborted) throw abortError();
        const detail = err instanceof Error ? sanitizeErrorText(err.message, 200) : '未知错误';
        throw new ProviderError('upstream_unreachable', `读取上游响应失败：${detail}`);
      }
      const content = completionFromJson(text);
      opts.onDelta(content);
      return content;
    }

    const reader = res.body?.getReader();
    if (reader === undefined) {
      throw new ProviderError('upstream_bad_response', '上游没有返回可读取的响应体');
    }

    const decoder = new TextDecoder();
    let buffer = '';
    let content = '';
    let streamDone = false;

    const handleLine = (rawLine: string): void => {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
      if (!line.startsWith('data:')) return; // 空行 / 注释 / event: 行一律忽略
      const payload = line.slice(5).trim();
      if (payload === '') return;
      if (payload === '[DONE]') {
        streamDone = true;
        return;
      }

      let data: unknown;
      try {
        data = JSON.parse(payload);
      } catch {
        throw new ProviderError('upstream_bad_response', '上游流式响应的数据不是合法 JSON');
      }
      const errorMessage = upstreamStreamError(data);
      if (errorMessage !== null) {
        throw new ProviderError('upstream_http_error', errorMessage);
      }

      const choices = (data as { choices?: unknown } | null)?.choices;
      const first = Array.isArray(choices)
        ? (choices[0] as { delta?: { content?: unknown } } | undefined)
        : undefined;
      const text = contentToString(first?.delta?.content);
      if (text !== '') {
        content += text;
        opts.onDelta(text);
      }
    };

    try {
      while (!streamDone) {
        const { done, value } = await reader.read();
        if (done) break;
        armIdleTimer();
        buffer += decoder.decode(value, { stream: true });
        let newline = buffer.indexOf('\n');
        while (!streamDone && newline >= 0) {
          const line = buffer.slice(0, newline);
          buffer = buffer.slice(newline + 1);
          handleLine(line);
          newline = buffer.indexOf('\n');
        }
      }
      if (!streamDone && buffer !== '') {
        buffer += decoder.decode();
        handleLine(buffer);
      }
    } catch (err) {
      if (err instanceof ProviderError) throw err;
      if (signal.aborted) throw abortError();
      const detail = err instanceof Error ? sanitizeErrorText(err.message, 200) : '未知错误';
      throw new ProviderError('upstream_unreachable', `读取上游流式响应失败：${detail}`);
    } finally {
      // 见到 [DONE] 后主动放掉连接，不等上游自己关
      if (streamDone) void reader.cancel().catch(() => undefined);
    }

    if (content.trim() === '') {
      throw new ProviderError('upstream_empty', '上游返回了空内容');
    }
    return content;
  } finally {
    if (idleTimer !== null) clearTimeout(idleTimer);
  }
}
