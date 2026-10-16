import OpenAI from 'openai';
import { afterEach, describe, expect, it } from 'vitest';
import { listThreadMessages } from '../../src/core/threads.js';
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

const authHeaders = (key: string): Record<string, string> => ({
  'content-type': 'application/json',
  authorization: `Bearer ${key}`,
});

describe('OpenAI 兼容端点（集成）', () => {
  it('非流式全链路（官方 SDK）：挂起 → 操作员回复 → 客户端拿到规范响应', async () => {
    const s = await boot();
    const client = new OpenAI({ baseURL: `${s.base}/v1`, apiKey: s.key, maxRetries: 0 });
    const pending = client.chat.completions.create({
      model: 'gpt-test',
      messages: [{ role: 'user', content: 'hi 你好' }],
    });

    await waitFor(() => s.queue.listPending().length === 1);
    const job = s.queue.listPending()[0]!;
    expect(s.queue.reply(job.id, '这是人工回复')).toBe(true);

    const completion = await pending;
    expect(completion.object).toBe('chat.completion');
    expect(completion.model).toBe('gpt-test');
    expect(completion.id).toMatch(/^chatcmpl-/);
    expect(completion.choices[0]!.message.content).toBe('这是人工回复');
    expect(completion.choices[0]!.finish_reason).toBe('stop');
    expect(completion.usage!.total_tokens).toBeGreaterThan(0);

    // 回复以 api 源落库（属于 API 转录本，可被下一轮前缀匹配）
    const msgs = listThreadMessages(s.db, job.threadId);
    expect(msgs.map((m) => [m.role, m.source, m.content])).toEqual([
      ['user', 'api', 'hi 你好'],
      ['assistant', 'api', '这是人工回复'],
    ]);
  });

  it('无效 key → 401 invalid_api_key', async () => {
    const s = await boot();
    const res = await fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: authHeaders('sk-chatshy-wrong'),
      body: JSON.stringify({ messages: [{ role: 'user', content: 'x' }] }),
    });
    expect(res.status).toBe(401);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe('invalid_api_key');
  });

  it('请求体不合法 → 400', async () => {
    const s = await boot();
    const res = await fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: authHeaders(s.key),
      body: JSON.stringify({ messages: [] }),
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: { type: string } };
    expect(body.error.type).toBe('invalid_request_error');
  });

  it('无人回复 → 504 timeout 错误体', async () => {
    const s = await boot();
    // 超时改成全局设置项，测试里临时调成 300ms
    const res = await s.withDefaultTimeout(300, () =>
      fetch(`${s.base}/v1/chat/completions`, {
        method: 'POST',
        headers: authHeaders(s.key),
        body: JSON.stringify({ messages: [{ role: 'user', content: '没人回复' }] }),
      }),
    );
    expect(res.status).toBe(504);
    const body = (await res.json()) as { error: { code: string; type: string } };
    expect(body.error.code).toBe('timeout');
    expect(body.error.type).toBe('server_error');
  });

  it('多轮会话归并：第二轮请求带历史归入同一会话', async () => {
    const s = await boot();
    const client = new OpenAI({ baseURL: `${s.base}/v1`, apiKey: s.key, maxRetries: 0 });

    const p1 = client.chat.completions.create({ model: 'm', messages: [{ role: 'user', content: 'u1' }] });
    await waitFor(() => s.queue.listPending().length === 1);
    const job1 = s.queue.listPending()[0]!;
    s.queue.reply(job1.id, 'a1');
    await p1;

    const p2 = client.chat.completions.create({
      model: 'm',
      messages: [
        { role: 'user', content: 'u1' },
        { role: 'assistant', content: 'a1' },
        { role: 'user', content: 'u2' },
      ],
    });
    await waitFor(() => s.queue.listPending().length === 1);
    const job2 = s.queue.listPending()[0]!;
    expect(job2.threadId).toBe(job1.threadId);
    s.queue.reply(job2.id, 'a2');
    await p2;

    const msgs = listThreadMessages(s.db, job1.threadId);
    expect(msgs.map((m) => m.content)).toEqual(['u1', 'a1', 'u2', 'a2']);
  });

  /**
   * 本次重构的核心回归点。所有调用方共用一个全局 Key，会话归并因此改按来源指纹隔离。
   * 若隔离失效，两个不同的人发相同的开场白（或 Claude Code 那种对所有用户几乎一致的
   * system 提示词）会被并进同一条会话，回复直接串台。
   */
  it('不同来源即使消息完全相同，也不会被并成一条会话', async () => {
    const s = await boot();
    for (const ua of ['CherryStudio/1.0.0', 'ChatBox/2.5.0']) {
      const res = fetch(`${s.base}/v1/chat/completions`, {
        method: 'POST',
        headers: { ...authHeaders(s.key), 'user-agent': ua },
        body: JSON.stringify({ messages: [{ role: 'user', content: '你好' }] }),
      });
      await waitFor(() => s.queue.listPending().length === 1);
      s.queue.reply(s.queue.listPending()[0]!.id, '好的');
      await res;
    }

    const rows = s.db.prepare('SELECT source_client FROM threads ORDER BY created_at').all() as {
      source_client: string;
    }[];
    expect(rows.map((r) => r.source_client)).toEqual(['Cherry Studio', 'ChatBox']);
  });

  it('CORS 预检通过（浏览器网页客户端可用）', async () => {
    const s = await boot();
    const res = await fetch(`${s.base}/v1/chat/completions`, {
      method: 'OPTIONS',
      headers: {
        origin: 'http://example.com',
        'access-control-request-method': 'POST',
        'access-control-request-headers': 'authorization,content-type',
      },
    });
    expect(res.status).toBeLessThan(300);
    expect(res.headers.get('access-control-allow-origin')).toBe('*');
  });

  it('GET /v1/models 与 /v1/models/:id', async () => {
    const s = await boot();
    const list = await fetch(`${s.base}/v1/models`, { headers: { authorization: `Bearer ${s.key}` } });
    expect(list.status).toBe(200);
    const listBody = (await list.json()) as { object: string; data: { id: string; owned_by: string }[] };
    expect(listBody.object).toBe('list');
    expect(listBody.data[0]!.id).toBe('chatshy');

    const one = await fetch(`${s.base}/v1/models/chatshy`, { headers: { authorization: `Bearer ${s.key}` } });
    expect(one.status).toBe(200);
    expect(((await one.json()) as { id: string }).id).toBe('chatshy');

    const unauth = await fetch(`${s.base}/v1/models`);
    expect(unauth.status).toBe(401);
  });
});
