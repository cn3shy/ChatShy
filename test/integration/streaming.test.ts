import Anthropic from '@anthropic-ai/sdk';
import OpenAI from 'openai';
import { afterEach, describe, expect, it } from 'vitest';
import { sleep, startTestServer, waitFor, type TestServer } from './helpers.js';

let servers: TestServer[] = [];
afterEach(async () => {
  for (const s of servers) await s.close();
  servers = [];
});

async function boot(): Promise<TestServer> {
  const s = await startTestServer();
  servers.push(s);
  return s;
}

/** 持续读流并把「到现在为止的全部文本」回调出去，直到流结束 */
async function readSse(res: Response, onProgress?: (all: string) => void): Promise<string> {
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let all = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    all += decoder.decode(value, { stream: true });
    onProgress?.(all);
  }
  return all;
}

describe('流式 SSE（集成）', () => {
  it('OpenAI 原始流：首帧立即到、空档期有 ping 注释、事件序列完整', async () => {
    const s = await boot();
    const res = await fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
      body: JSON.stringify({
        model: 'gpt-test',
        messages: [{ role: 'user', content: 'hi' }],
        stream: true,
        stream_options: { include_usage: true },
      }),
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('text/event-stream');
    expect(res.headers.get('x-accel-buffering')).toBe('no');

    let seen = '';
    const done = readSse(res, (all) => {
      seen = all;
    });

    // 首帧 role delta 立即到达（无需等回复）
    await waitFor(() => seen.includes('"delta":{"role":"assistant","content":""}'));
    // 空档期心跳（ping 间隔 80ms）
    await waitFor(() => seen.includes(': ping'));

    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, '流式人工回复');

    const text = await done;
    expect(text).toContain('"delta":{"content":"流式人工回复"}');
    expect(text).toContain('"finish_reason":"stop"');
    expect(text).toContain('"usage"');
    expect(text.trimEnd().endsWith('data: [DONE]')).toBe(true);

    const idxRole = text.indexOf('"role":"assistant"');
    const idxContent = text.indexOf('"content":"流式人工回复"');
    const idxDone = text.indexOf('data: [DONE]');
    expect(idxRole).toBeLessThan(idxContent);
    expect(idxContent).toBeLessThan(idxDone);
  });

  it('OpenAI 官方 SDK 流式消费', async () => {
    const s = await boot();
    const client = new OpenAI({ baseURL: `${s.base}/v1`, apiKey: s.key, maxRetries: 0 });
    const stream = await client.chat.completions.create({
      model: 'gpt-test',
      messages: [{ role: 'user', content: 'hi' }],
      stream: true,
    });

    const textPromise = (async () => {
      let text = '';
      for await (const chunk of stream) {
        text += chunk.choices[0]?.delta?.content ?? '';
      }
      return text;
    })();

    await waitFor(() => s.queue.listPending().length === 1);
    await sleep(150); // 让心跳也流过去
    s.queue.reply(s.queue.listPending()[0]!.id, 'SDK 收到的流式回复');

    expect(await textPromise).toBe('SDK 收到的流式回复');
  });

  it('Anthropic 官方 SDK 流式：事件类型齐、finalMessage 可组装、ping 可观测', async () => {
    const s = await boot();
    const client = new Anthropic({ baseURL: s.base, apiKey: s.key, maxRetries: 0 });
    const stream = client.messages.stream({
      model: 'claude-test',
      max_tokens: 100,
      messages: [{ role: 'user', content: 'hi' }],
    });

    const collected: string[] = [];
    const iter = (async () => {
      for await (const ev of stream) collected.push(ev.type);
    })();

    await waitFor(() => s.queue.listPending().length === 1);
    await sleep(150); // 至少一个 ping（80ms 间隔）
    s.queue.reply(s.queue.listPending()[0]!.id, 'Anthropic 流式回复');

    await iter;
    const final = await stream.finalMessage();
    expect(final.content[0]).toMatchObject({ type: 'text', text: 'Anthropic 流式回复' });
    expect(final.stop_reason).toBe('end_turn');

    expect(collected.filter((t) => t !== 'ping')).toEqual([
      'message_start',
      'content_block_start',
      'content_block_delta',
      'content_block_stop',
      'message_delta',
      'message_stop',
    ]);
    // 注：wire 上的 ping 事件由 SDK 内部消化（不进入迭代器），校验见原始 fetch 流测试
  });

  it('Anthropic 流式超时：先 message_start，后 event: error（overloaded）关闭', async () => {
    const s = await boot();
    // 全局超时临时调成 300ms（旧的 per-key shortKey 已随多 Key 体系移除）
    const text = await s.withDefaultTimeout(300, async () => {
      const res = await fetch(`${s.base}/v1/messages`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-api-key': s.key, 'anthropic-version': '2023-06-01' },
        body: JSON.stringify({
          model: 'claude-test',
          max_tokens: 100,
          stream: true,
          messages: [{ role: 'user', content: '没人回复' }],
        }),
      });
      expect(res.status).toBe(200);
      return readSse(res);
    });
    expect(text).toContain('event: message_start');
    expect(text).toContain('event: ping');
    expect(text).toContain('event: error');
    expect(text).toContain('overloaded_error');
    expect(text).not.toContain('message_stop');
  });

  it('OpenAI 流式超时：error 帧后关闭，无 [DONE]', async () => {
    const s = await boot();
    const text = await s.withDefaultTimeout(300, async () => {
      const res = await fetch(`${s.base}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
        body: JSON.stringify({ messages: [{ role: 'user', content: '没人回复' }], stream: true }),
      });
      expect(res.status).toBe(200);
      return readSse(res);
    });
    expect(text).toContain('"code":"timeout"');
    expect(text).not.toContain('[DONE]');
  });

  it('调用方断开 → Job 标记 cancelled', async () => {
    const s = await boot();
    const ac = new AbortController();
    // SSE 响应头立即可到，fetch 会先兑现为 Response；abort 主要体现在 body 中断
    const res = await fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
      body: JSON.stringify({ messages: [{ role: 'user', content: '会断开' }], stream: true }),
      signal: ac.signal,
    });
    expect(res.status).toBe(200);

    await waitFor(() => s.queue.listPending().length === 1);
    const jobId = s.queue.listPending()[0]!.id;

    ac.abort();
    await waitFor(() => s.queue.listPending().length === 0);
    void res.body?.cancel().catch(() => undefined);

    const row = s.db.prepare('SELECT status FROM jobs WHERE id = ?').get(jobId) as { status: string };
    expect(row.status).toBe('cancelled');
  });
});
