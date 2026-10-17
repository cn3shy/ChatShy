import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import {
  ADMIN_PASSWORD,
  isFetchBlockedPort,
  startTestServer,
  waitFor,
  type TestServer,
} from './helpers.js';

/** 假上游：记录请求、按需延迟/报错，模拟 DeepSeek/千问/GLM 的 OpenAI 兼容端点 */
interface UpstreamRequest {
  path: string;
  headers: http.IncomingHttpHeaders;
  body: {
    model?: string;
    messages?: { role: string; content: string }[];
    stream?: boolean;
  };
}

type Handler = (req: UpstreamRequest, res: http.ServerResponse) => void;

interface MockUpstream {
  base: string;
  requests: UpstreamRequest[];
  setHandler(handler: Handler | null): void;
  close(): Promise<void>;
}

function jsonCompletion(res: http.ServerResponse, content: string): void {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(
    JSON.stringify({
      id: 'chatcmpl-test',
      object: 'chat.completion',
      created: 0,
      model: 'upstream-model',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    }),
  );
}

const defaultHandler: Handler = (req, res) => {
  const messages = Array.isArray(req.body.messages) ? req.body.messages : [];
  const lastUser = [...messages].reverse().find((m) => m.role === 'user');
  jsonCompletion(res, `AI: ${lastUser?.content ?? ''}`);
};

