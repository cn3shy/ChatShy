import type { PayloadMessage } from './types';

export interface StreamHandlers {
  onDelta(content: string, reasoning: string): void;
}

export type StreamResult = 'ok' | 'blocked';

/** 同源 /api/chat，服务端已固定上游与 Key；这里只负责解析 SSE。 */
export async function streamChat(
  messages: PayloadMessage[],
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<StreamResult> {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages }),
    signal,
  });

  // 服务端敏感词拦截：静默丢弃本轮回复
  if (response.headers.get('X-ChatShy-Blocked') === '1') {
    try {
      await response.body?.cancel();
    } catch {
      /* 忽略取消流的异常 */
    }
    return 'blocked';
  }

  if (!response.ok || response.body === null) {
    let message = `请求失败(${response.status})`;
    try {
      const body = (await response.json()) as { error?: { message?: string } };
      message = body.error?.message || message;
    } catch {
      /* 保留默认 */
    }
    throw new Error(message);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let content = '';
  let reasoning = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let newline: number;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (payload === '' || payload === '[DONE]') continue;

      let data: {
        choices?: Array<{ delta?: Record<string, unknown>; message?: Record<string, unknown> }>;
      };
      try {
        data = JSON.parse(payload) as typeof data;
      } catch {
        continue;
      }
      const choice = data.choices?.[0];
      const delta = choice?.delta ?? choice?.message ?? {};
      const reason = (delta.reasoning_content ?? delta.reasoning) as string | undefined;
      const text = delta.content as string | undefined;
      if (typeof reason === 'string' && reason !== '') {
        reasoning += reason;
        handlers.onDelta(content, reasoning);
      }
      if (typeof text === 'string' && text !== '') {
        content += text;
        handlers.onDelta(content, reasoning);
      }
    }
  }
  return 'ok';
}

export async function fetchModel(): Promise<string> {
  try {
    const response = await fetch('/api/config');
    if (!response.ok) return '';
    const body = (await response.json()) as { model?: unknown };
    return typeof body.model === 'string' ? body.model : '';
  } catch {
    return '';
  }
}
