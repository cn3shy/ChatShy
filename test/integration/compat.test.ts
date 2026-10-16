import { afterEach, describe, expect, it } from 'vitest';
import { startTestServer, waitFor, type TestServer } from './helpers.js';

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

const anthropicHeaders = (key: string): Record<string, string> => ({
  'content-type': 'application/json',
  'x-api-key': key,
  'anthropic-version': '2023-06-01',
});

describe('客户端兼容层（集成）', () => {
  it('ANTHROPIC_BASE_URL 误带 /v1 时（/v1/v1/messages?beta=true）也能通', async () => {
    const s = await boot();
    // 注意：非流式请求要等操作员回复后才返回响应头，不能先 await 再回复
    const resP = fetch(`${s.base}/v1/v1/messages?beta=true`, {
      method: 'POST',
      headers: anthropicHeaders(s.key),
      body: JSON.stringify({ model: 'm', max_tokens: 100, messages: [{ role: 'user', content: '你好' }] }),
    });
    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, '双 v1 别名回复');

    const res = await resP;
    expect(res.status).toBe(200);
    const body = (await res.json()) as { content: Array<{ text: string }> };
    expect(body.content[0]!.text).toBe('双 v1 别名回复');
  });

  it('/v1/chat/completions 同样支持 /v1/v1 别名', async () => {
    const s = await boot();
    const resP = fetch(`${s.base}/v1/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
      body: JSON.stringify({ messages: [{ role: 'user', content: '你好' }] }),
    });
    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, 'ok');
    expect((await resP).status).toBe(200);
  });

  it('/v1/messages 接受 Authorization: Bearer（ANTHROPIC_AUTH_TOKEN 用法）', async () => {
    const s = await boot();
    const resP = fetch(`${s.base}/v1/messages`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
      body: JSON.stringify({ model: 'm', max_tokens: 100, messages: [{ role: 'user', content: '你好' }] }),
    });
    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, 'Bearer 也能过');
    const res = await resP;
    expect(res.status).toBe(200);
    const body = (await res.json()) as { content: Array<{ text: string }> };
    expect(body.content[0]!.text).toBe('Bearer 也能过');
  });

  it('/v1/models 接受 x-api-key（Claude Code 风格探测自带 x-api-key 头）', async () => {
    const s = await boot();
    const res = await fetch(`${s.base}/v1/models`, { headers: { 'x-api-key': s.key } });
    expect(res.status).toBe(200);
    expect(((await res.json()) as { object: string }).object).toBe('list');
  });

  it('POST /v1/messages/count_tokens 返回 input_tokens 估算', async () => {
    const s = await boot();
    const res = await fetch(`${s.base}/v1/messages/count_tokens`, {
      method: 'POST',
      headers: anthropicHeaders(s.key),
      body: JSON.stringify({
        model: 'm',
        system: '你是助手',
        messages: [{ role: 'user', content: '你好世界' }],
      }),
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { input_tokens: number };
    expect(body.input_tokens).toBeGreaterThanOrEqual(8); // 你是助手 + 你好世界 = 8 个 CJK 字
  });

  it('连接探测：HEAD/GET /api/hello 与 /v1/api/hello 返回 2xx', async () => {
    const s = await boot();
    for (const path of ['/api/hello', '/v1/api/hello']) {
      const head = await fetch(`${s.base}${path}`, { method: 'HEAD' });
      expect(head.status).toBeLessThan(300);
      const get = await fetch(`${s.base}${path}`);
      expect(get.status).toBeLessThan(300);
    }
  });
});
