import crypto from 'node:crypto';
import type { ChatMsg, ParseResult, Prompt } from './types.js';

/**
 * Anthropic /v1/messages 协议适配（设计 §4.3）。
 * 纯函数：解析请求为内部 Prompt；把回复渲染成 JSON 或 SSE 事件帧。
 */

export interface AnthropicParsedRequest {
  prompt: Prompt;
  stream: boolean;
}

const unsupportedBlock = (type: string): string => `[不支持的块类型: ${type}]`;

function blocksToString(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    const parts: string[] = [];
    for (const block of content) {
      if (block !== null && typeof block === 'object' && typeof (block as { type?: unknown }).type === 'string') {
        const b = block as { type: string; text?: unknown };
        if (b.type === 'text' && typeof b.text === 'string') parts.push(b.text);
        else parts.push(unsupportedBlock(b.type));
      } else {
        parts.push(unsupportedBlock('unknown'));
      }
    }
    return parts.join('');
  }
  if (content === null || content === undefined) return '';
  return String(content);
}

export function parseAnthropicRequest(body: unknown): ParseResult<AnthropicParsedRequest> {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    return { ok: false, message: 'Invalid request body' };
  }
  const b = body as Record<string, unknown>;
  if (!Array.isArray(b.messages) || b.messages.length === 0) {
    return { ok: false, message: "'messages' must be a non-empty array" };
  }

  const messages: ChatMsg[] = [];
  for (const raw of b.messages) {
    if (raw === null || typeof raw !== 'object') return { ok: false, message: 'Invalid message entry' };
    const m = raw as { role?: unknown; content?: unknown };
    if (m.role !== 'user' && m.role !== 'assistant') {
      return { ok: false, message: `Unsupported message role: ${m.role === undefined ? '(missing)' : String(m.role)}` };
    }
    messages.push({ role: m.role, content: blocksToString(m.content) });
  }

  let system: string | undefined;
  if (typeof b.system === 'string' && b.system !== '') {
    system = b.system;
  } else if (Array.isArray(b.system)) {
    const s = blocksToString(b.system);
    if (s !== '') system = s;
  }

  return {
    ok: true,
    value: {
      prompt: {
        model: typeof b.model === 'string' ? b.model : '',
        system,
        messages,
      },
      stream: b.stream === true,
    },
  };
}

// ---- 渲染 ----

export function newMessageId(): string {
  return `msg_${crypto.randomBytes(12).toString('hex')}`;
}

export function eventFrame(event: string, data: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/** 官方协议里的 ping 事件，用于流式空档期保活 */
export function anthropicPingFrame(): string {
  return eventFrame('ping', { type: 'ping' });
}

export interface MessageParams {
  id: string;
  model: string;
  content: string;
  inputTokens: number;
  outputTokens: number;
}

export function renderMessage(p: MessageParams): object {
  return {
    id: p.id,
    type: 'message',
    role: 'assistant',
    model: p.model,
    content: [{ type: 'text', text: p.content }],
    stop_reason: 'end_turn',
    stop_sequence: null,
    usage: { input_tokens: p.inputTokens, output_tokens: p.outputTokens },
  };
}

/** 完整流式事件序列（设计 §4.3），不含心跳；server 层负责穿插 ping */
export function buildMessageFrames(p: MessageParams): string[] {
  return [
    eventFrame('message_start', {
      type: 'message_start',
      message: {
        id: p.id,
        type: 'message',
        role: 'assistant',
        model: p.model,
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: p.inputTokens, output_tokens: 0 },
      },
    }),
    eventFrame('content_block_start', {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'text', text: '' },
    }),
    eventFrame('content_block_delta', {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: p.content },
    }),
    eventFrame('content_block_stop', { type: 'content_block_stop', index: 0 }),
    eventFrame('message_delta', {
      type: 'message_delta',
      delta: { stop_reason: 'end_turn', stop_sequence: null },
      usage: { output_tokens: p.outputTokens },
    }),
    eventFrame('message_stop', { type: 'message_stop' }),
  ];
}

// ---- 错误体 ----

export function anthropicError(type: string, message: string): object {
  return { type: 'error', error: { type, message } };
}

export const anthropicErrors = {
  invalidKey: (): object => anthropicError('authentication_error', 'invalid x-api-key'),
  badRequest: (message: string): object => anthropicError('invalid_request_error', message),
  quota: (): object => anthropicError('rate_limit_error', 'Rate limit exceeded'),
  tooManyPending: (): object => anthropicError('rate_limit_error', 'Too many pending requests for this key'),
  timeout: (): object => anthropicError('overloaded_error', 'Overloaded'),
} as const;
