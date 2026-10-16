import crypto from 'node:crypto';
import type { ChatMsg, ParseResult, Prompt } from './types.js';

/**
 * OpenAI /v1/chat/completions 协议适配（设计 §4.2）。
 * 纯函数：解析请求为内部 Prompt；把回复渲染成 JSON 或 SSE 事件帧。
 */

export interface OpenAIParsedRequest {
  prompt: Prompt;
  stream: boolean;
  includeUsage: boolean;
}

const unsupportedPart = (type: string): string => `[不支持的部件: ${type}]`;

function contentToString(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    const parts: string[] = [];
    for (const part of content) {
      if (part !== null && typeof part === 'object' && typeof (part as { type?: unknown }).type === 'string') {
        const p = part as { type: string; text?: unknown };
        if (p.type === 'text' && typeof p.text === 'string') parts.push(p.text);
        else parts.push(unsupportedPart(p.type));
      } else {
        parts.push(unsupportedPart('unknown'));
      }
    }
    return parts.join('');
  }
  if (content === null || content === undefined) return '';
  return String(content);
}

export function parseOpenAIRequest(body: unknown): ParseResult<OpenAIParsedRequest> {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    return { ok: false, message: 'Invalid request body' };
  }
  const b = body as Record<string, unknown>;
  if (!Array.isArray(b.messages) || b.messages.length === 0) {
    return { ok: false, message: "'messages' must be a non-empty array" };
  }

  const systemParts: string[] = [];
  const messages: ChatMsg[] = [];

  for (const raw of b.messages) {
    if (raw === null || typeof raw !== 'object') return { ok: false, message: 'Invalid message entry' };
    const m = raw as { role?: unknown; content?: unknown; tool_calls?: unknown };
    const role = typeof m.role === 'string' ? m.role : '';
    let content = contentToString(m.content);
    if (content === '' && Array.isArray(m.tool_calls) && m.tool_calls.length > 0) {
      content = unsupportedPart('tool_calls');
    }
    switch (role) {
      case 'system':
      case 'developer':
        systemParts.push(content);
        break;
      case 'assistant':
        messages.push({ role: 'assistant', content });
        break;
      case 'user':
      case 'tool':
      case 'function':
        messages.push({ role: 'user', content });
        break;
      default:
        return { ok: false, message: `Unsupported message role: ${role === '' ? '(missing)' : role}` };
    }
  }

  const streamOptions = b.stream_options;
  const includeUsage =
    streamOptions !== null &&
    typeof streamOptions === 'object' &&
    (streamOptions as { include_usage?: unknown }).include_usage === true;

  return {
    ok: true,
    value: {
      prompt: {
        model: typeof b.model === 'string' ? b.model : '',
        system: systemParts.length > 0 ? systemParts.join('\n\n') : undefined,
        messages,
      },
      stream: b.stream === true,
      includeUsage,
    },
  };
}

// ---- 渲染 ----

export function newCompletionId(): string {
  return `chatcmpl-${crypto.randomBytes(12).toString('hex')}`;
}

export function sseFrame(data: unknown): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

export const SSE_DONE = 'data: [DONE]\n\n';

export interface CompletionParams {
  id: string;
  /** Unix 秒 */
  created: number;
  model: string;
  content: string;
  promptTokens: number;
  completionTokens: number;
  /** 流式是否在结尾附带 usage 块（stream_options.include_usage） */
  includeUsage: boolean;
}

function usageOf(p: CompletionParams): { prompt_tokens: number; completion_tokens: number; total_tokens: number } {
  return {
    prompt_tokens: p.promptTokens,
    completion_tokens: p.completionTokens,
    total_tokens: p.promptTokens + p.completionTokens,
  };
}

function chunk(p: CompletionParams, choices: unknown[]): object {
  return { id: p.id, object: 'chat.completion.chunk', created: p.created, model: p.model, choices };
}

export function renderCompletion(p: CompletionParams): object {
  return {
    id: p.id,
    object: 'chat.completion',
    created: p.created,
    model: p.model,
    choices: [
      {
        index: 0,
        message: { role: 'assistant', content: p.content },
        finish_reason: 'stop',
      },
    ],
    usage: usageOf(p),
  };
}

/** 流式空档期心跳：SSE 注释行，被所有客户端解析器忽略 */
export const SSE_PING_COMMENT = ': ping\n\n';

/** 连接建立后立即发送的首帧（role delta），让客户端确认服务已响应 */
export function completionRoleFrame(p: { id: string; created: number; model: string }): string {
  return sseFrame({
    id: p.id,
    object: 'chat.completion.chunk',
    created: p.created,
    model: p.model,
    choices: [{ index: 0, delta: { role: 'assistant', content: '' }, finish_reason: null }],
  });
}

/** 回复就绪后发送的帧：内容 delta、finish、（可选）usage、[DONE] */
export function completionContentFrames(p: CompletionParams): string[] {
  const frames = [
    sseFrame(chunk(p, [{ index: 0, delta: { content: p.content }, finish_reason: null }])),
    sseFrame(chunk(p, [{ index: 0, delta: {}, finish_reason: 'stop' }])),
  ];
  if (p.includeUsage) {
    frames.push(sseFrame({ ...chunk(p, []), usage: usageOf(p) }));
  }
  frames.push(SSE_DONE);
  return frames;
}

/** 完整流式事件序列（整段内容一次送达），不含心跳；server 层负责穿插 ping */
export function buildCompletionFrames(p: CompletionParams): string[] {
  return [completionRoleFrame(p), ...completionContentFrames(p)];
}

export function renderModel(modelName: string, created: number): object {
  return { id: modelName, object: 'model', created, owned_by: 'chatshy' };
}

export function renderModels(modelName: string, created: number): object {
  return { object: 'list', data: [renderModel(modelName, created)] };
}

// ---- 错误体 ----

export function openaiError(message: string, type: string, code: string | null = null): object {
  return { error: { message, type, param: null, code } };
}

export const openaiErrors = {
  invalidKey: (): object => openaiError('Incorrect API key provided', 'invalid_request_error', 'invalid_api_key'),
  badRequest: (message: string): object => openaiError(message, 'invalid_request_error', null),
  quota: (): object => openaiError('You exceeded your current quota.', 'insufficient_quota', 'rate_limit_exceeded'),
  tooManyPending: (): object =>
    openaiError('Too many pending requests for this key.', 'rate_limit_error', 'rate_limit_exceeded'),
  timeout: (): object =>
    openaiError('The model timed out while generating a response.', 'server_error', 'timeout'),
} as const;