async function startMockUpstream(): Promise<MockUpstream> {
  const requests: UpstreamRequest[] = [];
  let handler: Handler = defaultHandler;

  for (let attempt = 0; attempt < 6; attempt++) {
    const server = http.createServer((req, res) => {
      let raw = '';
      req.on('data', (chunk) => (raw += chunk));
      req.on('end', () => {
        let body: UpstreamRequest['body'] = {};
        try {
          body = JSON.parse(raw) as UpstreamRequest['body'];
        } catch {
          /* 保持空对象 */
        }
        const record: UpstreamRequest = { path: req.url ?? '', headers: req.headers, body };
        requests.push(record);
        handler(record, res);
      });
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = (server.address() as AddressInfo).port;
    if (!isFetchBlockedPort(port)) {
      return {
        base: `http://127.0.0.1:${port}`,
        requests,
        setHandler(next) {
          handler = next ?? defaultHandler;
        },
        close: () =>
          new Promise((resolve) => {
            server.closeAllConnections();
            server.close(() => resolve());
          }),
      };
    }
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
  throw new Error('反复拿到 fetch 拒绝的端口');
}

// ---- 测试服务辅助 ----

let servers: TestServer[] = [];
let upstreams: MockUpstream[] = [];

afterEach(async () => {
  for (const s of servers) await s.close();
  for (const u of upstreams) await u.close();
  servers = [];
  upstreams = [];
});

async function boot(): Promise<TestServer> {
  const s = await startTestServer();
  servers.push(s);
  return s;
}

async function bootUpstream(): Promise<MockUpstream> {
  const u = await startMockUpstream();
  upstreams.push(u);
  return u;
}

async function login(s: TestServer): Promise<string> {
  const res = await fetch(`${s.base}/admin/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: ADMIN_PASSWORD }),
  });
  expect(res.status).toBe(200);
  return res.headers.get('set-cookie')!.split(';')[0]!;
}

const jsonHeaders = (cookie?: string): Record<string, string> => ({
  'content-type': 'application/json',
  ...(cookie === undefined ? {} : { cookie }),
});

async function fetchJson<T>(url: string, init?: RequestInit): Promise<{ status: number; body: T }> {
  const res = await fetch(url, init);
  return { status: res.status, body: (await res.json()) as T };
}

async function createProvider(
  s: TestServer,
  cookie: string,
  upstream: MockUpstream,
  overrides: Record<string, string> = {},
): Promise<{ id: string; name: string }> {
  const res = await fetchJson<{ provider: { id: string; name: string } }>(`${s.base}/admin/api/providers`, {
    method: 'POST',
    headers: jsonHeaders(cookie),
    body: JSON.stringify({
      name: '测试厂商',
      baseUrl: `${upstream.base}/v1`,
      apiKey: 'test-upstream-key',
      model: 'upstream-model',
      ...overrides,
    }),
  });
  expect(res.status).toBe(200);
  return res.body.provider;
}

async function patchSettings(
  s: TestServer,
  cookie: string,
  patch: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const res = await fetchJson<Record<string, unknown>>(`${s.base}/admin/api/settings`, {
    method: 'PATCH',
    headers: jsonHeaders(cookie),
    body: JSON.stringify(patch),
  });
  expect(res.status).toBe(200);
  return res.body;
}

/** 发起一个会挂起的调用方请求（不 await，等转发或人工回复后再读） */
function callerRequest(
  s: TestServer,
  body: Record<string, unknown>,
  ua = 'CherryStudio/1.0.0',
): Promise<Response> {
  return fetch(`${s.base}/v1/chat/completions`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${s.key}`,
      'user-agent': ua,
    },
    body: JSON.stringify({ model: 'chatter', ...body }),
  });
}

describe('AI 厂商转发（集成）', () => {
  it('手动转发：用选定厂商直接回复，请求形状与 via 标记都正确', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);
    const provider = await createProvider(s, cookie, upstream);

    const pending = callerRequest(s, {
      messages: [
        { role: 'system', content: '你是简洁助手' },
        { role: 'user', content: '你好' },
      ],
    });
    await waitFor(() => s.queue.listPending().length === 1);
    const job = s.queue.listPending()[0]!;

    const forward = await fetchJson<{ ok: boolean; content: string; providerName: string }>(
      `${s.base}/admin/api/jobs/${job.id}/forward`,
      { method: 'POST', headers: jsonHeaders(cookie), body: JSON.stringify({ providerId: provider.id }) },
    );
    expect(forward.status).toBe(200);
    expect(forward.body).toMatchObject({ ok: true, content: 'AI: 你好', providerName: '测试厂商' });

    const caller = await pending;
    expect(caller.status).toBe(200);
    const callerBody = (await caller.json()) as { choices: Array<{ message: { content: string } }> };
    expect(callerBody.choices[0]!.message.content).toBe('AI: 你好');

    // 上游收到的是内部 Prompt 重建后的请求：配置的模型 + system 在首条
    expect(upstream.requests).toHaveLength(1);
    const sent = upstream.requests[0]!;
    expect(sent.path).toBe('/v1/chat/completions');
    expect(sent.headers.authorization).toBe('Bearer test-upstream-key');
    expect(sent.body.model).toBe('upstream-model');
    expect(sent.body.stream).toBe(false);
    expect(sent.body.messages).toEqual([
      { role: 'system', content: '你是简洁助手' },
      { role: 'user', content: '你好' },
    ]);

    const threadId = (
      s.db.prepare('SELECT thread_id FROM jobs WHERE id = ?').get(job.id) as { thread_id: string }
    ).thread_id;
    const detail = await fetchJson<{
      messages: Array<{ role: string; content: string; via: string }>;
      pendingJobs: unknown[];
    }>(`${s.base}/admin/api/threads/${threadId}`, { headers: { cookie } });
    const assistant = detail.body.messages.find((m) => m.role === 'assistant')!;
    expect(assistant).toMatchObject({ content: 'AI: 你好', via: '测试厂商' });
    // 触发请求的 user 消息也关联了同一个 job，但绝不能标成 AI 写的
    expect(detail.body.messages.find((m) => m.role === 'user')!.via).toBe('');
    expect(detail.body.pendingJobs).toHaveLength(0);

    // 已经回复过的 job 再点转发：409，且不会再调上游
    const again = await fetchJson<{ error: string }>(`${s.base}/admin/api/jobs/${job.id}/forward`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ providerId: provider.id }),
    });
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('job_ended');
    expect(upstream.requests).toHaveLength(1);
  });

  it('Anthropic 调用方经 OpenAI 兼容上游返回 Anthropic 协议结果', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);
    const provider = await createProvider(s, cookie, upstream);

    const pending = fetch(`${s.base}/v1/messages`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': s.key,
        'anthropic-version': '2023-06-01',
        'user-agent': 'claude-cli/2.0.0',
      },
      body: JSON.stringify({
        model: 'claude-test',
        max_tokens: 128,
        system: '上游系统提示',
        messages: [{ role: 'user', content: [{ type: 'text', text: '从 Anthropic 发来' }] }],
      }),
    });
    await waitFor(() => s.queue.listPending().length === 1);
    const job = s.queue.listPending()[0]!;

    const forward = await fetchJson<{ ok: boolean }>(`${s.base}/admin/api/jobs/${job.id}/forward`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ providerId: provider.id }),
    });
    expect(forward.status).toBe(200);

    const caller = await pending;
    expect(caller.status).toBe(200);
    const body = (await caller.json()) as { content: Array<{ type: string; text: string }> };
    expect(body.content).toEqual([{ type: 'text', text: 'AI: 从 Anthropic 发来' }]);
    expect(upstream.requests[0]!.body.messages![0]).toEqual({ role: 'system', content: '上游系统提示' });
  });

  it('上游失败：管理端 502、job 保持 pending、可人工接管', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);
    const provider = await createProvider(s, cookie, upstream);

    upstream.setHandler((_req, res) => {
      res.writeHead(500, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: '上游炸了' } }));
    });

    const pending = callerRequest(s, { messages: [{ role: 'user', content: '会失败' }] });
    await waitFor(() => s.queue.listPending().length === 1);
    const job = s.queue.listPending()[0]!;

    const forward = await fetchJson<{ error: string; message: string }>(
      `${s.base}/admin/api/jobs/${job.id}/forward`,
      { method: 'POST', headers: jsonHeaders(cookie), body: JSON.stringify({ providerId: provider.id }) },
    );
    expect(forward.status).toBe(502);
    expect(forward.body.error).toBe('upstream_error');
    expect(forward.body.message).toContain('上游炸了');

    // Job 没被吞掉，失败原因已落库
    expect(s.queue.listPending()).toHaveLength(1);
    const row = s.db.prepare('SELECT forward_error FROM jobs WHERE id = ?').get(job.id) as {
      forward_error: string | null;
    };
    expect(row.forward_error).toContain('上游炸了');

    const reply = await fetchJson<{ delivered: boolean }>(`${s.base}/admin/api/jobs/${job.id}/reply`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ content: '人工接管' }),
    });
    expect(reply.body.delivered).toBe(true);
    const caller = await pending;
    const body = (await caller.json()) as { choices: Array<{ message: { content: string } }> };
    expect(body.choices[0]!.message.content).toBe('人工接管');
  });

  it('自动转发：无操作台连接也能直接回复', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);
    const provider = await createProvider(s, cookie, upstream);
    await patchSettings(s, cookie, { autoForwardEnabled: true, autoForwardProviderId: provider.id });

    // 整个用例没有建立任何 /admin/api/events 连接
    const res = await callerRequest(s, { messages: [{ role: 'user', content: '自动回答我' }] });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { choices: Array<{ message: { content: string } }> };
    expect(body.choices[0]!.message.content).toBe('AI: 自动回答我');

    expect(upstream.requests).toHaveLength(1);
    expect(s.queue.listPending()).toHaveLength(0);

    const thread = s.db
      .prepare("SELECT thread_id FROM messages WHERE content = '自动回答我'")
      .get() as { thread_id: string };
    const detail = await fetchJson<{ messages: Array<{ role: string; via: string }> }>(
      `${s.base}/admin/api/threads/${thread.thread_id}`,
      { headers: { cookie } },
    );
    expect(detail.body.messages.find((m) => m.role === 'assistant')!.via).toBe('测试厂商');
  });

  it('自动转发失败：请求留在 pending 等人工，错误可读', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);
    const provider = await createProvider(s, cookie, upstream);
    await patchSettings(s, cookie, { autoForwardEnabled: true, autoForwardProviderId: provider.id });

    upstream.setHandler((_req, res) => {
      res.writeHead(429, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'rate limited' } }));
    });

    const pending = callerRequest(s, { messages: [{ role: 'user', content: '自动失败' }] });
    await waitFor(() => s.queue.listPending().length === 1);
    const job = s.queue.listPending()[0]!;
    await waitFor(() => {
      const row = s.db.prepare('SELECT forward_error FROM jobs WHERE id = ?').get(job.id) as {
        forward_error: string | null;
      };
      return row.forward_error !== null;
    });

    const detail = await fetchJson<{ pendingJobs: Array<{ forwardError: string | null }> }>(
      `${s.base}/admin/api/threads/${job.threadId}`,
      { headers: { cookie } },
    );
    expect(detail.body.pendingJobs[0]!.forwardError).toContain('rate limited');

    const reply = await fetchJson<{ delivered: boolean }>(`${s.base}/admin/api/jobs/${job.id}/reply`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ content: '人工补位' }),
    });
    expect(reply.body.delivered).toBe(true);
    const caller = await pending;
    const body = (await caller.json()) as { choices: Array<{ message: { content: string } }> };
    expect(body.choices[0]!.message.content).toBe('人工补位');
  });

  it('同一 job 并发手动转发只发一次上游请求', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);
    const provider = await createProvider(s, cookie, upstream);

    let release: (() => void) | null = null;
    upstream.setHandler((_req, res) => {
      release = () => jsonCompletion(res, '慢回答');
    });

    const pending = callerRequest(s, { messages: [{ role: 'user', content: '并发' }] });
    await waitFor(() => s.queue.listPending().length === 1);
    const job = s.queue.listPending()[0]!;

    const first = fetch(`${s.base}/admin/api/jobs/${job.id}/forward`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ providerId: provider.id }),
    });
    await waitFor(() => release !== null);

    const second = await fetchJson<{ error: string }>(`${s.base}/admin/api/jobs/${job.id}/forward`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ providerId: provider.id }),
    });
    expect(second.status).toBe(409);
    expect(second.body.error).toBe('forward_in_progress');

    release!();
    const firstRes = await first;
    expect(firstRes.status).toBe(200);
    expect(upstream.requests).toHaveLength(1);

    const caller = await pending;
    const body = (await caller.json()) as { choices: Array<{ message: { content: string } }> };
    expect(body.choices[0]!.message.content).toBe('慢回答');
  });

  it('厂商管理：鉴权、校验、掩码、测试、删除引用保护', async () => {
    const s = await boot();
    const upstream = await bootUpstream();
    const cookie = await login(s);

    // 未登录
    expect((await fetch(`${s.base}/admin/api/providers`)).status).toBe(401);

    // 非法 URL
    const bad = await fetchJson<{ error: string }>(`${s.base}/admin/api/providers`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({
        name: '坏厂商',
        baseUrl: 'ftp://example.com/v1',
        apiKey: 'k',
        model: 'm',
      }),
    });
    expect(bad.status).toBe(400);
    expect(bad.body.error).toBe('invalid_base_url');

    const provider = await createProvider(s, cookie, upstream);

    // 列表不回显完整 Key
    const list = await fetchJson<{ providers: Array<Record<string, unknown>> }>(
      `${s.base}/admin/api/providers`,
      { headers: { cookie } },
    );
    const listed = list.body.providers[0]!;
    expect(listed.apiKey).toBeUndefined();
    expect(listed.apiKeyMasked).toBe('••••••••-key');
    expect(listed.hasApiKey).toBe(true);

    // 同名冲突
    const dup = await fetchJson<{ error: string }>(`${s.base}/admin/api/providers`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({
        name: '测试厂商',
        baseUrl: `${upstream.base}/v1`,
        apiKey: 'other',
        model: 'other',
      }),
    });
    expect(dup.status).toBe(409);
    expect(dup.body.error).toBe('provider_exists');

    // 连通性测试
    const test = await fetchJson<{ ok: boolean; latencyMs: number; preview: string }>(
      `${s.base}/admin/api/providers/${provider.id}/test`,
      { method: 'POST', headers: jsonHeaders(cookie), body: '{}' },
    );
    expect(test.status).toBe(200);
    expect(test.body.ok).toBe(true);
    expect(test.body.preview).toContain('AI:');

    // 被自动转发引用时禁止删除
    await patchSettings(s, cookie, { autoForwardEnabled: true, autoForwardProviderId: provider.id });
    const blocked = await fetchJson<{ error: string }>(`${s.base}/admin/api/providers/${provider.id}`, {
      method: 'DELETE',
      headers: { cookie },
    });
    expect(blocked.status).toBe(409);
    expect(blocked.body.error).toBe('provider_in_use');

    // 关闭自动转发后可以删除，并清空选择
    await patchSettings(s, cookie, { autoForwardEnabled: false });
    const removed = await fetchJson<{ ok: boolean }>(`${s.base}/admin/api/providers/${provider.id}`, {
      method: 'DELETE',
      headers: { cookie },
    });
    expect(removed.status).toBe(200);
    const settings = await fetchJson<{ autoForwardProviderId: string }>(`${s.base}/admin/api/settings`, {
      headers: { cookie },
    });
    expect(settings.body.autoForwardProviderId).toBe('');
  });

  it('开启自动转发但未选厂商 → 400', async () => {
    const s = await boot();
    const cookie = await login(s);
    const res = await fetchJson<{ error: string }>(`${s.base}/admin/api/settings`, {
      method: 'PATCH',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ autoForwardEnabled: true }),
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_auto_forward');
  });
});
